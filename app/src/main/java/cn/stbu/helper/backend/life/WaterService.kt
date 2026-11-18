package cn.stbu.helper.backend.life

import cn.stbu.helper.R
import cn.stbu.helper.ui.MainActivity

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

/** Keeps the ongoing-water action reachable when the WebView is backgrounded. */
class WaterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        QuzhiManager.initialize(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("water", "用水状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (QuzhiManager.activeSession == null) { stopSelf(); return START_NOT_STICKY }
        show("本机有用水记录，结束时请靠近水表并结算")
        if (intent?.action == "STOP_WATER") scope.launch {
            try { val result = QuzhiManager.stop(); if (QuzhiManager.activeSession != null) show(result.optString("message")) }
            catch (_: Exception) { show("未确认结束，请打开应用、靠近水表后重试") }
        }
        return START_STICKY
    }
    private fun show(message: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WaterService::class.java).setAction("STOP_WATER"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "water").setSmallIcon(cn.stbu.helper.R.drawable.ic_launcher).setContentTitle("趣智校园用水")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "结束并结算", stop).build()).build()
        startForeground(21, notification)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
