import Foundation
import CryptoKit
import SwiftSoup
import UserNotifications
import WidgetKit

@MainActor
final class Academic {
    struct Course: Codable {
        let id: String
        let classId: String
        let cpi: String
        let name: String
        let teacher: String
        var key: String { id + ":" + classId }
    }

    struct Tracking: Codable {
        var courses = [Course]()
        var selected: [String]?
        var updatedAt = Date.distantPast
    }

    let http = OfficialHTTP()
    private let store: CampusStore
    private(set) var state: Object = ["running": false]
    private var queuedConnections = Set<CampusProvider>()
    private var reminderGeneration = 0

    init(store: CampusStore) {
        self.store = store
        for provider in CampusProvider.allCases where hasAccount(provider) {
            _ = try? selectCache(provider, adoptLegacy: true)
        }
    }

    var accounts: Object {
        Dictionary(uniqueKeysWithValues: CampusProvider.allCases.map { provider in
            (provider.rawValue, hasAccount(provider))
        })
    }

    private func hasAccount(_ provider: CampusProvider) -> Bool {
        store.settings.bool(forKey: "connected." + provider.rawValue) && ((try? http.cookies(provider).isEmpty) == false)
    }

    func connected(_ provider: CampusProvider, freshLogin: Bool) throws {
        let existing = store.settings.bool(forKey: "connected." + provider.rawValue)
        _ = try selectCache(provider, freshSchoolLogin: freshLogin || !existing, adoptLegacy: existing && !freshLogin)
        store.settings.set(true, forKey: "connected." + provider.rawValue)
        if state["running"] as? Bool == true { queuedConnections.insert(provider) }
        else { start(provider.rawValue, scope: "all") }
    }

    private func selectCache(_ provider: CampusProvider, freshSchoolLogin: Bool = false, adoptLegacy: Bool) throws -> String {
        let owner: String
        if provider == .learning { owner = try learningOwner() }
        else if !freshSchoolLogin, let previous = try store.owner(.school) { owner = previous }
        else { owner = UUID().uuidString.lowercased() }
        if try store.selectAccount(provider, owner: owner, adoptLegacy: adoptLegacy), provider == .learning {
            reminderGeneration += 1
            let center = UNUserNotificationCenter.current()
            center.removeAllPendingNotificationRequests()
            center.removeAllDeliveredNotifications()
            publishWidgets()
        }
        return owner
    }

    func logout(_ provider: CampusProvider) throws -> Object {
        guard state["running"] as? Bool != true else { throw AppError("请在同步完成后退出账号") }
        try Keychain.remove("portal." + provider.rawValue)
        store.settings.removeObject(forKey: "connected." + provider.rawValue)
        if provider == .learning {
            reminderGeneration += 1
            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        }
        publishWidgets()
        return accounts
    }

    func autoStart() {
        guard hasAccount(.learning), let owner = try? store.owner(.learning) else { return }
        let last = max(store.settings.double(forKey: "lastHomework." + owner),
                       store.settings.double(forKey: "lastHomeworkCheck." + owner))
        if Date().timeIntervalSince1970 - last >= 43_200 {
            start("learning", scope: "homework")
        }
    }

