package cn.stbu.helper.ui.auth

import cn.stbu.helper.backend.academic.AcademicSync
import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.security.CredentialVault

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** App-owned login form with an isolated, invisible official-site authentication engine. */
class InlineLogin(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val storage = Executors.newSingleThreadExecutor()
    private val probe = activity.assets.open("auth-engine/login-probe.js").bufferedReader().use { it.readText() }
    private val submit = activity.assets.open("auth-engine/login-submit.js").bufferedReader().use { it.readText() }
    private var browser: WebView? = null
    private var generation = 0L
    private var provider = ""
    private var username = ""
    private var password = ""
    private var remember = true
    private var deadline = 0L
    private var promoted = false
    private var passwordSubmitted = false
    private var value = JSONObject().put("stage", "idle")
    private val tick = object : Runnable { override fun run() { inspect(); if (browser != null) handler.postDelayed(this, 800) } }

    fun status(): JSONObject = JSONObject(value.toString())
    private fun state(stage: String, message: String = ""): JSONObject {
        value = JSONObject().put("id", generation).put("provider", provider).put("stage", stage).put("message", message)
        return status()
    }

    fun begin(args: JSONObject): JSONObject {
        val kind = args.getString("provider")
        require(kind in setOf("school", "learning"))
        require(!AcademicSession(activity).status().optBoolean(kind)) { "账号已连接，可直接同步；切换账号请先退出" }
        require(!AcademicSync.state().optBoolean("running")) { "请等当前同步完成后再登录" }
        val user = args.optString("username").trim()
        val secret = args.optString("password")
        require(user.isNotEmpty() && user.length <= 128 && secret.length <= 1024) { "请填写有效账号" }
        cancel(); provider = kind; username = user; remember = args.optBoolean("remember", true)
        AcademicSession.beginLogin(kind)
        val token = generation
        state("preparing", "正在登录")
        storage.execute {
            val resolved = runCatching {
                if (secret.isNotEmpty()) secret else {
                    val saved = CredentialVault(activity).readJson(bucket(kind))
                    require(saved?.optString("username") == user) { "请填写密码" }
                    saved!!.getString("password")
                }
            }
            handler.post {
                if (token != generation || activity.isFinishing || activity.isDestroyed) return@post
                resolved.fold(onSuccess = { password = it; startBrowser(token) }, onFailure = { state("failed", "无法读取已保存密码，请重新输入密码"); clearSecrets() })
            }
        }
        return status()
    }

    private fun startBrowser(token: Long) {
        deadline = System.currentTimeMillis() + 180_000; promoted = false; passwordSubmitted = false
        val engine = WebView(activity)
        browser = engine
        engine.alpha = 0f; engine.translationX = -10000f
        engine.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        engine.isFocusable = false; engine.isClickable = false
        engine.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION") setSaveFormData(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(engine, false)
        engine.webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(message: ConsoleMessage) = true }
        engine.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (PortalPolicy.dataUrl(url)) return false
                if (PortalPolicy.classify(url) == PortalPolicy.Action.UPGRADE && PortalPolicy.isDataHost(request.url.host)) {
                    if (request.isForMainFrame) view.loadUrl(PortalPolicy.https(url)); return true
                }
                if (request.isForMainFrame) fail("webRequired", "此登录方式需要官方网页授权，请主动点击打开官方网页。")
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (token == generation) state("submitting", "正在登录") }
            override fun onPageFinished(view: WebView, url: String) { if (token == generation) inspect() }
            override fun onReceivedSslError(view: WebView, result: SslErrorHandler, error: android.net.http.SslError) { result.cancel(); fail("failed", "官方站点证书校验失败，请稍后重试") }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) fail("failed", "无法连接官方登录服务，请检查网络后重试") }
        }
        // A measured but transparent view lets official CAPTCHA and form scripts finish layout.
        activity.addContentView(engine, ViewGroup.LayoutParams(1024, 768))
        engine.loadUrl(if (provider == "school") SCHOOL_LOGIN else LEARNING_LOGIN)
        handler.post(tick)
    }

    private fun inspect() {
        val engine = browser ?: return
        if (System.currentTimeMillis() > deadline) { fail("failed", "登录等待超时，请重试或主动打开官方网页"); return }
        if (value.optString("stage") == "saving") return
        val url = engine.url.orEmpty(); val uri = Uri.parse(url)
        val learningReady = provider == "learning" && uri.host == "i.chaoxing.com" && uri.path.orEmpty().startsWith("/base") && Regex("(?:^|;)\\s*(?:_uid|UID|PUID)=").containsMatchIn(CookieManager.getInstance().getCookie(url).orEmpty())
        val schoolReady = provider == "school" && uri.host == "jwxt.stbu.edu.cn" && uri.path.orEmpty().contains("index_initMenu")
        if (learningReady || schoolReady) { authenticated(url); return }
        if (!promoted && provider == "school" && uri.host == "auth.stbu.edu.cn" && uri.path.orEmpty().startsWith("/index")) { promoted = true; engine.loadUrl(PortalPolicy.SCHOOL_HOME); return }
        if (!promoted && provider == "learning" && uri.host == "passport2.chaoxing.com" && Regex("(?:^|;)\\s*(?:_uid|UID|PUID)=").containsMatchIn(CookieManager.getInstance().getCookie(PortalPolicy.LEARNING_HOME).orEmpty())) { promoted = true; engine.loadUrl(PortalPolicy.LEARNING_HOME); return }
        val token = generation
        engine.evaluateJavascript(probe) { raw ->
            if (token != generation || engine !== browser || engine.url != url) return@evaluateJavascript
            val result = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
            when (val stage = result.optString("stage")) {
                "ready" -> if (!passwordSubmitted) execute(JSONObject().put("kind", "password").put("username", username).put("password", password))
                "captcha", "sms" -> {
                    value = result.put("id", generation).put("provider", provider)
                    if (stage == "captcha" && result.optString("image").length > 512_000) fail("webRequired", "验证码过大，请主动打开官方网页验证")
                }
                "failed", "webRequired" -> fail(stage, result.optString("message", "请检查账号和密码后重试"))
            }
        }
    }

    fun challenge(code: String): JSONObject {
        require(code.isNotBlank() && code.length <= 32) { "请输入验证码" }
        val stage = value.optString("stage"); require(stage == "captcha" || stage == "sms") { "当前没有等待输入的验证码" }
        deadline = System.currentTimeMillis() + 180_000
        val payload = JSONObject().put("kind", if (stage == "sms") "sms" else "password").put("code", code)
        if (stage == "captcha") payload.put("username", username).put("password", password)
        execute(payload)
        return status()
    }

    fun sendSms(): JSONObject { require(value.optString("stage") == "sms") { "当前不需要短信验证" }; execute(JSONObject().put("kind", "sendSms")); return status() }
    fun refreshCaptcha(): JSONObject { require(value.optString("stage") == "captcha") { "当前没有图片验证码" }; execute(JSONObject().put("kind", "refresh")); return status() }
    private fun execute(payload: JSONObject) {
        val engine = browser ?: return
        val token = generation
        state("submitting", "正在登录")
        if (payload.optString("kind") == "password") passwordSubmitted = true
        engine.evaluateJavascript("($submit)($payload)") { result ->
            if (token == generation && browser === engine && result != "true") fail("webRequired", "官方登录表单暂不兼容，请主动打开官方网页完成登录")
        }
    }

    private fun authenticated(url: String) {
        val token = generation; val kind = provider; val user = username; val secret = password; val save = remember
        val submitted = passwordSubmitted
        state("saving", "正在保存本机会话"); disposeBrowser(); clearSecrets()
        storage.execute {
            val outcome = runCatching {
                val vault = CredentialVault(activity)
                if (save) vault.saveJson(bucket(kind), JSONObject().put("username", user).put("password", secret)) else vault.remove(bucket(kind))
                val sessions = AcademicSession(activity)
                val connected = sessions.status().optBoolean(kind)
                sessions.capture(url)
                sessions.selectAccount(kind, schoolUsername = user.takeIf { kind == "school" && submitted },
                    newSchoolSession = kind == "school" && !connected, adoptLegacy = connected && !submitted)
                sessions.mark(kind, true)
            }
            handler.post {
                if (token != generation) return@post
                if (outcome.isSuccess) { state("success", "登录成功"); AcademicSync.start(activity.applicationContext, provider = kind, afterCurrent = true) }
                else state("failed", "官方已完成认证，但本机加密保存未完成。请重试；密码未改用其他方式保存。")
            }
        }
    }

    private fun fail(stage: String, message: String) { state(stage, message); disposeBrowser(); clearSecrets() }
    fun cancel(): JSONObject { generation++; disposeBrowser(); clearSecrets(); return state("cancelled") }
    private fun clearSecrets() { username = ""; password = "" }
    private fun disposeBrowser() { handler.removeCallbacks(tick); browser?.let { engine -> browser = null; engine.stopLoading(); (engine.parent as? ViewGroup)?.removeView(engine); engine.destroy() } }
    fun destroy() { cancel(); storage.shutdown() }

    companion object {
        const val SCHOOL_LOGIN = "https://cas.stbu.edu.cn/cas/login?service=https%3A%2F%2Fauth.stbu.edu.cn%2Findex"
        const val LEARNING_LOGIN = "https://passport2.chaoxing.com/login?fid=-1&newversion=true&refer=https%3A%2F%2Fi.chaoxing.com%2Fbase%3Fws%3D1"
        fun bucket(provider: String) = if (provider == "school") "school" else "learning.credentials"
    }
}
