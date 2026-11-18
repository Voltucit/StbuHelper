package com.hualala.linyu.data

import cn.stbu.helper.backend.life.QuzhiManager
import com.hualala.linyu.model.DeviceInfo

/** Compatibility adapter for LinYu's active-order checks. State is encrypted by the host app. */
object ShowerController {
    data class Order(val orderNo: String)
    fun isRunning(snCode: String) = QuzhiManager.activeSession?.snCode == snCode
    fun activeOrderFor(snCode: String): Order? = QuzhiManager.activeSession?.takeIf { it.snCode == snCode }?.let { Order(it.orderNo) }
    @Suppress("UNUSED_PARAMETER") fun ensureActiveOrder(snCode: String, orderNo: String, device: DeviceInfo) = Unit
}
