package cn.stbu.helper.backend.life

import cn.stbu.helper.backend.security.CredentialVault

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import com.google.gson.Gson
import com.hualala.linyu.ble.BleConnectResult
import com.hualala.linyu.ble.BleController
import com.hualala.linyu.data.BleShowerController
import com.hualala.linyu.data.MacFormat
import com.hualala.linyu.model.DeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Direct official HTTPS API + LinYu BLE protocol. No third-party relay and no cached passwords. */
object QuzhiManager {
    private lateinit var context: Context
    private lateinit var vault: CredentialVault
    private lateinit var ble: BleController
    private val mutex = Mutex()
    private val devices = ConcurrentHashMap<String, DeviceInfo>()
    private val deviceDeposits = ConcurrentHashMap<String, String>()
    private const val BASE = "https://v3-api.china-qzxy.cn"
    @Volatile private var session: JSONObject? = null
    private var boundDevices = JSONArray()
    @Volatile private var connectedMac: String? = null
    @Volatile var activeSession: BleShowerController.Session? = null
        private set

    @Synchronized fun initialize(applicationContext: Context) {
        if (::context.isInitialized) return
        context = applicationContext.applicationContext
        vault = CredentialVault(context)
        ble = BleController(context)
        session = try { vault.readJson("quzhi") } catch (_: Exception) { null }
        loadBoundDevices()
        activeSession = session?.optJSONObject("active")?.let { Gson().fromJson(it.toString(), BleShowerController.Session::class.java) }
    }

