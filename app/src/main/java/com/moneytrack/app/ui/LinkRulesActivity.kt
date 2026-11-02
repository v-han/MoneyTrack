package com.moneytrack.app.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ActivityLinkRulesBinding
import com.moneytrack.app.databinding.ItemLinkRuleBinding
import com.moneytrack.core.NotificationLinkRule
import com.moneytrack.core.PaymentRule
import java.util.Locale

/** 选择已配置的主副来源并编辑秒数；默认不启用，不修改已有账单。 */
class LinkRulesActivity : BaseActivity() {
    private lateinit var binding: ActivityLinkRulesBinding
    private var sources: List<PaymentRule> = emptyList()
    private var original: Pair<String, String>? = null
    private var mainPackage = ""
    private var secondaryPackage = ""
    private var ready = false
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.mainChoice.setOnItemClickListener { _, _, position, _ ->
            mainPackage = sources[position].packageName
            binding.mainChoice.setText(sources[position].displayName, false)
        }
        binding.secondaryChoice.setOnItemClickListener { _, _, position, _ ->
            secondaryPackage = sources[position].packageName
            binding.secondaryChoice.setText(sources[position].displayName, false)
        }
        binding.addButton.setOnClickListener {
            if (sources.size < 2) { toast(getString(R.string.link_need_sources)); return@setOnClickListener }
            openEditor(null)
        }
        binding.cancelButton.setOnClickListener { binding.editorCard.visibility = View.GONE; refresh() }
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener {
            val key = original ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle(R.string.delete_notification_link)
                .setMessage(R.string.delete_notification_link_hint).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_notification_link) { _, _ ->
                    setSaving(true)
                    databaseTask({ runCatching { app.database.deleteLink(key.first, key.second) } }) { result ->
                        setSaving(false)
                        result.onSuccess { binding.editorCard.visibility = View.GONE; app.publishChange(); refresh() }
                            .onFailure { binding.result.setText(R.string.link_save_failed) }
                    }
                }.show()
        }
        if (savedInstanceState?.getBoolean("editorVisible") == true) {
            original = savedInstanceState.getString("originalMain")?.let { it to savedInstanceState.getString("originalSecondary").orEmpty() }
            mainPackage = savedInstanceState.getString("main").orEmpty()
            secondaryPackage = savedInstanceState.getString("secondary").orEmpty()
            binding.windowSeconds.setText(savedInstanceState.getString("window", "30"))
            binding.enabledSwitch.isChecked = savedInstanceState.getBoolean("enabled")
            binding.editorCard.visibility = View.VISIBLE
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        ready = false
        binding.addButton.isEnabled = false
        binding.saveButton.isEnabled = false
        databaseTask({ app.database.rules() to app.database.links() }) { (rules, links) ->
            sources = rules
            val labels = sources.map { "${it.displayName} (${it.packageName})" }.toTypedArray()
            binding.mainChoice.setSimpleItems(labels)
            binding.secondaryChoice.setSimpleItems(labels)
            showChoices()
            binding.links.removeAllViews()
            binding.emptyHint.visibility = if (links.isEmpty() && !binding.editorCard.isVisible) View.VISIBLE else View.GONE
            links.forEach { link ->
                val row = ItemLinkRuleBinding.inflate(layoutInflater, binding.links, false)
                val main = sources.find { it.packageName == link.mainPackageName }?.displayName ?: link.mainPackageName
                val secondary = sources.find { it.packageName == link.secondaryPackageName }?.displayName ?: link.secondaryPackageName
                row.title.text = getString(R.string.link_pair_title, main, secondary)
                row.summary.text = getString(R.string.link_pair_summary,
                    getString(if (link.enabled) R.string.rule_active else R.string.rule_inactive), link.windowSeconds)
                row.root.setOnClickListener { if (!saving) openEditor(link) }
                binding.links.addView(row.root)
            }
            ready = true
            setSaving(false)
        }
    }

    private fun openEditor(link: NotificationLinkRule?) {
        original = link?.let { it.mainPackageName to it.secondaryPackageName }
        mainPackage = link?.mainPackageName ?: sources[0].packageName
        secondaryPackage = link?.secondaryPackageName ?: sources[1].packageName
        // 数字输入与 toIntOrNull 采用一致的 ASCII 格式，不受系统数字语言影响。
        binding.windowSeconds.setText(String.format(Locale.ROOT, "%d", link?.windowSeconds ?: 30))
        binding.enabledSwitch.isChecked = link?.enabled == true
        binding.result.text = ""
        binding.editorCard.visibility = View.VISIBLE
        binding.emptyHint.visibility = View.GONE
        showChoices()
        binding.root.smoothScrollTo(0, 0)
    }

    private fun showChoices() {
        fun label(packageName: String) = sources.find { it.packageName == packageName }?.displayName.orEmpty()
        binding.mainChoice.setText(label(mainPackage), false)
        binding.secondaryChoice.setText(label(secondaryPackage), false)
        binding.deleteButton.visibility = if (original != null) View.VISIBLE else View.GONE
        binding.editorTitle.setText(if (original == null) R.string.add_notification_link else R.string.edit_notification_link)
    }

    private fun save() {
        if (!ready || saving) return
        val value = NotificationLinkRule(mainPackage, secondaryPackage,
            binding.windowSeconds.text.toString().toIntOrNull() ?: 0, binding.enabledSwitch.isChecked)
        value.validationError()?.let { binding.result.text = it; return }
        val key = original
        setSaving(true)
        databaseTask({ runCatching { app.database.saveLink(value, key) } }) { result ->
            setSaving(false)
            result.onSuccess { binding.editorCard.visibility = View.GONE; app.publishChange(); refresh() }
                .onFailure { binding.result.text = if (it is IllegalArgumentException) it.message else getString(R.string.link_save_failed) }
        }
    }

    private fun setSaving(value: Boolean) {
        saving = value
        listOf(binding.addButton, binding.saveButton, binding.deleteButton, binding.cancelButton,
            binding.mainChoice, binding.secondaryChoice, binding.windowSeconds, binding.enabledSwitch).forEach { it.isEnabled = ready && !value }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("editorVisible", binding.editorCard.isVisible)
        outState.putString("main", mainPackage)
        outState.putString("secondary", secondaryPackage)
        outState.putString("originalMain", original?.first)
        outState.putString("originalSecondary", original?.second)
        outState.putString("window", binding.windowSeconds.text.toString())
        outState.putBoolean("enabled", binding.enabledSwitch.isChecked)
    }
}
