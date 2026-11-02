package com.moneytrack.app.ui

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ActivityRuleEditorBinding
import com.moneytrack.core.ExpenseParser
import com.moneytrack.core.PaymentRule
import com.moneytrack.core.PaymentRulePresets

/** 编辑与测试复用同一个 PaymentRule，避免测试成功但保存后行为不同。 */
class RuleEditorActivity : BaseActivity() {
    private lateinit var binding: ActivityRuleEditorBinding
    private var existingPackage: String? = null
    private var ready = false
    private lateinit var expenseEditor: KeywordListEditor
    private lateinit var excludedEditor: KeywordListEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        expenseEditor = KeywordListEditor(binding.expenseConditions, expense = true)
        excludedEditor = KeywordListEditor(binding.excludedConditions, expense = false)
        binding.addExpenseCondition.setOnClickListener { if (ready) expenseEditor.addKeyword() }
        binding.addExcludedCondition.setOnClickListener { if (ready) excludedEditor.addKeyword() }
        binding.backButton.setOnClickListener { finish() }
        binding.packageName.doAfterTextChanged {
            binding.applyPreset.visibility = if (PaymentRulePresets.forPackage(it.toString().trim()) != null) View.VISIBLE else View.GONE
        }
        binding.applyPreset.setOnClickListener {
            PaymentRulePresets.forPackage(binding.packageName.text.toString().trim())?.let { preset ->
                if (!ready) return@let
                expenseEditor.setKeywords(preset.expenseKeywords)
                toast("默认匹配条件已载入，请测试并保存后生效")
            }
        }
        binding.testButton.setOnClickListener { testRule() }
        binding.saveButton.setOnClickListener { saveRule() }
        binding.deleteButton.setOnClickListener { deleteRule() }
        binding.saveButton.isEnabled = false
        binding.deleteButton.visibility = View.GONE
        if (savedInstanceState?.getBoolean("ready") == true) {
            existingPackage = savedInstanceState.getString("existingPackage")
            fill(PaymentRule(
                savedInstanceState.getString("package").orEmpty(), savedInstanceState.getString("name").orEmpty(),
                savedInstanceState.getStringArrayList("expense").orEmpty(),
                savedInstanceState.getStringArrayList("excluded").orEmpty(), savedInstanceState.getBoolean("enabled"),
            ))
        } else {
            val requestedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
            databaseTask({ app.database.rule(requestedPackage) }) { stored ->
                existingPackage = stored?.packageName
                val suppliedName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val preset = PaymentRulePresets.forPackage(requestedPackage)
                fill(stored ?: preset?.copy(displayName = suppliedName.ifBlank { preset.displayName })
                    ?: PaymentRule(requestedPackage, suppliedName))
            }
        }
    }

    private fun fill(rule: PaymentRule) {
        binding.packageName.setText(rule.packageName)
        binding.displayName.setText(rule.displayName)
        expenseEditor.setKeywords(rule.expenseKeywords)
        excludedEditor.setKeywords(rule.excludedKeywords)
        binding.enabled.isChecked = rule.enabled
        binding.packageName.isEnabled = existingPackage == null
        binding.deleteButton.visibility = if (existingPackage == null) View.GONE else View.VISIBLE
        ready = true
        binding.saveButton.isEnabled = true
    }

    private fun formRule() = PaymentRule(
        binding.packageName.text.toString().trim(), binding.displayName.text.toString().trim(),
        expenseEditor.keywords(), excludedEditor.keywords(), binding.enabled.isChecked,
    )

    private fun testRule() {
        if (!ready) return
        val expenseValid = expenseEditor.validate()
        val excludedValid = excludedEditor.validate()
        if (!expenseValid || !excludedValid) { binding.testResult.setText(R.string.rule_conditions_invalid); return }
        val rule = formRule()
        rule.validationError()?.let { binding.testResult.text = it; return }
        val parsed = ExpenseParser().parse(rule.packageName, binding.sample.text.toString(), rule.copy(enabled = true))
        binding.testResult.text = if (parsed == null) "未匹配：请检查匹配条件、排除词及金额格式；多笔金额会被拒绝。"
            else "识别支出 ${money(parsed.amountFen)}（仅预览，不入账）"
    }

    private fun saveRule() {
        if (!ready) return
        val expenseValid = expenseEditor.validate()
        val excludedValid = excludedEditor.validate()
        if (!expenseValid || !excludedValid) { toast(getString(R.string.rule_conditions_invalid)); return }
        val rule = formRule()
        rule.validationError()?.let { toast(it); return }
        databaseTask({
            if (existingPackage == null && app.database.rule(rule.packageName) != null) false
            else { app.database.saveRule(rule); true }
        }) { saved ->
            if (saved) {
                app.publishChange()
                toast(getString(if (rule.enabled) R.string.rule_saved_enabled else R.string.rule_saved_disabled))
                finish()
            }
            else toast("这个包名已经存在，请返回列表编辑原规则")
        }
    }

    private fun deleteRule() {
        val packageName = existingPackage ?: return
        MaterialAlertDialogBuilder(this).setTitle("删除此应用规则？").setMessage("删除后停止识别，相关联动配置也会删除；已有账单保留。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                databaseTask({ app.database.deleteRule(packageName) }) { app.publishChange(); finish() }
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val rule = formRule()
        outState.putBoolean("ready", ready)
        outState.putString("existingPackage", existingPackage)
        outState.putString("package", rule.packageName)
        outState.putString("name", rule.displayName)
        outState.putStringArrayList("expense", ArrayList(rule.expenseKeywords))
        outState.putStringArrayList("excluded", ArrayList(rule.excludedKeywords))
        outState.putBoolean("enabled", rule.enabled)
        // 测试文本不进入状态 Bundle，避免系统保存真实通知原文。
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_NAME = "display_name"
    }
}
