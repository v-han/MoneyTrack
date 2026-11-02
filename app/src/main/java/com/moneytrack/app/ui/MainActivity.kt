package com.moneytrack.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.data.Expense
import com.moneytrack.app.databinding.ActivityMainBinding
import com.moneytrack.app.databinding.ItemLedgerBinding
import com.moneytrack.app.databinding.PopupReimbursementFilterBinding
import com.moneytrack.app.databinding.ItemReimbursementFilterBinding
import com.moneytrack.app.notification.ContinuousListeningService
import com.moneytrack.app.notification.ListeningPreferences
import com.moneytrack.app.notification.NotificationConnection
import com.moneytrack.app.notification.PaymentNotificationListener
import com.moneytrack.core.ReimbursementStatus
import com.moneytrack.core.ExpenseMetadata
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** 底部导航分离今日核对、历史统计和设置；页面仅展示数据库结果。 */
class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private var period = HistoryPeriod.MONTH
    private var anchor = LocalDate.now()
    private var homeLimit = 100
    private var historyLimit = 100
    private var reimbursementFilter: ReimbursementStatus? = null
    private var filterPopup: PopupWindow? = null
    private val reimbursementFilters = listOf(null, ReimbursementStatus.PENDING, ReimbursementStatus.PARTIAL,
        ReimbursementStatus.REIMBURSED, ReimbursementStatus.NONE)
    private var refreshVersion = 0
    private val onChanged: () -> Unit = { refresh() }
    private val midnightRefresh = Runnable { refresh(); scheduleMidnightRefresh() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableContinuousListening()
        else toast("未开启持续监听，可在系统应用通知设置中允许通知后重试")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        period = savedInstanceState?.getString("period")?.let { HistoryPeriod.valueOf(it) } ?: HistoryPeriod.MONTH
        anchor = savedInstanceState?.getString("anchor")?.let(LocalDate::parse) ?: LocalDate.now()
        reimbursementFilter = savedInstanceState?.getString("reimbursementFilter")?.let(ReimbursementStatus::fromDatabase)
        binding.navigation.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        binding.navigation.selectedItemId = savedInstanceState?.getInt("tab") ?: R.id.nav_home
        showTab(binding.navigation.selectedItemId)
        val history = binding.historyPanel
        updateFilterButton()
        history.reimbursementFilter.setOnClickListener { showReimbursementFilter() }
        history.periodGroup.check(when (period) {
            HistoryPeriod.DAY -> R.id.periodDay
            HistoryPeriod.WEEK -> R.id.periodWeek
            HistoryPeriod.MONTH -> R.id.periodMonth
        })
        history.periodGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                period = when (id) {
                    R.id.periodDay -> HistoryPeriod.DAY
                    R.id.periodWeek -> HistoryPeriod.WEEK
                    else -> HistoryPeriod.MONTH
                }
                historyLimit = 100
                refresh()
            }
        }
        history.previousButton.setOnClickListener { moveHistory(-1) }
        history.nextButton.setOnClickListener { moveHistory(1) }
        history.currentButton.setOnClickListener { anchor = LocalDate.now(); historyLimit = 100; refresh() }
        history.historyMore.setOnClickListener { historyLimit += 100; refresh() }
        binding.homePanel.moreButton.setOnClickListener { homeLimit += 100; refresh() }
        binding.homePanel.refreshButton.setOnClickListener { refresh() }
        binding.homePanel.addExpenseButton.setOnClickListener {
            startActivity(Intent(this, ManualExpenseActivity::class.java))
        }
        val settings = binding.settingsPanel
        settings.manageTagsButton.setOnClickListener { startActivity(Intent(this, TagManagerActivity::class.java)) }
        settings.permissionButton.setOnClickListener { explainPermission() }
        settings.rulesButton.setOnClickListener { startActivity(Intent(this, NotificationConfigurationActivity::class.java)) }
        settings.reconnectButton.setOnClickListener {
            if (!NotificationConnection.hasAccess(this)) explainPermission()
            else {
                val requested = NotificationConnection.resetFromSettings(this)
                toast(if (requested) getString(R.string.reconnect_sent) else "连接请求间隔过短，请稍候再试")
            }
        }
        settings.backgroundToggle.setOnClickListener {
            // 已选择开启但服务被系统中断时，仍允许关闭该选择；不能把中断当成用户关闭。
            if (ListeningPreferences.isEnabled(this) || app.backgroundListening) {
                runCatching {
                    ListeningPreferences.setEnabled(this, false)
                    stopService(Intent(this, ContinuousListeningService::class.java))
                    app.publishChange()
                }.onFailure { toast("关闭失败，请重新打开应用后重试") }
            } else enableContinuousListening()
        }
        settings.appSettingsButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }
                .onFailure { toast("无法打开应用详情，请手动进入系统设置") }
        }
        settings.clearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("清空全部账本？")
                .setMessage("将删除所有待确认、已确认记录、备注和去重标记，无法撤销。支付 App 规则和标签保留。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                    databaseTask({ app.database.clearExpenses() }) { app.publishChange() }
                }.show()
        }
    }

    private fun showTab(id: Int) {
        binding.homePanel.root.visibility = if (id == R.id.nav_home) View.VISIBLE else View.GONE
        binding.historyPanel.root.visibility = if (id == R.id.nav_history) View.VISIBLE else View.GONE
        binding.settingsPanel.root.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
    }

    private fun updateFilterButton() {
        val title = reimbursementFilter?.let { ExpenseMetadataEditor.title(this, it) }
            ?: getString(R.string.reimbursement_all)
        binding.historyPanel.reimbursementFilter.text = title
        binding.historyPanel.reimbursementFilter.contentDescription = getString(R.string.reimbursement_filter_description, title)
    }

    private fun showReimbursementFilter() {
        filterPopup?.dismiss()
        val panel = PopupReimbursementFilterBinding.inflate(layoutInflater)
        val density = resources.displayMetrics.density
        val width = minOf((280 * density).toInt(), binding.root.width - (32 * density).toInt())
        val popup = PopupWindow(panel.root, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
            cornerRadius = 20 * density
        })
        popup.elevation = 10 * density
        popup.isOutsideTouchable = true
        reimbursementFilters.forEach { status ->
            val row = ItemReimbursementFilterBinding.inflate(layoutInflater, panel.options, false)
            val title = status?.let { ExpenseMetadataEditor.title(this, it) } ?: getString(R.string.reimbursement_all)
            val selected = status == reimbursementFilter
            row.title.text = title
            row.description.setText(when (status) {
                null -> R.string.filter_all_description
                ReimbursementStatus.PENDING, ReimbursementStatus.NONE -> R.string.filter_full_description
                ReimbursementStatus.PARTIAL -> R.string.filter_partial_description
                ReimbursementStatus.REIMBURSED -> R.string.filter_done_description
            })
            val dotColor = when (status) {
                null -> R.color.reimbursement_accent
                ReimbursementStatus.NONE -> R.color.outline
                else -> ReimbursementAppearance.colors(status).second
            }
            row.statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, dotColor))
            }
            // 原生选项背景不叠加 Material 的父级海拔色，保持浮层白底和报销专用选中色。
            val shape = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(ContextCompat.getColor(this@MainActivity,
                    if (selected) R.color.reimbursement_selection else R.color.surface))
            }
            row.root.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(
                ContextCompat.getColor(this, R.color.reimbursement_accent), 40)), shape, null)
            row.root.isSelected = selected
            row.root.contentDescription = if (selected) getString(R.string.filter_selected_description, title) else title
            row.selectedMark.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.root.setOnClickListener {
                reimbursementFilter = status
                updateFilterButton()
                historyLimit = 100
                popup.dismiss()
                refresh()
            }
            panel.options.addView(row.root)
        }
        // 字号放大时限制面板高度，选项可滚动，避免底部选项被系统栏遮住。
        panel.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        popup.height = minOf(panel.root.measuredHeight, (binding.root.height * 0.65f).toInt())
        filterPopup = popup
        popup.setOnDismissListener { if (filterPopup === popup) filterPopup = null }
        popup.showAsDropDown(binding.historyPanel.reimbursementFilter, 0, (8 * density).toInt(), Gravity.END)
    }

    override fun onStart() {
        super.onStart()
        app.changes.add(onChanged)
        scheduleMidnightRefresh()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时可申请系统重连，但不会自动开启用户未选择的前台模式。
        if (!app.listenerConnected) NotificationConnection.requestReconnect(this)
        if (ListeningPreferences.isEnabled(this) && !app.backgroundListening) enableContinuousListening(askPermission = false)
        refresh()
    }

    override fun onStop() {
        filterPopup?.dismiss()
        app.changes.remove(onChanged)
        app.mainHandler.removeCallbacks(midnightRefresh)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", binding.navigation.selectedItemId)
        outState.putString("period", period.name)
        outState.putString("anchor", anchor.toString())
        outState.putString("reimbursementFilter", reimbursementFilter?.databaseValue)
    }

    private fun moveHistory(direction: Long) {
        anchor = period.move(anchor, direction)
        historyLimit = 100
        refresh()
    }

    private fun scheduleMidnightRefresh() {
        app.mainHandler.removeCallbacks(midnightRefresh)
        val nextDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        app.mainHandler.postDelayed(midnightRefresh, (nextDay - System.currentTimeMillis()).coerceAtLeast(1_000) + 100)
    }

    private fun refresh() {
        val granted = NotificationConnection.hasAccess(this)
        val status = when {
            !granted -> "通知读取：未授权"
            app.listenerConnected -> "通知读取：已连接"
            else -> "通知读取：已授权，尚未连接"
        }
        binding.settingsPanel.status.text = listOfNotNull(status, app.lastError).joinToString("\n")
        binding.settingsPanel.backgroundStatus.setText(when {
            app.backgroundListening -> R.string.background_active
            ListeningPreferences.isEnabled(this) -> R.string.background_interrupted
            else -> R.string.background_inactive
        })
        binding.settingsPanel.connectionHelp.visibility = if (!app.listenerConnected) View.VISIBLE else View.GONE
        binding.settingsPanel.notificationDiagnostic.text = if (app.lastNotificationAt == 0L) getString(R.string.diagnostic_none)
            else getString(R.string.diagnostic_result, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(app.lastNotificationAt)), app.lastNotificationResult)
        binding.settingsPanel.backgroundToggle.setText(
            if (app.backgroundListening || ListeningPreferences.isEnabled(this)) R.string.disable_background else R.string.enable_background)
        val zone = ZoneId.systemDefault()
        val (todayStart, tomorrow) = HistoryPeriod.DAY.timestamps(LocalDate.now(), zone)
        val (start, end) = period.timestamps(anchor, zone)
        val dates = period.dates(anchor)
        val format = DateTimeFormatter.ofPattern("yyyy.MM.dd")
        binding.historyPanel.rangeLabel.text = if (period == HistoryPeriod.DAY) dates.first.format(format)
            else "${dates.first.format(format)} — ${dates.second.minusDays(1).format(format)}"
        // 快速切换日/周/月时，忽略旧查询回调，避免旧区间结果覆盖新选择。
        val version = ++refreshVersion
        val requestedHomeLimit = homeLimit
        val requestedHistoryLimit = historyLimit
        val requestedReimbursementFilter = reimbursementFilter
        databaseTask({
            app.database.homeSnapshot(todayStart, tomorrow, requestedHomeLimit) to
                (app.database.historySnapshot(start, end, requestedHistoryLimit, requestedReimbursementFilter) to app.database.rules().count { it.enabled })
        }) { (home, historyAndRules) ->
            val (history, ruleCount) = historyAndRules
            if (version != refreshVersion) return@databaseTask
            binding.settingsPanel.rulesStatus.text = getString(R.string.active_rules_count, ruleCount)
            binding.homePanel.total.text = money(home.todayTotalFen)
            binding.homePanel.pendingCount.text = getString(R.string.pending_count, home.pendingCount, home.todayCount)
            renderExpenses(binding.homePanel.pending, home.pending)
            renderExpenses(binding.homePanel.today, home.today)
            binding.homePanel.pendingEmpty.visibility = if (home.pendingCount == 0L) View.VISIBLE else View.GONE
            binding.homePanel.todayEmpty.visibility = if (home.todayCount == 0L) View.VISIBLE else View.GONE
            binding.homePanel.moreButton.visibility = if (home.pending.size.toLong() < home.pendingCount || home.today.size.toLong() < home.todayCount) View.VISIBLE else View.GONE
            binding.historyPanel.historyTotal.text = money(history.totalFen)
            binding.historyPanel.count.text = getString(R.string.period_count, history.count)
            renderExpenses(binding.historyPanel.expenses, history.expenses)
            binding.historyPanel.empty.visibility = if (history.count == 0L) View.VISIBLE else View.GONE
            binding.historyPanel.shownCount.text = getString(R.string.shown_count, history.expenses.size, history.count)
            binding.historyPanel.historyMore.visibility = if (history.expenses.size.toLong() < history.count) View.VISIBLE else View.GONE
        }
    }

    private fun renderExpenses(container: LinearLayout, expenses: List<Expense>) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        container.removeAllViews()
        expenses.forEach { expense ->
            val row = ItemLedgerBinding.inflate(layoutInflater, container, false)
            row.amount.text = money(expense.amountFen)
            row.source.text = getString(R.string.record_source_time, expense.sourceName, dateFormat.format(Date(expense.receivedAt)))
            row.statusLabel.setText(when {
                expense.manual -> R.string.confirmed_manual_record
                expense.confirmed -> R.string.confirmed_record
                else -> R.string.pending_record
            })
            // 待确认优先显示核对底色；已确认账单整张卡片按报销状态着色，文字仍说明状态。
            val (background, foreground) = when {
                !expense.confirmed -> R.color.pending_container to R.color.on_pending_container
                else -> ReimbursementAppearance.colors(expense.reimbursement)
            }
            row.root.setCardBackgroundColor(ContextCompat.getColor(this, background))
            val textColor = ContextCompat.getColor(this, foreground)
            listOf(row.statusLabel, row.amount, row.source, row.reimbursementLabel, row.countedAmount, row.metadataPreview)
                .forEach { it.setTextColor(textColor) }
            row.reimbursementLabel.text = if (expense.reimbursement == ReimbursementStatus.PARTIAL)
                getString(R.string.partial_reimbursed_amount, money(expense.reimbursedAmountFen))
                else ExpenseMetadataEditor.title(this, expense.reimbursement)
            row.reimbursementLabel.visibility = if (expense.reimbursement == ReimbursementStatus.NONE) View.GONE else View.VISIBLE
            val deducted = expense.reimbursement == ReimbursementStatus.PARTIAL || expense.reimbursement == ReimbursementStatus.REIMBURSED
            row.countedAmount.visibility = if (deducted) View.VISIBLE else View.GONE
            if (deducted) row.countedAmount.text = getString(
                if (expense.confirmed) R.string.counted_expense_amount else R.string.counted_after_confirmation,
                money(ExpenseMetadata(reimbursement = expense.reimbursement, reimbursedAmountFen = expense.reimbursedAmountFen)
                    .countedExpenseFen(expense.amountFen)),
            )
            row.metadataPreview.text = listOfNotNull(
                expense.linkedSources.takeIf { it.isNotEmpty() }?.let { getString(R.string.linked_sources, it.joinToString(" + ")) },
                expense.tags.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "#${it.name}" },
                expense.note.takeIf { it.isNotBlank() }?.let { getString(R.string.note_preview, it) },
            ).joinToString("\n")
            row.metadataPreview.visibility = if (row.metadataPreview.text.isEmpty()) View.GONE else View.VISIBLE
            row.root.setOnClickListener {
                startActivity(Intent(this, ExpenseDetailsActivity::class.java).putExtra(ExpenseDetailsActivity.EXTRA_ID, expense.id))
            }
            container.addView(row.root)
        }
    }

    private fun enableContinuousListening(askPermission: Boolean = true) {
        if (!NotificationConnection.hasAccess(this)) { if (askPermission) explainPermission(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (askPermission) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                toast(getString(R.string.background_permission_hint))
            }
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(ContinuousListeningService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            if (!askPermission) return
            toast("请允许显示持续监听通知后重试")
            runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            return
        }
        // 从用户可见页面启动，遵守 Android 12+ 的后台启动限制。
        runCatching {
            startForegroundService(Intent(this, ContinuousListeningService::class.java).setAction(ContinuousListeningService.ACTION_START))
        }.onFailure { toast("无法启动持续监听，请检查系统后台限制") }
    }

    private fun explainPermission() {
        MaterialAlertDialogBuilder(this).setTitle("通知读取说明")
            .setMessage(getString(R.string.permission_detailed_help))
            .setNegativeButton("取消", null).setPositiveButton("前往系统设置") { _, _ ->
                val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, PaymentNotificationListener::class.java).flattenToString())
                    else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                runCatching { startActivity(detail) }.recoverCatching {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.onFailure { toast("无法打开设置，请手动进入系统的“通知使用权”页面") }
            }.show()
    }
}