    @discardableResult
    func start(_ selected: String, scope: String) -> Object {
        if state["running"] as? Bool == true { return state }
        let id = UUID().uuidString
        state = ["running": true, "id": id]
        Task {
            var failures = [String]()
            var expired = [String]()
            var homeworkComplete: Bool?
            var homeworkIssue: String?
            let providers = CampusProvider.allCases.filter { selected == "all" ? hasAccount($0) : selected == $0.rawValue }
            for provider in providers {
                if provider == .learning && scope != "courses" {
                    homeworkComplete = false
                    homeworkIssue = "暂时无法确认学习通账号或读取本机缓存。"
                }
                guard let owner = try? selectCache(provider, adoptLegacy: true) else { failures.append(provider.rawValue); continue }
                do {
                    if provider == .learning {
                        var incomplete = false
                        if scope != "homework" {
                            do { try await timetable(full: scope != "current", owner: owner) }
                            catch is SessionExpired { throw SessionExpired() }
                            catch { incomplete = true }
                        }
                        if scope != "courses" {
                            store.settings.set(Date().timeIntervalSince1970, forKey: "lastHomeworkCheck." + owner)
                            homeworkIssue = "学习通的作业列表暂未读取，或本机记录未能保存。"
                            homeworkIssue = try await homework(owner: owner)
                            homeworkComplete = homeworkIssue == nil
                            if homeworkIssue == nil {
                                store.settings.set(Date().timeIntervalSince1970, forKey: "lastHomework." + owner)
                            } else { incomplete = true }
                        }
                        if incomplete { failures.append(provider.rawValue) }
                    } else { try await school(owner: owner) }
                } catch is SessionExpired {
                    if (try? store.owner(provider)) == owner {
                        store.settings.set(false, forKey: "connected." + provider.rawValue)
                        expired.append(provider.rawValue)
                    } else { failures.append(provider.rawValue) }
                } catch let error as LearningRateLimited {
                    if provider == .learning && scope != "courses" { homeworkIssue = error.errorDescription }
                    failures.append(provider.rawValue)
                } catch { failures.append(provider.rawValue) }
            }
            publishWidgets()
            await scheduleReminders()
            state = ["id": id, "running": false, "success": !providers.isEmpty && failures.isEmpty && expired.isEmpty, "needLogin": expired]
            if let homeworkComplete { state["homeworkComplete"] = homeworkComplete }
            if let homeworkIssue { state["homeworkIssue"] = homeworkIssue }
            if let provider = queuedConnections.first {
                let selected = queuedConnections.count > 1 ? "all" : provider.rawValue
                queuedConnections.removeAll()
                start(selected, scope: "all")
            }
        }
        return state
    }

