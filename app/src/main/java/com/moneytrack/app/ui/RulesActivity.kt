package com.moneytrack.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ActivityRulesBinding
import com.moneytrack.app.databinding.ItemRuleBinding

/** 展示用户配置的来源应用，点击卡片进入规则编辑。 */
class RulesActivity : BaseActivity() {
    private lateinit var binding: ActivityRulesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.helpButton.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.setup_steps_title).setMessage(R.string.setup_steps)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        binding.addManual.setOnClickListener { openEditor() }
        binding.addInstalled.setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        databaseTask({ app.database.rules().map { rule ->
            rule to runCatching { packageManager.getApplicationIcon(rule.packageName) }
                .getOrElse { packageManager.defaultActivityIcon }
        } }) { rules ->
            binding.rules.removeAllViews()
            rules.forEach { (rule, icon) ->
                val row = ItemRuleBinding.inflate(layoutInflater, binding.rules, false)
                row.icon.setImageDrawable(icon)
                row.name.text = rule.displayName
                row.packageLabel.text = rule.packageName
                row.statusLabel.setText(if (rule.enabled) R.string.rule_active else R.string.rule_inactive_short)
                row.statusLabel.setTextColor(ContextCompat.getColor(this,
                    if (rule.enabled) R.color.primary else R.color.on_surface_variant))
                row.root.setOnClickListener { openEditor(rule.packageName) }
                binding.rules.addView(row.root)
            }
        }
    }

    private fun openEditor(packageName: String? = null, displayName: String? = null) {
        startActivity(Intent(this, RuleEditorActivity::class.java)
            .putExtra(RuleEditorActivity.EXTRA_PACKAGE, packageName)
            .putExtra(RuleEditorActivity.EXTRA_NAME, displayName))
    }
}
