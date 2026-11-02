package com.moneytrack.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.ClipData
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.data.ExpenseTag
import com.moneytrack.app.databinding.ActivityTagManagerBinding
import com.moneytrack.app.databinding.DialogTagNameBinding
import com.moneytrack.app.databinding.ItemTagBinding

/** 自定义标签管理；更名影响关联账单的显示，删除后由快照保留已有账单上的标签。 */
class TagManagerActivity : BaseActivity() {
    private lateinit var binding: ActivityTagManagerBinding
    private var draggingRow: View? = null
    private var originalOrder = emptyList<Long>()
    private var dropCommitted = false
    private var savingOrder = false
    private var pointerScreenY = 0f
    // 手指停在边缘时仍需继续滚动；拖放结束或页面销毁时移除回调，避免后台持有页面。
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (draggingRow == null) return
            val location = IntArray(2)
            binding.root.getLocationOnScreen(location)
            val step = when {
                pointerScreenY < location[1] + binding.root.paddingTop + dp(64) -> -dp(12)
                pointerScreenY > location[1] + binding.root.height - binding.root.paddingBottom - dp(64) -> dp(12)
                else -> 0
            }
            binding.root.scrollBy(0, step)
            moveToPointer()
            binding.root.postDelayed(this, 32)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTagManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.tags.setOnDragListener { view, event -> handleDrag(view, event) }
        binding.addButton.setOnClickListener {
            val name = binding.newName.text.toString().trim()
            if (!validName(name)) return@setOnClickListener
            databaseTask({ app.database.saveTag(null, name) }) { saved ->
                if (saved) { binding.newName.text?.clear(); app.publishChange(); refresh() }
                else binding.nameLayout.error = getString(R.string.tag_duplicate)
            }
        }
        refresh()
    }

    private fun validName(name: String): Boolean {
        binding.nameLayout.error = if (name.isBlank() || name.length > 20) getString(R.string.tag_name_error) else null
        return binding.nameLayout.error == null
    }

    private fun refresh() {
        databaseTask({ app.database.tags() }) { tags ->
            if (draggingRow != null || savingOrder) return@databaseTask
            binding.tags.removeAllViews()
            binding.count.text = getString(R.string.tag_count, tags.size)
            tags.forEach { tag ->
                val row = ItemTagBinding.inflate(layoutInflater, binding.tags, false)
                row.name.text = tag.name
                setupDrag(row, tag)
                row.renameButton.setOnClickListener { rename(tag) }
                row.deleteButton.setOnClickListener {
                    MaterialAlertDialogBuilder(this).setTitle(getString(R.string.delete_tag_title, tag.name))
                        .setMessage(R.string.delete_tag_hint).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete_tag) { _, _ ->
                            databaseTask({ app.database.deleteTag(tag.id) }) { app.publishChange(); refresh() }
                        }.show()
                }
                binding.tags.addView(row.root)
            }
        }
    }

    /** 手柄按下即拖放，读屏通过独立的上移/下移操作排序；performClick 保留点击提示。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(row: ItemTagBinding, tag: ExpenseTag) {
        row.root.tag = tag.id
        row.dragHandle.contentDescription = getString(R.string.drag_tag_description, tag.name)
        row.dragHandle.setOnClickListener { toast(getString(R.string.drag_tag_hint)) }
        row.dragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startDrag(row.root)
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> false
            }
        }
        row.root.setOnLongClickListener { startDrag(row.root) }
        // 拖放不依赖读屏手势；读屏用户可直接执行上移/下移操作。
        ViewCompat.addAccessibilityAction(row.dragHandle, getString(R.string.move_tag_up)) { _, _ -> moveOne(row.root, -1) }
        ViewCompat.addAccessibilityAction(row.dragHandle, getString(R.string.move_tag_down)) { _, _ -> moveOne(row.root, 1) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun orderedIds(): List<Long> = (0 until binding.tags.childCount).map { binding.tags.getChildAt(it).tag as Long }

    private fun startDrag(row: View): Boolean {
        if (draggingRow != null || savingOrder) return false
        originalOrder = orderedIds()
        dropCommitted = false
        draggingRow = row
        // 数据只在本页面的 localState 传递，标签名称不放进可向其他应用传输的拖放内容。
        val started = row.startDragAndDrop(ClipData.newPlainText("MoneyTrack tag order", ""),
            View.DragShadowBuilder(row), row, 0)
        if (started) {
            row.alpha = 0.35f
            binding.addButton.isEnabled = false
        } else draggingRow = null
        return started
    }

    private fun handleDrag(view: View, event: DragEvent): Boolean {
        val row = draggingRow ?: return false
        if (event.localState !== row) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                pointerScreenY = location[1] + event.y
                moveToPointer()
                binding.root.removeCallbacks(edgeScroll)
                binding.root.postDelayed(edgeScroll, 32)
            }
            DragEvent.ACTION_DRAG_EXITED -> binding.root.removeCallbacks(edgeScroll)
            DragEvent.ACTION_DROP -> {
                dropCommitted = true
                saveOrder()
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                binding.root.removeCallbacks(edgeScroll)
                row.alpha = 1f
                draggingRow = null
                binding.addButton.isEnabled = !savingOrder
                if (!dropCommitted) {
                    val rows = (0 until binding.tags.childCount).map { binding.tags.getChildAt(it) }.associateBy { it.tag as Long }
                    binding.tags.removeAllViews()
                    originalOrder.forEach { id -> rows[id]?.let(binding.tags::addView) }
                } else if (!savingOrder) refresh()
            }
        }
        return true
    }

    private fun moveToPointer() {
        val row = draggingRow ?: return
        val location = IntArray(2)
        binding.tags.getLocationOnScreen(location)
        val listY = pointerScreenY - location[1]
        val target = (0 until binding.tags.childCount).minByOrNull { index ->
            val child = binding.tags.getChildAt(index)
            kotlin.math.abs(listY - (child.top + child.bottom) / 2f)
        } ?: return
        if (target != binding.tags.indexOfChild(row)) {
            binding.tags.removeView(row)
            binding.tags.addView(row, target)
        }
    }

    private fun moveOne(row: View, direction: Int): Boolean {
        if (draggingRow != null || savingOrder) return false
        val target = binding.tags.indexOfChild(row) + direction
        if (target !in 0 until binding.tags.childCount) return false
        binding.tags.removeView(row)
        binding.tags.addView(row, target)
        saveOrder()
        return true
    }

    private fun saveOrder() {
        val ids = orderedIds()
        savingOrder = true
        binding.addButton.isEnabled = false
        val application = app
        databaseTask({ runCatching {
            application.database.reorderTags(ids).also { if (it) application.publishChange() }
        } }) { result ->
            savingOrder = false
            binding.addButton.isEnabled = true
            if (result.getOrDefault(false).not()) toast(getString(R.string.tag_order_failed))
            refresh()
        }
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(edgeScroll)
        draggingRow?.cancelDragAndDrop()
        super.onDestroy()
    }

    private fun rename(tag: ExpenseTag) {
        val form = DialogTagNameBinding.inflate(layoutInflater)
        form.name.setText(tag.name)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.rename_tag).setView(form.root)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save_changes, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = form.name.text.toString().trim()
                if (name.isBlank() || name.length > 20) { form.nameLayout.error = getString(R.string.tag_name_error); return@setOnClickListener }
                databaseTask({ app.database.saveTag(tag.id, name) }) { saved ->
                    if (saved) { dialog.dismiss(); app.publishChange(); refresh() }
                    else form.nameLayout.error = getString(R.string.tag_duplicate)
                }
            }
        }
        dialog.show()
    }
}
