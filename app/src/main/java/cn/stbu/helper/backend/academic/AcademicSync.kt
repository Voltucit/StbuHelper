package cn.stbu.helper.backend.academic

import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.homework.HomeworkReminders
import cn.stbu.helper.backend.homework.HomeworkTracking
import cn.stbu.helper.backend.storage.LocalRepository

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.atomic.AtomicBoolean

/** Background reads are separate from the water-control bridge; at most three independent requests run together. */
object AcademicSync {
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = Executors.newFixedThreadPool(3)
    private val busy = AtomicBoolean(false)
    private val startLock = Any()
    private var queuedProvider: String? = null
    private var queuedForce = false
    private var queuedHomeworkOnly = true
    private var queuedCoursesOnly = true
    private var queuedCurrentWeekOnly = true
    private const val HOMEWORK_INTERVAL = 3 * 60 * 60 * 1000L
    private const val SCHEDULE_INTERVAL = 24 * 60 * 60 * 1000L
    private const val DETAIL_INTERVAL = 6 * 60 * 60 * 1000L
    @Volatile private var current = JSONObject().put("running", false)

    fun state(): JSONObject = JSONObject(current.toString())

    fun start(context: Context, force: Boolean = true, provider: String = "all", afterCurrent: Boolean = false, homeworkOnly: Boolean = false, coursesOnly: Boolean = false, currentWeekOnly: Boolean = false, finished: (() -> Unit)? = null): JSONObject {
        require(!homeworkOnly || !coursesOnly)
        val app = context.applicationContext
        val runId = System.nanoTime().toString()
        synchronized(startLock) {
            if (!busy.compareAndSet(false, true)) {
                if (afterCurrent) {
                    queuedProvider = if (queuedProvider == null || queuedProvider == provider) provider else "all"
                    queuedForce = queuedForce || force
                    queuedHomeworkOnly = queuedHomeworkOnly && homeworkOnly
                    queuedCoursesOnly = queuedCoursesOnly && coursesOnly
                    queuedCurrentWeekOnly = queuedCurrentWeekOnly && currentWeekOnly
                }
                finished?.invoke(); return state()
            }
            current = JSONObject().put("running", true).put("id", runId)
        }
        executor.execute {
            val required = JSONArray()
            var success = true
            var homeworkComplete: Boolean? = null
            var attempted = 0
            val sessions = AcademicSession(app)
            try {
                sessions.restore()
                val connected = sessions.status()
                val providers = if (homeworkOnly || coursesOnly) listOf("learning") else if (provider == "all") listOf("learning", "school").filter { connected.optBoolean(it) } else listOf(provider)
                for (target in providers) {
                    if (!connected.optBoolean(target)) { required.put(target); success = false; continue }
                    attempted++
                    val owner = runCatching { sessions.selectAccount(target, adoptLegacy = true) }.getOrNull()
                    if (owner == null) { success = false; continue }
                    val repository = LocalRepository(app, target to owner)
                    try {
                        if (target == "learning" && !coursesOnly) homeworkComplete = false
                        val complete = if (target == "learning") learning(app, sessions, repository, owner, homeworkOnly, coursesOnly, currentWeekOnly) { homeworkComplete = it }
                        else school(sessions, repository)
                        val sameAccount = sessions.markForAccount(target, owner, true)
                        success = success && complete && sameAccount
                    } catch (_: LoginRequired) {
                        if (sessions.markForAccount(target, owner, false)) required.put(target)
                        success = false
                    }
                    catch (_: Exception) { success = false }
                }
                sessions.capture()
                HomeworkReminders.check(app)
                current = JSONObject().put("running", false).put("id", runId).put("success", success && attempted > 0).put("needLogin", required).put("finishedAt", System.currentTimeMillis())
                    .put("homeworkComplete", homeworkComplete)
            } catch (_: Exception) {
                current = JSONObject().put("running", false).put("id", runId).put("success", false).put("finishedAt", System.currentTimeMillis())
            } finally {
                synchronized(startLock) {
                    busy.set(false)
                    val next = queuedProvider
                    val nextForce = queuedForce
                    val nextHomeworkOnly = queuedHomeworkOnly
                    val nextCoursesOnly = queuedCoursesOnly
                    val nextCurrentWeekOnly = queuedCurrentWeekOnly
                    queuedProvider = null; queuedForce = false; queuedHomeworkOnly = true; queuedCoursesOnly = true; queuedCurrentWeekOnly = true
                    if (next != null) start(app, force = nextForce, provider = next, homeworkOnly = nextHomeworkOnly, coursesOnly = nextCoursesOnly, currentWeekOnly = nextCurrentWeekOnly)
                }
                finished?.invoke()
            }
        }
        return state()
    }

