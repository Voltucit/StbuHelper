package cn.stbu.helper.ui

import cn.stbu.helper.BuildConfig
import cn.stbu.helper.backend.academic.AcademicSync
import cn.stbu.helper.backend.academic.LoginRequired
import cn.stbu.helper.backend.auth.AcademicSession
import cn.stbu.helper.backend.auth.PortalPolicy
import cn.stbu.helper.backend.homework.HomeworkReminders
import cn.stbu.helper.backend.homework.HomeworkTracking
import cn.stbu.helper.backend.life.DeviceQr
import cn.stbu.helper.backend.life.QuzhiManager
import cn.stbu.helper.backend.security.CredentialVault
import cn.stbu.helper.backend.settings.AppPreferences
import cn.stbu.helper.backend.settings.BackgroundStore
import cn.stbu.helper.backend.storage.LocalRepository
import cn.stbu.helper.backend.storage.CacheMaintenance
import cn.stbu.helper.ui.auth.InlineLogin
import cn.stbu.helper.ui.auth.PortalActivity
import cn.stbu.helper.ui.life.DeviceScanActivity
import cn.stbu.helper.ui.life.RechargeLauncher
import cn.stbu.helper.ui.input.NativeTextEditor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var cacheMaintenance: CacheMaintenance
    private lateinit var root: FrameLayout
    private lateinit var appPreferences: AppPreferences
    private lateinit var backgrounds: BackgroundStore
    private var pendingBackground: Int? = null
    private lateinit var repository: LocalRepository
    private lateinit var vault: CredentialVault
    private lateinit var inlineLogin: InlineLogin
    private lateinit var nativeTextEditor: NativeTextEditor
    private val worker = Executors.newSingleThreadExecutor()
    private val courseReader = Executors.newSingleThreadExecutor()
    private val walletReader = Executors.newSingleThreadExecutor()
    private val appUrl = "https://appassets.androidplatform.net/assets/frontend/index.html"
    private var pendingExport: String? = null
    private var pendingPermission: Triple<Int, String, String>? = null
    private var notificationPermissionAsked = false
    private var launchPage = ""
    private var pendingScan: Int? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        cn.stbu.helper.ui.widgets.CampusWidgets.publishPickerPreviews(applicationContext)
        repository = LocalRepository(this)
        appPreferences = AppPreferences(this)
        backgrounds = BackgroundStore(this)
        nativeTextEditor = NativeTextEditor(this) { appPreferences.read().optBoolean("dark") }
        vault = CredentialVault(this)
        inlineLogin = InlineLogin(this)
        AcademicSession(this).also { sessions ->
            sessions.restore()
            worker.execute { sessions.restoreAccountCaches() }
        }
        HomeworkReminders.initialize(this)
        launchPage = intent.getStringExtra("page").orEmpty()
        QuzhiManager.initialize(applicationContext)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor(if (appPreferences.read().optBoolean("dark")) "#151b18" else "#f7f8f5"))
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION") setSaveFormData(false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/background/", WebViewAssetLoader.InternalStoragePathHandler(this, backgrounds.directory)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "Blocked".byteInputStream())
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        web.addJavascriptInterface(LocalBridge(this), "StbuNative")
        root = FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        applyInsets(root)
        applyAppearance()
        web.loadUrl(appUrl)
        cacheMaintenance = CacheMaintenance(applicationContext, web)
    }

    override fun onResume() {
        super.onResume()
        if (::cacheMaintenance.isInitialized) cacheMaintenance.start()
        AcademicSync.autoStart(applicationContext)
        if (::web.isInitialized) web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null)
    }

    fun call(id: Int, method: String, payload: String) {
        if (method == "chooseAppBackground") {
            runOnUiThread {
                if (pendingBackground != null) { scannerReply(id, JSONObject().put("ok", false).put("error", "请先完成当前图片选择")); return@runOnUiThread }
                pendingBackground = id
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp")), 103)
                } catch (_: Exception) { pendingBackground = null; scannerReply(id, JSONObject().put("ok", false).put("error", "无法打开系统图片选择器")) }
            }
            return
        }
        if (method == "editLocalText") {
            runOnUiThread {
                try {
                    nativeTextEditor.open(JSONObject(payload)) { value -> scannerReply(id, JSONObject().put("ok", true).put("data", value)) }
                } catch (_: Exception) { scannerReply(id, JSONObject().put("ok", false).put("error", "系统输入框未能打开，请重试")) }
            }
            return
        }
        if (method == "lifeRecharge") {
            runOnUiThread {
                val response = try {
                    require(QuzhiManager.status().optBoolean("connected")) { "请先连接趣智校园" }
                    RechargeLauncher.open(this)
                    JSONObject().put("ok", true).put("data", JSONObject().put("opened", true))
                } catch (_: android.content.ActivityNotFoundException) {
                    JSONObject().put("ok", false).put("error", "未安装支付宝，无法打开充值页面")
                } catch (error: Exception) {
                    JSONObject().put("ok", false).put("error", if (error is IllegalArgumentException) error.message else "充值页面未能打开，请检查支付宝后重试")
                }
                scannerReply(id, response)
            }
            return
        }
        if (method == "saveAppPreferences") {
            runOnUiThread {
                val response = try {
                    val changes = JSONObject(payload)
                    val saved = appPreferences.save(changes)
                    if (changes.has("emptyDayMessage") || changes.has("emptyTasksMessage")) {
                        runCatching { cn.stbu.helper.ui.widgets.CampusWidgets.update(applicationContext) }
                    }
                    applyAppearance()
                    JSONObject().put("ok", true).put("data", saved)
                } catch (error: Exception) {
                    JSONObject().put("ok", false).put("error", if (error is IllegalArgumentException) error.message ?: "设置内容不正确" else "设置未能保存，请重试")
                }
                scannerReply(id, response)
            }
            return
        }
        if (method in setOf("beginLogin", "loginStatus", "submitLoginChallenge", "sendLoginSms", "refreshLoginCaptcha", "cancelLogin")) {
            runOnUiThread {
                val response = try {
                    val args = JSONObject(payload)
                    val result = when (method) {
                        "beginLogin" -> inlineLogin.begin(args)
                        "submitLoginChallenge" -> inlineLogin.challenge(args.getString("code"))
                        "sendLoginSms" -> inlineLogin.sendSms()
                        "refreshLoginCaptcha" -> inlineLogin.refreshCaptcha()
                        "cancelLogin" -> inlineLogin.cancel()
                        else -> inlineLogin.status()
                    }
                    JSONObject().put("ok", true).put("data", result)
                } catch (error: Exception) {
                    JSONObject().put("ok", false).put("error", if (error is IllegalArgumentException) error.message ?: "请检查登录输入" else "登录操作未完成，请重试")
                }
                scannerReply(id, response)
            }
            return
        }
        if (method == "lifeScanCode") {
            runOnUiThread {
                if (pendingScan != null) { scannerReply(id, JSONObject().put("ok", false).put("error", "请先完成当前扫码")); return@runOnUiThread }
                pendingScan = id
                try {
                    IntentIntegrator(this).setCaptureActivity(DeviceScanActivity::class.java)
                        .setDesiredBarcodeFormats(listOf(IntentIntegrator.QR_CODE)).setPrompt("扫描趣智校园设备二维码")
                        .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(true).setTimeout(120_000).initiateScan()
                } catch (_: Exception) { pendingScan = null; scannerReply(id, JSONObject().put("ok", false).put("error", "无法打开相机，请检查相机权限")) }
            }
            return
        }
        val required = if (method in setOf("lifeScan", "lifeStart", "lifeStop", "lifeConnect", "lifeEnd")) blePermissions().toList() else emptyList()
        val requested = required + if (method == "lifeStart" && android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = requested.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            runOnUiThread {
                if (pendingPermission == null) {
                    pendingPermission = Triple(id, method, payload)
                    if (missing.contains(android.Manifest.permission.POST_NOTIFICATIONS)) notificationPermissionAsked = true
                    requestPermissions(missing.toTypedArray(), 201)
                } else web.evaluateJavascript("window.Native.resolve($id,{ok:false,error:'请先完成蓝牙权限选择'})", null)
            }
            return
        }
        val dispatcher = when (method) {
            "refreshHomeworkCourses" -> courseReader
            "lifeBills", "lifeRefresh", "lifeReceipt" -> walletReader
            else -> worker
        }
        dispatcher.execute {
            val response = try {
                val args = JSONObject(payload)
                val result = when (method) {
                    "bootstrap" -> JSONObject().put("data", repository.read()).put("credential", vault.status()).put("life", QuzhiManager.status()).put("android", true)
                        .put("app", JSONObject().put("name", applicationInfo.loadLabel(packageManager).toString()).put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
                        .put("preferences", appPreferences.read())
                        .put("academic", AcademicSession(this).status()).put("sync", AcademicSync.state()).put("reminders", HomeworkReminders.status(this)).put("tracking", HomeworkTracking(this).status()).put("launchPage", launchPage).also { launchPage = "" }
                    "loginProfile" -> {
                        val provider = args.getString("provider"); require(provider in setOf("school", "learning"))
                        val saved = runCatching { vault.readJson(InlineLogin.bucket(provider)) }.getOrNull()
                        JSONObject().put("saved", saved != null).put("username", saved?.optString("username").orEmpty())
                    }
                    "homeworkTracking" -> HomeworkTracking(this).status()
                    "refreshHomeworkCourses" -> try { HomeworkTracking(this).refreshCourses() } catch (error: LoginRequired) { AcademicSession(this).mark("learning", false); throw IllegalArgumentException("学习通登录已失效，请重新连接") }
                    "saveHomeworkTracking" -> {
                        val result = HomeworkTracking(this).save(args.getString("account"), args.optBoolean("custom"), args.getJSONArray("selected"))
                        runCatching { HomeworkReminders.trackingChanged(this) }
                        runCatching { cn.stbu.helper.ui.widgets.CampusWidgets.update(applicationContext) }
                        AcademicSync.start(applicationContext, provider = "learning", afterCurrent = true, homeworkOnly = true)
                        result
                    }
                    "syncAcademics" -> { val provider = args.optString("provider", "all"); require(provider in setOf("all", "learning", "school")); AcademicSync.start(applicationContext, force = true, provider = provider, homeworkOnly = args.optBoolean("homeworkOnly"), coursesOnly = args.optBoolean("coursesOnly"), currentWeekOnly = args.optBoolean("currentWeekOnly")) }
                    "academicState" -> AcademicSync.state().put("updatedAt", repository.read().optString("updatedAt"))
                    "academicLogout" -> { require(!AcademicSync.state().optBoolean("running")) { "同步进行中，请完成后再退出账号" }; val provider = args.getString("provider"); require(provider in setOf("learning", "school")); AcademicSession(this).clear(provider); AcademicSession(this).status() }
                    "configureReminders" -> {
                        val enabled = args.optBoolean("enabled")
                        val result = HomeworkReminders.configure(this, enabled)
                        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 202) }
                        result
                    }
                    "notificationSettings" -> {
                        runOnUiThread { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
                        JSONObject()
                    }
                    "openTask" -> {
                        val url = args.getString("url"); require(PortalPolicy.dataUrl(url))
                        runOnUiThread { startActivity(Intent(this, PortalActivity::class.java).putExtra("kind", "tasks").putExtra("url", url)) }; JSONObject()
                    }
                    "lifeStatus" -> QuzhiManager.status()
                    "lifeLogin" -> QuzhiManager.login(args.getString("telephone"), args.getString("password"))
                    "lifeLogout" -> QuzhiManager.logout()
                    "lifeRefresh" -> QuzhiManager.refresh()
                    "lifeBills" -> QuzhiManager.bills(args.getString("month"))
                    "lifeReceipt" -> QuzhiManager.receipt(args.getString("id"))
                    "lifeConfirmReceipt" -> QuzhiManager.confirmReceipt(args.getString("id"))
                    "clearAppBackground" -> { backgrounds.clear(); JSONObject().put("preferences", appPreferences.read()) }
                    "lifeScan" -> runBlocking { QuzhiManager.scan() }
                    "lifeDevice" -> QuzhiManager.device(args.getString("mac"))
                    "lifeBindDevice" -> QuzhiManager.bindDevice(args.getString("mac"), args.getString("kind"))
                    "lifeUnbindDevice" -> QuzhiManager.unbindDevice(args.getString("mac"))
                    "lifeStart" -> runBlocking { QuzhiManager.start(args.getString("mac")) }
                    "lifeConnect" -> runBlocking { QuzhiManager.connect(args.getString("mac")) }
                    "lifeEnd" -> runBlocking { QuzhiManager.disconnectAndEnd(args.optString("mac").takeIf { it.isNotBlank() }) }
                    "lifeStop" -> runBlocking { QuzhiManager.stop(args.optString("mac").takeIf { it.isNotBlank() }) }
                    "saveCourse" -> repository.saveCourse(args.getJSONObject("course"))
                    "deleteCourse" -> repository.deleteCourse(args.getString("id"))
                    "saveSettings" -> repository.saveSettings(args.getJSONObject("settings"))
                    "clearAcademicData" -> { require(!AcademicSync.state().optBoolean("running")) { "请等同步完成后再清除数据" }; repository.clearData(); repository.read() }
                    "saveData" -> { require(!AcademicSync.state().optBoolean("running")) { "请等同步完成后再导入备份" }; repository.write(args.getJSONObject("data")); JSONObject().put("saved", true) }
                    "saveCredentials" -> { vault.save(args.getString("username").trim(), args.getString("password")); vault.status() }
                    "clearCredentials" -> { require(!AcademicSync.state().optBoolean("running")) { "同步进行中，请完成后再删除凭据" }; vault.clear(); AcademicSession(this).clear("school"); vault.status() }
                    "openPortal" -> {
                        val kind = args.optString("kind", "courses")
                        require(kind in LocalRepository.kinds || kind in setOf("portal", "learning", "school"))
                        runOnUiThread { startActivity(Intent(this, PortalActivity::class.java).putExtra("kind", kind).putExtra("stayOpen", true)) }
                        JSONObject().put("opened", true)
                    }
                    "importFile" -> { runOnUiThread { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 101) }; JSONObject() }
                    "exportFile" -> {
                        pendingExport = repository.exportData().toString(2)
                        runOnUiThread { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "StbuHelper-${java.time.LocalDate.now()}.json"), 102) }
                        JSONObject()
                    }
                    else -> throw IllegalArgumentException("不支持的操作")
                }
                JSONObject().put("ok", true).put("data", result)
            } catch (e: Exception) {
                // No exception text, URLs or credential-bearing payloads are logged or returned.
                val message = when (method) {
                    "saveCredentials" -> "系统密钥库未能保存凭据。密码没有改用其他方式保存，请重试或直接打开学校登录页。"
                    "bootstrap" -> "本地数据无法读取，请检查手机存储。原文件仍保留在应用内。"
                    else -> if (e is IllegalArgumentException) e.message ?: "数据格式不正确" else if (method.startsWith("life")) "趣智校园操作未确认完成。请核对设备状态，勿重复开启；可尝试结束并结算。" else "操作未完成，请重试"
                }
                JSONObject().put("ok", false).put("error", message)
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) web.evaluateJavascript("window.Native && window.Native.resolve($id,${response})", null) }
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 103) {
            val id = pendingBackground ?: return
            pendingBackground = null
            val image = data?.data
            if (resultCode != RESULT_OK || image == null) { scannerReply(id, JSONObject().put("ok", true).put("data", JSONObject().put("cancelled", true))); return }
            worker.execute {
                val response = try {
                    backgrounds.importImage(image)
                    JSONObject().put("ok", true).put("data", JSONObject().put("cancelled", false).put("preferences", appPreferences.read()))
                } catch (error: Exception) { JSONObject().put("ok", false).put("error", if (error is IllegalArgumentException) error.message else "背景图片未能保存，请重试") }
                runOnUiThread { scannerReply(id, response) }
            }
            return
        }
        val scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanned != null) {
            val id = pendingScan ?: return
            pendingScan = null
            val response = try {
                val value = if (scanned.contents == null) JSONObject().put("cancelled", true) else JSONObject().put("mac", DeviceQr.address(scanned.contents))
                JSONObject().put("ok", true).put("data", value)
            } catch (error: IllegalArgumentException) { JSONObject().put("ok", false).put("error", error.message) }
            scannerReply(id, response)
            return
        }
        if (resultCode != RESULT_OK) { pendingExport = null; return }
        val uri = data?.data ?: return
        worker.execute {
            val message = try {
                if (requestCode == 101) {
                    require(!AcademicSync.state().optBoolean("running")) { "请等同步完成后再导入备份" }
                    val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= LocalRepository.MAX_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, LocalRepository.MAX_BYTES + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.size <= LocalRepository.MAX_BYTES)
                    repository.write(JSONObject(bytes.toString(Charsets.UTF_8)))
                    "已导入本地数据"
                } else {
                    val content = pendingExport ?: throw IllegalStateException()
                    contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    pendingExport = null
                    "已导出；文件不包含学号密码"
                }
            } catch (_: Exception) { "文件操作失败，请检查格式和存储权限" }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null) }
        }
    }

    @Deprecated("WebView back navigation")
    override fun onBackPressed() { web.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : false") { if (it != "true") finish() } }
    override fun onPause() { if (::cacheMaintenance.isInitialized) cacheMaintenance.pause(); super.onPause() }
    override fun onDestroy() { if (::cacheMaintenance.isInitialized) cacheMaintenance.close(); nativeTextEditor.close(); inlineLogin.destroy(); web.removeJavascriptInterface("StbuNative"); web.destroy(); worker.shutdown(); courseReader.shutdown(); walletReader.shutdownNow(); super.onDestroy() }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        launchPage = newIntent.getStringExtra("page").orEmpty()
        val page = launchPage
        if (page in setOf("tasks", "week")) web.evaluateJavascript("window.onNotificationRoute && window.onNotificationRoute('$page')") { if (it == "true" && launchPage == page) launchPage = "" }
    }

    private fun scannerReply(id: Int, response: JSONObject) {
        if (::web.isInitialized && !isFinishing && !isDestroyed) web.evaluateJavascript("window.Native && window.Native.resolve($id,$response)", null)
    }

    private fun blePermissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202) {
            HomeworkReminders.check(this)
            web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null)
            return
        }
        if (requestCode != 201) return
        val pending = pendingPermission ?: return
        pendingPermission = null
        if (blePermissions().all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) call(pending.first, pending.second, pending.third)
        else web.evaluateJavascript("window.Native.resolve(${pending.first},{ok:false,error:'未授予蓝牙权限，请在系统设置中允许附近设备或定位权限'})", null)
    }

    @Suppress("DEPRECATION")
    private fun applyAppearance() {
        val dark = appPreferences.read().optBoolean("dark")
        val background = Color.parseColor(if (dark) "#151b18" else "#f7f8f5")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
        window.statusBarColor = background
        window.navigationBarColor = background
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            val light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (dark) window.decorView.systemUiVisibility and light.inv() else window.decorView.systemUiVisibility or light
        }
        if (::root.isInitialized) root.setBackgroundColor(background)
        web.setBackgroundColor(background)
    }

    companion object {
        fun applyInsets(view: android.view.View) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                (view.context as? Activity)?.window?.setDecorFitsSystemWindows(false)
                view.setOnApplyWindowInsetsListener { target, insets ->
                    val types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                    val bars = insets.getInsets(types)
                    // Apply insets to the outer container, never to an editable WebView.
                    if (target.paddingLeft != bars.left || target.paddingTop != bars.top || target.paddingRight != bars.right || target.paddingBottom != bars.bottom)
                        target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    // Keep IME updates flowing to modern WebViews; only zero the system areas handled above.
                    android.view.WindowInsets.Builder(insets).setInsets(types, android.graphics.Insets.NONE).build()
                }
                view.requestApplyInsets()
            }
        }
    }
}

class LocalBridge(private val activity: MainActivity) {
    @JavascriptInterface fun call(id: Int, method: String, payload: String) {
        if (payload.length <= LocalRepository.MAX_BYTES) activity.call(id, method, payload)
    }
}
