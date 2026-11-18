package cn.stbu.helper.backend.settings

import android.content.Context
import android.os.Build
import java.util.Locale
import org.json.JSONObject

/** Non-secret display preferences, kept separately from academic data and account credentials. */
class AppPreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
    private val background = BackgroundStore(context.applicationContext)

    fun read(): JSONObject = JSONObject()
        .put("color", (store.getString("color", DEFAULT_COLOR) ?: DEFAULT_COLOR).takeIf { it in COLORS } ?: DEFAULT_COLOR)
        .put("dark", store.getBoolean("dark", false))
        .put("backgroundUrl", background.url())
        .put("backgroundBrightness", store.getInt("backgroundBrightness", 100).coerceIn(25, 150))
        .put("nativeInput", store.getBoolean("nativeInput", defaultNativeInput()))
        .put("emptyDayMessage", store.getString("emptyDayMessage", EMPTY_DAY) ?: EMPTY_DAY)
        .put("emptyTasksMessage", store.getString("emptyTasksMessage", EMPTY_TASKS) ?: EMPTY_TASKS)

    fun save(value: JSONObject): JSONObject {
        val next = read()
        if (value.has("color")) {
            val color = value.getString("color")
            require(color in COLORS) { "请选择有效的主题颜色" }
            next.put("color", color)
        }
        if (value.has("dark")) next.put("dark", value.getBoolean("dark"))
        if (value.has("backgroundBrightness")) {
            val brightness = value.getInt("backgroundBrightness")
            require(brightness in 25..150) { "背景亮度需在 25% 到 150% 之间" }
            next.put("backgroundBrightness", brightness)
        }
        if (value.has("nativeInput")) next.put("nativeInput", value.getBoolean("nativeInput"))
        for ((key, fallback) in listOf("emptyDayMessage" to EMPTY_DAY, "emptyTasksMessage" to EMPTY_TASKS)) {
            if (!value.has(key)) continue
            val message = value.getString(key).trim()
            require(message.length <= 200) { "首页提示最多填写 200 个字符" }
            next.put(key, message.ifEmpty { fallback })
        }
        store.edit().putString("color", next.getString("color")).putBoolean("dark", next.getBoolean("dark")).putBoolean("nativeInput", next.getBoolean("nativeInput"))
            .putInt("backgroundBrightness", next.getInt("backgroundBrightness"))
            .putString("emptyDayMessage", next.getString("emptyDayMessage"))
            .putString("emptyTasksMessage", next.getString("emptyTasksMessage")).apply()
        return next
    }

    private fun defaultNativeInput(): Boolean {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        return Build.VERSION.SDK_INT >= 36 && (brand.contains("vivo") || brand.contains("iqoo"))
    }

    companion object {
        private const val DEFAULT_COLOR = "blue"
        private val COLORS = setOf(
            "blue", "mist", "indigo", "teal", "cobalt", "deepsea", "jade", "berry",
            "green", "purple", "orange", "rose"
        )
        const val EMPTY_DAY = "今天没有课程哦，可以好好休息"
        const val EMPTY_TASKS = "好厉害，作业全部都做完了！"
    }
}
