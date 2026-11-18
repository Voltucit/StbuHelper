package cn.stbu.helper.backend.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/** User-selected images stay in an isolated private directory served only by the local asset loader. */
class BackgroundStore(private val context: Context) {
    val directory = File(context.filesDir, "app-background").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("app-background", Context.MODE_PRIVATE)
    private val names = Regex("background-[a-f0-9-]+\\.(jpg|png|webp)")

    private fun current(): File? = preferences.getString("file", null)?.takeIf { names.matches(it) }?.let { File(directory, it) }?.takeIf { it.isFile }
    fun url(): String = current()?.let { "https://appassets.androidplatform.net/background/${it.name}" }.orEmpty()

    fun importImage(uri: Uri) {
        val temporary = File(directory, "pending-${UUID.randomUUID()}.tmp")
        var created: File? = null
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取所选图片")
            input.use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 8 * 1024 * 1024) { "请选择不超过 8 MB 的背景图片" }
                    output.write(buffer, 0, count)
                }
            } }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, options)
            require(options.outWidth in 1..8192 && options.outHeight in 1..8192) { "图片无法识别，或尺寸超过 8192 像素" }
            require(options.outWidth.toLong() * options.outHeight <= 16_777_216L) { "图片尺寸过大，请选择 1600 万像素以内的图片" }
            val extension = when (options.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> throw IllegalArgumentException("请选择 JPG、PNG 或 WebP 图片")
            }
            val old = current()
            val target = File(directory, "background-${UUID.randomUUID()}.$extension")
            check(temporary.renameTo(target)) { "背景图片未能保存" }
            created = target
            check(preferences.edit().putString("file", target.name).commit()) { "背景设置未能保存" }
            created = null
            old?.delete()
        } finally { temporary.delete(); created?.delete() }
    }

    fun clear() {
        val old = current()
        check(preferences.edit().remove("file").commit()) { "背景设置未能恢复" }
        old?.delete()
    }

    /** Interrupted imports/replacements may leave files; retain the chosen image and all recent writes. */
    fun pruneUnused(now: Long) {
        val selected = current()?.name
        val temporaryNames = Regex("pending-[a-f0-9-]+\\.tmp")
        directory.listFiles()?.forEach { file ->
            if (file.name != selected && file.isFile && file.canonicalFile.parentFile == directory.canonicalFile &&
                (names.matches(file.name) || temporaryNames.matches(file.name)) && now - file.lastModified() >= 24 * 60 * 60 * 1000L) file.delete()
        }
    }
}
