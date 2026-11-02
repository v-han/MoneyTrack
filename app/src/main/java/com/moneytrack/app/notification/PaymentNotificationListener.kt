package com.moneytrack.app.notification

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.moneytrack.app.MoneyTrackApplication
import com.moneytrack.core.ExpenseParser
import com.moneytrack.core.NotificationIdentity
import com.moneytrack.app.data.NotificationSaveResult

/** 只作为系统绑定的通知入口，不再混用主动启动/停止或前台服务状态。 */
class PaymentNotificationListener : NotificationListenerService() {
    private val app get() = application as MoneyTrackApplication
    private val parser = ExpenseParser()
    private val reconnectTasks = mutableListOf<Runnable>()

    override fun onListenerConnected() {
        cancelReconnects()
        app.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        cancelReconnects()
        app.setListenerConnected(false)
        if (!NotificationConnection.hasAccess(this)) return
        listOf(2_000L, 10_000L, 30_000L).forEach { delay ->
            val task = Runnable { if (!app.listenerConnected) NotificationConnection.requestReconnect(this) }
            reconnectTasks.add(task)
            app.mainHandler.postDelayed(task, delay)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 升级前旧版本可能留下 started 状态。只结束旧启动状态，系统绑定不受影响。
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun cancelReconnects() {
        reconnectTasks.forEach(app.mainHandler::removeCallbacks)
        reconnectTasks.clear()
    }

    override fun onDestroy() {
        cancelReconnects()
        app.setListenerConnected(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // 分组摘要可能重复列出子通知中的交易，因此只处理单条通知。
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        app.io.execute {
            try {
                // 先查包名白名单，再取正文；未启用的应用不进入解析/保存流程。
                val rule = app.database.rule(sbn.packageName)
                if (rule == null || !rule.enabled) {
                    app.recordNotificationResult("系统已投递通知，来源尚未配置或规则未启用")
                    return@execute
                }
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                // 展开文本通常包含普通文本；只取一个正文来源，避免把同笔金额拼接两次。
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()
                val text = listOf(title, body).filter(String::isNotBlank).distinct().joinToString("\n")
                val parsed = parser.parse(sbn.packageName, text, rule)
                if (parsed == null) {
                    app.recordNotificationResult("${rule.displayName}：已收到，但未匹配支出；请在规则页测试关键词、排除词和金额格式")
                    return@execute
                }
                // when 在部分应用中为 0，这时使用系统投递时间。它不等同于银行交易时间。
                val eventTime = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
                val fingerprint = NotificationIdentity.fingerprint(sbn.packageName, sbn.key, eventTime, parsed.amountFen)
                val result = app.database.recordNotification(fingerprint, sbn.packageName, rule.displayName, parsed.amountFen, sbn.postTime)
                app.recordNotificationResult(when (result) {
                    NotificationSaveResult.CREATED -> "${rule.displayName}：已生成待确认支出"
                    NotificationSaveResult.LINKED -> "${rule.displayName}：已联动为同一笔待确认支出"
                    NotificationSaveResult.DUPLICATE -> "${rule.displayName}：重复事件，已跳过"
                })
            } catch (_: Exception) {
                app.reportError("最近一次通知处理失败，请检查存储空间或重启应用；该通知可能未记录。")
            }
        }
    }
}
