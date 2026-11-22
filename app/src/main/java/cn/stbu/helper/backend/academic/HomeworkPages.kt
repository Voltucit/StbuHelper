package cn.stbu.helper.backend.academic

import android.net.Uri
import android.os.SystemClock
import org.jsoup.Jsoup
import java.net.URLEncoder

data class HomeworkPage(val records: List<TaskRecord>, val referer: String, val complete: Boolean = true)
class HomeworkPageError(message: String) : IllegalStateException(message)

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
            catch (error: AcademicResponseError) {
                if (error.status != 403) throw error
                synchronized(lock) { entries.remove(key) }
            }
            catch (_: HomeworkPageError) { synchronized(lock) { entries.remove(key) } }
        }
        val page = http.get(query("https://mooc1.chaoxing.com/visit/stucoursemiddle", mapOf("courseid" to course.id, "clazzid" to course.classId, "cpi" to course.cpi, "ismooc2" to "1", "v" to "2")))
        val document = Jsoup.parse(page.body)
        val workEnc = document.selectFirst("input#workEnc, input[name=workEnc]")?.attr("value").orEmpty()
        if (workEnc.isBlank()) throw HomeworkPageError("课程页未返回作业入口")
        val pageEnc = document.selectFirst("#enc")?.attr("value").orEmpty().ifBlank {
            Uri.parse(page.url).getQueryParameter("enc").orEmpty()
        }
        if (pageEnc.isBlank()) throw HomeworkPageError("课程页未返回作业签名")
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
        return AcademicParsers.mobileHomework(list.body, courses, list.url, now)
    }

    private fun readList(http: AcademicHttp, course: LearningCourse, entry: Entry, now: Long): HomeworkPage {
        val list = http.get(query("https://mooc1.chaoxing.com/mooc2/work/list", entry.fields + ("t" to now.toString())), entry.referer)
        val document = Jsoup.parse(list.body)
        val text = document.text()
        if (Regex("无权限|无效的用户|没有此页面访问权限").containsMatchIn(text)) throw HomeworkPageError("学习通拒绝读取课程作业")
        val parsed = AcademicParsers.homework(document, course, list.url, now)
        if (parsed.isEmpty() && !AcademicParsers.confirmsNoHomework(list.body, text)) throw HomeworkPageError("课程作业列表格式暂未识别")
        return HomeworkPage(parsed, list.url)
    }

    private fun query(base: String, fields: Map<String, String>) = base + "?" + fields.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
}
