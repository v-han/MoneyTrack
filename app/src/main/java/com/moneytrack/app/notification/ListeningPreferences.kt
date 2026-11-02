package com.moneytrack.app.notification

import android.content.Context
import androidx.core.content.edit

/** 用户的持续监听选择与服务实际存活分开，系统回收不能被显示成用户主动关闭。 */
object ListeningPreferences {
    fun isEnabled(context: Context): Boolean = context.getSharedPreferences("listener_mode", Context.MODE_PRIVATE).getBoolean("enabled", false)

    /** 只在用户开启、关闭时修改；与旧版本使用同一偏好文件，保留原选择。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("listener_mode", Context.MODE_PRIVATE).edit { putBoolean("enabled", enabled) }
    }
}
