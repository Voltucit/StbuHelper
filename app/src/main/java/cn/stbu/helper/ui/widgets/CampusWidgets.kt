package cn.stbu.helper.ui.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import cn.stbu.helper.R
import cn.stbu.helper.backend.academic.AcademicParsers
import cn.stbu.helper.backend.homework.HomeworkTracking
import cn.stbu.helper.backend.settings.AppPreferences
import cn.stbu.helper.backend.storage.LocalRepository
import cn.stbu.helper.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NextCourseWidget : CampusWidget(false)
class PendingHomeworkWidget : CampusWidget(true)

abstract class CampusWidget(private val homework: Boolean) : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CampusWidgets.REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)))
        } else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CampusWidgets.worker.execute {
            try { CampusWidgets.render(context.applicationContext, manager, javaClass, homework, ids) }
            finally { pending?.finish() }
        }
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(CampusWidgets.refreshIntent(context, javaClass))
    }
}

object CampusWidgets {
    const val REFRESH = "cn.stbu.helper.widgets.REFRESH"
    internal val worker = Executors.newSingleThreadExecutor()
    private val previewsRequested = AtomicBoolean(false)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    private val period = Regex("([01]\\d|2[0-3]):([0-5]\\d)-([01]\\d|2[0-3]):([0-5]\\d)")
    private data class Item(val name: String, val detail: String, val whenAt: Instant)
    private data class CourseSlot(val item: Item, val end: Instant?)