    /** Opening the app only refreshes homework, and only after three hours since a successful homework sync. */
    fun autoStart(context: Context) { homeworkIfDue(context) }
    fun homeworkIfDue(context: Context, finished: (() -> Unit)? = null) {
        val saved = AcademicSession(context).status().optBoolean("learning")
        val prefs = context.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
        val owner = prefs.getString("learning.trackingOwner", "")
        val last = prefs.getLong("lastHomeworkSync.$owner", 0)
        if (saved && System.currentTimeMillis() - last >= HOMEWORK_INTERVAL) start(context, force = false, provider = "learning", homeworkOnly = true, finished = finished)
        else finished?.invoke()
    }

    private fun learning(context: Context, sessions: AcademicSession, repo: LocalRepository, owner: String, homeworkOnly: Boolean, coursesOnly: Boolean, currentWeekOnly: Boolean, onHomeworkComplete: (Boolean) -> Unit): Boolean {
        val cookie = CookieManager.getInstance().getCookie(PortalPolicy.LEARNING_HOME).orEmpty()
        if (!sessions.status().optBoolean("learning") && !Regex("(?:^|;)\\s*(?:_uid|UID|PUID)=").containsMatchIn(cookie)) throw LoginRequired("learning")
        // Publish the requested week first; neither a semester sweep nor slow course homework can hold it back.
        val prefs = context.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
        val plan = if (homeworkOnly) null else try { prepareSchedule(prefs, sessions, repo, owner) } catch (error: LoginRequired) { throw error } catch (_: Exception) { null }
        val tasksComplete = if (coursesOnly) true else try { homework(context, sessions, repo, owner) } catch (error: LoginRequired) { throw error } catch (_: Exception) { false }
        if (!coursesOnly) {
            onHomeworkComplete(tasksComplete)
            if (tasksComplete) prefs.edit().putLong("lastHomeworkSync.$owner", System.currentTimeMillis()).apply()
        }
        if (homeworkOnly) return tasksComplete
        val scheduleComplete = if (plan == null) false else if (currentWeekOnly && !plan.changedSemester) true else try { finishSchedule(prefs, sessions, repo, owner, plan) } catch (error: LoginRequired) { throw error } catch (_: Exception) { false }
        return tasksComplete && scheduleComplete
    }

    private fun <T, R> parallel(values: List<T>, onComplete: ((R) -> Unit)? = null, action: (T) -> R): List<Result<R>> {
        val completed = ExecutorCompletionService<Result<R>>(requests)
        values.forEach { value -> completed.submit(Callable { runCatching { action(value) } }) }
        return List(values.size) { completed.take().get().also { result -> result.getOrNull()?.let { onComplete?.invoke(it) } } }
    }

    private fun teachingWeek(start: String): Int? = runCatching {
        val date = LocalDate.parse(start)
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        (Math.floorDiv(ChronoUnit.DAYS.between(monday, LocalDate.now(ZoneId.of("Asia/Shanghai"))), 7) + 1).toInt().takeIf { it in 1..60 }
    }.getOrNull()

