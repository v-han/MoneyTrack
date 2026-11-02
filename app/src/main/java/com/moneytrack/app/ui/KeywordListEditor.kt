package com.moneytrack.app.ui

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ItemRuleConditionBinding
import com.moneytrack.core.PaymentRule

/** 一行一个条件，不再按逗号拆分；支出句式的语法校验统一交给 core。 */
class KeywordListEditor(private val container: LinearLayout, private val expense: Boolean) {
    private val rows = mutableListOf<ItemRuleConditionBinding>()

    /** 加载原列表，数组中的每一项完整保留，包括其中的标点。 */
    fun setKeywords(keywords: List<String>) {
        rows.clear()
        container.removeAllViews()
        keywords.forEach { addKeyword(it, focus = false) }
    }

    /** 新增空白条件供编辑，限制每类最多 20 项；未完成的支出条件会在保存时提示，排除词可留空。 */
    fun addKeyword(value: String = "", focus: Boolean = true) {
        if (rows.size >= 20) {
            Toast.makeText(container.context, R.string.too_many_rule_conditions, Toast.LENGTH_SHORT).show()
            return
        }
        val row = ItemRuleConditionBinding.inflate(LayoutInflater.from(container.context), container, false)
        // 动态行复用 XML ID；只通过 Activity 的规则数组恢复，避免系统按重复 ID 把所有行恢复成同一段文字。
        row.root.isSaveFromParentEnabled = false
        row.condition.setText(value)
        row.condition.doAfterTextChanged { row.conditionLayout.error = null }
        row.removeButton.setOnClickListener {
            rows.remove(row)
            container.removeView(row.root)
            updateLabels()
        }
        rows.add(row)
        container.addView(row.root)
        updateLabels()
        if (focus) row.condition.requestFocus()
    }

    /** 支出条件保留空项供校验；排除词允许空白行，生成配置时忽略，不拆分逗号。 */
    fun keywords(): List<String> = rows.map { it.condition.text.toString().trim() }
        .let { values -> if (expense) values else values.filter(String::isNotBlank) }

    /** 将每个条件的错误标在对应输入框，测试和保存共用。 */
    fun validate(): Boolean {
        var valid = true
        rows.forEach { row ->
            val value = row.condition.text.toString().trim()
            val error = if (expense) PaymentRule.expenseKeywordError(value)
                else if (value.length > 40) "每个排除词最多 40 个字符" else null
            row.conditionLayout.error = error
            if (error != null) valid = false
        }
        return valid
    }

    private fun updateLabels() {
        rows.forEachIndexed { index, row ->
            row.number.text = container.context.getString(R.string.rule_condition_index, index + 1)
            row.condition.hint = container.context.getString(
                if (expense) R.string.expense_rule_number else R.string.excluded_rule_number, index + 1)
            row.removeButton.contentDescription = container.context.getString(R.string.remove_rule_number, index + 1)
        }
    }
}
