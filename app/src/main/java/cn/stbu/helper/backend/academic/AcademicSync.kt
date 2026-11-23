package cn.stbu.helper.backend.academic

import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.homework.HomeworkReminders
import cn.stbu.helper.backend.homework.HomeworkTracking
import cn.stbu.helper.backend.storage.LocalRepository

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Academic requests have separate concurrency limits for homework and other data. */
object AcademicSync {
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = Executors.newFixedThreadPool(3)
    private val homeworkRequests = Executors.newFixedThreadPool(2)
    private val busy = AtomicBoolean(false)
    private val startLock = Any()
    private var queuedProvider: String? = null
    private var queuedScope: Scope? = null
    private const val HOMEWORK_INTERVAL = 12 * 60 * 60 * 1000L
    private const val DETAIL_INTERVAL = 6 * 60 * 60 * 1000L
    @Volatile private var current = JSONObject().put("running", false)

    fun state(): JSONObject = JSONObject(current.toString())

    enum class Scope { ALL, HOME, COURSES, HOMEWORK, RECORDS;
        companion object {
            fun fromRequest(args: JSONObject) = when (args.optString("scope")) {
                "home" -> HOME
                "courses" -> COURSES
                "homework" -> HOMEWORK
                "records" -> RECORDS
                else -> when {
                    args.optBoolean("homeworkOnly") -> HOMEWORK
                    args.optBoolean("coursesOnly") -> COURSES
                    args.optBoolean("currentWeekOnly") -> HOME
                    else -> ALL
                }
            }
        }
    }

    fun start(context: Context, provider: String = "all", afterCurrent: Boolean = false, scope: Scope = Scope.ALL, finished: (() -> Unit)? = null): JSONObject {
        val app = context.applicationContext
        val runId = System.nanoTime().toString()
        synchronized(startLock) {
            if (!busy.compareAndSet(false, true)) {
                if (afterCurrent) {
                    queuedProvider = if (queuedProvider == null || queuedProvider == provider) provider else "all"
                    queuedScope = if (queuedScope == null || queuedScope == scope) scope else Scope.ALL
                }
                finished?.invoke(); return state()
            }
            current = JSONObject().put("running", true).put("id", runId)
        }
        executor.execute {
            val required = JSONArray()
            var success = true
            var homeworkComplete: Boolean? = null
            var homeworkIssue: String? = null
            var scheduleIssue: String? = null
            var attempted = 0
            val sessions = AcademicSession(app)
            try {
                sessions.restore()
                val connected = sessions.status()
                val providers = when {
                    scope == Scope.HOMEWORK -> listOf("learning")
                    scope == Scope.COURSES || scope == Scope.RECORDS -> listOf("school")
                    provider == "all" -> listOf("school", "learning").filter { connected.optBoolean(it) }
                    else -> listOf(provider)
                }
                for (target in providers) {
                    if (!connected.optBoolean(target)) { required.put(target); success = false; continue }
                    attempted++
                    if (target == "learning") {
                        homeworkComplete = false
                        homeworkIssue = "暂时无法确认学习通账号或读取本机缓存。"
                    }
                    val owner = runCatching { sessions.selectAccount(target, adoptLegacy = true) }.getOrNull()
                    if (owner == null) { success = false; continue }
                    val repository = LocalRepository(app, target to owner)
                    try {
                        val complete = if (target == "learning") {
                            val tasks = learning(app, sessions, repository, owner)
                            homeworkComplete = tasks.complete
                            homeworkIssue = tasks.issue
                            tasks.complete
                        } else if (scope == Scope.RECORDS) {
                            schoolRecords(sessions, repository)
                        } else {
                            val timetable = try {
                                val schedule = SchoolTimetable.read(AcademicHttp(sessions))
                                repository.replaceTimetable(schedule.courses, schedule.settings)
                                true
                            } catch (e: LoginRequired) { throw e }
                            catch (e: Exception) {
                                scheduleIssue = when (e) {
                                    is IOException -> "无法连接学校教务系统，已保留本机课表"
                                    is JSONException -> "教务系统未返回完整课表，已保留本机课表"
                                    is IllegalArgumentException -> e.message
                                    else -> "教务课表同步未完成，已保留本机课表"
                                }
                                false
                            }
                            if (scope == Scope.ALL) schoolRecords(sessions, repository) && timetable else timetable
                        }
                        val sameAccount = sessions.markForAccount(target, owner, true)
                        success = success && complete && sameAccount
                    } catch (_: LoginRequired) {
                        if (sessions.markForAccount(target, owner, false)) required.put(target)
                        success = false
                    } catch (error: LearningVerificationRequired) {
                        if (target == "learning") homeworkIssue = error.message
                        success = false
                    } catch (error: AcademicResponseError) {
                        if (target == "learning") homeworkIssue = describeHomeworkFailure(error)
                        success = false
                    }
                    catch (_: Exception) { success = false }
                }
                sessions.capture()
                HomeworkReminders.check(app)
                current = JSONObject().put("running", false).put("id", runId).put("success", success && attempted > 0).put("needLogin", required).put("finishedAt", System.currentTimeMillis())
                    .put("homeworkComplete", homeworkComplete).put("homeworkIssue", homeworkIssue).put("scheduleIssue", scheduleIssue)
            } catch (_: Exception) {
                current = JSONObject().put("running", false).put("id", runId).put("success", scope == Scope.HOMEWORK && homeworkComplete == true)
                    .put("needLogin", required).put("homeworkComplete", homeworkComplete).put("homeworkIssue", homeworkIssue).put("scheduleIssue", scheduleIssue)
                    .put("finishedAt", System.currentTimeMillis())
            } finally {
                synchronized(startLock) {
                    busy.set(false)
                    val next = queuedProvider
                    val nextScope = queuedScope ?: Scope.ALL
                    queuedProvider = null
                    queuedScope = null
                    if (next != null) start(app, provider = next, scope = nextScope)
                }
                finished?.invoke()
            }
        }
        return state()
    }

