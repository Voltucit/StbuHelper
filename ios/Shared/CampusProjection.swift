import Foundation

enum CampusProjection {
    struct Item {
        let name: String
        let detail: String
        let date: Date
    }

    static var calendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        cal.firstWeekday = 2
        return cal
    }

    static func milliseconds(_ date: Date) -> Int { Int(date.timeIntervalSince1970 * 1000) }

    static func format(_ date: Date, _ pattern: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    static func week(_ start: String, _ date: Date) -> Int? {
        let parts = start.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, let first = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
              let monday = calendar.dateInterval(of: .weekOfYear, for: first)?.start else { return nil }
        let days = calendar.dateComponents([.day], from: monday, to: calendar.startOfDay(for: date)).day ?? 0
        return Int(floor(Double(days) / 7)) + 1
    }

    static func normalizeTimes(_ rows: [String]) -> String {
        let pattern = #"^(\d{1,2}):([0-5]\d)\s*[-–—~～至]\s*(\d{1,2}):([0-5]\d)$"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return "" }
        let times = rows.compactMap { raw -> String? in
            let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "：", with: ":")
            guard let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
            let parts = (1...4).compactMap { index in Range(match.range(at: index), in: text).flatMap { Int(text[$0]) } }
            guard parts.count == 4, parts[0] <= 23, parts[2] <= 23,
                  parts[0] * 60 + parts[1] < parts[2] * 60 + parts[3] else { return nil }
            return String(format: "%02d:%02d-%02d:%02d", parts[0], parts[1], parts[2], parts[3])
        }
        return times.count == rows.count ? times.joined(separator: "\n") : ""
    }

    static func isPending(_ status: String) -> Bool {
        status.range(of: "已完成|已提交|已交|待批|已批|过期|逾期|已截止|已过截止|已结束|已作答|截止时间未提供|截止时间未知|无截止时间", options: .regularExpression) == nil &&
        status.range(of: "未交|未提交|未完成|待完成|待提交|未作答|未开始|进行中|待做", options: .regularExpression) != nil
    }

    static func pending(_ rows: [[String: Any]], at now: Date) -> [Item] {
        rows.compactMap { row -> Item? in
            guard let name = row["name"] as? String, !name.isEmpty, let status = row["status"] as? String, isPending(status),
                  let timestamp = (row["deadlineAt"] as? NSNumber)?.doubleValue ?? Double(row["deadlineAt"] as? String ?? ""),
                  timestamp.isFinite else { return nil }
            let deadline = Date(timeIntervalSince1970: timestamp / 1000)
            guard deadline > now else { return nil }
            return Item(name: name, detail: row["course"] as? String ?? "", date: deadline)
        }.sorted { $0.date < $1.date }
    }

    struct CourseSlot {
        let item: Item
        let endsAt: Date?
    }

    static func nextMidnight(after date: Date) -> Date {
        calendar.dateInterval(of: .day, for: date)?.end ?? date.addingTimeInterval(24 * 3600)
    }

    static func todayCourses(_ data: [String: Any], at date: Date) -> [CourseSlot] {
        let rules = data["settings"] as? [String: Any] ?? [:]
        let courses = data["courses"] as? [[String: Any]] ?? []
        let start = rules["semesterStart"] as? String ?? ""
        guard let week = week(start, date), (1...60).contains(week) else { return [] }
        let cal = calendar
        let day = cal.startOfDay(for: date)
        let weekday = (cal.component(.weekday, from: day) + 5) % 7 + 1
        let monthDay = format(day, "MM-dd")
        let from = rules["summerFrom"] as? String ?? "05-01"
        let to = rules["summerTo"] as? String ?? "09-30"
        let summer = from <= to ? (monthDay >= from && monthDay <= to) : (monthDay >= from || monthDay <= to)
        let seasonal = rules[summer ? "summerTimes" : "winterTimes"] as? String ?? ""
        let raw = seasonal.isEmpty ? rules["officialTimes"] as? String ?? "" : seasonal
        let rows = raw.components(separatedBy: CharacterSet(charactersIn: "\n,，")).map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty }
        let periods = normalizeTimes(rows).split(separator: "\n")

        func sectionTime(_ section: Int, end: Bool) -> Date? {
            guard section > 0, section <= periods.count else { return nil }
            let stamp = end ? periods[section - 1].suffix(5) : periods[section - 1].prefix(5)
            let parts = stamp.split(separator: ":").compactMap { Int($0) }
            guard parts.count == 2 else { return nil }
            return cal.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: day)
        }

        return courses.compactMap { row -> CourseSlot? in
            guard row["day"] as? Int == weekday, (row["weeks"] as? [Int])?.contains(week) == true,
                  let sections = row["sections"] as? [Int], let first = sections.min(), let last = sections.max(),
                  let name = row["name"] as? String, let begin = sectionTime(first, end: false) else { return nil }
            let end = sectionTime(last, end: true).flatMap { $0 > begin ? $0 : nil }
            let detail = ["第 \(first) 节", row["room"] as? String ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
            return CourseSlot(item: Item(name: name, detail: detail, date: begin), endsAt: end)
        }.sorted { $0.item.date < $1.item.date }
    }
}

enum WidgetData {
    private static var file: URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "StbuAppGroup") as? String else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent("campus-widget.json")
    }

    static func read() -> [String: Any] {
        guard let file, let data = try? Data(contentsOf: file) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    static func write(_ value: [String: Any]) -> Bool {
        guard let file, let data = try? JSONSerialization.data(withJSONObject: value, options: .sortedKeys) else { return false }
        if (try? Data(contentsOf: file)) == data { return false }
        do {
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var path = file
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? path.setResourceValues(values)
            return true
        } catch { return false }
    }
}
