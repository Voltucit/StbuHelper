package cn.stbu.helper.backend.homework

import cn.stbu.helper.backend.academic.AcademicHttp
import cn.stbu.helper.backend.academic.AcademicParsers
import cn.stbu.helper.backend.academic.LearningCourse
import cn.stbu.helper.backend.academic.LoginRequired
import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.security.CredentialVault

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HomeworkSelection(val custom: Boolean, val groups: Set<String>) {
    fun includes(group: String) = !custom || group in groups
}

/** A course catalogue and tracking choice, kept locally in a separate encrypted bucket for each learning account. */
class HomeworkTracking(context: Context) {
    private val app = context.applicationContext
    private val vault = CredentialVault(app)
    private val owner = accountKey(app)
    private fun bucket() = "homework.tracking." + (owner ?: throw IllegalArgumentException("请先登录学习通"))
    private fun read(): JSONObject = if (owner == null) JSONObject() else vault.readJson(bucket()) ?: JSONObject()
    private fun selection(state: JSONObject): HomeworkSelection {
        val rows = state.optJSONArray("selected") ?: JSONArray()
        return HomeworkSelection(state.optBoolean("custom"), (0 until rows.length()).map { rows.getString(it) }.toSet())
    }

    fun selection(): HomeworkSelection = synchronized(lock) { selection(read()) }

    fun status(): JSONObject = synchronized(lock) {
        try {
            val state = read()
            val choice = selection(state)
            val courses = state.optJSONArray("courses") ?: JSONArray()
            val rows = JSONArray()
            for (i in 0 until courses.length()) {
                val course = courses.getJSONObject(i)
                rows.put(JSONObject().put("id", course.getString("id")).put("name", course.getString("name")).put("teacher", course.optString("teacher")).put("tracked", choice.includes(course.getString("id"))))
            }
            JSONObject().put("account", owner.orEmpty()).put("custom", choice.custom).put("selected", JSONArray(choice.groups.sorted())).put("courses", rows).put("teacherMetadataRead", state.optBoolean("teacherMetadataRead")).put("unavailable", false)
        } catch (_: Exception) {
            JSONObject().put("account", owner.orEmpty()).put("custom", true).put("selected", JSONArray()).put("courses", JSONArray()).put("unavailable", true)
        }
    }

    fun updateCourses(courses: List<LearningCourse>) = synchronized(lock) {
        require(owner != null && owner == accountKey(app) && owner == AcademicSession.learningOwner()) { "学习通账号已变化，请重新打开课程追踪" }
        val state = read()
        val rows = courses.map { JSONObject().put("id", "${it.id}:${it.classId}").put("courseId", it.id).put("classId", it.classId).put("cpi", it.cpi).put("name", it.name).put("teacher", it.teacher) }
        state.put("courses", JSONArray(rows)).put("teacherMetadataRead", true).put("updatedAt", System.currentTimeMillis())
        vault.saveJson(bucket(), state)
    }

    /** Reuse the encrypted directory for selected courses; the public status never includes cpi. */
    fun cachedCourses(): List<LearningCourse>? = synchronized(lock) {
        if (owner == null || owner != accountKey(app)) return@synchronized null
        val state = read()
        val age = System.currentTimeMillis() - state.optLong("updatedAt")
        if (state.optLong("updatedAt") <= 0 || age !in 0L until 6 * 60 * 60 * 1000L) return@synchronized null
        val rows = state.optJSONArray("courses") ?: return@synchronized null
        val courses = (0 until rows.length()).map { rows.getJSONObject(it) }
        if (courses.any { it.optString("courseId").isBlank() || it.optString("classId").isBlank() || it.optString("cpi").isBlank() }) return@synchronized null
        courses.map { LearningCourse(it.getString("courseId"), it.getString("classId"), it.getString("cpi"), it.getString("name"), it.optString("teacher")) }
    }

    fun invalidateCourses() = synchronized(lock) {
        if (owner != null && owner == accountKey(app)) vault.saveJson(bucket(), read().put("updatedAt", 0))
    }

    fun save(account: String, custom: Boolean, selected: JSONArray): JSONObject = synchronized(lock) {
        require(owner != null && account == owner && owner == accountKey(app)) { "学习通账号已变化，请重新打开课程追踪" }
        require(selected.length() <= 3000) { "选择的课程过多" }
        val state = read()
        val courses = state.optJSONArray("courses") ?: JSONArray()
        val available = (0 until courses.length()).map { courses.getJSONObject(it).getString("id") }.toSet()
        val wanted = (0 until selected.length()).map { selected.getString(it) }.toSet()
        require(!custom || wanted.all { it in available }) { "课程列表已更新，请重新选择" }
        state.put("custom", custom).put("selected", JSONArray(if (custom) wanted.sorted() else emptyList<String>()))
        vault.saveJson(bucket(), state)
        status()
    }

    /** Fetch course names and teachers only; this never reads every course's homework. */
    fun refreshCourses(): JSONObject {
        val response = JSONObject(AcademicHttp(AcademicSession(app)).get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1", "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction").body)
        if (!response.has("channelList") && Regex("未登录|重新登录|请登录").containsMatchIn(response.toString())) throw LoginRequired("learning")
        updateCourses(AcademicParsers.learningCourses(response))
        return status()
    }

    companion object {
        private val lock = Any()
        private fun accountKey(context: Context): String? =
            context.getSharedPreferences("academic-status", Context.MODE_PRIVATE).getString("learning.trackingOwner", null)
    }
}