    /** Opening the app checks for homework at most once every 12 hours. */
    fun autoStart(context: Context) { homeworkIfDue(context) }
    fun homeworkIfDue(context: Context, finished: (() -> Unit)? = null) {
        val sessions = AcademicSession(context)
        val saved = sessions.status().optBoolean("learning")
        val prefs = context.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
        val owner = prefs.getString("learning.trackingOwner", "")
        val last = maxOf(prefs.getLong("lastHomeworkSync.$owner", 0), prefs.getLong("lastHomeworkCheck.$owner", 0))
        if (saved && !sessions.isLearningVerificationRequired() && System.currentTimeMillis() - last >= HOMEWORK_INTERVAL) {
            start(context, provider = "learning", scope = Scope.HOMEWORK, finished = finished)
        }
        else finished?.invoke()
    }

    private fun learning(context: Context, sessions: AcademicSession, repo: LocalRepository, owner: String): HomeworkResult {
        if (sessions.isLearningVerificationRequired()) throw LearningVerificationRequired()
        val cookie = CookieManager.getInstance().getCookie(PortalPolicy.LEARNING_HOME).orEmpty()
        if (!sessions.status().optBoolean("learning") && !Regex("(?:^|;)\\s*(?:_uid|UID|PUID)=").containsMatchIn(cookie)) throw LoginRequired("learning")
        val prefs = context.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
        prefs.edit().putLong("lastHomeworkCheck.$owner", System.currentTimeMillis()).apply()
        val tasks = try {
            homework(context, sessions, repo, owner)
        } catch (error: LoginRequired) {
            throw error
        } catch (error: AcademicResponseError) {
            if (error.status == 429) throw error
            HomeworkResult(false, describeHomeworkFailure(error))
        } catch (error: Exception) {
            HomeworkResult(false, describeHomeworkFailure(error) ?: "无法读取课程或保存本机作业。")
        }
        if (tasks.complete) prefs.edit().putLong("lastHomeworkSync.$owner", System.currentTimeMillis()).apply()
        return tasks
    }

    private fun <T, R> parallel(values: List<T>, pool: ExecutorService = requests, onComplete: ((R) -> Unit)? = null, action: (T) -> R): List<Result<R>> {
        val completed = ExecutorCompletionService<Result<R>>(pool)
        values.forEach { value -> completed.submit(Callable { runCatching { action(value) } }) }
        return List(values.size) { completed.take().get().also { result -> result.getOrNull()?.let { onComplete?.invoke(it) } } }
    }

    private data class CourseWork(val group: String, val records: List<TaskRecord>, val referer: String)
    private data class HomeworkResult(val complete: Boolean, val issue: String? = null)

    private fun describeHomeworkFailure(error: Throwable): String? = when (error) {
        is LearningVerificationRequired -> error.message
        is AcademicResponseError -> if (error.status == 429) "学习通限制了请求频率，请稍后再同步" else "学习通返回 HTTP ${error.status}"
        is HomeworkPageError -> error.message
        is SocketTimeoutException -> "访问学习通超时"
        is IOException -> "无法连接学习通或访问本机记录"
        else -> null
    }

