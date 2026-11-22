package cn.stbu.helper.backend.academic

import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy

import android.os.SystemClock
import android.webkit.CookieManager
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class LoginRequired(val provider: String) : IllegalStateException("${if (provider == "school") "教务系统" else "学习通"}会话已失效，请重新连接")
class AcademicResponseError(val status: Int, message: String) : IllegalArgumentException(message)
class LearningVerificationRequired : IllegalStateException("学习通要求人工验证，请打开官方验证页")
data class AcademicResponse(val url: String, val body: String)

class AcademicHttp(private val sessions: AcademicSession) {
    fun get(url: String, referer: String = "") = request(url, "GET", emptyMap(), referer)
    fun post(url: String, fields: Map<String, String>, referer: String = "") = request(url, "POST", fields, referer)
    fun request(initial: String, initialMethod: String, fields: Map<String, String>, referer: String): AcademicResponse {
        var url = PortalPolicy.https(initial)
        var method = initialMethod
        val encoded = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        repeat(9) {
            require(PortalPolicy.dataUrl(url)) { "数据接口跳转到了未配置的服务" }
            val uri = URI(url)
            val provider = if (uri.host.endsWith("stbu.edu.cn")) "school" else "learning"
            val cooldownKey = if (provider == "learning") provider else uri.host
            sessions.ensureRequestSession(provider)
            if (provider == "learning" && sessions.isLearningVerificationRequired()) throw LearningVerificationRequired()
            if (uri.path.contains("/cas/login") || uri.path.contains("login_slogin") || uri.host.startsWith("passport2") && uri.path.startsWith("/login")) throw LoginRequired(provider)
            pace(uri.host, cooldownKey)
            if (provider == "learning" && sessions.isLearningVerificationRequired()) throw LearningVerificationRequired()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                if (referer.isNotBlank()) connection.setRequestProperty("Referer", referer)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
                if (method == "POST") {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    connection.setRequestProperty("Origin", "https://${uri.host}")
                    connection.outputStream.use { output -> output.write(encoded.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code == 429) blockedUntil[cooldownKey] = SystemClock.elapsedRealtime() + 60_000
                sessions.ensureRequestSession(provider)
                connection.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { CookieManager.getInstance().setCookie(url, it) }
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = PortalPolicy.https(URI(url).resolve(connection.getHeaderField("Location") ?: throw IllegalStateException("接口跳转缺少地址")).toString())
                    if (provider == "learning" && isLearningVerificationUrl(url)) {
                        sessions.requireLearningVerification(url)
                        throw LearningVerificationRequired()
                    }
                    if (code in listOf(301, 302, 303)) method = "GET"
                } else {
                    val charset = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(connection.contentType.orEmpty())?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
                    val stream = if (code in 200..299) connection.inputStream
                        else if (provider == "learning" && code in setOf(401, 403, 429)) connection.errorStream else null
                    val text = stream?.bufferedReader(charset)?.use { it.readText() }.orEmpty()
                    sessions.ensureRequestSession(provider)
                    if (provider == "learning" && isLearningVerificationPage(text)) {
                        sessions.requireLearningVerification(if (method == "GET") url else PortalPolicy.LEARNING_HOME)
                        throw LearningVerificationRequired()
                    }
                    if (code == 401) throw LoginRequired(provider)
                    if (code !in 200..299) throw AcademicResponseError(
                        code, if (code == 429) "请求较频繁，请稍后再同步" else "${if (provider == "school") "教务系统" else "学习通"}暂未返回数据（HTTP $code）"
                    )
                    if (Regex("<input[^>]+(?:type=[\"']password|id=[\"']password|id=[\"']mm[\"'])", RegexOption.IGNORE_CASE).containsMatchIn(text) && Regex("用户登录|统一身份认证|手机号/超星号|账号登录").containsMatchIn(text)) throw LoginRequired(provider)
                    return AcademicResponse(url, text)
                }
            } finally { connection.disconnect() }
        }
        throw IllegalStateException("登录跳转次数过多，请在官方页面完成验证")
    }

    companion object {
        private val verificationPrompt = Regex("操作(?:受到?|已被)?限制|操作过于频繁|9010")

        internal fun isLearningVerificationPage(html: String): Boolean {
            if (!html.contains("验证码")) return false
            val text = Jsoup.parse(html).text()
            return text.contains("验证码") && verificationPrompt.containsMatchIn(text)
        }

        internal fun isLearningVerificationUrl(url: String): Boolean {
            if (!PortalPolicy.dataUrl(url)) return false
            val uri = URI(url)
            val host = uri.host.lowercase()
            return (host == "chaoxing.com" || host.endsWith(".chaoxing.com")) &&
                uri.path.split('/').any { it.equals("antispider", ignoreCase = true) }
        }

        private val requestGate = Any()
        private val lastStart = mutableMapOf<String, Long>()
        private val blockedUntil = ConcurrentHashMap<String, Long>()
        private fun checkCooldown(key: String) {
            if (SystemClock.elapsedRealtime() < (blockedUntil[key] ?: 0L)) {
                throw AcademicResponseError(429, "服务暂时限流，保留已有数据")
            }
        }

        private fun pace(host: String, cooldownKey: String) = synchronized(requestGate) {
            checkCooldown(cooldownKey)
            val now = SystemClock.elapsedRealtime()
            val gap = 200 - (now - (lastStart[host] ?: 0L))
            if (gap > 0) Thread.sleep(gap)
            checkCooldown(cooldownKey)
            lastStart[host] = SystemClock.elapsedRealtime()
        }
    }
}