    private func learningOwner() throws -> String {
        guard let uid = try http.cookies(.learning).first(where: { ["_uid", "UID", "PUID"].contains($0.name) })?.value else {
            throw AppError("请先连接学习通")
        }
        return SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func trackingKey() throws -> String {
        if let owner = try store.owner(.learning) { return "tracking." + owner }
        return "tracking." + (try learningOwner())
    }

    private func tracking() throws -> Tracking {
        try Keychain.read(trackingKey(), as: Tracking.self) ?? Tracking()
    }

    func trackingStatus() -> Object {
        guard let key = try? trackingKey(), let value = try? tracking() else {
            return ["custom": false, "selected": [String](), "courses": [Object]()]
        }
        return ["account": key, "custom": value.selected != nil, "selected": value.selected ?? [],
                "teacherMetadataRead": true, "courses": value.courses.map { course in
            ["id": course.key, "name": course.name, "teacher": course.teacher,
             "tracked": value.selected?.contains(course.key) ?? true] as Object
        }]
    }

    func saveTracking(_ args: Object) throws -> Object {
        guard state["running"] as? Bool != true else { throw AppError("当前同步尚未结束，请稍后保存") }
        let key = try trackingKey()
        guard args.text("account") == key else { throw AppError("学习通账号已变化") }
        var value = try tracking()
        let selected = args["selected"] as? [String] ?? []
        guard Set(selected).isSubset(of: Set(value.courses.map(\.key))) else { throw AppError("请重新选择课程") }
        value.selected = args["custom"] as? Bool == true ? selected : nil
        try Keychain.write(key, value)
        publishWidgets()
        start("learning", scope: "homework")
        return trackingStatus()
    }

    func refreshCourses() async throws -> Object {
        let key = try trackingKey()
        guard key == "tracking." + (try learningOwner()) else { throw AppError("学习通账号已变化") }
        let (body, _) = try await http.page(URL(string: "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1")!,
                                           provider: .learning, referer: URL(string: "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction"))
        let record = try json(body)
        guard let channels = record["channelList"] as? [Object] else {
            if body.contains("请登录") || body.contains("未登录") { throw SessionExpired() }
            throw AppError("学习通课程列表暂未返回")
        }
        var seen = Set<String>()
        let courses = channels.compactMap { row -> Course? in
            guard let content = row["content"] as? Object, let course = (content["course"] as? Object)?.rows("data").first else { return nil }
            let result = Course(id: course.text("id"), classId: content.text("id"), cpi: row.text("cpi", content.text("cpi")),
                                name: course.text("name"), teacher: course.text("teacherfactor", course.text("teacherName")))
            return [result.id, result.classId, result.cpi, result.name].contains(where: \.isEmpty) || !seen.insert(result.key).inserted ? nil : result
        }
        guard channels.isEmpty || !courses.isEmpty else { throw AppError("学习通课程格式暂未识别") }
        guard try trackingKey() == key, key == "tracking." + (try learningOwner()) else { throw AppError("学习通账号已变化") }
        var value = try tracking()
        value.courses = courses
        value.updatedAt = Date()
        try Keychain.write(key, value)
        return trackingStatus()
    }

    private func homework(owner: String) async throws -> String? {
        var choice = try tracking()
        if choice.selected?.isEmpty != true && (choice.selected == nil || Date().timeIntervalSince(choice.updatedAt) >= 21_600 ||
            !(Set(choice.selected ?? []).isSubset(of: Set(choice.courses.map(\.key))))) {
            _ = try await refreshCourses()
            choice = try tracking()
        }
        let courses = choice.courses.filter { choice.selected?.contains($0.key) ?? true }
        let expected = Set(choice.selected ?? courses.map(\.key))
        let history = try store.read().rows("tasks")
        let known = Dictionary(history.map { ($0.text("id"), $0) }, uniquingKeysWith: { first, _ in first })
        var rows = [Object]()
        var readGroups = Set<String>()
        var refreshed = Set<String>()
        var missingDeadlines = 0
        func collect(_ result: (String, Data?)) throws {
            let (id, data) = result
            guard let data, let decoded = try JSONSerialization.jsonObject(with: data) as? [Object] else { return }
            rows += decoded
            readGroups.insert(id)
            let unknown = decoded.filter { CampusProjection.isPending($0.text("status")) && $0.number("deadlineAt") <= 0 }.count
            missingDeadlines += unknown
            if unknown == 0 { refreshed.insert(id) }
        }
        try await withThrowingTaskGroup(of: (String, Data?).self) { group in
            for (index, course) in courses.enumerated() {
                if index >= 2, let result = try await group.next() { try collect(result) }
                group.addTask { @MainActor in
                    do {
                        let rows = try await self.courseHomework(course, known: known)
                        return (course.key, try JSONSerialization.data(withJSONObject: rows))
                    }
                    catch is SessionExpired { throw SessionExpired() }
                    catch let error as LearningRateLimited { throw error }
                    catch { return (course.key, nil) }
                }
            }
            for try await result in group { try collect(result) }
        }
        try store.requireOwner(.learning, owner)
        guard try learningOwner() == owner else { throw AppError("学习通账号已变化") }
        let failed = expected.subtracting(refreshed)
        let received = Set(rows.map { $0.text("id") })
        rows = rows.map { row in
            guard CampusProjection.isPending(row.text("status")), row.number("deadlineAt") <= 0,
                  let previous = known[row.text("id")], previous.number("deadlineAt") > 0 else { return row }
            var retained = row
            retained["deadlineAt"] = previous.number("deadlineAt")
            retained["deadline"] = previous.text("deadline")
            return retained
        }
        rows += history.filter { $0.text("source") != "manual" && failed.contains($0.text("groupId")) && !received.contains($0.text("id")) }
        var value = try store.read()
        store.replace("tasks", rows: rows, in: &value)
        try commit(value)
        guard !failed.isEmpty else { return nil }
        let unread = courses.count - readGroups.count
        let missingTracked = expected.count - courses.count
        var issues = [String]()
        if unread > 0 { issues.append("\(unread) 门课程的作业暂未读取") }
        if missingDeadlines > 0 { issues.append("\(missingDeadlines) 项作业的截止时间待确认") }
        if missingTracked > 0 { issues.append("\(missingTracked) 门追踪课程未在目录找到，可在「课程追踪」核对") }
        return issues.joined(separator: "，") + "。"
    }

    private func courseHomework(_ course: Course, known: [String: Object]) async throws -> [Object] {
        let entry = address("https://mooc1.chaoxing.com/visit/stucoursemiddle", [
            "courseid": course.id, "clazzid": course.classId, "cpi": course.cpi, "ismooc2": "1", "v": "2"])
        let (body, referer) = try await http.page(entry, provider: .learning)
        let doc = try SwiftSoup.parse(body)
        func value(_ id: String) throws -> String { try doc.select(id).first()?.attr("value") ?? "" }
        let workEnc = try value("#workEnc")
        guard !workEnc.isEmpty else { throw AppError("课程作业入口暂未返回") }
        var enc = try value("#enc")
        if enc.isEmpty { enc = URLComponents(url: referer, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "enc" }?.value ?? "" }
        guard !enc.isEmpty else { throw AppError("课程作业签名未返回") }
        let url = address("https://mooc1.chaoxing.com/mooc2/work/list", ["courseId": course.id, "classId": course.classId,
            "cpi": course.cpi, "ut": "s", "openc": try value("#openc"), "enc": workEnc, "stuenc": enc,
            "t": String(CampusProjection.milliseconds(Date())), "isdisplaytable": "2"])
        let (html, base) = try await http.page(url, provider: .learning, referer: referer)
        guard html.range(of: "无权限|无效的用户|没有此页面访问权限", options: .regularExpression) == nil else {
            throw AppError("暂时无法读取这门课的作业")
        }
        let list = try SwiftSoup.parse(html)
        let nodes = try list.select("li[data], li[aria-label]").array()
        var rows = [Object]()
        var seen = Set<String>()
        for item in nodes {
            let labels = try item.attr("aria-label").components(separatedBy: ";")
            let title = try item.select(".overHidden2, .right-content .title").first()?.text() ?? labels.first ?? ""
            guard !title.isEmpty, try item.select(".right-content, .overHidden2, .status").first() != nil else { continue }
            var status = try item.select(".status").first()?.text() ?? labels.dropFirst().joined(separator: ";")
            let time = try item.select(".time").first()?.text() ?? ""
            let text = ([status, time] + Array(labels.dropFirst())).joined(separator: " ")
            if text.range(of: "已过期|已截止|已结束|已超时|已逾期|逾期|已超期|已过截止|过期未交", options: .regularExpression) != nil { status += " 已过期" }
            if text.range(of: "截止时间未提供|截止时间未知|未设置截止|无截止时间|不限时|永久有效", options: .regularExpression) != nil { status += " 截止时间未提供" }
            var link = try item.attr("data")
            if link.isEmpty { link = try item.select("a[href]").first()?.attr("href") ?? "" }
            if link.lowercased().hasPrefix("http%") || link.lowercased().hasPrefix("%2f") { link = link.removingPercentEncoding ?? link }
            let resolved = link.isEmpty ? nil : URL(string: link, relativeTo: base)?.absoluteURL
            let taskURL = resolved.flatMap { CampusProvider.learning.allows($0) ? $0 : nil }
            let workId = taskURL.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name.lowercased() == "workid" }?.value }
            let id = "work:" + course.key + ":" + (workId ?? title)
            guard seen.insert(id).inserted else { continue }
            var deadline = try Self.accessibleDeadline(item)
            if deadline == 0 { deadline = Self.labelledDeadline(text) }
            if deadline == 0 { deadline = Self.deadline(time) }
            var checked = 0
            if deadline == 0, CampusProjection.isPending(status), let taskURL {
                let now = CampusProjection.milliseconds(Date())
                let previous = known[id]
                if let previous, previous.number("deadlineAt") > now,
                   (0..<21_600_000).contains(now - previous.number("deadlineCheckedAt")) {
                    deadline = previous.number("deadlineAt")
                    checked = previous.number("deadlineCheckedAt")
                } else {
                    do {
                        let (detail, _) = try await http.page(taskURL, provider: .learning, referer: base)
                        let page = try SwiftSoup.parse(detail)
                        deadline = try Self.accessibleDeadline(page)
                        if deadline == 0 { deadline = Self.labelledDeadline(try page.text()) }
                        if deadline > 0 { checked = now }
                    } catch is SessionExpired { throw SessionExpired() }
                    catch let error as LearningRateLimited { throw error }
                    catch { deadline = 0 }
                }
            }
            rows.append(["id": id, "name": title, "course": course.name, "groupId": course.key, "status": status,
                         "deadlineAt": deadline, "deadlineCheckedAt": checked, "deadline": deadline > 0
                         ? CampusProjection.format(Date(timeIntervalSince1970: Double(deadline) / 1000), "yyyy-MM-dd HH:mm") : "截止时间未提供",
                         "url": taskURL?.absoluteString ?? "", "source": "school", "provider": "learning"])
        }
        if rows.isEmpty {
            let emptyProgress = html.range(of: #"<span>\s*0\s*/\s*0\s*</span>\s*</div>\s*</div>"#, options: [.regularExpression, .caseInsensitive]) != nil
            let emptyText = try list.text().range(of: #"(?:暂无|没有)作业(?!权限|入口|功能)|共\s*0\s*[份个]"#, options: .regularExpression) != nil
            guard emptyProgress || emptyText else { throw AppError("作业列表格式暂未识别") }
        }
        return rows
    }

    private func timetable(full: Bool, owner: String) async throws {
        let local = try store.read()["settings"] as? Object ?? [:]
        let week = min(max(CampusProjection.week(local.text("semesterStart"), Date()) ?? 1, 1), max(1, min(local.number("teachingWeeks"), 60)))
        let first = try await weekPage(week)
        guard let curriculum = first["curriculum"] as? Object else { throw AppError("尚未返回学期数据") }
        let limit = min(max(curriculum.number("maxWeek") == 0 ? 30 : curriculum.number("maxWeek"), 1), 60)
        let primaryStart = curriculum.number("firstWeekDate")
        let startMs = primaryStart > 0 ? primaryStart : curriculum.number("firstWeekDateReal")
        let start = startMs > 0 ? CampusProjection.format(Date(timeIntervalSince1970: Double(startMs) / 1000), "yyyy-MM-dd") : ""
        let actualStart = local["customTimetable"] as? Bool == true || start.isEmpty ? local.text("semesterStart") : start
        let current = min(max(CampusProjection.week(actualStart, Date()) ?? 1, 1), limit)
        let prefs = store.settings
        let signature = start + ":" + String(limit)
        let newSemester = local.text("semesterStart").isEmpty == false && !start.isEmpty && start != local.text("semesterStart") && local["customTimetable"] as? Bool != true
        let needsFull = newSemester || (full && (prefs.string(forKey: "scheduleSignature." + owner) != signature ||
            Date().timeIntervalSince1970 - prefs.double(forKey: "lastSchedule." + owner) >= 86_400))
        let currentPage = current == week ? first : try await weekPage(current)
        var pages = [current: currentPage]
        if needsFull {
            for number in 1...limit where number != current {
                pages[number] = number == week ? first : try await weekPage(number)
            }
        }

        try store.requireOwner(.learning, owner)
        guard try learningOwner() == owner else { throw AppError("学习通账号已变化") }
        var rows = [String: Object]()
        var value = try store.read()
        for row in value.rows("courses") where row.text("source") != "manual" && !needsFull {
            let remaining = (row["weeks"] as? [Int] ?? []).filter { pages[$0] == nil }
            if !remaining.isEmpty { var updated = row; updated["weeks"] = remaining; rows[row.text("id")] = updated }
        }
        for (number, page) in pages {
            guard let raw = page["lessonArray"] as? [Object] else { throw AppError("课表格式暂未识别") }
            for row in raw {
                let day = row.number("dayOfWeek"), first = row.number("beginNumber"), length = row.number("length")
                guard (1...7).contains(day), (1...16).contains(first), (1...16).contains(length),
                      first + length <= 17, !row.text("name").isEmpty else { throw AppError("课表节次暂未识别") }
                let id = "cx:" + [row.text("lessonId"), row.text("name"), String(day), String(first), String(length),
                                  row.text("location"), row.text("teacherName")].joined(separator: "|")
                let weeks = (rows[id]?["weeks"] as? [Int] ?? []) + [number]
                rows[id] = ["id": id, "name": row.text("name"), "day": day, "weeks": weeks.sorted(), "sections": Array(first..<first + length),
                            "room": row.text("location"), "teacher": row.text("teacherName"), "source": "school", "provider": "learning"]
            }
        }
        store.replace("courses", rows: Array(rows.values), in: &value)
        var settings = value["settings"] as? Object ?? [:]
        if !actualStart.isEmpty && settings["customTimetable"] as? Bool != true { settings["semesterStart"] = actualStart }
        if let periods = curriculum["lessonTimeConfigArray"] as? [String] {
            let normalized = CampusProjection.normalizeTimes(periods)
            if !normalized.isEmpty { settings["officialTimes"] = normalized }
        }
        settings["teachingWeeks"] = limit
        value["settings"] = settings
        try commit(value)
        if needsFull {
            prefs.set(signature, forKey: "scheduleSignature." + owner)
            prefs.set(Date().timeIntervalSince1970, forKey: "lastSchedule." + owner)
        }
    }

    private func weekPage(_ week: Int) async throws -> Object {
        let (body, _) = try await http.page(URL(string: "https://kb.chaoxing.com/pc/curriculum/getMyLessons")!,
                                          provider: .learning, form: ["week": String(week)])
        let response = try json(body)
        guard response.number("result") == 1, let value = response["data"] as? Object else { throw AppError("课表未返回") }
        return value
    }

    private func school(owner: String) async throws {
        let (html, _) = try await http.page(CampusProvider.school.home, provider: .school)
        let menu = try SwiftSoup.parse(html)
        let year = try menu.select("#xnm option[selected]").first()?.attr("value") ?? ""
        let term = try menu.select("#xqm option[selected]").first()?.attr("value") ?? ""
        let paths = ["grades": "/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005",
                     "exams": "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"]
        for kind in ["grades", "exams"] {
            var all = [Object]()
            var complete = false
            for page in 1...30 {
                let filters = ["xnm": kind == "grades" ? "" : year, "xqm": kind == "grades" ? "" : term,
                               "_search": "false", "nd": String(CampusProjection.milliseconds(Date())),
                               "queryModel.showCount": "100", "queryModel.currentPage": String(page),
                               "queryModel.sortName": "", "queryModel.sortOrder": "asc", "time": "0"]
                let (body, _) = try await http.page(URL(string: "https://jwxt.stbu.edu.cn" + paths[kind]!)!,
                                                  provider: .school, form: filters, referer: CampusProvider.school.home)
                let result = try json(body)
                guard let items = result["items"] as? [Object] else { throw AppError("教务列表未返回") }
                all += try items.map { row in
                    let name = row.text("kcmc")
                    guard !name.isEmpty else { throw AppError("教务数据格式暂未识别") }
                    var entry: Object = ["name": name, "source": "school", "provider": "school"]
                    if kind == "grades" {
                        let code = row.text("kch_id", row.text("kch", name))
                        entry["id"] = ["grade", code, row.text("xnm"), row.text("xqm"), row.text("ksxz")].joined(separator: ":")
                        entry["score"] = row.text("cj", row.text("zhcj"))
                        entry["credits"] = row.text("xf")
                        entry["term"] = row.text("xnmmc", row.text("xnm")) + " " + row.text("xqmmc")
                        entry["code"] = code
                        entry["category"] = row.text("kcxzmc", row.text("kclbmc"))
                    } else {
                        let time = row.text("kssj")
                        entry["id"] = ["exam", row.text("kch", name), time, row.text("ksmc")].joined(separator: ":")
                        entry["time"] = time
                        entry["date"] = Self.examDate(time)
                        entry["room"] = row.text("cdmc")
                        entry["seat"] = row.text("zwh")
                    }
                    return entry
                }
                if items.count < 100 || (result.number("totalPage") > 0 && page >= result.number("totalPage")) { complete = true; break }
            }
            guard complete else { throw AppError("教务列表分页未完成") }
            try store.requireOwner(.school, owner)
            var value = try store.read()
            store.replace(kind, rows: all, in: &value)
            try commit(value)
        }
    }

    private func commit(_ value: Object) throws {
        var value = value
        value["updatedAt"] = ISO8601DateFormatter().string(from: Date())
        try store.save(value)
    }

    func publishWidgets() {
        guard let data = try? store.read() else { return }
        let keys: Set<String> = ["semesterStart", "summerFrom", "summerTo", "summerTimes", "winterTimes", "officialTimes"]
        let settings = (data["settings"] as? Object ?? [:]).filter { keys.contains($0.key) }
        let courses = data.rows("courses").map { row in
            ["name": row.text("name"), "room": row.text("room"), "day": row.number("day"),
             "weeks": row["weeks"] as? [Int] ?? [], "sections": row["sections"] as? [Int] ?? []] as Object
        }
        var widgetData: Object = ["courses": courses, "settings": settings]
        let appearance = store.preferences
        for key in ["emptyDayMessage", "emptyTasksMessage"] {
            widgetData[key] = appearance[key] as? String
        }
        let selection = (try? tracking())?.selected.map { Set($0) }
        widgetData["tasks"] = data.rows("tasks").filter { selection?.contains($0.text("groupId")) ?? true }.map { task in
            ["name": task.text("name"), "course": task.text("course"), "status": task.text("status"), "deadlineAt": task.number("deadlineAt")] as Object
        }
        if WidgetData.write(widgetData) { WidgetCenter.shared.reloadAllTimelines() }
    }

    func reminderStatus() async -> Object {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return ["enabled": store.settings.bool(forKey: "homeworkReminders"), "permission": status == .authorized || status == .provisional]
    }

    func configureReminders(_ enabled: Bool) async throws -> Object {
        if enabled { _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
        store.settings.set(enabled, forKey: "homeworkReminders")
        await scheduleReminders()
        return await reminderStatus()
    }

    func scheduleReminders() async {
        reminderGeneration += 1
        let generation = reminderGeneration
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        guard store.settings.bool(forKey: "homeworkReminders"), let data = try? store.read() else { return }
        let selection = (try? tracking())?.selected.map { Set($0) }
        let tasks = data.rows("tasks").filter { selection?.contains($0.text("groupId")) ?? true }
        let now = Date()
        let pending = CampusProjection.pending(tasks, at: now)
        let notices = pending.flatMap { item in
            [24, 3].map { hours in (item, hours, item.date.addingTimeInterval(-Double(hours) * 3600)) }
        }.filter { $0.2 > now }.sorted { $0.2 < $1.2 }.prefix(60)
        for (index, notice) in notices.enumerated() {
            guard reminderGeneration == generation else { return }
            let identifier = "homework-\(generation)-\(index)"
            let content = UNMutableNotificationContent()
            content.title = "作业还有 \(notice.1) 小时截止"
            content.body = notice.0.name
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, notice.2.timeIntervalSinceNow), repeats: false)
            try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
            if reminderGeneration != generation {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                return
            }
        }
    }

