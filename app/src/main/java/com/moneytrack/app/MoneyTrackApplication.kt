package com.moneytrack.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.moneytrack.app.data.LedgerDatabase
import java.util.concurrent.Executors

/** Application 的生命周期比页面长；数据库任务不会因为页面旋转而被中途取消。 */
class MoneyTrackApplication : Application() {
    val database by lazy { LedgerDatabase(this) }
    // 所有数据库读写排队执行：既不阻塞界面，也让“保存规则后接收通知”的顺序明确。
    val io = Executors.newSingleThreadExecutor()
    val mainHandler = Handler(Looper.getMainLooper())
    val changes = mutableSetOf<() -> Unit>()
    var listenerConnected: Boolean = false
        private set
    var backgroundListening: Boolean = false
        private set
    var lastNotificationAt: Long = 0
        private set
    var lastNotificationResult: String? = null
        private set
    var lastError: String? = null
        private set

    fun publishChange() { mainHandler.post { changes.toList().forEach { it() } } }

    fun setListenerConnected(connected: Boolean) {
        listenerConnected = connected
        publishChange()
    }

    /** 这是服务实际进入前台的状态，与通知监听是否连接分别展示。 */
    fun setBackgroundListening(active: Boolean) {
        backgroundListening = active
        publishChange()
    }

    /** 诊断只保留本次进程的处理结果和接收时间，不保留通知正文或标题。 */
    fun recordNotificationResult(result: String) {
        mainHandler.post {
            lastNotificationAt = System.currentTimeMillis()
            lastNotificationResult = result
            lastError = null
            changes.toList().forEach { it() }
        }
    }

    /** 不记录异常里的通知正文；只给用户展示不包含支付隐私的错误提示。 */
    fun reportError(message: String) {
        mainHandler.post {
            lastError = message
            changes.toList().forEach { it() }
        }
    }
}
