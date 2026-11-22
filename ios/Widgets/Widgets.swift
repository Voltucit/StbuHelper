import SwiftUI
import WidgetKit

private struct CampusEntry: TimelineEntry {
    let date: Date
    let homework: Bool
    let item: CampusProjection.Item?
    let courseEnd: Date?
    let count: Int
    let emptyMessage: String

    static func defaultMessage(homework: Bool) -> String {
        homework ? "好厉害，作业全部都做完了！" : "今天没有课程哦，可以好好休息"
    }
}

private struct CampusTimeline: TimelineProvider {
    let homework: Bool

    func placeholder(in context: Context) -> CampusEntry {
        CampusEntry(date: .now, homework: homework, item: nil, courseEnd: nil,
                    count: 0, emptyMessage: CampusEntry.defaultMessage(homework: homework))
    }

    func getSnapshot(in context: Context, completion: @escaping (CampusEntry) -> Void) {
        completion(entries(WidgetData.read(), from: Date()).first ?? placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<CampusEntry>) -> Void) {
        let data = WidgetData.read()
        let now = Date()
        let midnight = CampusProjection.nextMidnight(after: now)
        // Give WidgetKit a dated entry for the next day in case a reload is delayed.
        let timeline = entries(data, from: now) + entries(data, from: midnight)
        let reload = min(midnight, now.addingTimeInterval(6 * 3600))
        completion(Timeline(entries: timeline, policy: .after(reload)))
    }

    private func entries(_ data: [String: Any], from now: Date) -> [CampusEntry] {
        let midnight = CampusProjection.nextMidnight(after: now)
        let key = homework ? "emptyTasksMessage" : "emptyDayMessage"
        let saved = (data[key] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let message = saved.isEmpty ? CampusEntry.defaultMessage(homework: homework) : saved
        let tasks = homework
            ? CampusProjection.pending(data["tasks"] as? [[String: Any]] ?? [], at: now) : []
        let courses = homework ? [] : CampusProjection.todayCourses(data, at: now)
        let courseChanges = courses.flatMap { [$0.item.date] + ($0.endsAt.map { [$0] } ?? []) }
        let dates = Set(tasks.map(\.date) + courseChanges).filter { $0 > now && $0 < midnight }.sorted()
        let changeTimes = Array(dates.prefix(24))
        let lastChange = dates.last.flatMap { changeTimes.last == $0 ? nil : $0 }
        return ([now] + changeTimes + (lastChange.map { [$0] } ?? [])).map { date in
            if homework {
                let remaining = tasks.drop(while: { $0.date <= date })
                return CampusEntry(date: date, homework: true, item: remaining.first, courseEnd: nil,
                                   count: remaining.count, emptyMessage: message)
            }
            let next = courses.first { $0.item.date > date }
            let current = next == nil ? courses.last { slot in
                slot.item.date <= date && slot.endsAt.map { $0 > date } == true
            } : nil
            return CampusEntry(date: date, homework: false, item: next?.item ?? current?.item, courseEnd: current?.endsAt,
                               count: 0, emptyMessage: message)
        }
    }
}

private struct CampusWidgetView: View {
    let entry: CampusEntry
    @Environment(\.widgetFamily) private var size

    private var title: String {
        if entry.homework { return "未完成作业" }
        return entry.courseEnd == nil ? "下一节课" : "上课中"
    }

    private var header: some View {
        Label(title, systemImage: entry.homework ? "checklist" : "calendar")
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color(red: 0.19, green: 0.43, blue: 0.33))
            .lineLimit(1)
    }

    private func description(_ item: CampusProjection.Item) -> String {
        let time: String
        if let courseEnd = entry.courseEnd {
            time = CampusProjection.format(courseEnd, "HH:mm") + " 下课"
        } else if entry.homework {
            time = homeworkDeadline(item.date)
        } else {
            time = CampusProjection.format(item.date, "HH:mm")
        }
        return [time, item.detail].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func homeworkDeadline(_ deadline: Date) -> String {
        let calendar = CampusProjection.calendar
        let day: String
        if calendar.isDate(deadline, inSameDayAs: entry.date) {
            day = "今天"
        } else if calendar.isDate(deadline, inSameDayAs: CampusProjection.nextMidnight(after: entry.date)) {
            day = "明天"
        } else {
            let sameYear = calendar.isDate(deadline, equalTo: entry.date, toGranularity: .year)
            day = CampusProjection.format(deadline, sameYear ? "M月d日" : "yyyy年M月d日")
        }
        return "\(day) \(CampusProjection.format(deadline, "HH:mm")) 截止"
    }

    private var itemContent: some View {
        VStack(alignment: .leading, spacing: 3) {
            if let item = entry.item {
                Text(item.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(description(item)).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            } else {
                Text(entry.emptyMessage).font(.subheadline).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var content: some View {
        Group {
            if size == .systemMedium {
                HStack(spacing: 13) {
                    header
                        .padding(.trailing, 11)
                        .overlay(alignment: .trailing) { Divider().frame(height: 30) }
                    itemContent
                    if entry.count > 1 {
                        Text("\(entry.count) 项").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            } else {
                VStack(alignment: .leading, spacing: 7) {
                    header
                    itemContent
                    if entry.count > 1 {
                        Text("\(entry.count) 项").font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .widgetURL(URL(string: entry.homework ? "stbu-helper://tasks" : "stbu-helper://week"))
    }

    var body: some View {
        if #available(iOSApplicationExtension 17.0, *) {
            content.containerBackground(.background, for: .widget)
        } else {
            content.padding()
        }
    }
}

@main
struct StbuWidgets: WidgetBundle {
    var body: some Widget {
        CampusWidget(kind: "StbuNextCourse", homework: false)
        CampusWidget(kind: "StbuHomework", homework: true)
    }
}

private struct CampusWidget: Widget {
    let kind: String
    let homework: Bool
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: CampusTimeline(homework: homework)) { entry in
            CampusWidgetView(entry: entry)
        }
        .configurationDisplayName(homework ? "未完成作业" : "下一节课")
        .description(homework ? "显示追踪课程中最早截止的未交作业。" : "显示今天的下一节课、时间和教室。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
