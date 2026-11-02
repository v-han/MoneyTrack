package com.moneytrack.app.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputLayout

/** 键盘改变可见区域后，把正在编辑的字段滚进来；不替换字段原有的焦点/失焦业务监听。 */
class FocusedInputScroller(private val root: View) {
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        if (focused is EditText) requestScroll()
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestScroll() }
    private val reveal = Runnable { revealFocusedInput() }

    init {
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        root.addOnLayoutChangeListener(layoutListener)
    }

    /** 合并焦点、布局和 IME inset 回调；等待 padding 和子视图位置完成更新后再计算。 */
    fun requestScroll() {
        root.removeCallbacks(reveal)
        root.post(reveal)
    }

    /** 再次点击同一输入框不会触发焦点变化，也要重新检查光标所在行。 */
    fun onTouchUp(x: Int, y: Int) {
        val focused = root.findFocus() as? EditText ?: return
        val bounds = Rect()
        if (focused.getGlobalVisibleRect(bounds) && bounds.contains(x, y)) requestScroll()
    }

    private fun revealFocusedInput() {
        if (!root.isAttachedToWindow || ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) != true) return
        val focused = root.findFocus() as? EditText ?: return
        // 下拉菜单不需要键盘，保留它自己定位弹层的行为。
        if (!focused.isShown || focused is AutoCompleteTextView) return
        var parent = focused.parent
        while (parent != null && parent !is ScrollView) parent = parent.parent
        val scroll = parent ?: return
        val viewport = Rect()
        if (!scroll.getGlobalVisibleRect(viewport)) return
        val location = IntArray(2)
        scroll.getLocationOnScreen(location)
        val gap = (16 * root.resources.displayMetrics.density).toInt()
        val top = maxOf(viewport.top, location[1] + scroll.paddingTop) + gap
        val bottom = minOf(viewport.bottom, location[1] + scroll.height - scroll.paddingBottom) - gap
        if (bottom <= top) return

        // 普通字段连同浮动标签一起保留；备注等长字段超出可视高度时只保证当前光标行可见。
        var field: View = focused
        var ancestor = focused.parent
        while (ancestor is View && ancestor !== scroll) {
            if (ancestor is TextInputLayout) { field = ancestor; break }
            ancestor = ancestor.parent
        }
        val target = Rect()
        if (field.height <= bottom - top) {
            field.getLocationOnScreen(location)
            target.set(location[0], location[1], location[0] + field.width, location[1] + field.height)
        } else {
            focused.getFocusedRect(target)
            focused.getLocationOnScreen(location)
            target.offset(location[0], location[1])
        }
        val distance = when {
            target.bottom > bottom -> target.bottom - bottom
            target.top < top -> target.top - top
            else -> 0
        }
        if (distance != 0) scroll.smoothScrollBy(0, distance)
    }

    /** 页面销毁时移除回调，避免尚未执行的滚动操作引用旧页面。 */
    fun close() {
        root.removeCallbacks(reveal)
        root.removeOnLayoutChangeListener(layoutListener)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
    }
}
