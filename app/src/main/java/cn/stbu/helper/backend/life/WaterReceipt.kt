package cn.stbu.helper.backend.life

import com.hualala.linyu.data.BleShowerController
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation metadata; monetary values come from official device configuration or matched bills. */
object WaterReceipt {
    fun id(active: BleShowerController.Session): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(active.mac, active.consumeDate, active.randomNumber, active.orderNo).joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun month(consumeDate: String): String {
        val digits = consumeDate.filter { it.isDigit() }
        val zone = ZoneId.of("Asia/Shanghai")
        return runCatching {
            when {
                digits.length == 13 -> Instant.ofEpochMilli(digits.toLong()).atZone(zone).toLocalDate().toString().take(7)
                digits.length == 10 -> Instant.ofEpochSecond(digits.toLong()).atZone(zone).toLocalDate().toString().take(7)
                digits.length >= 8 -> LocalDate.parse(digits.take(8), DateTimeFormatter.BASIC_ISO_DATE).toString().take(7)
                else -> throw IllegalArgumentException()
            }
        }.getOrElse { LocalDate.now(zone).toString().take(7) }
    }

    fun create(active: BleShowerController.Session, display: JSONObject, cleared: Boolean): JSONObject = JSONObject()
        .put("id", id(active)).put("orderNo", active.orderNo).put("billMonth", month(active.consumeDate))
        .put("deviceName", display.optString("name")).put("deviceType", display.optString("deviceType"))
        .put("preDeductMoney", display.opt("preDeductMoney") ?: JSONObject.NULL)
        .put("money", JSONObject.NULL).put("consumeTime", "").put("deviceCleared", cleared)

    fun enrich(receipt: JSONObject, rows: JSONArray): JSONObject {
        val result = JSONObject(receipt.toString())
        val order = receipt.optString("orderNo")
        if (order.isBlank()) return result // Never guess a bill by amount, list position or device name.
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("orderNo") != order) continue
            result.put("money", row.getString("money")).put("consumeTime", row.optString("date"))
            row.optString("deviceName").takeIf { it.isNotBlank() }?.let { result.put("deviceName", it) }
            row.optString("deviceType").takeIf { it.isNotBlank() }?.let { result.put("deviceType", it) }
            break
        }
        return result
    }
}