    private data class SchedulePlan(val settings: JSONObject, val signature: String, val maxWeek: Int, val full: Boolean, val changedSemester: Boolean, val pages: Map<Int, JSONObject>)
    private fun fetchWeek(sessions: AcademicSession, week: Int): JSONObject {
        val response = JSONObject(AcademicHttp(sessions).post("https://kb.chaoxing.com/pc/curriculum/getMyLessons", mapOf("week" to week.toString())).body)
        check(response.optInt("result") == 1) { "课表未返回" }
        return response.getJSONObject("data")
    }
    private fun weekRows(data: JSONObject, week: Int): List<JSONObject> {
        val raw = data.getJSONArray("lessonArray")
        val rows = AcademicParsers.lessons(raw, week)
        check(raw.length() == 0 || rows.size == raw.length()) { "课表格式未完整识别" }
        return rows
    }
    private fun prepareSchedule(prefs: SharedPreferences, sessions: AcademicSession, repo: LocalRepository, owner: String): SchedulePlan {
        val old = repo.read()
        val settings = old.getJSONObject("settings")
        val oldStart = settings.optString("semesterStart")
        val requested = (teachingWeek(oldStart) ?: 1).coerceIn(1, settings.optInt("teachingWeeks", 60).coerceIn(1, 60))
        val initial = fetchWeek(sessions, requested)
        val curriculum = initial.getJSONObject("curriculum")
        val incoming = AcademicParsers.curriculumSettings(curriculum)
        val maxWeek = curriculum.optInt("maxWeek", 30).coerceIn(1, 60)
        val actual = (teachingWeek(incoming.optString("semesterStart", oldStart)) ?: 1).coerceIn(1, maxWeek)
        val pages = linkedMapOf(requested to initial)
        val current = pages[actual] ?: fetchWeek(sessions, actual).also { pages[actual] = it }
        val rows = weekRows(current, actual)
        val signature = incoming.optString("semesterStart") + ":" + maxWeek
        val full = prefs.getString("scheduleSignature.$owner", "") != signature || System.currentTimeMillis() - prefs.getLong("lastSchedule.$owner", 0) >= SCHEDULE_INTERVAL
        val changed = incoming.has("semesterStart") && oldStart.isNotBlank() && oldStart != incoming.optString("semesterStart")
        if (!changed || old.getJSONArray("courses").length() == 0) {
            repo.applyRemote("learning", "courses", JSONArray(rows), complete = false, refreshedWeeks = setOf(actual))
            repo.mergeSettings(incoming)
        }
        return SchedulePlan(incoming, signature, maxWeek, full, changed, pages)
    }
    private fun finishSchedule(prefs: SharedPreferences, sessions: AcademicSession, repo: LocalRepository, owner: String, plan: SchedulePlan): Boolean {
        if (!plan.full) return true
        val results = parallel((1..plan.maxWeek).toList()) { week ->
            week to weekRows(plan.pages[week] ?: fetchWeek(sessions, week), week)
        }
        val refreshed = mutableSetOf<Int>()
        val merged = linkedMapOf<String, JSONObject>()
        for (result in results) {
            val error = result.exceptionOrNull()
            if (error is LoginRequired) throw error
            val (week, rows) = result.getOrNull() ?: continue
            refreshed.add(week)
            for (row in rows) {
                val previous = merged[row.getString("id")]
                if (previous == null) merged[row.getString("id")] = row else previous.getJSONArray("weeks").put(week)
            }
        }
        for (row in merged.values) {
            val weeks = row.getJSONArray("weeks")
            row.put("weeks", JSONArray((0 until weeks.length()).map { weeks.getInt(it) }.distinct().sorted()))
        }
        val complete = refreshed.size == plan.maxWeek
        if (refreshed.isNotEmpty() && (!plan.changedSemester || complete)) {
            repo.applyRemote("learning", "courses", JSONArray(merged.values.toList()), complete = complete, refreshedWeeks = refreshed)
            repo.mergeSettings(plan.settings)
        }
        if (complete) prefs.edit().putLong("lastSchedule.$owner", System.currentTimeMillis()).putString("scheduleSignature.$owner", plan.signature).apply()
        return complete
    }

