package cn.stbu.helper.backend.auth

import java.net.URI

/** Navigation and data-access policies are separate: OAuth pages never receive app data access. */
object PortalPolicy {
    const val LEARNING_HOME = "https://i.chaoxing.com/base?ws=1"
    const val SCHOOL_HOME = "https://jwxt.stbu.edu.cn/xtgl/index_initMenu.html?jsdm=xs&echarts=1"
    enum class Action { INTERNAL, UPGRADE, APP, BLOCK }
    private val oauthHosts = setOf("open.weixin.qq.com", "long.open.weixin.qq.com", "lp.open.weixin.qq.com", "mp.weixin.qq.com", "graph.qq.com", "connect.qq.com", "xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com", "ptlogin2.qq.com", "ui.ptlogin2.qq.com")
    private val appSchemes = setOf("weixin", "mqq", "mqqapi", "mqqopensdkapi", "wtloginmqq", "intent")
    fun isDataHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "chaoxing.com" || h.endsWith(".chaoxing.com") || h == "stbu.edu.cn" || h.endsWith(".stbu.edu.cn")
    }
    fun classify(url: String): Action = try {
        val uri = URI(url)
        when {
            uri.scheme?.lowercase() in appSchemes -> Action.APP
            uri.userInfo != null || uri.port !in listOf(-1, 80, 443) -> Action.BLOCK
            !isDataHost(uri.host) && uri.host?.lowercase() !in oauthHosts -> Action.BLOCK
            uri.scheme.equals("http", true) && uri.port != 443 -> Action.UPGRADE
            uri.scheme.equals("https", true) && uri.port != 80 -> Action.INTERNAL
            else -> Action.BLOCK
        }
    } catch (_: Exception) { Action.BLOCK }
    fun https(url: String): String {
        return url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^https://([^/?#:]+):80(?=/|\\?|#|$)", RegexOption.IGNORE_CASE), "https://$1")
    }
    fun dataUrl(url: String): Boolean = try { val uri = URI(url); uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null && isDataHost(uri.host) } catch (_: Exception) { false }
}
