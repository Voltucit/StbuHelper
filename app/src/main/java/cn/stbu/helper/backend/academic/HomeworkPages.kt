package cn.stbu.helper.backend.academic

import android.os.SystemClock
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.io.IOException

data class HomeworkPage(val records: List<TaskRecord>, val referer: String)

/** Short-lived signed course entries stay only in bounded process memory, never in a disk cache. */
object HomeworkPages {
    private const val LIFETIME = 10 * 60 * 1000L
    private const val CAPACITY = 64
    private data class Entry(val owner: String, val savedAt: Long, val referer: String, val fields: Map<String, String>)
    private val entries = linkedMapOf<String, Entry>()
    private val lock = Any()

    fun prune() = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.savedAt !in 0L until LIFETIME }
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }

    fun read(http: AcademicHttp, course: LearningCourse, owner: String, now: Long): HomeworkPage {
        val key = "$owner:${course.id}:${course.classId}:${course.cpi}"
        val cached = synchronized(lock) {
            prune()
            entries.entries.removeAll { it.value.owner != owner }
            entries[key]
        }
        if (cached != null) {
            try { return readList(http, course, cached, now) }
            catch (error: IOException) { throw error } // Network failures do not justify a second full request chain.
            catch (_: IllegalStateException) { synchronized(lock) { entries.remove(key) } }
            catch (_: IllegalArgumentException) { synchronized(lock) { entries.remove(key) } }
        }
        val page = http.get(query("https://mooc1.chaoxing.com/visit/stucoursemiddle", mapOf("courseid" to course.id, "clazzid" to course.classId, "cpi" to course.cpi, "ismooc2" to "1", "v" to "2")))
        val document = Jsoup.parse(page.body)
        val workEnc = document.selectFirst("#workEnc")?.attr("value").orEmpty()
        check(workEnc.isNotBlank()) { "课程作业入口暂未返回" }
        val pageEnc = document.selectFirst("#enc")?.attr("value").orEmpty().ifBlank {
            URI(page.url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("enc=") }?.substringAfter('=').orEmpty()
        }
        check(pageEnc.isNotBlank()) { "作业签名未提供" }
        val entry = Entry(owner, SystemClock.elapsedRealtime(), page.url, mapOf(
            "courseId" to course.id, "classId" to course.classId, "cpi" to course.cpi, "ut" to "s",
            "openc" to document.selectFirst("#openc")?.attr("value").orEmpty(), "enc" to workEnc,
            "stuenc" to pageEnc, "isdisplaytable" to "2"
        ))
        val result = readList(http, course, entry, now)
        synchronized(lock) { entries.remove(key); entries[key] = entry; prune() }
        return result
    }

    fun readMobile(http: AcademicHttp, courses: List<LearningCourse>, now: Long): HomeworkPage {
        val list = http.get("https://mooc1.chaoxing.com/mooc-ans/work/stu-work?ut=s")
        val document = Jsoup.parse(list.body)
        check(document.selectFirst("li[data][onclick*=goTask]") != null ||
            Regex("暂无作业|没有作业").containsMatchIn(document.text())) { "移动端作业列表暂未识别" }
        return HomeworkPage(AcademicParsers.mobileHomework(list.body, courses, list.url, now), list.url)
    }

    private fun readList(http: AcademicHttp, course: LearningCourse, entry: Entry, now: Long): HomeworkPage {
        val list = http.get(query("https://mooc1.chaoxing.com/mooc2/work/list", entry.fields + ("t" to now.toString())), entry.referer)
        check(!Regex("无权限|无效的用户|没有此页面访问权限").containsMatchIn(list.body)) { "作业权限未确认" }
        check(list.body.contains("<ul", true) || Regex("暂无作业|没有作业|共\\s*0\\s*[份个]").containsMatchIn(list.body)) { "作业列表未识别" }
        val parsed = AcademicParsers.homework(list.body, course, list.url, now)
        check(parsed.isNotEmpty() || Regex("暂无作业|没有作业|共\\s*0\\s*[份个]").containsMatchIn(Jsoup.parse(list.body).text())) { "未确认作业为空" }
        return HomeworkPage(parsed, list.url)
    }

    private fun query(base: String, fields: Map<String, String>) = base + "?" + fields.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
}
