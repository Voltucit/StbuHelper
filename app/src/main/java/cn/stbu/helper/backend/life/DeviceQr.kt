package cn.stbu.helper.backend.life

import com.hualala.linyu.data.MacFormat

/** LinYu's documented KLCXKJ-Water,M,MAC device code; no scanned URL is opened or requested. */
object DeviceQr {
    fun address(raw: String): String {
        require(raw.length <= 4096) { "二维码内容过长，请扫描设备上的趣智校园二维码" }
        val text = raw.trim()
        val value = if (text.contains("KLCXKJ-Water", ignoreCase = true)) text.substringAfterLast(',').trim() else text
        require(Regex("(?:[0-9a-fA-F]{12}|[0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})").matches(value)) { "未识别到趣智校园设备地址，可改用附近设备绑定" }
        val normalized = MacFormat.normalize(value) ?: throw IllegalArgumentException("设备地址无效")
        return MacFormat.connectAddressFor(normalized)
    }
}
