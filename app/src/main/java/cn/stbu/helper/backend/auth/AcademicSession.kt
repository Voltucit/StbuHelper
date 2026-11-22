package cn.stbu.helper.backend.auth

import cn.stbu.helper.backend.security.CredentialVault
import cn.stbu.helper.backend.homework.HomeworkReminders
import cn.stbu.helper.backend.storage.LocalRepository

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Persistent account sessions are stored separately from academic data and never exported. */
class AcademicSession(context: Context) {
    private val app = context.applicationContext
    private val vault = CredentialVault(app)
    private val state = context.getSharedPreferences("academic-status", Context.MODE_PRIVATE)
    private val requestGeneration = sessionGenerations.mapValues { it.value.get() }
    private val hosts = linkedSetOf("i.chaoxing.com", "passport2.chaoxing.com", "kb.chaoxing.com", "mooc1.chaoxing.com", "mooc1-api.chaoxing.com", "mooc2-ans.chaoxing.com", "notice.chaoxing.com", "jwxt.stbu.edu.cn", "cas.stbu.edu.cn", "auth.stbu.edu.cn")

    fun restore() = synchronized(lock) {
        val saved = try { vault.readJson("academic.sessions") } catch (_: Exception) { null } ?: return@synchronized
        val cookies = saved.optJSONObject("cookies") ?: return@synchronized
        for (host in cookies.keys()) {
            if (!PortalPolicy.isDataHost(host)) continue
            val provider = if (host.endsWith("stbu.edu.cn")) "school" else "learning"
            if (state.contains("$provider.connected") && !state.getBoolean("$provider.connected", false)) continue
            // Never resurrect a cookie WebView still has. Server validity is checked on every sync.
            val current = CookieManager.getInstance().getCookie("https://$host/").orEmpty().split(';').map { it.trim().substringBefore('=') }.toSet()
            cookies.optString(host).split(';').map { it.trim() }.filter { it.contains('=') }.forEach { cookie ->
                if (cookie.substringBefore('=') !in current) CookieManager.getInstance().setCookie("https://$host/", "$cookie; Path=/; Secure")
            }
        }
    }

    fun capture(extraUrl: String? = null) = synchronized(lock) {
        extraUrl?.let { try { URI(it).host?.takeIf(PortalPolicy::isDataHost)?.let(hosts::add) } catch (_: Exception) {} }
        val previous = try { vault.readJson("academic.sessions")?.optJSONObject("cookies") } catch (_: Exception) { null } ?: JSONObject()
        for (host in hosts) {
            val value = CookieManager.getInstance().getCookie("https://$host/").orEmpty()
            if (value.isNotBlank()) previous.put(host, value)
        }
        if (previous.length() > 0) vault.saveJson("academic.sessions", JSONObject().put("cookies", previous).put("savedAt", System.currentTimeMillis()))
        CookieManager.getInstance().flush()
    }

    fun mark(provider: String, connected: Boolean) { state.edit().putBoolean("$provider.connected", connected).apply() }
    fun status(): JSONObject {
        val existing = CookieManager.getInstance().getCookie(PortalPolicy.LEARNING_HOME).orEmpty()
        val learning = if (state.contains("learning.connected")) state.getBoolean("learning.connected", false) else Regex("(?:^|;)\\s*(?:_uid|UID|PUID)=").containsMatchIn(existing)
        return JSONObject().put("learning", learning).put("school", state.getBoolean("school.connected", false))
            .put("learningVerificationRequired", isLearningVerificationRequired())
    }

    fun isLearningVerificationRequired() = state.getBoolean("learning.verificationRequired", false)

    internal fun requireLearningVerification(url: String) = synchronized(lock) {
        ensureRequestSession("learning")
        if (learningVerificationPage == null) learningVerificationPage = url
        state.edit().putBoolean("learning.verificationRequired", true).apply()
    }

    fun learningVerificationUrl(): String? = synchronized(lock) {
        if (isLearningVerificationRequired()) learningVerificationPage
            ?: "https://mooc1.chaoxing.com/mooc-ans/work/stu-work?ut=s"
        else null
    }

