package cn.stbu.helper.backend.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import cn.stbu.helper.backend.academic.HomeworkPages
import cn.stbu.helper.backend.settings.BackgroundStore
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reclaim disposable resources while the app is visible; account/database storage is never traversed. */
class CacheMaintenance(context: Context, private val web: WebView) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("cache-maintenance", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val inspecting = AtomicBoolean(false)
    private var active = false
    private var closed = false
    private val repeat = Runnable { inspect() }

    fun start() { if (!closed) { active = true; main.removeCallbacks(repeat); inspect() } }
    fun pause() { active = false; main.removeCallbacks(repeat) }
    fun close() { pause(); closed = true; worker.shutdown() }

    private fun inspect() {
        if (!active || closed || !inspecting.compareAndSet(false, true)) return
        worker.execute {
            val now = System.currentTimeMillis()
            val clearResources = runCatching {
                BackgroundStore(app).pruneUnused(now)
                HomeworkPages.prune()
                val last = preferences.getLong("lastResourceCleanup", 0)
                now < last || now - last >= DAY || cacheBytes(app.cacheDir) >= LIMIT
            }.getOrDefault(false)
            main.post {
                inspecting.set(false)
                if (active && !closed) {
                    if (clearResources) runCatching {
                        // WebView owns its cache files; use its API instead of deleting live Chromium files.
                        web.clearCache(true)
                        preferences.edit().putLong("lastResourceCleanup", now).apply()
                    }
                    main.postDelayed(repeat, INSPECTION_INTERVAL)
                }
            }
        }
    }

    private fun cacheBytes(directory: File): Long {
        val root = directory.canonicalFile
        val pending = ArrayDeque<File>().apply { add(root) }
        val seen = mutableSetOf<String>()
        var bytes = 0L
        var inspected = 0
        while (pending.isNotEmpty()) {
            val file = pending.removeFirst().canonicalFile
            if (file != root && !file.path.startsWith(root.path + File.separator) || !seen.add(file.path)) continue
            if (++inspected > 10_000) return LIMIT
            if (file.isDirectory) file.listFiles()?.forEach { pending.add(it) }
            else bytes += file.length()
            if (bytes >= LIMIT) return bytes
        }
        return bytes
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val INSPECTION_INTERVAL = 30 * 60 * 1000L
        private const val LIMIT = 32 * 1024 * 1024L
    }
}
