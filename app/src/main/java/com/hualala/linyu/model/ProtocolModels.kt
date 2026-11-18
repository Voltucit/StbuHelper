package com.hualala.linyu.model

/** Minimal transport models extracted from LinYu (MIT); Compose-only presentation properties omitted. */
data class DeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val snCode: String,
    val macAddress: String,
    val withholdMoney: Double,
    val onlineStatusId: Int,
    val bigTypeId: Int? = null,
    val smallTypeId: Int? = null,
    val realMac: String? = null
)

data class BaseResponse<T>(val success: Boolean, val data: T?, val errorCode: Int = 0, val errorMessage: String? = null) {
    val displayMessage: String? get() = errorMessage
}
