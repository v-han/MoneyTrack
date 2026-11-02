package com.moneytrack.app.ui

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.moneytrack.app.R
import com.moneytrack.app.data.RuleConfiguration
import com.moneytrack.app.databinding.ActivityRuleTransferBinding
import com.moneytrack.app.data.NotificationConfiguration

/** 用户主动通过系统文件选择器传输规则；文件和数据库访问均在 io 线程完成。 */
class RuleTransferActivity : BaseActivity() {
    private lateinit var binding: ActivityRuleTransferBinding
    private lateinit var transfer: TransferState

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = transfer.exportText
        transfer.exportText = null
        transfer.pickerLaunched = false
        render()
        if (uri != null && text != null) {
            val resolver = applicationContext.contentResolver
            runTask({
                val output = resolver.openOutputStream(uri, "wt") ?: error("无法打开文件")
                output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }) { state, _ -> state.status = "配置已导出" }
        } else if (uri != null) {
            transfer.status = "导出已中断，请重新导出"
            render()
        } else {
            transfer.status = "已取消导出"
            render()
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            transfer.preview = null
            val resolver = applicationContext.contentResolver
            val database = app.database
            runTask({
                val input = resolver.openInputStream(uri) ?: error("无法打开文件")
                val rules = input.use(RuleConfiguration::readConfiguration)
                rules to database.rules().map { it.packageName }.toSet()
            }) { state, result ->
                state.preview = result.first
                state.existingPackages = result.second
                state.status = if (result.first.rules.isEmpty() && result.first.links.isEmpty()) "文件中没有可导入的规则" else "请核对下方规则后确认导入"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        @Suppress("DEPRECATION")
        transfer = lastCustomNonConfigurationInstance as? TransferState ?: TransferState().apply {
            exportText = savedInstanceState?.getString("exportText")
            pickerLaunched = savedInstanceState?.getBoolean("pickerLaunched") ?: false
            preview = savedInstanceState?.getString("preview")?.let { runCatching { RuleConfiguration.decodeConfiguration(it) }.getOrNull() }
            existingPackages = savedInstanceState?.getStringArrayList("existingPackages")?.toSet() ?: emptySet()
            status = if (savedInstanceState?.getBoolean("busy") == true) "操作已中断，请检查结果后重试"
                else savedInstanceState?.getString("status").orEmpty()
        }
        binding.backButton.setOnClickListener { finish() }
        binding.exportButton.setOnClickListener {
            val database = app.database
            runTask({ RuleConfiguration.encode(NotificationConfiguration(database.rules(), database.links())) }) { state, text ->
                state.exportText = text
                state.status = "请选择配置文件的保存位置"
            }
        }
        binding.importButton.setOnClickListener {
            runCatching { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                .onFailure { toast("无法打开系统文件选择器") }
        }
        binding.cancelButton.setOnClickListener {
            transfer.preview = null
            transfer.status = "已取消导入，现有规则保持不变"
            render()
        }
        binding.confirmButton.setOnClickListener {
            val rules = transfer.preview ?: return@setOnClickListener
            val application = app
            runTask({
                application.database.importConfiguration(rules)
                application.publishChange()
            }) { state, _ ->
                state.preview = null
                state.status = "已导入 ${rules.rules.size} 个应用、${rules.links.size} 条联动"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        transfer.changed = { render() }
        render()
    }

    override fun onStop() {
        transfer.changed = null
        super.onStop()
    }

    // 只保留此页面的数据与任务状态，旋转后不重复执行写入，也不让后台任务持有旧 View。
    @Deprecated("仅用于此原生页面的旋转状态保留")
    override fun onRetainCustomNonConfigurationInstance(): Any = transfer

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 文件上限同时约束 Bundle 大小；进程被回收后只恢复预览，绝不自动导入。
        transfer.preview?.let { outState.putString("preview", RuleConfiguration.encode(it)) }
        outState.putString("exportText", transfer.exportText)
        outState.putBoolean("pickerLaunched", transfer.pickerLaunched)
        outState.putStringArrayList("existingPackages", ArrayList(transfer.existingPackages))
        outState.putString("status", transfer.status)
        outState.putBoolean("busy", transfer.busy)
    }

    private fun render() {
        binding.exportButton.isEnabled = !transfer.busy && transfer.exportText == null
        binding.importButton.isEnabled = !transfer.busy && transfer.exportText == null
        binding.confirmButton.isEnabled = !transfer.busy
        binding.cancelButton.isEnabled = !transfer.busy
        binding.status.text = if (transfer.busy) "正在处理配置…" else transfer.status
        val configuration = transfer.preview
        val rules = configuration?.rules.orEmpty()
        val links = configuration?.links.orEmpty()
        binding.previewCard.visibility = if (rules.isNotEmpty() || links.isNotEmpty()) View.VISIBLE else View.GONE
        val replacements = rules.count { it.packageName in transfer.existingPackages }
        binding.previewSummary.text = getString(R.string.import_summary,
            rules.size - replacements, replacements, rules.count { it.enabled }, links.size)
        val ruleText = rules.joinToString("\n\n") { rule ->
            val action = if (rule.packageName in transfer.existingPackages) "覆盖" else "新增"
            val enabled = if (rule.enabled) "启用" else "停用"
            "$action · ${rule.displayName} · $enabled\n${rule.packageName}\n匹配：\n${rule.expenseKeywords.joinToString("\n")}\n排除：${rule.excludedKeywords.joinToString("、").ifEmpty { "无" }}"
        }
        val linkText = links.joinToString("\n\n") { link ->
            val enabled = if (link.enabled) "启用" else "停用"
            "联动 · $enabled\n主：${link.mainPackageName}\n副：${link.secondaryPackageName}\n主通知前后各 ${link.windowSeconds} 秒，相同金额"
        }
        binding.previewRules.text = listOf(ruleText, linkText).filter(String::isNotBlank).joinToString("\n\n")
        // 任务结束时页面可能在后台；等当前实例可见后再启动文件选择器。
        if (!transfer.busy && transfer.exportText != null && !transfer.pickerLaunched) {
            transfer.pickerLaunched = true
            runCatching { createDocument.launch("MoneyTrack-rules.json") }.onFailure {
                transfer.exportText = null
                transfer.pickerLaunched = false
                transfer.status = "无法打开系统文件选择器"
                render()
            }
        }
    }

    private fun <T> runTask(work: () -> T, done: (TransferState, T) -> Unit) {
        val state = transfer
        val application = app
        state.busy = true
        state.pickerLaunched = false
        render()
        application.io.execute {
            val result = runCatching(work)
            application.mainHandler.post {
                state.busy = false
                result.fold({ done(state, it) }, {
                    state.status = if (it is IllegalArgumentException) it.message ?: "配置无效"
                        else "操作失败，请检查文件权限、可用空间后重试"
                })
                state.changed?.invoke()
            }
        }
    }

    private class TransferState {
        var preview: NotificationConfiguration? = null
        var existingPackages: Set<String> = emptySet()
        var exportText: String? = null
        var pickerLaunched = false
        var busy = false
        var status = ""
        var changed: (() -> Unit)? = null
    }
}
