package cn.stbu.helper.backend.storage

import android.content.Context
import android.util.AtomicFile
import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.ui.widgets.CampusWidgets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

class LocalRepository(context: Context, private val account: Pair<String, String>? = null) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "campus.json"))
    private val archives = File(app.filesDir, "academic-accounts")

    private fun readFile(file: AtomicFile): JSONObject? = try {
        JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (_: FileNotFoundException) {
        if (file.baseFile.exists()) throw IllegalStateException("本机学习数据无法读取")
        null
    }

    private fun readDocument() = readFile(file)?.takeUnless { it.optBoolean("demo") } ?: empty()
    private fun owners(document: JSONObject) = document.optJSONObject(OWNERS) ?: JSONObject()

    fun owner(provider: String): String? = synchronized(lock) {
        owners(readDocument()).optString(provider).takeIf { it.isNotBlank() }
    }

    private fun checkAccount(document: JSONObject) {
        account?.let { (provider, owner) ->
            val actual = if (provider == "learning") AcademicSession.learningOwner() else owner
            check(owners(document).optString(provider) == owner && actual == owner) { "校园账号已变化，请重新同步" }
        }
    }

    fun read(): JSONObject = synchronized(lock) {
        val document = readDocument()
        checkAccount(document)
        document.remove(OWNERS)
        document
    }

    fun write(value: JSONObject) = synchronized(lock) {
        validate(value)
        val document = readDocument()
        checkAccount(document)
        val saved = JSONObject(value.toString()).put(OWNERS, owners(document))
        writeFile(file, saved)
        runCatching { CampusWidgets.update(app) }
        Unit
    }

    private fun writeFile(file: AtomicFile, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "数据文件超过 2 MB，请减少导入记录" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }

    /** Archive the outgoing account before atomically publishing the incoming one. */
    fun selectAccount(provider: String, owner: String, adoptLegacy: Boolean): Boolean = synchronized(lock) {
        val document = readDocument()
        val current = owners(document)
        val previous = current.optString(provider)
        if (previous == owner) return@synchronized false
        require(provider in setOf("learning", "school") && owner.matches(Regex("[a-f0-9-]{32,64}")))
        if (previous.isNotEmpty() || !adoptLegacy) {
            check(archives.isDirectory || archives.mkdirs()) { "无法创建账号缓存目录" }
            val destination = archive(provider, previous.ifEmpty { "legacy" })
            val incoming = readFile(archive(provider, owner))
            writeFile(destination, swapAccountData(document, provider, incoming))
        }
        document.put(OWNERS, current.put(provider, owner)).put("updatedAt", java.time.Instant.now().toString())
        writeFile(file, document)
        runCatching { CampusWidgets.update(app) }
        true
    }

    private fun archive(provider: String, owner: String): AtomicFile {
        require(owner == "legacy" || owner.matches(Regex("[a-f0-9-]{32,64}")))
        return AtomicFile(File(archives, "$provider-$owner.json"))
    }

    fun clearData() = synchronized(lock) {
        archives.listFiles()?.forEach { file -> check(file.delete()) { "未能清除本机学习缓存" } }
        write(empty())
        val prefs = app.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { name -> SYNC_KEYS.any { name == it || name.startsWith("$it.") } }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun merge(kind: String, records: JSONArray): Int = synchronized(lock) {
        require(kind in kinds && records.length() in 1..3000)
        val document = read()
        val combined = JSONArray()
        val old = document.optJSONArray(kind) ?: JSONArray()
        for (i in 0 until old.length()) { val item = old.getJSONObject(i); if (item.optString("source") == "manual") combined.put(item) }
        for (i in 0 until records.length()) combined.put(records.getJSONObject(i).put("source", "school"))
        document.put(kind, combined).put("updatedAt", java.time.Instant.now().toString()).put("demo", false)
        write(document)
        records.length()
    }

    fun applyRemote(provider: String, kind: String, records: JSONArray, complete: Boolean, groups: Set<String> = emptySet()) = synchronized(lock) {
        require(kind in kinds)
        val document = read()
        val old = document.getJSONArray(kind)
        val merged = linkedMapOf<String, JSONObject>()
        for (i in 0 until old.length()) {
            val item = old.getJSONObject(i)
            val sameProvider = item.optString("provider") == provider && item.optString("source") != "manual"
            val legacy = item.optString("provider").isBlank() && item.optString("source") == "school"
            if ((sameProvider && (complete || item.optString("groupId") in groups)) || legacy && complete) continue
            merged[item.optString("id", "old:$i")] = item
        }
        for (i in 0 until records.length()) {
            val item = records.getJSONObject(i)
            if (merged[item.getString("id")]?.optString("source") == "manual") continue
            merged[item.getString("id")] = item.put("provider", provider).put("source", "school")
        }
        document.put(kind, JSONArray(merged.values.toList())).put("updatedAt", java.time.Instant.now().toString())
        write(document)
    }

    fun replaceTimetable(courses: JSONArray, incoming: JSONObject) = synchronized(lock) {
        val document = read()
        applyTimetable(document, courses, incoming)
        document.put("updatedAt", java.time.Instant.now().toString())
        write(document)
    }

    fun saveCourse(course: JSONObject): JSONObject = synchronized(lock) {
        val document = read()
        val courses = document.getJSONArray("courses")
        val id = course.getString("id")
        val next = JSONArray()
        for (i in 0 until courses.length()) if (courses.getJSONObject(i).optString("id") != id) next.put(courses.getJSONObject(i))
        course.put("source", "manual").remove("provider")
        next.put(course)
        document.put("courses", next); write(document); document
    }

    fun deleteCourse(id: String): JSONObject = synchronized(lock) {
        val document = read()
        val courses = document.getJSONArray("courses")
        document.put("courses", JSONArray((0 until courses.length()).map { courses.getJSONObject(it) }.filter { it.optString("id") != id }))
        write(document); document
    }

    fun saveSettings(settings: JSONObject): JSONObject = synchronized(lock) {
        val document = read()
        val current = document.getJSONObject("settings")
        for (key in settings.keys()) current.put(key, settings.get(key))
        write(document); document
    }

    fun exportData(): JSONObject = synchronized(lock) {
        val document = read()
        val tasks = document.getJSONArray("tasks")
        // Official task links can contain short-lived signed access parameters.
        for (i in 0 until tasks.length()) tasks.getJSONObject(i).remove("url")
        document
    }

    companion object {
        private val lock = Any()
        private const val OWNERS = "_academicOwners"
        private val CALENDAR_KEYS = listOf("schoolTerm", "semesterStart", "teachingWeeks", "officialTimes")
        private val SYNC_KEYS = listOf("scheduleSignature", "lastSchedule", "lastHomeworkSync")
        const val MAX_BYTES = 2 * 1024 * 1024
        val kinds = setOf("courses", "grades", "exams", "tasks")
        fun empty() = JSONObject().put("schemaVersion", 1).put("settings", JSONObject()).put("courses", JSONArray())
            .put("grades", JSONArray()).put("exams", JSONArray()).put("tasks", JSONArray()).put("updatedAt", "").put("demo", false)

        internal fun swapAccountData(document: JSONObject, provider: String, incoming: JSONObject?): JSONObject {
            val outgoing = JSONObject()
            val settings = document.getJSONObject("settings")
            val savedSettings = incoming?.optJSONObject("settings")
            // On upgrade the old timetable still belongs to Learning until School supplies its first complete term.
            val schoolTimetable = settings.has("schoolTerm")
            val archiveCalendar = if (provider == "school") schoolTimetable else !schoolTimetable
            val restoreCalendar = if (provider == "school") savedSettings?.has("schoolTerm") == true else !schoolTimetable
            val calendar = archiveCalendar || restoreCalendar
            val collections = if (provider == "learning") mutableListOf("tasks") else mutableListOf("grades", "exams")
            if (calendar) collections.add("courses")
            for (kind in collections) {
                val rows = document.getJSONArray(kind)
                val local = mutableListOf<JSONObject>()
                val synced = mutableListOf<JSONObject>()
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (row.optString("source") == "manual") local.add(row) else synced.add(row)
                }
                if (kind != "courses" || archiveCalendar) outgoing.put(kind, JSONArray(synced))
                val retained = if (kind != "courses" || restoreCalendar) incoming?.optJSONArray(kind) else null
                val manualIds = local.mapTo(mutableSetOf()) { it.optString("id") }
                if (retained != null) {
                    for (i in 0 until retained.length()) {
                        val row = retained.getJSONObject(i)
                        if (row.optString("id") !in manualIds) local.add(row)
                    }
                }
                document.put(kind, JSONArray(local))
            }
            if (calendar) {
                val saved = JSONObject()
                for (key in CALENDAR_KEYS) {
                    if (key == "semesterStart" && settings.optBoolean("customTimetable")) continue
                    settings.remove(key)?.let { saved.put(key, it) }
                    if (restoreCalendar) savedSettings?.opt(key)?.let { settings.put(key, it) }
                }
                if (archiveCalendar) outgoing.put("settings", saved)
            }
            return outgoing
        }

        internal fun applyTimetable(document: JSONObject, courses: JSONArray, incoming: JSONObject) {
            val old = document.getJSONArray("courses")
            val manual = (0 until old.length()).map(old::getJSONObject).filter { it.optString("source") == "manual" }
            val ids = manual.mapTo(mutableSetOf()) { it.optString("id") }
            val merged = JSONArray(manual)
            for (i in 0 until courses.length()) {
                val course = courses.getJSONObject(i)
                if (course.getString("id") !in ids) merged.put(course)
            }
            val settings = document.getJSONObject("settings")
            val newTerm = settings.optString("schoolTerm") != incoming.getString("schoolTerm")
            for (key in CALENDAR_KEYS) {
                if (key == "semesterStart" && settings.optBoolean("customTimetable")) continue
                if (incoming.has(key)) settings.put(key, incoming.get(key)) else if (newTerm) settings.remove(key)
            }
            document.put("courses", merged)
        }

        fun validate(value: JSONObject) {
            require(value.optInt("schemaVersion") == 1) { "请选择 StbuHelper 导出的 JSON 文件" }
            require(!value.optBoolean("demo")) { "文件不包含实际学习数据" }
            require(value.has("settings") && value.get("settings") is JSONObject) { "缺少设置数据" }
            val allowed = kinds + setOf("schemaVersion", "settings", "updatedAt", "demo")
            require(value.keys().asSequence().all { it in allowed }) { "文件包含未支持的字段" }
            for (kind in kinds) {
                val rows = value.getJSONArray(kind)
                require(rows.length() <= 3000) { "导入记录过多" }
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    require(row.optString("name").isNotBlank() && row.optString("name").length <= 500) { "记录缺少名称" }
                    if (kind == "courses") {
                        require(row.optInt("day") in 1..7) { "课程星期格式不正确" }
                        val sections = row.getJSONArray("sections")
                        val weeks = row.getJSONArray("weeks")
                        require(sections.length() in 1..16 && weeks.length() in 1..60) { "课程节次或周次为空" }
                        for (j in 0 until sections.length()) require(sections.getInt(j) in 1..16) { "课程节次超出范围" }
                        for (j in 0 until weeks.length()) require(weeks.getInt(j) in 1..60) { "课程周次超出范围" }
                    }
                }
            }
        }
    }
}
