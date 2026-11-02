package com.moneytrack.app.ui

import android.content.Context
import android.view.View
import android.text.InputType
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.moneytrack.app.R
import com.moneytrack.app.data.ExpenseTag
import com.moneytrack.app.databinding.ViewExpenseMetadataBinding
import com.moneytrack.core.ExpenseMetadata
import com.moneytrack.core.ManualExpense
import com.moneytrack.core.ReimbursementStatus
import java.math.BigDecimal

/** 手工新增与账单详情共用的附加信息表单，不执行数据库操作。 */
class ExpenseMetadataEditor(private val context: Context, private val binding: ViewExpenseMetadataBinding) {
    private var enabled = true
    private val selectedIds = linkedSetOf<Long>()
    private var amountFen: Long? = null
    private var confirmed = true
    private var selectedReimbursement = ReimbursementStatus.NONE
    private val statuses = listOf(ReimbursementStatus.NONE, ReimbursementStatus.PENDING,
        ReimbursementStatus.PARTIAL, ReimbursementStatus.REIMBURSED)

    init {
        binding.reimbursementChoice.setSimpleItems(statuses.map { title(context, it) }.toTypedArray())
        binding.reimbursementChoice.setText(title(context, selectedReimbursement), false)
        binding.reimbursementChoice.setOnItemClickListener { _, _, position, _ ->
            selectedReimbursement = statuses[position]
            showReimbursement()
        }
        // 保留粘贴的非法字符交给 core 拒绝，避免输入被过滤后变成另一个金额。
        binding.reimbursedAmount.setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)
        binding.reimbursedAmount.doAfterTextChanged { showReimbursement() }
        binding.reimbursedAmount.setOnFocusChangeListener { _, focused -> if (!focused) finishReimbursementEditing() }
        binding.reimbursementChoice.setOnFocusChangeListener { _, focused -> if (!focused) finishReimbursementEditing() }
        showReimbursement()
    }

    /** 只在首次加载或恢复草稿时赋值，跳转标签管理后不覆盖尚未保存的文字。 */
    fun setMetadata(metadata: ExpenseMetadata, amountText: String? = null) {
        binding.note.setText(metadata.note)
        binding.reimbursedAmount.setText(amountText ?: metadata.reimbursedAmountFen.takeIf { it > 0 }
            ?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty())
        selectedReimbursement = metadata.reimbursement
        binding.reimbursementChoice.setText(title(context, selectedReimbursement), false)
        selectedIds.clear()
        selectedIds.addAll(metadata.tagIds)
        showReimbursement()
    }

    /** 返回当前表单快照，避免后台保存过程中继续读取可变控件。 */
    fun metadata(): ExpenseMetadata {
        val status = selectedReimbursement
        return ExpenseMetadata(binding.note.text.toString(), status, selectedIds.toList(),
            if (status == ReimbursementStatus.PARTIAL) ManualExpense.parseAmountFen(reimbursementAmountText()) ?: -1 else 0)
    }

    /** 保留未完成或非法的金额草稿，旋转时不能悄悄替换用户输入。 */
    fun reimbursementAmountText(): String = binding.reimbursedAmount.text.toString()

    /** 失焦或提交时才归一状态，输入过程中保留原文，避免刚输入首位数字就隐藏输入框。 */
    fun finishReimbursementEditing() {
        val amount = amountFen ?: return
        val completed = metadata().completeReimbursementIfCovered(amount)
        if (completed.reimbursement != selectedReimbursement) {
            selectedReimbursement = completed.reimbursement
            binding.reimbursementChoice.setText(title(context, selectedReimbursement), false)
            binding.reimbursedAmount.text?.clear()
            showReimbursement()
            Toast.makeText(context, R.string.reimbursement_auto_completed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 当前状态始终显示数据库已保存的值，修改草稿和旋转恢复都不覆盖它。 */
    fun setCurrentReimbursement(status: ReimbursementStatus) {
        binding.currentReimbursement.text = title(context, status)
        binding.currentReimbursement.setTextColor(androidx.core.content.ContextCompat.getColor(context,
            ReimbursementAppearance.colors(status).second))
    }

    /** 原金额由调用页提供；待确认页明确提示只有记录后才会计入统计。 */
    fun setExpenseAmount(amountFen: Long?, confirmed: Boolean = true) {
        this.amountFen = amountFen
        this.confirmed = confirmed
        showReimbursement()
    }

    private fun showReimbursement() {
        val metadata = metadata()
        val partial = metadata.reimbursement == ReimbursementStatus.PARTIAL
        binding.reimbursedAmountLayout.visibility = if (partial) View.VISIBLE else View.GONE
        binding.reimbursementHelp.setText(when (metadata.reimbursement) {
            ReimbursementStatus.NONE -> R.string.reimbursement_none_help
            ReimbursementStatus.PENDING -> R.string.reimbursement_pending_help
            ReimbursementStatus.PARTIAL -> R.string.reimbursement_partial_help
            ReimbursementStatus.REIMBURSED -> R.string.reimbursement_done_help
        })
        val amount = amountFen
        val preview = if (amount != null) metadata.completeReimbursementIfCovered(amount) else metadata
        if (partial && preview.reimbursement == ReimbursementStatus.REIMBURSED) {
            binding.reimbursementHelp.setText(R.string.reimbursement_complete_on_blur)
        }
        val error = preview.copy(note = "", tagIds = emptyList()).validationError(amount)
        binding.reimbursedAmountLayout.error = if (partial && reimbursementAmountText().isNotBlank()) error else null
        binding.countedAmount.text = if (amount != null && error == null) context.getString(
            if (confirmed) R.string.counted_expense_amount else R.string.counted_after_confirmation,
            "¥" + BigDecimal.valueOf(preview.countedExpenseFen(amount), 2).toPlainString(),
        ) else ""
        binding.countedAmount.visibility = if (binding.countedAmount.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 可选标签横向排列；已关联账单的删除标签以快照保留，用户可主动取消选择。 */
    fun showTags(tags: List<ExpenseTag>, archivedTags: List<ExpenseTag> = emptyList()) {
        val choices = tags + archivedTags
        selectedIds.retainAll(choices.map { it.id }.toSet())
        binding.tagChoices.removeAllViews()
        binding.noTags.visibility = if (choices.isEmpty()) View.VISIBLE else View.GONE
        choices.forEach { tag ->
            val chip = Chip(context).apply {
                id = View.generateViewId()
                text = tag.name
                if (tag.archived) contentDescription = context.getString(R.string.archived_tag_description, tag.name)
                isCheckable = true
                isEnabled = enabled
                isChecked = tag.id in selectedIds
                setOnCheckedChangeListener { view, checked ->
                    if (checked && selectedIds.size >= 8 && tag.id !in selectedIds) {
                        view.isChecked = false
                        Toast.makeText(context, R.string.too_many_tags, Toast.LENGTH_SHORT).show()
                    } else if (checked) selectedIds.add(tag.id) else selectedIds.remove(tag.id)
                }
            }
            binding.tagChoices.addView(chip)
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        binding.note.isEnabled = enabled
        binding.reimbursedAmount.isEnabled = enabled
        binding.reimbursementChoice.isEnabled = enabled
        binding.manageTags.isEnabled = enabled
        for (index in 0 until binding.tagChoices.childCount) binding.tagChoices.getChildAt(index).isEnabled = enabled
    }

    companion object {
        /** 报销标记与确认状态分别显示。 */
        fun title(context: Context, status: ReimbursementStatus): String = context.getString(when (status) {
            ReimbursementStatus.NONE -> R.string.reimbursement_none
            ReimbursementStatus.PENDING -> R.string.reimbursement_pending
            ReimbursementStatus.REIMBURSED -> R.string.reimbursement_done
            ReimbursementStatus.PARTIAL -> R.string.reimbursement_partial
        })
    }
}
