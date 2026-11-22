package cn.stbu.helper.backend.life

import com.hualala.linyu.data.BleShowerController
import org.json.JSONObject
import java.security.MessageDigest

/** Local record for acknowledging a settlement and deduplicating it after a retry. */
object WaterReceipt {
    fun id(active: BleShowerController.Session): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(active.mac, active.consumeDate, active.randomNumber, active.orderNo).joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun create(active: BleShowerController.Session, display: JSONObject, cleared: Boolean): JSONObject = JSONObject()
        .put("id", id(active))
        .put("deviceName", display.optString("name")).put("deviceType", display.optString("deviceType"))
        .put("deviceCleared", cleared)
}
