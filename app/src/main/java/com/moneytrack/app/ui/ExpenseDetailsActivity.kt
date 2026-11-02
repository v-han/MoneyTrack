package com.moneytrack.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.data.Expense
import com.moneytrack.app.databinding.ActivityExpenseDetailsBinding
import com.moneytrack.core.ExpenseMetadata
import com.moneytrack.core.ReimbursementStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 待确认账单以“记录支出”同时保存并确认；已确认账单可修改报销、备注与标签。 */
class ExpenseDetailsActivity : BaseActivity() {
    private lateinit var binding: ActivityExpenseDetailsBinding
    private lateinit var editor: ExpenseMetadataEditor
    private var expenseId = -1L
    private var ready = false
    private var hasMetadataDraft = false
    private var saving = false
    private var confirmed = false
    private var amountFen = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpenseDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        expenseId = intent.getLongExtra(EXTRA_ID, -1)
        editor = ExpenseMetadataEditor(this, binding.metadata)
        // 草稿先同步恢复，不能等数据库查询。连续旋转/切主题可能在查询完成前再次重建页面。
        hasMetadataDraft = savedInstanceState?.getBoolean("hasMetadataDraft") == true || savedInstanceState?.getBoolean("ready") == true
        if (hasMetadataDraft && savedInstanceState != null) {
            editor.setMetadata(ExpenseMetadata(
                savedInstanceState.getString("note").orEmpty(),
                ReimbursementStatus.fromDatabase(savedInstanceState.getString("reimbursement").orEmpty()),
                savedInstanceState.getLongArray("tagIds")?.toList().orEmpty(),
                savedInstanceState.getLong("reimbursedAmountFen"),
            ), savedInstanceState.getString("reimbursedAmountText"))
        }
        setSaving(true)
        binding.backButton.setOnClickListener { finish() }
        binding.metadata.manageTags.setOnClickListener { startActivity(Intent(this, TagManagerActivity::class.java)) }
        binding.saveButton.setOnClickListener { save() }
        binding.removeButton.setOnClickListener {
            if (!ready || saving) return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle(R.string.remove_expense_title)
                .setMessage(R.string.remove_expense_hint).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove_expense) { _, _ ->
                    databaseTask({ app.database.dismiss(expenseId) }) { app.publishChange(); finish() }
                }.show()
        }
        databaseTask({ app.database.expense(expenseId) to app.database.tags() }) { (expense, tags) ->
            if (expense == null) { toast(getString(R.string.expense_missing)); finish(); return@databaseTask }
            showExpense(expense)
            if (!hasMetadataDraft) {
                editor.setMetadata(ExpenseMetadata(expense.note, expense.reimbursement, expense.tags.map { it.id }, expense.reimbursedAmountFen))
                hasMetadataDraft = true
            }
            editor.showTags(tags, expense.tags.filter { it.archived })
            ready = true
            setSaving(false)
        }
    }

    override fun onResume() {
        super.onResume()
        databaseTask({ app.database.tags() to app.database.expense(expenseId) }) { (tags, expense) ->
            editor.showTags(tags, expense?.tags.orEmpty().filter { it.archived })
        }
    }

    private fun showExpense(expense: Expense) {
        confirmed = expense.confirmed
        amountFen = expense.amountFen
        editor.setExpenseAmount(amountFen, confirmed)
        editor.setCurrentReimbursement(expense.reimbursement)
        binding.amount.text = money(expense.amountFen)
        binding.source.text = getString(R.string.record_source_time, expense.sourceName,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(expense.receivedAt)))
        binding.confirmation.setText(if (expense.confirmed) R.string.confirmed_record else R.string.pending_record)
        binding.linkedSources.visibility = if (expense.linkedSources.isEmpty()) View.GONE else View.VISIBLE
        binding.linkedSources.text = getString(R.string.linked_sources, expense.linkedSources.joinToString(" + "))
        binding.saveButton.setText(if (confirmed) R.string.save_changes else R.string.record_expense)
    }

    private fun save() {
        if (!ready || saving) return
        editor.finishReimbursementEditing()
        val metadata = editor.metadata()
        metadata.validationError(amountFen)?.let { toast(it); return }
        setSaving(true)
        val application = app
        databaseTask({ runCatching {
            application.database.saveMetadata(expenseId, metadata, confirm = !confirmed).also { application.publishChange() }
        } }) { result ->
            result.onSuccess { saved ->
                toast(getString(if (saved) R.string.details_saved else R.string.expense_missing))
                finish()
            }.onFailure { setSaving(false); binding.result.setText(R.string.details_save_failed) }
        }
    }

    private fun setSaving(value: Boolean) {
        saving = value
        editor.setEnabled(!value)
        binding.saveButton.isEnabled = !value
        binding.removeButton.isEnabled = !value
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val metadata = editor.metadata()
        outState.putBoolean("hasMetadataDraft", hasMetadataDraft)
        outState.putString("note", metadata.note)
        outState.putString("reimbursement", metadata.reimbursement.databaseValue)
        outState.putLong("reimbursedAmountFen", metadata.reimbursedAmountFen)
        outState.putString("reimbursedAmountText", editor.reimbursementAmountText())
        outState.putLongArray("tagIds", metadata.tagIds.toLongArray())
    }

    companion object { const val EXTRA_ID = "expense_id" }
}