    fun publishPickerPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM ||
            !previewsRequested.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            val manager = AppWidgetManager.getInstance(app)
            val providers = listOf(
                NextCourseWidget::class.java to R.layout.campus_widget_course_preview,
                PendingHomeworkWidget::class.java to R.layout.campus_widget_homework_preview
            )
            for ((provider, layout) in providers) {
                // Picker previews use only bundled samples, including on a signed-out device.
                runCatching {
                    manager.setWidgetPreview(
                        ComponentName(app, provider),
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                        RemoteViews(app.packageName, layout)
                    )
                }
            }
        }
    }

    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (type in listOf(NextCourseWidget::class.java, PendingHomeworkWidget::class.java)) {
            if (manager.getAppWidgetIds(ComponentName(context, type)).isNotEmpty()) {
                context.sendBroadcast(Intent(context, type).setAction(REFRESH))
            }
        }
    }

    internal fun refreshIntent(context: Context, type: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, type).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    internal fun render(context: Context, manager: AppWidgetManager, type: Class<*>, homework: Boolean, ids: IntArray) {
        val now = Instant.now()
        val date = now.atZone(zone).toLocalDate()
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
        val data = runCatching { LocalRepository(context).read() }.getOrNull()
        val tasks = if (homework && data != null) pending(context, data, now) else emptyList()
        val courses = if (!homework && data != null) todayCourses(data, date) else emptyList()
        val upcoming = courses.firstOrNull { it.item.whenAt.isAfter(now) }
        val current = if (upcoming == null) courses.lastOrNull { it.end?.isAfter(now) == true } else null
        val first = if (homework) tasks.firstOrNull() else upcoming?.item ?: current?.item
        val nextChange = current?.end ?: first?.whenAt ?: midnight
        val refreshAt = if (homework) minOf(nextChange, midnight) else nextChange
        val fallback = if (homework) AppPreferences.EMPTY_TASKS else AppPreferences.EMPTY_DAY
        val key = if (homework) "emptyTasksMessage" else "emptyDayMessage"
        val message = AppPreferences(context).read().optString(key).trim().ifBlank { fallback }
        val page = if (homework) "tasks" else "week"
        val intent = Intent(context, MainActivity::class.java).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, if (homework) 72 else 71, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.campus_widget).apply {
            val title = if (homework) "未完成作业" else if (current != null) "上课中" else "下一节课"
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_name, first?.name.orEmpty())
            setViewVisibility(R.id.widget_name, if (first == null) View.GONE else View.VISIBLE)
            val detail = first?.let {
                val time = current?.end?.let { end -> clock.format(end) + " 下课" }
                    ?: if (homework) deadlineLabel(it.whenAt, date) else clock.format(it.whenAt)
                listOf(time, it.detail).filter(String::isNotBlank).joinToString(" · ")
            }.orEmpty()
            setTextViewText(R.id.widget_detail, detail)
            setViewVisibility(R.id.widget_detail, if (first == null) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_empty_message, message)
            setViewVisibility(R.id.widget_empty_message, if (first == null) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_count, "${tasks.size} 项")
            setViewVisibility(R.id.widget_count, if (tasks.size > 1) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.widget_root, open)
        }
        manager.updateAppWidget(ids, views)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val next = refreshIntent(context, type)
        alarm.cancel(next)
        if (manager.getAppWidgetIds(ComponentName(context, type)).isNotEmpty()) {
            alarm.set(AlarmManager.RTC, refreshAt.toEpochMilli() + 1000, next)
        }
    }

    private fun deadlineLabel(deadline: Instant, today: LocalDate): String {
        val due = deadline.atZone(zone).toLocalDate()
        val day = when (due) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> {
                val year = if (due.year == today.year) "" else "${due.year}年"
                "$year${due.monthValue}月${due.dayOfMonth}日"
            }
        }
        return "$day ${clock.format(deadline)} 截止"
    }

    private fun pending(context: Context, data: JSONObject, now: Instant): List<Item> {
        val selection = runCatching { HomeworkTracking(context).selection() }.getOrElse { return emptyList() }
        val tasks = data.optJSONArray("tasks") ?: JSONArray()
        return (0 until tasks.length()).mapNotNull { index ->
            val task = tasks.optJSONObject(index) ?: return@mapNotNull null
            val deadline = task.optLong("deadlineAt")
            if (!selection.includes(task.optString("groupId")) ||
                !AcademicParsers.actionable(task.optString("status"), deadline, now.toEpochMilli())) return@mapNotNull null
            Item(task.optString("name"), task.optString("course"), Instant.ofEpochMilli(deadline))
        }.sortedBy { it.whenAt }
    }

    private fun todayCourses(data: JSONObject, date: LocalDate): List<CourseSlot> {
        val settings = data.optJSONObject("settings") ?: return emptyList()
        val start = runCatching { LocalDate.parse(settings.optString("semesterStart")) }.getOrNull() ?: return emptyList()
        val monday = start.minusDays((start.dayOfWeek.value - 1).toLong())
        if (date.isBefore(monday)) return emptyList()
        val week = (ChronoUnit.DAYS.between(monday, date) / 7 + 1).toInt()
        if (week !in 1..60) return emptyList()
        val rows = data.optJSONArray("courses") ?: return emptyList()
        val from = settings.optString("summerFrom").ifBlank { "05-01" }
        val to = settings.optString("summerTo").ifBlank { "09-30" }
        val mmdd = date.toString().takeLast(5)
        val summer = if (from <= to) mmdd in from..to else mmdd >= from || mmdd <= to
        val times = settings.optString(if (summer) "summerTimes" else "winterTimes")
            .ifBlank { settings.optString("officialTimes") }.split(Regex("[\\n,，]+"))
            .map(String::trim).filter(String::isNotBlank)

        fun atSection(section: Int, end: Boolean): Instant? {
            val match = times.getOrNull(section - 1)?.let(period::matchEntire) ?: return null
            val index = if (end) 3 else 1
            val time = LocalTime.of(match.groupValues[index].toInt(), match.groupValues[index + 1].toInt())
            return date.atTime(time).atZone(zone).toInstant()
        }
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.optInt("day") != date.dayOfWeek.value) return@mapNotNull null
            val weeks = row.optJSONArray("weeks") ?: return@mapNotNull null
            if ((0 until weeks.length()).none { weeks.optInt(it) == week }) return@mapNotNull null
            val sections = row.optJSONArray("sections") ?: return@mapNotNull null
            val numbers = (0 until sections.length()).map { sections.optInt(it) }.filter { it in 1..16 }
            val first = numbers.minOrNull() ?: return@mapNotNull null
            val begin = atSection(first, end = false) ?: return@mapNotNull null
            val end = numbers.maxOrNull()?.let { atSection(it, end = true) }?.takeIf { it.isAfter(begin) }
            val detail = listOf("第 $first 节", row.optString("room")).filter(String::isNotBlank).joinToString(" · ")
            CourseSlot(Item(row.optString("name"), detail, begin), end)
        }.sortedBy { it.item.whenAt }
    }
}