    private fun homework(context: Context, sessions: AcademicSession, repo: LocalRepository, owner: String): HomeworkResult {
        val tracking = HomeworkTracking(context)
        check(tracking.status().optString("account") == owner) { "学习通账号已变化" }
        val selection = tracking.selection()
        if (selection.custom && selection.groups.isEmpty()) {
            repo.applyRemote("learning", "tasks", JSONArray(), complete = true)
            return HomeworkResult(true)
        }
        val savedRows = repo.read().getJSONArray("tasks")
        val known = (0 until savedRows.length()).associate { savedRows.getJSONObject(it).let { row -> row.optString("id") to row } }
        val previouslySupplemented = known.values.filter { it.optString("id").startsWith("mobile-work:") }
            .mapTo(mutableSetOf()) { it.optString("groupId") }
        val cachedCatalogue = if (selection.custom) tracking.cachedCourses()?.takeIf { list -> selection.groups.all { group -> list.any { "${it.id}:${it.classId}" == group } } } else null
        val allCourses = cachedCatalogue ?: run {
            val body = AcademicHttp(sessions).get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1", "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction").body
            val response = try { JSONObject(body) } catch (_: JSONException) { throw HomeworkPageError("课程目录返回了无法识别的页面") }
            if (!response.has("channelList") && Regex("未登录|重新登录|请登录").containsMatchIn(response.toString())) throw LoginRequired("learning")
            val catalogue = try { AcademicParsers.learningCourses(response) } catch (_: IllegalStateException) {
                throw HomeworkPageError("无法识别学习通课程目录")
            }
            tracking.updateCourses(catalogue)
            catalogue
        }
        val courses = allCourses.filter { selection.includes("${it.id}:${it.classId}") }
        val now = System.currentTimeMillis()
        var published = false
        // Read lists first. Detail lookups use the same bounded pool afterwards, without nested waits.
        val results = parallel(courses, pool = homeworkRequests, onComplete = { work: CourseWork ->
            if (!published && work.group !in previouslySupplemented && work.records.any { AcademicParsers.actionable(it.status, it.deadlineAt, now) } &&
                work.records.none { AcademicParsers.pending(it.status) && it.deadlineAt <= 0 }) {
                runCatching { repo.applyRemote("learning", "tasks", JSONArray(work.records.map { it.json() }), complete = false, groups = setOf(work.group)) }.onSuccess { published = true }
            }
        }) { course ->
            val group = "${course.id}:${course.classId}"
            val page = HomeworkPages.read(AcademicHttp(sessions), course, owner, now)
            val records = page.records.map { record -> reuseDeadline(record, known[record.id], now) }
            CourseWork(group, records, page.referer)
        }
        if (sessions.isLearningVerificationRequired()) throw LearningVerificationRequired()
        results.firstNotNullOfOrNull { (it.exceptionOrNull() as? AcademicResponseError)?.takeIf { error -> error.status == 429 } }
            ?.let { throw it }
        if (cachedCatalogue != null && results.any { it.isFailure }) runCatching { tracking.invalidateCourses() }
        results.firstNotNullOfOrNull { it.exceptionOrNull() as? LoginRequired }?.let { throw it }
        val works = results.mapNotNull { it.getOrNull() }
        val details = resolveDeadlines(sessions, works.flatMap { work -> work.records.map { it to work.referer } }, now)
        val tasks = works.flatMap { it.records }.associateByTo(linkedMapOf()) { it.id }
        tasks.putAll(details)
        val readGroups = works.mapTo(mutableSetOf()) { it.group }
        val unresolved = tasks.values.filter { AcademicParsers.pending(it.status) && it.deadlineAt <= 0 }.mapTo(mutableSetOf()) { it.group }
        val missingListTimes = tasks.values.filter { it.missingListTime }.mapTo(mutableSetOf()) { it.group }
        val activeGroups = tasks.values.filter { AcademicParsers.actionable(it.status, it.deadlineAt, now) }.mapTo(mutableSetOf()) { it.group }
        val fallbackCourses = courses.filter {
            val group = "${it.id}:${it.classId}"
            group !in readGroups || group !in activeGroups || group in unresolved ||
                group in missingListTimes || group in previouslySupplemented
        }
        var mobileFailure: Throwable? = null
        if (fallbackCourses.isNotEmpty()) {
            // The account-level page can return homework even when the course tab is missing or empty.
            val mobile = runCatching { HomeworkPages.readMobile(AcademicHttp(sessions), fallbackCourses, now) }
                .onFailure {
                    if (it is AcademicResponseError && it.status == 429) throw it
                    mobileFailure = it
                }.getOrNull()
            if (sessions.isLearningVerificationRequired()) throw LearningVerificationRequired()
            if (mobile != null) {
                if (mobile.complete) {
                    // The verified mobile list covers the account's pending homework.
                    readGroups.addAll(fallbackCourses.map { "${it.id}:${it.classId}" })
                }
                val byTitle = tasks.values.groupBy { it.group to it.name }
                val extra = mobile.records.mapNotNull { record ->
                    val matches = byTitle[record.group to record.name].orEmpty()
                    if (matches.isEmpty()) return@mapNotNull record
                    val existing = matches.singleOrNull() ?: return@mapNotNull null
                    if ((existing.missingListTime && !record.missingListTime) ||
                        (AcademicParsers.pending(existing.status) && existing.deadlineAt <= 0)) {
                        record.copy(id = existing.id)
                    } else null
                }.map { record -> reuseDeadline(record, known[record.id], now) }
                val resolved = resolveDeadlines(sessions, extra.map { it to mobile.referer }, now, optional = true)
                for (record in extra) {
                    val task = resolved[record.id] ?: record
                    tasks[task.id] = task
                }
            }
            if (mobile?.complete != true) readGroups.removeAll(previouslySupplemented)
        }
        val unread = courses.size - readGroups.size
        val missingTracked = if (selection.custom) selection.groups.size - courses.size else 0
        var missingDeadlines = 0
        val rows = tasks.values.map { task ->
            if (AcademicParsers.pending(task.status) && task.deadlineAt <= 0) {
                missingDeadlines++
                readGroups.remove(task.group)
                task.copy(deadlineAt = known[task.id]?.optLong("deadlineAt") ?: 0).json()
            } else task.json()
        }
        val complete = unread == 0 && missingTracked == 0 && missingDeadlines == 0
        repo.applyRemote("learning", "tasks", JSONArray(rows), complete = complete, groups = readGroups)
        if (complete) return HomeworkResult(true)
        val issue = buildList {
            if (unread > 0) add("${unread} 门课程的作业暂未读取")
            if (missingDeadlines > 0) add("${missingDeadlines} 项作业的截止时间待确认")
            if (missingTracked > 0) add("${missingTracked} 门追踪课程未在目录找到，可在「课程追踪」核对")
        }.joinToString("，", postfix = "。")
        val reason = if (unread > 0) {
            val failures = results.mapNotNull { it.exceptionOrNull() } + listOfNotNull(mobileFailure)
            failures.firstNotNullOfOrNull(::describeHomeworkFailure)
        } else null
        return HomeworkResult(false, if (reason == null) issue else "$issue $reason。")
    }