    private func json(_ text: String) throws -> Object {
        guard let result = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? Object else { throw AppError("官方返回的数据格式暂未识别") }
        return result
    }

    private func address(_ base: String, _ fields: [String: String]) -> URL { URL(string: base + "?" + OfficialHTTP.form(fields))! }

    private static func examDate(_ time: String) -> String {
        guard let range = time.range(of: #"\d{4}[-/]\d{1,2}[-/]\d{1,2}"#, options: .regularExpression) else { return "" }
        let parts = time[range].split(whereSeparator: { $0 == "-" || $0 == "/" }).compactMap { Int($0) }
        return parts.count == 3 ? String(format: "%04d-%02d-%02d", parts[0], parts[1], parts[2]) : ""
    }

    private static let homeworkDate = #"(?:(20\d{2})[-/年])?(\d{1,2})[-/月](\d{1,2})(?:日\s*|[T\s]+)(\d{1,2})(?::|时)(\d{2})(?::(\d{2})|分(?:(\d{1,2})秒)?)?"#

    private static func accessibleDeadline(_ element: Element) throws -> Int {
        for node in try element.select("[aria-label]").array() {
            let due = labelledDeadline(try node.attr("aria-label"))
            if due > 0 { return due }
        }
        return 0
    }

    private static func labelledDeadline(_ value: String) -> Int {
        let text = value.replacingOccurrences(of: "：", with: ":")
        let labelled = #"(?:截止(?:时间|日期)?|结束(?:时间|日期))\s*:?\s*"# + homeworkDate
        let period = #"(?:作答时间|答题时间)\s*:?\s*"# + homeworkDate + #"\s*(?:至|~|～|—|–|-)\s*"# + homeworkDate
        guard let range = text.range(of: labelled, options: .regularExpression) ?? text.range(of: period, options: .regularExpression) else { return 0 }
        return deadline(String(text[range]))
    }

    private static func deadline(_ value: String) -> Int {
        let text = value.replacingOccurrences(of: "：", with: ":")
        guard let pattern = try? NSRegularExpression(pattern: homeworkDate) else { return 0 }
        let matches = pattern.matches(in: text, range: NSRange(text.startIndex..., in: text))
        if matches.count == 1, text.contains("开始"), text.range(of: "截止|结束|至", options: .regularExpression) == nil { return 0 }
        guard let match = matches.last else { return 0 }
        func group(_ index: Int) -> Int? { Range(match.range(at: index), in: text).flatMap { Int(text[$0]) } }
        let calendar = CampusProjection.calendar
        let now = Date()
        let year = calendar.component(.year, from: now)
        let years = group(1).map { [$0] } ?? [year - 1, year, year + 1]
        return years.compactMap { candidate -> Date? in
            let parts = DateComponents(year: candidate, month: group(2), day: group(3), hour: group(4), minute: group(5), second: group(6) ?? group(7) ?? 0)
            return parts.isValidDate(in: calendar) ? calendar.date(from: parts) : nil
        }.min { abs($0.timeIntervalSince(now)) < abs($1.timeIntervalSince(now)) }.map(CampusProjection.milliseconds) ?? 0
    }
}
