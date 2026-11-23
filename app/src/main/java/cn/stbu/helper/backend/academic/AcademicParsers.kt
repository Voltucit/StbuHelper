package cn.stbu.helper.backend.academic

import cn.stbu.helper.backend.auth.PortalPolicy

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.time.*
import java.time.format.DateTimeFormatter

data class LearningCourse(val id: String, val classId: String, val cpi: String, val name: String, val teacher: String = "")
data class TaskRecord(val id: String, val name: String, val course: String, val group: String, val status: String, val deadlineAt: Long, val url: String, val deadlineCheckedAt: Long = 0, val missingListTime: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("name", name).put("course", course).put("groupId", group).put("status", status)
        .put("deadlineAt", deadlineAt).put("deadline", if (deadlineAt > 0) Instant.ofEpochMilli(deadlineAt).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) else "截止时间未提供")
        .put("url", url).put("deadlineCheckedAt", deadlineCheckedAt).put("source", "school").put("provider", "learning")
}

object AcademicParsers {
    private val zone = ZoneId.of("Asia/Shanghai")
    private const val HOMEWORK_DATE = """(?:(20\d{2})[-/年])?(\d{1,2})[-/月](\d{1,2})(?:日\s*|[T\s]+)(\d{1,2})(?::|时)(\d{2})(?::(\d{2})|分(?:(\d{1,2})秒)?)?"""
    private val absoluteHomeworkDate = Regex(HOMEWORK_DATE)
    private val labelledDeadline = Regex("""(?:截止(?:时间|日期)?|结束(?:时间|日期))\s*:?\s*$HOMEWORK_DATE""")
    private val answerPeriod = Regex("""(?:作答时间|答题时间)\s*:?\s*$HOMEWORK_DATE\s*(?:至|~|～|—|–|-)\s*$HOMEWORK_DATE""")
    private val noHomework = Regex("""(?:暂无|没有)作业(?!权限|入口|功能)|共\s*0\s*[份个]""")
    private val emptyHomeworkProgress = Regex("""<span>\s*0\s*/\s*0\s*</span>\s*</div>\s*</div>""", RegexOption.IGNORE_CASE)
    private val mobileClosed = Regex("""^(?:已完成|已提交|已交|待批阅|已批阅|已作答|已截止|已结束|已过期|已超时|已超期|已逾期|逾期未交|过期未交|无截止时间|截止时间未提供)$""")

