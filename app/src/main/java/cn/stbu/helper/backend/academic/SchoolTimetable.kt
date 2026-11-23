package cn.stbu.helper.backend.academic

import cn.stbu.helper.backend.auth.PortalPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object SchoolTimetable {
    private const val TABLE = "https://jwxt.stbu.edu.cn/kbcx/xskbcx_cx"
    private const val MODULE = ".html?gnmkdm=N2151"
    private const val INDEX = TABLE + "XskbcxIndex" + MODULE
    private const val CALENDAR = "https://jwxt.stbu.edu.cn/xtgl/index_cxAreaOne.html?localeKey=zh_CN&gnmkdm=index"

    data class Term(val year: String, val term: String) {
        val key = "$year-$term"
        val fields get() = mapOf("xnm" to year, "xqm" to term)
    }

    data class Schedule(val courses: JSONArray, val settings: JSONObject)

    private fun value(page: Document, field: String): String {
        val element = page.getElementById(field) ?: return ""
        return if (element.tagName() == "select") {
            (element.selectFirst("option[selected]") ?: element.select("option").singleOrNull())?.attr("value").orEmpty()
        } else element.attr("value")
    }

    fun term(page: Document): Term {
        val year = value(page, "xnm")
        val term = value(page, "xqm")
        require(year.matches(Regex("[0-9]{4}")) && term.matches(Regex("[0-9]{1,2}"))) { "未能识别教务系统的当前学期" }
        return Term(year, term)
    }

    fun read(http: AcademicHttp): Schedule {
        val term = term(Jsoup.parse(http.get(INDEX, PortalPolicy.SCHOOL_HOME).body))
        val form = term.fields + mapOf("kzlx" to "ck", "xsdm" to "", "kclbdm" to "")
        val table = JSONObject(http.post(TABLE + "Xsgrkb" + MODULE, form, INDEX).body)
        val courses = courses(table, term)
        val settings = JSONObject().put("schoolTerm", term.key)
        // The date anchor and the bell times are optional. The timetable itself is a full semester snapshot.
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        optional {
            val calendar = Jsoup.parse(http.post(CALENDAR, emptyMap(), PortalPolicy.SCHOOL_HOME).body)
            semesterStart(calendar, term, today)
        }?.let { settings.put("semesterStart", it) }
        optional {
            val campus = table.optJSONObject("xsxx")?.optString("XQH_ID").orEmpty()
            val body = http.post(TABLE + "Rjc" + MODULE, term.fields + ("xqh_id" to campus), INDEX).body
            periodTimes(JSONArray(body))
        }?.let { settings.put("officialTimes", it) }
        return Schedule(courses, settings)
    }

    private inline fun <T> optional(action: () -> T): T? = try { action() }
        catch (e: LoginRequired) { throw e }
        catch (_: Exception) { null }

    fun semesterStart(page: Document, expected: Term, today: LocalDate): String? {
        if (value(page, "xnm") != expected.year || value(page, "xqm") != expected.term) return null
        val week = value(page, "dqzc").toIntOrNull()?.takeIf { it in 1..60 } ?: return null
        return today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((week - 1).toLong()).toString()
    }

    private val range = Regex("""(?:第)?(\d{1,2})(?:-(\d{1,2}))?""")
    private val weekPart = Regex("""(第?\d{1,2}(?:-\d{1,2})?)(周(?:\(([单双])\)|([单双])周?)?|([单双])周)?""")
    private fun normalized(raw: String) = raw.replace(Regex("""\s+"""), "")
        .replace(Regex("[，、]"), ",").replace(Regex("[~～至—–]"), "-").replace('（', '(').replace('）', ')')

    private fun numbers(raw: String, max: Int): IntRange {
        val m = requireNotNull(range.matchEntire(raw)) { "教务课表的周次或节次格式未识别" }
        val first = m.groupValues[1].toInt()
        val last = m.groupValues[2].toIntOrNull() ?: first
        require(first in 1..max && last in first..max) { "教务课表的周次或节次超出范围" }
        return first..last
    }

    private fun weeks(raw: String): List<Int> {
        val parts = normalized(raw).split(',')
        val result = sortedSetOf<Int>()
        val group = mutableListOf<Int>()
        // In “1-5,7-11周(单),12-16周” the first two ranges share the odd-week qualifier.
        for ((index, part) in parts.withIndex()) {
            val m = requireNotNull(weekPart.matchEntire(part)) { "教务课表的周次格式未识别" }
            group.addAll(numbers(m.groupValues[1], 60))
            if (m.groupValues[2].isNotEmpty() || index == parts.lastIndex) {
                val parity = m.groupValues.drop(3).firstOrNull { it.isNotEmpty() }
                result.addAll(group.filter { parity == null || it % 2 == if (parity == "单") 1 else 0 })
                group.clear()
            }
        }
        require(result.isNotEmpty()) { "教务课程缺少有效周次" }
        return result.toList()
    }

    fun courses(table: JSONObject, term: Term): JSONArray {
        val info = table.optJSONObject("xsxx")
        val rows = requireNotNull(table.optJSONArray("kbList")) { "教务系统未返回个人课表" }
        require(info?.optString("XNM") == term.year && info.optString("XQM") == term.term) { "教务课表的学期未确认" }
        val merged = linkedMapOf<String, JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val name = row.optString("kcmc").trim()
            val day = row.optInt("xqj")
            require(name.isNotEmpty() && day in 1..7) { "教务课表的课程名称或星期未识别" }
            val sections = normalized(row.optString("jcs").ifBlank { row.optString("jc") }).removeSuffix("节")
                .split(',').flatMap { numbers(it, 16) }.distinct().sorted()
            val weekNumbers = weeks(row.optString("zcd"))
            val room = row.optString("cdmc")
            val teacher = row.optString("xm")
            val key = listOf(term.key, row.optString("jxb_id").ifBlank { row.optString("kch_id") },
                name, day, sections.joinToString(","), room, teacher).joinToString("|", prefix = "jw:")
            val course = merged.getOrPut(key) { JSONObject().put("id", key).put("name", name).put("day", day)
                .put("sections", JSONArray(sections)).put("room", room).put("teacher", teacher).put("provider", "school").put("source", "school") }
            val existing = course.optJSONArray("weeks")
            val previous = if (existing == null) emptyList() else (0 until existing.length()).map(existing::getInt)
            course.put("weeks", JSONArray((weekNumbers + previous).distinct().sorted()))
        }
        return JSONArray(merged.values.toList())
    }

    fun periodTimes(array: JSONArray): String? {
        if (array.length() !in 1..16) return null
        val clock = DateTimeFormatter.ofPattern("H:mm")
        val periods = (0 until array.length()).map { array.getJSONObject(it) }.sortedBy { it.optInt("jcmc") }
        val result = mutableListOf<String>()
        for ((index, row) in periods.withIndex()) {
            if (row.optInt("jcmc") != index + 1) return null
            val start = LocalTime.parse(row.getString("qssj"), clock)
            val end = LocalTime.parse(row.getString("jssj"), clock)
            if (start >= end) return null
            result += "$start-$end"
        }
        return result.joinToString("\n")
    }
}
