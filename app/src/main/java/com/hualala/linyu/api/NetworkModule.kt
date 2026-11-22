package com.hualala.linyu.api

import cn.stbu.helper.backend.life.QuzhiManager
import com.google.gson.Gson
import com.hualala.linyu.data.BleShowerController
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.BleDownRateData
import com.hualala.linyu.model.BleUploadData
import org.json.JSONObject
import org.json.JSONException

/** Small adapter for the upstream BLE orchestration. No developer proxy or HTTP logging. */
object NetworkModule {
    val apiService = QzApiService()
    fun authFields(): Map<String, String> = QuzhiManager.authFields()
    fun beginRateRequest() = QuzhiManager.beginRateRequest()
    fun prepareSession(session: BleShowerController.Session) = QuzhiManager.prepareSession(session)
    fun markValveWriteStarted(session: BleShowerController.Session) = QuzhiManager.markValveWriteStarted(session)
    suspend fun resolveUnsentOrder(session: BleShowerController.Session) = QuzhiManager.resolveUnsentOrder(session)
}

class QzApiService {
    fun <T> post(path: String, fields: Map<String, String>, type: Class<T>, requireStatus: Boolean = false): BaseResponse<T> {
        val json = QuzhiManager.request("POST", path, fields)
        if (requireStatus && json.opt("success") !is Boolean) throw JSONException("计费响应缺少明确结果")
        val value = json.opt("data")?.takeIf { it != JSONObject.NULL }
        return BaseResponse(json.optBoolean("success"), if (value == null) null else Gson().fromJson(value.toString(), type), json.optInt("errorCode"), json.optString("errorMessage", "趣智校园未完成此操作"))
    }
}

suspend fun QzApiService.bluetoothRateOrderSafe(deviceId: String, macAddress: String, macType: String, bigTypeId: String, smallTypeId: String, protocolType: String, randomNumber: String, xfModel: String, signature: String, auth: Map<String, String>): BaseResponse<BleDownRateData> =
    post("/order/downRate/bluetooth/rateOrder", auth + mapOf("deviceId" to deviceId, "macAddress" to macAddress, "macType" to macType, "bigTypeId" to bigTypeId, "smallTypeId" to smallTypeId, "protocolType" to protocolType, "randomNumber" to randomNumber, "xfModel" to xfModel, "signature" to signature), BleDownRateData::class.java, requireStatus = true)

suspend fun QzApiService.bluetoothUploadDataSafe(protocolType: String, randomNumber: String, xfData: String, signature: String, auth: Map<String, String>): BaseResponse<BleUploadData> =
    post("/order/upload/bluetooth/data", auth + mapOf("protocolType" to protocolType, "randomNumber" to randomNumber, "xfData" to xfData, "signature" to signature), BleUploadData::class.java)

suspend fun QzApiService.bluetoothFailOrderSafe(consumeDate: String, signature: String, auth: Map<String, String>): BaseResponse<Any> =
    post("/order/upload/bluetooth/fail", auth + mapOf("consumeDate" to consumeDate, "signature" to signature), Any::class.java)