    fun clearLearningVerification() = synchronized(lock) {
        learningVerificationPage = null
        state.edit().remove("learning.verificationRequired").apply()
    }

    fun restoreAccountCaches() {
        val connected = status()
        for (provider in listOf("learning", "school")) {
            if (connected.optBoolean(provider)) runCatching { selectAccount(provider, adoptLegacy = true) }
        }
    }

    fun markForAccount(provider: String, owner: String, connected: Boolean): Boolean = synchronized(lock) {
        if (requestGeneration[provider] != sessionGenerations.getValue(provider).get()) return@synchronized false
        if (provider == "learning" && learningOwner()?.let { it != owner } == true) return@synchronized false
        if (LocalRepository(app).owner(provider) != owner) return@synchronized false
        mark(provider, connected)
        true
    }

    fun selectAccount(provider: String, schoolUsername: String? = null, newSchoolSession: Boolean = false, adoptLegacy: Boolean = false): String = synchronized(lock) {
        val repository = LocalRepository(app)
        val owner = if (provider == "learning") {
            requireNotNull(learningOwner()) { "学习通账号尚未确认" }
        } else {
            schoolUsername?.let { fingerprint("school:$it") }
                ?: repository.owner(provider)?.takeUnless { newSchoolSession }
                ?: UUID.randomUUID().toString()
        }
        val changed = repository.selectAccount(provider, owner, adoptLegacy)
        if (provider == "learning") {
            state.edit().putString("learning.trackingOwner", owner).apply()
            if (changed) {
                HomeworkReminders.cancelNotice(app)
                cn.stbu.helper.ui.widgets.CampusWidgets.update(app)
            }
        }
        owner
    }

    fun clear(provider: String) = synchronized(lock) {
        val saved = try { vault.readJson("academic.sessions")?.optJSONObject("cookies") } catch (_: Exception) { null } ?: JSONObject()
        val affected = (hosts + saved.keys().asSequence().toList()).filter { if (provider == "learning") it.endsWith("chaoxing.com") else it.endsWith("stbu.edu.cn") }
        for (host in affected) {
            val value = CookieManager.getInstance().getCookie("https://$host/").orEmpty()
            for (cookie in value.split(';')) {
                val name = cookie.trim().substringBefore('=')
                if (name.isBlank()) continue
                CookieManager.getInstance().setCookie("https://$host/", "$name=; Max-Age=0; Path=/; Secure")
                if (host.endsWith("chaoxing.com")) CookieManager.getInstance().setCookie("https://$host/", "$name=; Max-Age=0; Path=/; Domain=.chaoxing.com; Secure")
                if (host.endsWith("stbu.edu.cn")) CookieManager.getInstance().setCookie("https://$host/", "$name=; Max-Age=0; Path=/; Domain=.stbu.edu.cn; Secure")
            }
            saved.remove(host)
        }
        vault.saveJson("academic.sessions", JSONObject().put("cookies", saved))
        mark(provider, false)
        if (provider == "learning") {
            clearLearningVerification()
            HomeworkReminders.cancelNotice(app)
        }
    }

    companion object {
        private val lock = Any()
        private var learningVerificationPage: String? = null
        private val sessionGenerations = mapOf("learning" to AtomicLong(), "school" to AtomicLong())

        internal fun beginLogin(provider: String) { sessionGenerations.getValue(provider).incrementAndGet() }
        private val learningUid = Regex("(?:^|;)\\s*(?:_uid|UID|PUID)=([^;]+)")
        internal fun learningOwner(): String? {
            val cookie = CookieManager.getInstance().getCookie(PortalPolicy.LEARNING_HOME).orEmpty()
            val uid = learningUid.find(cookie)?.groupValues?.get(1)?.trim().orEmpty()
            return uid.takeIf { it.isNotEmpty() }?.let(::fingerprint)
        }

        private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    internal fun ensureRequestSession(provider: String) {
        check(requestGeneration[provider] == sessionGenerations.getValue(provider).get()) { "校园账号已变化，请重新同步" }
    }
}
