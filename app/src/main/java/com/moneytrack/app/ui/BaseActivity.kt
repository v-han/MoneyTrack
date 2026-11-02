package com.moneytrack.app.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moneytrack.app.MoneyTrackApplication
import java.math.BigDecimal

/** 共用线程与窗口处理；AppCompatActivity 负责加载 Material 主题和控件。 */
abstract class BaseActivity : AppCompatActivity() {
    private var inputScroller: FocusedInputScroller? = null
    protected val app get() = application as MoneyTrackApplication

    protected fun <T> databaseTask(work: () -> T, done: (T) -> Unit) {
        app.io.execute {
            val result = runCatching(work)
            app.mainHandler.post {
                // Activity 可能在后台任务结束前已经关闭/旋转，不能更新旧页面。
                if (isFinishing || isDestroyed) return@post
                result.fold(done) { toast("操作失败，请检查存储空间后重试") }
            }
        }
    }

    protected fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    protected fun fitSystemBars(view: View) {
        inputScroller?.close()
        inputScroller = FocusedInputScroller(view)
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        // Android 15+ 强制 edge-to-edge；把状态栏、导航栏和键盘高度加到内容边距。
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
            }
            inputScroller?.requestScroll()
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else {
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        view.requestApplyInsets()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) inputScroller?.onTouchUp(event.rawX.toInt(), event.rawY.toInt())
        return handled
    }

    override fun onDestroy() {
        inputScroller?.close()
        super.onDestroy()
    }

    protected fun money(fen: Long): String = "¥" + BigDecimal.valueOf(fen, 2).toPlainString()
}
