package cn.stbu.helper.ui.life

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** Launch a fixed Alipay mini-program route; no session, phone number or payment order is embedded. */
object RechargeLauncher {
    fun open(activity: Activity) {
        val target = Uri.Builder().scheme("alipays").authority("platformapi").appendPath("startapp")
            .appendQueryParameter("appId", "2018090661238647")
            .appendQueryParameter("page", "pages/recharge/recharge").build()
        activity.startActivity(Intent(Intent.ACTION_VIEW, target).setPackage("com.eg.android.AlipayGphone"))
    }
}