    private fun reuseDeadline(record: TaskRecord, cached: JSONObject?, now: Long): TaskRecord {
        val checkedAt = cached?.optLong("deadlineCheckedAt") ?: 0
        val deadline = cached?.optLong("deadlineAt") ?: 0
        return if (record.deadlineAt <= 0 && deadline > now && AcademicParsers.pending(record.status) &&
            now - checkedAt in 0L until DETAIL_INTERVAL) record.copy(deadlineAt = deadline, deadlineCheckedAt = checkedAt)
        else record
    }

    private fun resolveDeadlines(sessions: AcademicSession, candidates: List<Pair<TaskRecord, String>>, now: Long, optional: Boolean = false): Map<String, TaskRecord> {
        val missing = candidates.filter { (record, _) ->
            record.deadlineAt <= 0 && AcademicParsers.pending(record.status) && record.url.isNotBlank()
        }.distinctBy { it.first.id }
        val resolved = parallel(missing, pool = homeworkRequests) { (record, referer) ->
            try {
                val due = AcademicParsers.detailDeadline(AcademicHttp(sessions).get(record.url, referer).body, now)
                record.copy(deadlineAt = due, deadlineCheckedAt = if (due > 0) now else 0)
            } catch (error: LoginRequired) {
                if (!optional) throw error
                record
            } catch (error: AcademicResponseError) {
                if (error.status == 429) throw error
                record
            } catch (_: Exception) { record }
        }
        if (sessions.isLearningVerificationRequired()) throw LearningVerificationRequired()
        resolved.firstNotNullOfOrNull { it.exceptionOrNull() as? AcademicResponseError }
            ?.let { throw it }
        resolved.firstNotNullOfOrNull { it.exceptionOrNull() as? LoginRequired }?.let { throw it }
        return resolved.mapNotNull { it.getOrNull() }.associateBy { it.id }
    }

    private fun schoolRecords(sessions: AcademicSession, repo: LocalRepository): Boolean {
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
