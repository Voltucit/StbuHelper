import Foundation
import WidgetKit

typealias Object = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func text(_ key: String, _ fallback: String = "") -> String {
        if let value = self[key] as? String, !value.isEmpty, value != "null" { return value }
        if let value = self[key] as? NSNumber { return value.stringValue }
        return fallback
    }
    func number(_ key: String) -> Int { Int(text(key)) ?? 0 }
    func rows(_ key: String) -> [Object] { self[key] as? [Object] ?? [] }
}

@MainActor
final class CampusStore {
    static let collections = ["courses", "tasks", "grades", "exams"]
    private static let ownersKey = "_academicOwners"
    private static let calendarKeys = ["schoolTerm", "semesterStart", "teachingWeeks", "officialTimes"]
    static var empty: Object {
        ["schemaVersion": 1, "settings": Object(), "courses": [Object](), "tasks": [Object](),
         "grades": [Object](), "exams": [Object](), "updatedAt": "", "demo": false]
    }

    let directory: URL
    private var file: URL { directory.appendingPathComponent("campus.json") }
    private var archives: URL { directory.appendingPathComponent("academic-accounts", isDirectory: true) }
    let settings = UserDefaults.standard

    init() throws {
        var path = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                appropriateFor: nil, create: true).appendingPathComponent("StbuHelper")
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try path.setResourceValues(values)
        directory = path
    }

    func read() throws -> Object {
        var value = try readFile(file) ?? Self.empty
        value.removeValue(forKey: Self.ownersKey)
        return value
    }

    private func readFile(_ file: URL) throws -> Object? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        guard let value = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? Object else {
            throw AppError("本机学习数据无法读取")
        }
        return value
    }

    func save(_ value: Object) throws {
        guard value.number("schemaVersion") == 1, value["settings"] is Object, value["demo"] as? Bool != true else {
            throw AppError("请选择川工商小助手导出的备份")
        }
        for key in Self.collections {
            guard let rows = value[key] as? [Object], rows.count <= 3000,
                  rows.allSatisfy({ !$0.text("name").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.text("name").count <= 500 }) else {
                throw AppError("备份里的记录格式不正确")
            }
        }
        guard value.rows("courses").allSatisfy({ row in
            guard (1...7).contains(row.number("day")), let weeks = row["weeks"] as? [Int],
                  let sections = row["sections"] as? [Int] else { return false }
            return !weeks.isEmpty && !sections.isEmpty && weeks.count <= 60 && sections.count <= 16 &&
                weeks.allSatisfy { (1...60).contains($0) } && sections.allSatisfy { (1...16).contains($0) }
        }) else { throw AppError("课表周次或节次不正确") }
        var saved = value
        saved[Self.ownersKey] = try readFile(file)?[Self.ownersKey]
        try writeFile(file, saved)
    }

    private func writeFile(_ file: URL, _ value: Object) throws {
        let bytes = try JSONSerialization.data(withJSONObject: value)
        guard bytes.count <= 2 * 1024 * 1024 else { throw AppError("学习数据超过 2 MB") }
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func owner(_ provider: CampusProvider) throws -> String? {
        (try readFile(file)?[Self.ownersKey] as? [String: String])?[provider.rawValue]
    }

    func requireOwner(_ provider: CampusProvider, _ owner: String) throws {
        guard try self.owner(provider) == owner else { throw AppError("校园账号已变化，请重新同步") }
    }

    @discardableResult
    func selectAccount(_ provider: CampusProvider, owner: String, adoptLegacy: Bool) throws -> Bool {
        guard owner.range(of: #"^[a-f0-9-]{32,64}$"#, options: .regularExpression) != nil else {
            throw AppError("校园账号尚未确认")
        }
        var value = try readFile(file) ?? Self.empty
        var owners = value[Self.ownersKey] as? [String: String] ?? [:]
        let previous = owners[provider.rawValue]
        if previous == owner { return false }
        if previous != nil || !adoptLegacy {
            try FileManager.default.createDirectory(at: archives, withIntermediateDirectories: true)
            let incoming = try readFile(archive(provider, owner: owner)) ?? [:]
            var outgoing: Object = [:]
            var rules = value["settings"] as? Object ?? [:]
            let savedRules = incoming["settings"] as? Object ?? [:]
            // Old Learning snapshots remain usable until a complete School timetable is available.
            let schoolTimetable = rules["schoolTerm"] != nil
            let archiveCalendar = provider == .school ? schoolTimetable : !schoolTimetable
            let restoreCalendar = provider == .school ? savedRules["schoolTerm"] != nil : !schoolTimetable
            let calendar = archiveCalendar || restoreCalendar
            var kinds = provider == .learning ? ["tasks"] : ["grades", "exams"]
            if calendar { kinds.append("courses") }
            for kind in kinds {
                let rows = value.rows(kind)
                if kind != "courses" || archiveCalendar { outgoing[kind] = rows.filter { $0.text("source") != "manual" } }
                replace(kind, rows: kind != "courses" || restoreCalendar ? incoming.rows(kind) : [], in: &value)
            }
            if calendar {
                var before: Object = [:]
                for key in Self.calendarKeys where key != "semesterStart" || rules["customTimetable"] as? Bool != true {
                    if archiveCalendar { before[key] = rules[key] }
                    rules[key] = restoreCalendar ? savedRules[key] : nil
                }
                value["settings"] = rules
                if archiveCalendar { outgoing["settings"] = before }
            }
            try writeFile(archive(provider, owner: previous ?? "legacy"), outgoing)
        }
        owners[provider.rawValue] = owner
        value[Self.ownersKey] = owners
        value["updatedAt"] = ISO8601DateFormatter().string(from: Date())
        try writeFile(file, value)
        return true
    }

    private func archive(_ provider: CampusProvider, owner: String) throws -> URL {
        guard owner == "legacy" || owner.range(of: #"^[a-f0-9-]{32,64}$"#, options: .regularExpression) != nil else {
            throw AppError("本机账号缓存格式不正确")
        }
        return archives.appendingPathComponent(provider.rawValue + "-" + owner + ".json")
    }

    func clearData() throws {
        if FileManager.default.fileExists(atPath: archives.path) { try FileManager.default.removeItem(at: archives) }
        try save(Self.empty)
        let syncKeys = ["lastHomework", "lastSchedule", "scheduleSignature"]
        for key in settings.dictionaryRepresentation().keys where syncKeys.contains(where: { key == $0 || key.hasPrefix($0 + ".") }) {
            settings.removeObject(forKey: key)
        }
    }

    func replace(_ key: String, rows: [Object], in value: inout Object) {
        let manual = value.rows(key).filter { $0.text("source") == "manual" }
        let ids = Set(manual.map { $0.text("id") })
        value[key] = manual + rows.filter { !ids.contains($0.text("id")) }
    }

    func replaceTimetable(_ courses: [Object], settings incoming: Object, in value: inout Object) {
        replace("courses", rows: courses, in: &value)
        var settings = value["settings"] as? Object ?? [:]
        let newTerm = settings.text("schoolTerm") != incoming.text("schoolTerm")
        for key in Self.calendarKeys where key != "semesterStart" || settings["customTimetable"] as? Bool != true {
            if newTerm || incoming[key] != nil { settings[key] = incoming[key] }
        }
        value["settings"] = settings
    }

    var preferences: Object {
        var value = settings.dictionary(forKey: "appearance") ?? [:]
        value["nativeInput"] = false
        if let image = settings.string(forKey: "background") {
            value["backgroundUrl"] = "stbu-app://local/background/" + image
        }
        return value
    }

    func savePreferences(_ changes: Object) -> Object {
        var value = settings.dictionary(forKey: "appearance") ?? [:]
        let colors = [
            "blue", "mist", "indigo", "teal", "cobalt", "deepsea", "jade", "berry",
            "green", "purple", "orange", "rose"
        ]
        if let color = changes["color"] as? String, colors.contains(color) {
            value["color"] = color
        }
        if let dark = changes["dark"] as? Bool { value["dark"] = dark }
        if let brightness = changes["backgroundBrightness"] as? Int, (25...150).contains(brightness) {
            value["backgroundBrightness"] = brightness
        }
        for key in ["emptyDayMessage", "emptyTasksMessage"] where changes[key] is String {
            value[key] = String(changes.text(key).prefix(200))
        }
        settings.set(value, forKey: "appearance")
        return preferences
    }
}
