package cn.stbu.helper.ui.auth

import cn.stbu.helper.backend.academic.AcademicSync
import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.security.CredentialVault
import cn.stbu.helper.ui.MainActivity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.*
import android.widget.*
import org.json.JSONObject

/** Official login browser, including OAuth. It never exposes a JavaScript interface. */
class PortalActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var sessions: AcademicSession
    private val handler = Handler(Looper.getMainLooper())
    private var autoStarted = false
    private var openedLogin = false
    private var provider = "learning"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind") ?: "courses"
        provider = if (kind in setOf("grades", "exams", "portal", "school")) "school" else "learning"
        sessions = AcademicSession(applicationContext)
        sessions.restore()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        toolbar.addView(Button(this).apply { text = "返回"; setOnClickListener { remember(web.url); finish() } })
        status = TextView(this).apply { text = "官方账号登录"; textSize = 12f; setPadding(8, 0, 8, 0) }
        toolbar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar)
        root.addView(TextView(this).apply { text = "在官方页面完成首次登录或验证，App 会自动同步；会话有效时无需再次登录。"; textSize = 12f; setTextColor(Color.DKGRAY); setPadding(16, 8, 16, 12) })
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION") setSaveFormData(false)
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = navigate(request.url.toString(), view, request.isForMainFrame)
            override fun onPageFinished(view: WebView, url: String) {
                status.text = Uri.parse(url).host.orEmpty()
                if (PortalPolicy.dataUrl(url)) {
                    remember(url)
                    fillSchool(url)
                    val uri = Uri.parse(url)
                    if (uri.path in setOf("/cas/login", "/xtgl/login_slogin.html") || uri.host == "passport2.chaoxing.com" && uri.path == "/login") {
                        if (!openedLogin) AcademicSession.beginLogin(if (uri.host == "passport2.chaoxing.com") "learning" else "school")
                        openedLogin = true
                        autoStarted = false
                    }
                    val learningReady = uri.host == "i.chaoxing.com" && uri.path.orEmpty().startsWith("/base") && CookieManager.getInstance().getCookie(url).orEmpty().contains("_uid=")
                    val schoolReady = uri.host == "jwxt.stbu.edu.cn" && uri.path.orEmpty().contains("index_initMenu")
                    if (!autoStarted && (learningReady || schoolReady)) {
                        provider = if (schoolReady) "school" else "learning"
                        val connected = sessions.status().optBoolean(provider)
                        val selected = runCatching {
                            sessions.selectAccount(provider, newSchoolSession = schoolReady && (openedLogin || !connected),
                                adoptLegacy = connected && !openedLogin)
                        }
                        if (selected.isFailure) { status.text = "本机账号缓存暂未切换，请重新打开登录页"; return }
                        openedLogin = false
                        sessions.mark(provider, true); autoStarted = true; synchronize()
                    }
                }
            }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: android.net.http.SslError) {
                ssl.cancel(); Toast.makeText(this@PortalActivity, "网站证书校验失败，连接已停止", Toast.LENGTH_LONG).show()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val popup = WebView(this@PortalActivity)
                popup.webViewClient = object : WebViewClient() {
                    private var handled = false
                    private fun transfer(url: String): Boolean {
                        if (handled || url == "about:blank") return false
                        handled = true
                        if (PortalPolicy.classify(url) == PortalPolicy.Action.INTERNAL) web.loadUrl(url) else navigate(url, web, true)
                        handler.post { popup.destroy() }; return true
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = transfer(request.url.toString())
                    override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) { transfer(url) }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget(); return true
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); MainActivity.applyInsets(root)
        val explicit = intent.getStringExtra("url")?.takeIf(PortalPolicy::dataUrl)
        web.loadUrl(explicit ?: if (provider == "school") PortalPolicy.SCHOOL_HOME else PortalPolicy.LEARNING_HOME)
    }
    private fun navigate(url: String, view: WebView, mainFrame: Boolean): Boolean = when (PortalPolicy.classify(url)) {
        PortalPolicy.Action.INTERNAL -> false
        PortalPolicy.Action.UPGRADE -> { if (mainFrame) view.loadUrl(PortalPolicy.https(url)); true }
        PortalPolicy.Action.APP -> { if (mainFrame) openLoginApp(url); true }
        PortalPolicy.Action.BLOCK -> { if (mainFrame) Toast.makeText(this, "此链接不是学校、学习通或微信／QQ 官方登录入口", Toast.LENGTH_LONG).show(); true }
    }
    private fun openLoginApp(url: String) {
        try {
            val parsed = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val target = when (parsed.data?.scheme?.lowercase()) { "weixin" -> "com.tencent.mm"; "mqq", "mqqapi", "mqqopensdkapi", "wtloginmqq" -> "com.tencent.mobileqq"; else -> null }
            if (target == null) {
                parsed.getStringExtra("browser_fallback_url")?.takeIf { PortalPolicy.classify(it) == PortalPolicy.Action.INTERNAL }?.let { web.loadUrl(it); return }
                Toast.makeText(this, "请使用页面中的二维码登录，或选择账号登录", Toast.LENGTH_LONG).show(); return
            }
            startActivity(Intent(Intent.ACTION_VIEW, parsed.data).setPackage(target).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) { Toast.makeText(this, "未安装对应的微信或 QQ，请使用网页二维码或账号登录", Toast.LENGTH_LONG).show() }
        catch (_: Exception) { Toast.makeText(this, "快捷登录未能打开，请使用网页二维码或账号登录", Toast.LENGTH_LONG).show() }
    }
    private fun synchronize() {
        remember(web.url); AcademicSync.start(applicationContext, force = true, provider = provider, afterCurrent = true)
        if (!intent.getBooleanExtra("stayOpen", false)) finish()
    }
    private fun fillSchool(url: String) {
        val uri = Uri.parse(url)
        val cas = uri.host == "cas.stbu.edu.cn" && uri.path == "/cas/login"
        val zf = uri.host == "jwxt.stbu.edu.cn" && uri.path == "/xtgl/login_slogin.html"
        if (!cas && !zf) return
        val account = try { CredentialVault(this).read() } catch (_: Exception) { null } ?: return
        val user = JSONObject.quote(account.optString("username")); val password = JSONObject.quote(account.optString("password"))
        val origin = JSONObject.quote("https://${uri.host}"); val path = JSONObject.quote(uri.path)
        val selectorUser = JSONObject.quote(if (cas) "#fm1 #username" else "#yhm")
        val selectorPass = JSONObject.quote(if (cas) "#fm1 #password" else "#mm")
        web.evaluateJavascript("(()=>{if(location.origin!==$origin||location.pathname!==$path)return;const u=document.querySelector($selectorUser),p=document.querySelector($selectorPass);if(u&&p&&!u.value&&!p.value){u.value=$user;p.value=$password;for(const e of [u,p])e.dispatchEvent(new Event('input',{bubbles:true}));}})()", null)
    }
    @Deprecated("WebView back navigation") override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
    private fun remember(url: String?) {
        try { sessions.capture(url) } catch (_: Exception) { Toast.makeText(this, "登录会话未能加密保存。请稍后重试保存，下次打开可能需要重新登录。", Toast.LENGTH_LONG).show() }
    }
    override fun onPause() { if (::web.isInitialized) remember(web.url); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
