package cn.stbu.helper.backend.homework

import cn.stbu.helper.R
import cn.stbu.helper.backend.academic.AcademicParsers
import cn.stbu.helper.backend.academic.AcademicSync
import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.storage.LocalRepository
import cn.stbu.helper.ui.MainActivity

import android.Manifest
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.Executors

data class ReminderTask(val id: String, val name: String, val status: String, val deadlineAt: Long)
data class ReminderDecision(val key: String, val task: ReminderTask, val reason: String)

object HomeworkReminderPlan {
    fun plan(tasks: List<ReminderTask>, seen: Set<String>, now: Long): List<ReminderDecision> = tasks.mapNotNull { task ->
        if (!AcademicParsers.actionable(task.status, task.deadlineAt, now)) return@mapNotNull null
        val window = when {
            task.deadlineAt > 0 && task.deadlineAt - now <= 3 * 60 * 60 * 1000 -> "3h"
            task.deadlineAt > 0 && task.deadlineAt - now <= 24 * 60 * 60 * 1000 -> "24h"
            else -> "new"
        }
        val key = if (window == "new") "new:${task.id}" else "$window:${task.id}:${task.deadlineAt / 86400000}"
        if (key in seen) null else ReminderDecision(key, task, when (window) { "3h" -> "3 小时内截止"; "24h" -> "24 小时内截止"; else -> "有待完成的作业" })
    }.distinctBy { it.key }
}

object HomeworkReminders {
    private const val JOB = 62
    private const val CHANNEL = "homework"
    fun initialize(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "学习通作业提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "新作业，以及截止前 24 小时和 3 小时提醒" })
        if (enabled(context)) schedule(context)
    }
    fun enabled(context: Context) = context.getSharedPreferences("homework-reminders", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun status(context: Context) = JSONObject().put("enabled", enabled(context)).put("permission", available(context))
    fun configure(context: Context, enabled: Boolean): JSONObject {
        context.getSharedPreferences("homework-reminders", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (enabled) { schedule(context); check(context) } else {
            context.getSystemService(JobScheduler::class.java).cancel(JOB)
            context.getSystemService(NotificationManager::class.java).cancel(JOB)
        }
        return status(context)
    }
    private fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) != null) return
        scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, HomeworkJobService::class.java)).setPeriodic(30 * 60 * 1000L).setPersisted(true).build())
    }
    fun check(context: Context) {
        if (!enabled(context) || !available(context)) return
        val preferences = context.getSharedPreferences("homework-reminders", Context.MODE_PRIVATE)
        val account = context.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
        val owner = account.getString("learning.trackingOwner", "").orEmpty()
        val rows = LocalRepository(context).read().getJSONArray("tasks")
        val selection = HomeworkTracking(context).selection()
        val tasks = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.takeIf { selection.includes(it.optString("groupId")) }?.let { ReminderTask(it.optString("id"), it.optString("name"), it.optString("status"), it.optLong("deadlineAt")) } }.filter { it.id.isNotBlank() }
        val delivered = if (owner.isEmpty()) "delivered" else "delivered.$owner"
        val seen = preferences.getStringSet(delivered, emptySet()).orEmpty()
        val decisions = HomeworkReminderPlan.plan(tasks, seen, System.currentTimeMillis())
        if (decisions.isEmpty()) return
        val open = PendingIntent.getActivity(context, 62, Intent(context, MainActivity::class.java).putExtra("page", "tasks").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = decisions.take(4).joinToString("\n") { "${it.task.name} · ${it.reason}" }
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_homework).setContentTitle("学习通：${decisions.size} 项作业需要关注")
            .setContentText(text.lineSequence().first()).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        try {
            if (account.getString("learning.trackingOwner", "").orEmpty() != owner) return
            context.getSystemService(NotificationManager::class.java).notify(JOB, notification)
            val keys = (seen + decisions.flatMap { listOf(it.key, "new:${it.task.id}") }).toList().takeLast(6000).toSet()
            preferences.edit().putStringSet(delivered, keys).apply()
        } catch (_: SecurityException) { /* Permission may be revoked during a scheduled check. */ }
    }

    fun trackingChanged(context: Context) {
        cancelNotice(context)
        check(context)
    }

    fun cancelNotice(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(JOB)
}

class HomeworkJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    override fun onStartJob(parameters: JobParameters): Boolean {
        stopped = false
        worker.execute {
            try {
                HomeworkReminders.check(applicationContext)
                if (!stopped && AcademicSession(applicationContext).status().optBoolean("learning")) AcademicSync.homeworkIfDue(applicationContext) { if (!stopped) jobFinished(parameters, false) }
                else if (!stopped) jobFinished(parameters, false)
            } catch (_: Exception) { if (!stopped) jobFinished(parameters, true) }
        }
        return true
    }
    override fun onStopJob(parameters: JobParameters): Boolean { stopped = true; return HomeworkReminders.enabled(this) }
    override fun onDestroy() { stopped = true; worker.shutdown(); super.onDestroy() }
}
