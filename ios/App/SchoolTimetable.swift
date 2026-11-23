import Foundation
import SwiftSoup

enum SchoolTimetable {
    struct Term: Equatable {
        let year: String
        let term: String
        var key: String { year + "-" + term }
        var fields: [String: String] { ["xnm": year, "xqm": term] }
    }

    private static func endpoint(_ action: String) -> URL {
        URL(string: "https://jwxt.stbu.edu.cn/kbcx/xskbcx_cx" + action + ".html?gnmkdm=N2151")!
    }

    private static func field(_ key: String, _ doc: Document) throws -> String {
        guard let element = try doc.getElementById(key) else { return "" }
        if element.tagName() == "select" {
            if let selected = try element.select("option[selected]").first() { return try selected.attr("value") }
            let options = try element.select("option").array()
            return options.count == 1 ? try options[0].attr("value") : ""
        }
        return try element.attr("value")
    }

    @MainActor
    static func read(_ http: OfficialHTTP) async throws -> (courses: [Object], settings: Object) {
        let index = endpoint("XskbcxIndex")
        let (page, _) = try await http.page(index, provider: .school, referer: CampusProvider.school.home)
        let doc = try SwiftSoup.parse(page)
        let term = try Term(year: field("xnm", doc), term: field("xqm", doc))
        guard term.year.range(of: #"^[0-9]{4}$"#, options: .regularExpression) != nil,
              term.term.range(of: #"^[0-9]{1,2}$"#, options: .regularExpression) != nil else {
            throw AppError("未能识别教务系统的当前学期")
        }
        var query = term.fields
        query["kzlx"] = "ck"; query["xsdm"] = ""; query["kclbdm"] = ""
        let (body, _) = try await http.page(endpoint("Xsgrkb"), provider: .school, form: query, referer: index)
        guard let table = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? Object,
              let info = table["xsxx"] as? Object, info.text("XNM") == term.year, info.text("XQM") == term.term,
              let rows = table["kbList"] as? [Object] else { throw AppError("教务系统未返回当前学期的个人课表") }
        let courses = try Self.courses(rows, term: term)
        var settings: Object = ["schoolTerm": term.key]
        // Missing calendar or bell times must not discard a valid full timetable.
        do {
            let url = URL(string: "https://jwxt.stbu.edu.cn/xtgl/index_cxAreaOne.html?localeKey=zh_CN&gnmkdm=index")!
            let today = Date()
            let (html, _) = try await http.page(url, provider: .school, form: [:], referer: CampusProvider.school.home)
            let calendar = try SwiftSoup.parse(html)
            if try field("xnm", calendar) == term.year, try field("xqm", calendar) == term.term,
               let week = Int(try field("dqzc", calendar)), (1...60).contains(week),
               let monday = CampusProjection.calendar.dateInterval(of: .weekOfYear, for: today)?.start,
               let first = CampusProjection.calendar.date(byAdding: .day, value: (1 - week) * 7, to: monday) {
                settings["semesterStart"] = CampusProjection.format(first, "yyyy-MM-dd")
            }
        } catch is SessionExpired { throw SessionExpired() } catch {}
        do {
            var fields = term.fields
            fields["xqh_id"] = info.text("XQH_ID")
            let (body, _) = try await http.page(endpoint("Rjc"), provider: .school, form: fields, referer: index)
            if let periods = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [Object],
               (1...16).contains(periods.count) {
                let ordered = periods.sorted { $0.number("jcmc") < $1.number("jcmc") }
                if ordered.enumerated().allSatisfy({ $0.element.number("jcmc") == $0.offset + 1 }) {
                    let times = CampusProjection.normalizeTimes(ordered.map { $0.text("qssj") + "-" + $0.text("jssj") })
                    if !times.isEmpty { settings["officialTimes"] = times }
                }
            }
        } catch is SessionExpired { throw SessionExpired() } catch {}
        return (courses, settings)
    }

    private static func normalized(_ raw: String) -> String {
        raw.replacingOccurrences(of: #"\s+"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "[，、]", with: ",", options: .regularExpression)
            .replacingOccurrences(of: "[~～至—–]", with: "-", options: .regularExpression)
            .replacingOccurrences(of: "（", with: "(").replacingOccurrences(of: "）", with: ")")
    }

    private static func numbers(_ raw: String, max: Int) throws -> ClosedRange<Int> {
        let digits = raw.hasPrefix("第") ? String(raw.dropFirst()) : raw
        let parts = digits.components(separatedBy: "-")
        guard (1...2).contains(parts.count), let first = Int(parts[0]), let last = Int(parts.last ?? ""),
              first >= 1, first <= last, last <= max else { throw AppError("教务课表的周次或节次未识别") }
        return first...last
    }

    private static let weekPart = try! NSRegularExpression(pattern: #"^(第?\d{1,2}(?:-\d{1,2})?)(周(?:\(([单双])\)|([单双])周?)?|([单双])周)?$"#)
    private static func weeks(_ raw: String) throws -> [Int] {
        let parts = normalized(raw).components(separatedBy: ",")
        var result = Set<Int>(), group = [Int]()
        for (index, part) in parts.enumerated() {
            guard let m = weekPart.firstMatch(in: part, range: NSRange(part.startIndex..., in: part)),
                  let span = Range(m.range(at: 1), in: part) else { throw AppError("教务课表的周次格式未识别") }
            group.append(contentsOf: try numbers(String(part[span]), max: 60))
            if m.range(at: 2).location != NSNotFound || index == parts.count - 1 {
                let parity = (3...5).compactMap { Range(m.range(at: $0), in: part).map { String(part[$0]) } }.first
                result.formUnion(group.filter { parity == nil || $0 % 2 == (parity == "单" ? 1 : 0) })
                group.removeAll()
            }
        }
        guard !result.isEmpty else { throw AppError("教务课程缺少有效周次") }
        return result.sorted()
    }

    private static func courses(_ rows: [Object], term: Term) throws -> [Object] {
        var result = [Object](), indices = [String: Int]()
        for row in rows {
            let name = row.text("kcmc").trimmingCharacters(in: .whitespacesAndNewlines)
            let day = row.number("xqj")
            guard !name.isEmpty, (1...7).contains(day) else { throw AppError("教务课表的课程名称或星期未识别") }
            var raw = normalized(row.text("jcs", row.text("jc")))
            if raw.hasSuffix("节") { raw.removeLast() }
            let sections = try Set(raw.components(separatedBy: ",").flatMap { try numbers($0, max: 16) }).sorted()
            let weeks = try Self.weeks(row.text("zcd"))
            let room = row.text("cdmc"), teacher = row.text("xm")
            let key = "jw:" + [term.key, row.text("jxb_id", row.text("kch_id")), name, String(day),
                               sections.map(String.init).joined(separator: ","), room, teacher].joined(separator: "|")
            if let i = indices[key] {
                result[i]["weeks"] = Set((result[i]["weeks"] as? [Int] ?? []) + weeks).sorted()
            } else {
                indices[key] = result.count
                result.append(["id": key, "name": name, "day": day, "sections": sections, "weeks": weeks,
                               "room": room, "teacher": teacher, "source": "school", "provider": "school"])
            }
        }
        return result
    }
}
