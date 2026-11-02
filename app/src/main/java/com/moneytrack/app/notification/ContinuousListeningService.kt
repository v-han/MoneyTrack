package com.moneytrack.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.moneytrack.app.MoneyTrackApplication
import com.moneytrack.app.R
import com.moneytrack.app.ui.MainActivity

/** 用户开启的可见后台监听服务；不直接解析通知，也不主动启动系统绑定的通知入口。 */
class ContinuousListeningService : Service() {
    private val app get() = application as MoneyTrackApplication
    private var lastConnected: Boolean? = null
    private val onChanged: () -> Unit = {
        if (app.backgroundListening && lastConnected != app.listenerConnected) {
            if (!NotificationConnection.hasAccess(this)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        app.changes.add(onChanged)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ListeningPreferences.setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) ListeningPreferences.setEnabled(this, true)
        if (!ListeningPreferences.isEnabled(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            updateNotification()
            app.setBackgroundListening(true)
            if (!NotificationConnection.hasAccess(this)) {
                // 授权已撤销，停止实际运行，但保留用户选择供设置页解释和手动恢复。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            if (!app.listenerConnected) NotificationConnection.requestReconnect(this)
        } catch (_: RuntimeException) {
            app.reportError("持续监听未启动，请检查通知权限和后台限制；开启选项已保留")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.foreground_channel), NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ContinuousListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_listener)
            .setContentTitle(getString(R.string.foreground_title))
            .setContentText(getString(if (app.listenerConnected) R.string.foreground_connected else R.string.foreground_disconnected))
            .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.foreground_stop), stop).build()).build()
        ServiceCompat.startForeground(this, 1001, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        lastConnected = app.listenerConnected
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉页面不清除用户选择；系统是否允许继续运行仍取决于厂商后台管理。
        if (!app.listenerConnected) NotificationConnection.requestReconnect(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        app.changes.remove(onChanged)
        app.setBackgroundListening(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.moneytrack.app.START_CONTINUOUS_LISTENING"
        const val ACTION_STOP = "com.moneytrack.app.STOP_CONTINUOUS_LISTENING"
        const val CHANNEL = "continuous_listening"
    }
}