    fun confirmsNoHomework(html: String, text: String) =
        noHomework.containsMatchIn(text) || emptyHomeworkProgress.containsMatchIn(html)
    fun learningCourses(json: JSONObject): List<LearningCourse> {
        val channels = json.optJSONArray("channelList") ?: throw IllegalStateException("学习通课程列表格式发生变化")
        val courses = (0 until channels.length()).mapNotNull { i ->
            val channel = channels.optJSONObject(i) ?: return@mapNotNull null
            val content = channel.optJSONObject("content") ?: return@mapNotNull null
            val course = content.optJSONObject("course")?.optJSONArray("data")?.optJSONObject(0) ?: return@mapNotNull null
            val id = course.optString("id"); val classId = content.optString("id"); val cpi = channel.optString("cpi", content.optString("cpi"))
            val teacher = listOf("teacherfactor", "teacherName").asSequence().mapNotNull { course.opt(it) as? String }
                .map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "null" }.orEmpty()
            if (id.isBlank() || classId.isBlank() || cpi.isBlank()) null else LearningCourse(id, classId, cpi, course.optString("name", content.optString("name")), teacher)
        }.distinctBy { "${it.id}:${it.classId}" }
        check(channels.length() == 0 || courses.isNotEmpty()) { "学习通课程列表暂未识别，保留本机作业" }
        return courses
    }

    fun homework(document: Element, course: LearningCourse, baseUrl: String, now: Long): List<TaskRecord> =
        document.select("li[data], li[aria-label]").mapNotNull { item ->
            val label = item.attr("aria-label").split(';')
            val title = item.selectFirst(".overHidden2, .right-content .title")?.text()?.trim().orEmpty().ifBlank { label.firstOrNull().orEmpty().trim() }
            if (title.isBlank() || item.selectFirst(".right-content, .overHidden2, .status") == null) return@mapNotNull null
            var status = item.selectFirst(".status")?.text()?.trim().orEmpty().ifBlank { label.drop(1).joinToString(";").trim() }
            val timing = item.selectFirst(".time")?.text().orEmpty()
            val statusText = (listOf(status, timing) + label.drop(1)).joinToString(" ")
            if (Regex("已过期|已截止|已结束|已超时|已逾期|逾期|已超期|已过截止|过期未交").containsMatchIn(statusText)) status += " 已过期"
            if (Regex("截止时间未提供|截止时间未知|未设置截止|无截止时间|不限时|永久有效").containsMatchIn(statusText)) status += " 截止时间未提供"
            val url = homeworkLink(item.attr("data").ifBlank { item.selectFirst("a[href]")?.attr("href").orEmpty() }, baseUrl)
            val workId = homeworkQuery(url)["workid"].orEmpty().ifBlank { title }
            val due = deadlineLabel(item, now).takeIf { it > 0 }
                ?: labelledTime(statusText, now).takeIf { it > 0 }
                ?: deadline(timing, now)
            // This layout removes .time for expired work but can leave its status as 未交.
            val missingListTime = due == 0L && timing.isBlank() && pending(status) &&
                item.`is`("li[onclick*=goTask][data]") && item.selectFirst(".overHidden2") != null &&
                item.select(".label").none { it.text().contains("互评") }
            TaskRecord("work:${course.id}:${course.classId}:$workId", title, course.name, "${course.id}:${course.classId}",
                if (missingListTime) "$status 截止时间未提供" else status, due, url, missingListTime = missingListTime)
        }.distinctBy { it.id }
    fun mobileHomework(html: String, courses: List<LearningCourse>, baseUrl: String, now: Long): HomeworkPage {
        val groups = courses.associateBy { "${it.id}:${it.classId}" }
        val document = Jsoup.parse(html)
        val text = document.text()
        if (Regex("无权限|无效的用户|没有此页面访问权限|没有作业权限").containsMatchIn(text)) throw HomeworkPageError("学习通拒绝读取移动端作业")
        val items = document.select("li[onclick*=goTask]")
        if (items.isEmpty() && !noHomework.containsMatchIn(text)) throw HomeworkPageError("移动端作业列表格式暂未识别")
        var complete = true
        val records = buildList {
            for (item in items) {
                val title = item.selectFirst("p")?.text().orEmpty()
                val url = homeworkLink(item.attr("data"), baseUrl)
                val fields = homeworkQuery(url)
                val courseId = fields["courseid"].orEmpty()
                val classId = (fields["clazzid"] ?: fields["classid"]).orEmpty()
                if (courseId.isBlank() || classId.isBlank()) { complete = false; continue }
                val group = "$courseId:$classId"
                val course = groups[group] ?: continue
                val ref = fields["taskrefid"].orEmpty()
                val status = item.selectFirst(".status")?.text().orEmpty().ifBlank {
                    item.select("[aria-label], span[aria-hidden=true]").asSequence()
                        .map { node -> node.attr("aria-label").substringAfter("作业状态", "").trim().ifBlank { node.ownText() } }
                        .firstOrNull { pending(it) || mobileClosed.matches(it) }.orEmpty()
                }
                val isPending = pending(status)
                if (title.isBlank() || ref.isBlank() || fields["type"] != "work" || (!isPending && !mobileClosed.matches(status))) {
                    complete = false
                    continue
                }
                if (isPending) {
                    val due = deadlineLabel(item, now)
                    val remaining = item.selectFirst(".fr")?.text().orEmpty()
                    val missingListTime = due == 0L && !remaining.contains("剩余")
                    add(TaskRecord("mobile-work:$group:$ref", title, course.name, group, status, due, url, missingListTime = missingListTime))
                }
            }
        }
        return HomeworkPage(records.distinctBy { it.id }, baseUrl, complete)
    }

    private fun homeworkLink(raw: String, baseUrl: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val link = if (raw.startsWith("http%", true) || raw.startsWith("%2F", true)) URLDecoder.decode(raw, "UTF-8") else raw
            val url = PortalPolicy.https(URI(baseUrl).resolve(link).toString())
            val host = URI(url).host.orEmpty().lowercase()
            url.takeIf { host == "chaoxing.com" || host.endsWith(".chaoxing.com") }?.takeIf(PortalPolicy::dataUrl).orEmpty()
        }.getOrDefault("")
    }

    private fun homeworkQuery(url: String): Map<String, String> =
        URI(url).rawQuery.orEmpty().split('&').filter { '=' in it }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }

    fun pending(status: String) = !Regex("已完成|已提交|已交|待批|已批|过期|逾期|已截止|已过截止|已结束|已作答|截止时间未提供|截止时间未知|无截止时间").containsMatchIn(status) && Regex("未交|未提交|未完成|待完成|待提交|未作答|未开始|进行中|待做").containsMatchIn(status)
    fun actionable(status: String, deadlineAt: Long, now: Long = System.currentTimeMillis()) = pending(status) && deadlineAt > now
    fun deadline(text: String, now: Long = System.currentTimeMillis()): Long {
        val absolute = absoluteHomeworkDate.findAll(text.replace('：', ':')).toList()
        if (absolute.isNotEmpty()) {
            // A labelled start alone is not a deadline. A range's last date is its end.
            if (absolute.size == 1 && text.contains("开始") && !Regex("截止|结束|至").containsMatchIn(text)) return 0
            val m = absolute.last()
            val year = m.groupValues[1].toIntOrNull()
            val seconds = m.groupValues[6].toIntOrNull() ?: m.groupValues[7].toIntOrNull() ?: 0
            val currentYear = Instant.ofEpochMilli(now).atZone(zone).year
            return (if (year == null) listOf(currentYear - 1, currentYear, currentYear + 1) else listOf(year)).mapNotNull { y ->
                runCatching { LocalDateTime.of(y, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt(), seconds).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            }.minByOrNull { kotlin.math.abs(it - now) } ?: 0
        }
        return 0 // Coarse “remaining N days” text is not an exact deadline.
    }

    fun detailDeadline(html: String, now: Long = System.currentTimeMillis()): Long {
        val document = Jsoup.parse(html)
        return deadlineLabel(document, now).takeIf { it > 0 } ?: labelledTime(document.text(), now)
    }

    private fun deadlineLabel(root: Element, now: Long): Long =
        root.select("[aria-label]").asSequence()
            .map { labelledTime(it.attr("aria-label"), now) }
            .firstOrNull { it > 0 } ?: 0

    private fun labelledTime(value: String, now: Long): Long {
        val text = value.replace('：', ':')
        val time = labelledDeadline.find(text) ?: answerPeriod.find(text) ?: return 0
        return deadline(time.value, now)
    }

    fun schoolRows(items: JSONArray, kind: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            val name = row.optString("kcmc"); if (name.isBlank()) continue
            val record = JSONObject().put("name", name).put("source", "school").put("provider", "school")
            if (kind == "grades") {
                val term = row.optString("xnm") + "/" + row.optString("xqm")
                record.put("id", "grade:${row.optString("kch_id", row.optString("kch", name))}:$term:${row.optString("ksxz")}")
                    .put("score", row.optString("cj", row.optString("zhcj"))).put("credits", row.optString("xf")).put("term", row.optString("xnmmc", row.optString("xnm")) + " " + row.optString("xqmmc"))
                    .put("code", row.optString("kch_id", row.optString("kch"))).put("category", row.optString("kcxzmc", row.optString("kclbmc")))
            } else {
                val time = row.optString("kssj")
                val dateMatch = Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").find(time)
                val date = dateMatch?.let { "${it.groupValues[1]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[3].padStart(2, '0')}" }.orEmpty()
                record.put("id", "exam:${row.optString("kch", name)}:$time:${row.optString("ksmc")}").put("date", date)
                    .put("time", time).put("room", row.optString("cdmc")).put("seat", row.optString("zwh"))
            }
            out.put(record)
        }
        return out
    }
}