    private data class CourseWork(val group: String, val records: List<TaskRecord>, val referer: String)
    private fun homework(context: Context, sessions: AcademicSession, repo: LocalRepository, owner: String): Boolean {
        val tracking = HomeworkTracking(context)
        check(tracking.status().optString("account") == owner) { "学习通账号已变化" }
        val selection = tracking.selection()
        if (selection.custom && selection.groups.isEmpty()) {
            repo.applyRemote("learning", "tasks", JSONArray(), complete = true)
            return true
        }
        val savedRows = repo.read().getJSONArray("tasks")
        val known = (0 until savedRows.length()).associate { savedRows.getJSONObject(it).let { row -> row.optString("id") to row } }
        val previouslySupplemented = known.values.filter { it.optString("id").startsWith("mobile-work:") }
            .mapTo(mutableSetOf()) { it.optString("groupId") }
        val cachedCatalogue = if (selection.custom) tracking.cachedCourses()?.takeIf { list -> selection.groups.all { group -> list.any { "${it.id}:${it.classId}" == group } } } else null
        val allCourses = cachedCatalogue ?: run {
            val response = JSONObject(AcademicHttp(sessions).get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1", "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction").body)
            if (!response.has("channelList") && Regex("未登录|重新登录|请登录").containsMatchIn(response.toString())) throw LoginRequired("learning")
            AcademicParsers.learningCourses(response).also { tracking.updateCourses(it) }
        }
        val courses = allCourses.filter { selection.includes("${it.id}:${it.classId}") }
        val now = System.currentTimeMillis()
        var published = false
        // Read lists first. Detail lookups use the same bounded pool afterwards, without nested waits.
        val results = parallel(courses, onComplete = { work: CourseWork ->
            if (!published && work.group !in previouslySupplemented && work.records.any { AcademicParsers.actionable(it.status, it.deadlineAt, now) } &&
                work.records.none { AcademicParsers.pending(it.status) && it.deadlineAt <= 0 }) {
                runCatching { repo.applyRemote("learning", "tasks", JSONArray(work.records.map { it.json() }), complete = false, groups = setOf(work.group)) }.onSuccess { published = true }
            }
        }) { course ->
            val group = "${course.id}:${course.classId}"
            val page = HomeworkPages.read(AcademicHttp(sessions), course, owner, now)
            val records = page.records.map { record ->
                val cached = known[record.id]
                val checkedAt = cached?.optLong("deadlineCheckedAt") ?: 0
                val previous = cached?.optLong("deadlineAt") ?: 0
                if (record.deadlineAt <= 0 && previous > now && AcademicParsers.pending(record.status) && now - checkedAt in 0L until DETAIL_INTERVAL)
                    record.copy(deadlineAt = previous, deadlineCheckedAt = checkedAt)
                else record
            }
            CourseWork(group, records, page.referer)
        }
        if (cachedCatalogue != null && results.any { it.isFailure }) runCatching { tracking.invalidateCourses() }
        results.firstNotNullOfOrNull { it.exceptionOrNull() as? LoginRequired }?.let { throw it }
        val works = results.mapNotNull { it.getOrNull() }
        val details = resolveDeadlines(sessions, works.flatMap { work -> work.records.map { it to work.referer } }, now)
        val tasks = works.flatMap { it.records }.associateByTo(linkedMapOf()) { it.id }
        tasks.putAll(details)
        val readGroups = works.mapTo(mutableSetOf()) { it.group }
        val unresolved = tasks.values.filter { AcademicParsers.pending(it.status) && it.deadlineAt <= 0 }.mapTo(mutableSetOf()) { it.group }
        val activeGroups = tasks.values.filter { AcademicParsers.actionable(it.status, it.deadlineAt, now) }.mapTo(mutableSetOf()) { it.group }
        val fallbackCourses = courses.filter {
            val group = "${it.id}:${it.classId}"
            group !in readGroups || group !in activeGroups || group in unresolved || group in previouslySupplemented
        }
        if (fallbackCourses.isNotEmpty()) {
            // The account-level page can return homework even when the course tab is missing or empty.
            val mobile = runCatching { HomeworkPages.readMobile(AcademicHttp(sessions), fallbackCourses, now) }.getOrNull()
            if (mobile != null) {
                val byTitle = tasks.values.groupBy { it.group to it.name }
                val extra = mobile.records.mapNotNull { record ->
                    val matches = byTitle[record.group to record.name].orEmpty()
                    if (matches.isEmpty()) return@mapNotNull record
                    val existing = matches.singleOrNull() ?: return@mapNotNull null
                    if (AcademicParsers.pending(existing.status) && existing.deadlineAt <= 0) record.copy(id = existing.id) else null
                }
                val resolved = resolveDeadlines(sessions, extra.map { it to mobile.referer }, now, optional = true)
                for (record in extra) {
                    val task = resolved[record.id] ?: record
                    tasks[task.id] = task
                }
            } else {
                readGroups.removeAll(previouslySupplemented)
            }
        }
        val rows = tasks.values.map { task ->
            if (AcademicParsers.pending(task.status) && task.deadlineAt <= 0) {
                readGroups.remove(task.group)
                task.copy(deadlineAt = known[task.id]?.optLong("deadlineAt") ?: 0).json()
            } else task.json()
        }
        val complete = readGroups.size == courses.size && (!selection.custom || courses.size == selection.groups.size)
        repo.applyRemote("learning", "tasks", JSONArray(rows), complete = complete, groups = readGroups)
        return complete
    }

    private fun resolveDeadlines(sessions: AcademicSession, candidates: List<Pair<TaskRecord, String>>, now: Long, optional: Boolean = false): Map<String, TaskRecord> {
        val missing = candidates.filter { (record, _) ->
            record.deadlineAt <= 0 && AcademicParsers.pending(record.status) && record.url.isNotBlank()
        }.distinctBy { it.first.id }
        val resolved = parallel(missing) { (record, referer) ->
            try {
                val due = AcademicParsers.detailDeadline(AcademicHttp(sessions).get(record.url, referer).body, now)
                record.copy(deadlineAt = due, deadlineCheckedAt = if (due > 0) now else 0)
            } catch (error: LoginRequired) {
                if (!optional) throw error
                record
            } catch (_: Exception) { record }
        }
        resolved.firstNotNullOfOrNull { it.exceptionOrNull() as? LoginRequired }?.let { throw it }
        return resolved.mapNotNull { it.getOrNull() }.associateBy { it.id }
    }

    private fun school(sessions: AcademicSession, repo: LocalRepository): Boolean {
        val http = AcademicHttp(sessions)
        val document = Jsoup.parse(http.get(PortalPolicy.SCHOOL_HOME).body)
        val year = document.selectFirst("#xnm option[selected]")?.attr("value").orEmpty()
        val term = document.selectFirst("#xqm option[selected]")?.attr("value").orEmpty()
        val results = parallel(listOf("grades" to "/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005", "exams" to "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105")) { (kind, path) ->
            val all = JSONArray()
            var page = 1
            do {
                val fields = mapOf("xnm" to if (kind == "grades") "" else year, "xqm" to if (kind == "grades") "" else term, "_search" to "false", "nd" to System.currentTimeMillis().toString(), "queryModel.showCount" to "100", "queryModel.currentPage" to page.toString(), "queryModel.sortName" to "", "queryModel.sortOrder" to "asc", "time" to "0")
                val response = JSONObject(AcademicHttp(sessions).post("https://jwxt.stbu.edu.cn$path", fields, PortalPolicy.SCHOOL_HOME).body)
                val items = response.optJSONArray("items") ?: throw IllegalStateException("学校列表未返回")
                for (i in 0 until items.length()) all.put(items.getJSONObject(i))
                val total = response.optInt("totalPage", if (items.length() == 100) page + 1 else page)
                page++
                if (items.length() < 100 || page > total) break
                check(page <= 30) { "分页未完成" }
            } while (true)
            val rows = AcademicParsers.schoolRows(all, kind)
            check(all.length() == 0 || rows.length() == all.length()) { "学校数据格式未完整识别" }
            repo.applyRemote("school", kind, rows, complete = true)
        }
        results.forEach { if (it.exceptionOrNull() is LoginRequired) throw it.exceptionOrNull()!! }
        return results.all { it.isSuccess }
    }

}