    fun status(): JSONObject = JSONObject().put("connected", session != null)
        .put("active", activeSession?.let { JSONObject(Gson().toJson(it)).removeSensitive() } ?: JSONObject.NULL)
        .put("devices", JSONArray(boundDevices.toString()))
        .put("receipt", session?.optJSONObject("pendingReceipt")?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
        .put("bluetooth", if (::ble.isInitialized && ble.isReady) JSONObject().put("mac", connectedMac ?: activeSession?.mac.orEmpty()) else JSONObject.NULL)

    private fun bindingsKey() = "quzhi.devices." + (session?.optString("userId") ?: throw IllegalArgumentException("请先连接趣智校园"))
    private fun loadBoundDevices() {
        boundDevices = if (session == null) JSONArray() else vault.readJson(bindingsKey())?.optJSONArray("devices") ?: JSONArray()
    }

    fun bindDevice(mac: String, kind: String): JSONObject {
        require(kind in setOf("shower", "drinking")) { "请选择洗澡或饮水" }
        val detail = device(mac)
        require(detail.optString("kind") != "drinking" || kind == "drinking") { "这是一台饮水设备，请在饮水区域绑定" }
        val address = MacFormat.normalize(mac) ?: throw IllegalArgumentException("设备地址无效")
        val next = JSONArray()
        for (i in 0 until boundDevices.length()) if (MacFormat.normalize(boundDevices.getJSONObject(i).optString("mac")) != address) next.put(boundDevices.getJSONObject(i))
        next.put(JSONObject().put("name", detail.getString("name")).put("mac", mac).put("kind", kind))
        vault.saveJson(bindingsKey(), JSONObject().put("devices", next))
        boundDevices = next
        return status()
    }

    fun unbindDevice(mac: String): JSONObject {
        val address = MacFormat.normalize(mac) ?: throw IllegalArgumentException("设备地址无效")
        require(activeSession?.let { MacFormat.normalize(it.mac) == address } != true) { "请先结束这台设备并完成结算" }
        val next = JSONArray()
        for (i in 0 until boundDevices.length()) if (MacFormat.normalize(boundDevices.getJSONObject(i).optString("mac")) != address) next.put(boundDevices.getJSONObject(i))
        vault.saveJson(bindingsKey(), JSONObject().put("devices", next))
        if (MacFormat.normalize(connectedMac.orEmpty()) == address) { ble.disconnect(); connectedMac = null }
        boundDevices = next
        return status()
    }

    private fun displayFor(mac: String): JSONObject {
        val address = MacFormat.normalize(mac)
        val info = devices.values.firstOrNull { MacFormat.normalize(it.realMac ?: it.macAddress) == address }
        val bound = (0 until boundDevices.length()).map { boundDevices.getJSONObject(it) }.firstOrNull { MacFormat.normalize(it.optString("mac")) == address }
        val kind = bound?.optString("kind").orEmpty().ifBlank { if (info?.bigTypeId == 5) "drinking" else "unknown" }
        return JSONObject().put("name", info?.deviceName ?: bound?.optString("name").orEmpty())
            .put("deviceType", when (kind) { "shower" -> "洗澡"; "drinking" -> "饮水"; else -> "设备类型未提供" })
            .put("preDeductMoney", deviceDeposits[address.orEmpty()] ?: JSONObject.NULL)
    }

    private fun JSONObject.removeSensitive(): JSONObject {
        val display = session?.optJSONObject("activeDisplay") ?: displayFor(optString("mac"))
        return JSONObject().put("mac", optString("mac")).put("snCode", optString("snCode"))
            .put("confirmed", session?.optBoolean("activeConfirmed", false) ?: false)
            .put("deviceName", display.optString("name")).put("deviceType", display.optString("deviceType"))
            .put("preDeductMoney", display.opt("preDeductMoney") ?: JSONObject.NULL)
    }

    private fun recordReceipt(active: BleShowerController.Session, cleared: Boolean) = synchronized(this) {
        val next = JSONObject((session ?: throw IllegalStateException()).toString())
        val id = WaterReceipt.id(active)
        if (next.optString("lastReceiptId") != id) {
            val saved = next.optJSONObject("pendingReceipt")?.takeIf { it.optString("id") == id }
            val receipt = saved?.let { JSONObject(it.toString()) } ?: WaterReceipt.create(active, next.optJSONObject("activeDisplay") ?: displayFor(active.mac), cleared)
            next.put("pendingReceipt", receipt.put("deviceCleared", cleared))
        }
        if (cleared) { next.remove("active"); next.remove("activeConfirmed"); next.remove("activeDisplay") }
        else next.put("activeConfirmed", false)
        vault.saveJson("quzhi", next)
        session = next
    }

    fun receipt(id: String): JSONObject {
        val saved = session?.optJSONObject("pendingReceipt")?.takeIf { it.optString("id") == id }
            ?.let { JSONObject(it.toString()) } ?: throw IllegalArgumentException("账单状态已变化，请重新打开")
        val rows = if (saved.optString("orderNo").isBlank()) null else bills(saved.getString("billMonth")).getJSONArray("bills")
        return synchronized(this) {
            val current = session ?: throw IllegalArgumentException("请先连接趣智校园")
            val latest = current.optJSONObject("pendingReceipt")
            require(latest?.optString("id") == id) { "账单状态已变化，请重新打开" }
            val updated = if (rows == null) JSONObject(latest!!.toString()) else WaterReceipt.enrich(latest!!, rows)
            val next = JSONObject(current.toString()).put("pendingReceipt", updated)
            vault.saveJson("quzhi", next); session = next
            updated
        }
    }

    @Synchronized fun confirmReceipt(id: String): JSONObject {
        val current = session ?: throw IllegalArgumentException("请先连接趣智校园")
        require(current.optJSONObject("pendingReceipt")?.optString("id") == id) { "账单状态已变化，请重新打开" }
        val next = JSONObject(current.toString()).put("lastReceiptId", id).apply { remove("pendingReceipt") }
        vault.saveJson("quzhi", next); session = next
        return status()
    }

    private fun JSONObject.text(vararg keys: String): String = keys.asSequence().filterNot { isNull(it) }.map { optString(it).trim() }.firstOrNull { it.isNotBlank() && !it.equals("null", true) }.orEmpty()

    fun authFields(): Map<String, String> {
        val current = session ?: throw IllegalArgumentException("请先连接趣智校园")
        return mapOf("loginCode" to current.getString("loginCode"), "userId" to current.getString("userId"),
            "accountId" to current.getString("accountId"), "projectId" to current.getString("projectId"),
            "telephone" to current.getString("telephone"), "telPhone" to current.getString("telephone"),
            "phoneSystem" to "android", "version" to "6.5.28")
    }

    fun request(method: String, path: String, fields: Map<String, String>): JSONObject {
        require(path.startsWith("/") && !path.contains("?") && !path.contains("://"))
        val encoded = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val connection = URL(BASE + path + if (method == "GET") "?$encoded" else "").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
            }
            require(connection.responseCode in 200..299) { "趣智校园连接未完成，请检查网络或重新登录" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun checked(response: JSONObject): Any {
        require(response.optBoolean("success") && response.optInt("errorCode", 0) == 0) {
            response.optString("errorMessage", "趣智校园未完成此操作，请核对账号或稍后重试").take(200)
        }
        return response.opt("data") ?: JSONObject.NULL
    }

    fun login(telephone: String, password: String): JSONObject {
        require(activeSession == null) { "请先结束当前设备并完成结算，再更换账号" }
        require(session?.optJSONObject("pendingReceipt") == null) { "请先确认用水账单，再更换账号" }
        require(telephone.matches(Regex("1[0-9]{10}")) && password.isNotEmpty() && password.length <= 1024) { "请检查手机号和密码" }
        val hash = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.takeLast(10).uppercase()
        val body = checked(request("POST", "/user/login", mapOf("telephone" to telephone, "password" to hash, "phoneSystem" to "android", "type" to "0", "version" to "6.5.28"))) as? JSONObject
            ?: throw IllegalArgumentException("登录响应中缺少账号信息")
        val account = body.getJSONObject("userAccount")
        require(body.optString("loginCode").isNotBlank()) { "未取得登录会话，请重试" }
        val next = JSONObject().put("telephone", telephone).put("loginCode", body.getString("loginCode"))
            .put("userId", body.get("userId").toString()).put("accountId", account.get("accountId").toString()).put("projectId", account.get("projectId").toString())
        vault.saveJson("quzhi", next)
        session = next
        loadBoundDevices()
        devices.clear(); deviceDeposits.clear()
        return status()
    }

    fun refresh(): JSONObject {
        val account = authFields()
        val wallet = checked(request("GET", "/account/wallet", account)) as? JSONObject ?: throw IllegalArgumentException("余额暂未返回")
        requireCurrentAccount(account)
        val money = wallet.text("money").toBigDecimalOrNull()
        return JSONObject().put("wallet", JSONObject().put("money", money?.toPlainString() ?: JSONObject.NULL)).put("updatedAt", java.time.Instant.now().toString())
    }

    fun bills(month: String): JSONObject {
        require(month.matches(Regex("[0-9]{4}-(0[1-9]|1[0-2])"))) { "请选择有效月份" }
        val account = authFields()
        val result = checked(request("GET", "/order/query/account/bill/list", account + mapOf("month" to month, "billRequestType" to "2")))
        val rows = when (result) { is JSONArray -> result; is JSONObject -> result.optJSONArray("list") ?: result.optJSONArray("records") ?: throw IllegalArgumentException("账单格式暂未识别"); else -> throw IllegalArgumentException("账单数据暂未返回") }
        val sanitized = JSONArray()
        val seen = mutableSetOf<String>()
        var incomplete = 0
        for (i in 0 until rows.length()) {
            val wrapper = rows.optJSONObject(i) ?: continue
            val row = wrapper.optJSONObject("consumeBillDTO") ?: wrapper
            val money = row.text("consumeMoney").toBigDecimalOrNull()
            val date = row.text("consumeDate", "createTime")
            if (money == null || date.isBlank()) { incomplete++; continue }
            if (money.signum() == 0 && row.has("uploadDate") && row.text("uploadDate").isEmpty()) { incomplete++; continue }
            val description = row.text("description", "deviceName", "bigTypeName").ifBlank { "用水消费" }
            val orderNo = row.text("orderNo")
            val id = row.text("orderId", "orderNo").ifBlank { "$description:$date:$money" }
            if (!seen.add(id)) continue
            sanitized.put(JSONObject().put("name", description).put("date", date).put("money", money.toPlainString()).put("orderNo", orderNo).put("deviceName", row.text("deviceName")).put("deviceType", row.text("bigTypeName", "deviceTypeName")))
        }
        requireCurrentAccount(account)
        return JSONObject().put("bills", sanitized).put("incomplete", incomplete)
    }

    private fun requireCurrentAccount(account: Map<String, String>) {
        val current = session
        require(current != null && current.optString("loginCode") == account["loginCode"] &&
            current.optString("accountId") == account["accountId"]) { "趣智校园账号已变化，请重新查询" }
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(): JSONObject {
        require(session != null) { "请先连接趣智校园" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        require(adapter?.isEnabled == true) { "请先开启手机蓝牙" }
        if (Build.VERSION.SDK_INT < 31) {
            val location = context.getSystemService(LocationManager::class.java)
            val enabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            require(enabled) { "此 Android 版本扫描蓝牙还需要开启系统定位开关" }
        }
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalArgumentException("这台手机暂时无法扫描蓝牙设备")
        val found = ConcurrentHashMap<String, JSONObject>()
        var failed = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val service = result.scanRecord?.serviceUuids?.any { it.uuid == BleController.SERVICE_UUID } == true
                if (!service && !Regex("KLCXKJ|water|热水|饮水|洗手", RegexOption.IGNORE_CASE).containsMatchIn(name)) return
                found[result.device.address] = JSONObject().put("name", name.ifBlank { "趣智校园用水设备" }).put("mac", result.device.address).put("rssi", result.rssi)
            }
            override fun onScanFailed(errorCode: Int) { failed = true }
        }
        try { scanner.startScan(callback); delay(8000) } finally { scanner.stopScan(callback) }
        require(!failed) { "蓝牙扫描未成功，请稍后重试" }
        return JSONObject().put("devices", JSONArray(found.values.sortedByDescending { it.optInt("rssi") }))
    }

    fun device(mac: String): JSONObject {
        require(MacFormat.normalize(mac) != null) { "设备地址无效" }
        val cached = devices[mac] ?: run {
            var raw: JSONObject? = null
            for (candidate in MacFormat.lookupVariants(mac)) {
                val response = checked(request("GET", "/device/info/mac", authFields() + mapOf("macAddress" to candidate)))
                if (response is JSONObject && response.optString("snCode").isNotBlank()) { raw = response; break }
            }
            val row = raw ?: throw IllegalArgumentException("趣智校园没有返回这台设备的信息，请核对水表地址")
            require(row.optInt("deviceId") > 0 && row.optString("macAddress").isNotBlank()) { "设备信息不完整，暂不能控制" }
            val deposit = row.text("withholdMoney").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
            val address = MacFormat.normalize(mac).orEmpty()
            if (deposit == null) deviceDeposits.remove(address) else deviceDeposits[address] = deposit.stripTrailingZeros().toPlainString()
            DeviceInfo(row.getInt("deviceId"), row.optString("deviceName", "用水设备"), row.getString("snCode"), row.getString("macAddress"), row.optDouble("withholdMoney", 0.0), row.optInt("onlineStatusId"), row.optInt("bigTypeId"), row.optInt("smallTypeId"), mac).also { devices[mac] = it }
        }
        val drinking = cached.bigTypeId == 5 || Regex("饮水|直饮|开水机|开水器|冷水").containsMatchIn(cached.deviceName)
        return JSONObject().put("name", cached.deviceName).put("mac", mac).put("withholdMoney", deviceDeposits[MacFormat.normalize(mac).orEmpty()] ?: JSONObject.NULL).put("kind", if (drinking) "drinking" else "unknown")
    }

    @Synchronized fun prepareSession(value: BleShowerController.Session) {
        val next = JSONObject((session ?: throw IllegalStateException()).toString()).put("active", JSONObject(Gson().toJson(value))).put("activeConfirmed", false).put("activeDisplay", displayFor(value.mac))
        vault.saveJson("quzhi", next) // Persist before the first valve command, including unconfirmed outcomes.
        session = next
        activeSession = value
        context.startForegroundService(Intent(context, WaterService::class.java))
    }

    suspend fun start(mac: String): JSONObject = mutex.withLock {
        val existing = activeSession
        if (existing != null) return@withLock JSONObject().put("message", "本机有未完成的用水记录，请先结束并结算，勿重复开启")
        require(session?.optJSONObject("pendingReceipt") == null) { "请先确认上一笔用水账单" }
        device(mac)
        val device = devices.getValue(mac)
        val auth = authFields()
        val outcome = BleShowerController.openValve(ble, device, auth.getValue("loginCode"), auth.getValue("telephone"))
        val confirmed = outcome is BleShowerController.OpenOutcome.Opened || outcome is BleShowerController.OpenOutcome.Resumed
        if (confirmed && activeSession != null) {
            val next = JSONObject(session!!.toString()).put("activeConfirmed", true)
            vault.saveJson("quzhi", next); session = next
        }
        connectedMac = if (ble.isReady) activeSession?.mac else null
        val message = when (outcome) {
            is BleShowerController.OpenOutcome.Opened -> "设备已确认开启。结束使用时请点击“结束并结算”。"
            is BleShowerController.OpenOutcome.Resumed -> "已恢复本机正在使用的设备"
            is BleShowerController.OpenOutcome.Recovered -> outcome.message
            is BleShowerController.OpenOutcome.Unconfirmed -> outcome.message
            is BleShowerController.OpenOutcome.NotBleDevice -> outcome.message
            is BleShowerController.OpenOutcome.Failed -> outcome.message
        }
        if (activeSession == null) ble.disconnect()
        JSONObject().put("message", message).put("active", activeSession != null).put("started", confirmed).put("life", status())
    }

    suspend fun connect(mac: String): JSONObject = mutex.withLock {
        require(session != null) { "请先连接趣智校园" }
        require(MacFormat.normalize(mac) != null) { "设备地址无效" }
        val active = activeSession
        require(active == null || MacFormat.normalize(active.mac) == MacFormat.normalize(mac)) { "请先结束正在使用的设备并完成结算" }
        device(mac)
        if (!ble.isReady || MacFormat.normalize(connectedMac.orEmpty()) != MacFormat.normalize(mac)) {
            connectedMac = null
            when (val result = ble.connect(mac)) {
                is BleConnectResult.Ready -> connectedMac = mac
                is BleConnectResult.NoService -> throw IllegalArgumentException("这台机器没有趣智校园蓝牙水控服务")
                is BleConnectResult.Failed -> throw IllegalArgumentException(result.reason)
            }
        }
        JSONObject().put("message", "蓝牙连接成功").put("life", status())
    }

    suspend fun disconnectAndEnd(mac: String? = null): JSONObject = mutex.withLock {
        if (activeSession != null) stopLocked(mac) else {
            require(mac == null || connectedMac == null || MacFormat.normalize(mac) == MacFormat.normalize(connectedMac.orEmpty())) { "当前连接的是另一台设备" }
            ble.disconnect(); connectedMac = null
            JSONObject().put("message", "已断开蓝牙连接").put("settled", false).put("life", status())
        }
    }

    suspend fun stop(mac: String? = null): JSONObject = mutex.withLock { stopLocked(mac) }
    private suspend fun stopLocked(mac: String?): JSONObject {
        val active = activeSession ?: throw IllegalArgumentException("本机没有需要结束的订单。若官方 App 正在用水，请在官方 App 中结束。")
        require(mac == null || MacFormat.normalize(mac) == MacFormat.normalize(active.mac)) { "当前用水设备与所选设备不同，请先结束当前设备" }
        if (!ble.isReady) require(ble.connect(active.mac) is BleConnectResult.Ready) { "未能连接正在使用的水表，请靠近后重试" }
        connectedMac = active.mac
        val auth = authFields()
        val result = BleShowerController.closeAndSettle(ble, active, auth.getValue("loginCode"), auth.getValue("telephone"))
        val message = when (result) {
            is BleShowerController.SettleOutcome.Uploaded -> {
                recordReceipt(active, result.recordCleared)
                if (result.recordCleared) {
                    activeSession = null; ble.disconnect(); connectedMac = null
                    context.stopService(Intent(context, WaterService::class.java))
                    "已结束用水并完成结算，消费金额请到账单中查看。"
                } else "服务端已记账，设备清理尚未确认。请靠近后再次结束并结算，勿重复开启。"
            }
            is BleShowerController.SettleOutcome.PartialSettlement -> result.message
            is BleShowerController.SettleOutcome.CloseFailed -> result.message
        }
        val month = WaterReceipt.month(active.consumeDate)
        return JSONObject().put("message", message).put("active", activeSession != null).put("settled", result is BleShowerController.SettleOutcome.Uploaded && result.recordCleared).put("orderNo", active.orderNo).put("billMonth", month).put("life", status())
    }

    fun logout(): JSONObject {
        require(activeSession == null) { "当前仍有未完成的用水记录，请先结束并结算" }
        require(session?.optJSONObject("pendingReceipt") == null) { "请先确认用水账单" }
        vault.remove("quzhi"); session = null; boundDevices = JSONArray(); devices.clear(); deviceDeposits.clear(); ble.disconnect(); connectedMac = null
        return status()
    }
}
