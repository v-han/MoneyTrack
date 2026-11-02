package com.moneytrack.app.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.moneytrack.app.MoneyTrackApplication

/** 只使用公开系统接口检查授权和申请重连，不修改授权设置。 */
object NotificationConnection {
    private var lastRequestAt = -2_000L

    /** Android 8.0 使用公开的授权组件列表；8.1+ 使用专用查询接口。 */
    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        return if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(component) == true
        }
    }

    /** 返回是否提交请求；系统决定何时绑定，不能把请求成功当作已连接。主线程调用。 */
    fun requestReconnect(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!hasAccess(context) || now - lastRequestAt < 2_000) return false
        lastRequestAt = now
        return runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, PaymentNotificationListener::class.java))
        }.isSuccess
    }

    /** 用户手动修复连接时才重置绑定，不在每次页面刷新时打断正在工作的监听。 */
    fun resetFromSettings(context: Context): Boolean {
        if (!hasAccess(context)) return false
        val application = context.applicationContext as MoneyTrackApplication
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequestAt < 2_000) return false
        if (Build.VERSION.SDK_INT >= 34) {
            val unbound = runCatching {
                NotificationListenerService.requestUnbind(ComponentName(context, PaymentNotificationListener::class.java))
            }.isSuccess
            if (unbound) {
                lastRequestAt = now
                application.setListenerConnected(false)
                application.mainHandler.postDelayed({ requestReconnect(application) }, 2_100)
                return true
            }
        }
        return requestReconnect(context)
    }

}
