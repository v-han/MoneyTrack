package com.moneytrack.app.ui

import android.os.Bundle
import android.text.InputType
import androidx.core.widget.doAfterTextChanged
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ActivityManualExpenseBinding
import com.moneytrack.core.ExpenseMetadata
import com.moneytrack.core.ManualExpense
import com.moneytrack.core.ReimbursementStatus
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 手工填写支出；保存即确认，提交标识随页面状态恢复，避免旋转或重试导致重复入账。 */
class ManualExpenseActivity : BaseActivity() {
    private lateinit var binding: ActivityManualExpenseBinding
    private lateinit var metadataEditor: ExpenseMetadataEditor
    private var entryId = UUID.randomUUID()
    private var selectedTime = LocalDateTime.now().withSecond(0).withNano(0)
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        metadataEditor = ExpenseMetadataEditor(this, binding.metadata)
        if (savedInstanceState != null) metadataEditor.setMetadata(ExpenseMetadata(
            savedInstanceState.getString("note").orEmpty(),
            ReimbursementStatus.fromDatabase(savedInstanceState.getString("reimbursement").orEmpty()),
            savedInstanceState.getLongArray("tagIds")?.toList().orEmpty(),
            savedInstanceState.getLong("reimbursedAmountFen"),
        ), savedInstanceState.getString("reimbursedAmountText"))
        binding.metadata.manageTags.setOnClickListener {
            startActivity(android.content.Intent(this, TagManagerActivity::class.java))
        }
        // 只提示数字键盘，保留文本 KeyListener；粘贴的负号、逗号和单位必须完整交给 core 校验，不能被静默过滤。
        binding.amount.setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)
        binding.amount.doAfterTextChanged {
            metadataEditor.setExpenseAmount(ManualExpense.parseAmountFen(it.toString()), confirmed = false)
        }
        savedInstanceState?.getString("entryId")?.let { entryId = UUID.fromString(it) }
        savedInstanceState?.getString("selectedTime")?.let { selectedTime = LocalDateTime.parse(it) }
        showSelectedTime()
        binding.backButton.setOnClickListener { finish() }
        binding.dateButton.setOnClickListener { pickDateTime() }
        binding.timeButton.setOnClickListener { pickDateTime() }
        binding.saveButton.setOnClickListener { saveExpense() }
        supportFragmentManager.setFragmentResultListener(WheelDateTimeDialog.RESULT_KEY, this) { _, result ->
            selectedTime = LocalDateTime.parse(result.getString("selection"))
            showSelectedTime()
        }
        if (savedInstanceState != null) checkSavedDraft()
    }

    override fun onResume() {
        super.onResume()
        databaseTask({ app.database.tags() }) { tags -> metadataEditor.showTags(tags) }
    }

    private fun showSelectedTime() {
        binding.dateButton.text = getString(R.string.manual_date_value, selectedTime.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
        binding.timeButton.text = getString(R.string.manual_time_value, selectedTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
    }

    private fun pickDateTime() {
        if (supportFragmentManager.findFragmentByTag(DATE_TIME_PICKER) == null) {
            WheelDateTimeDialog.newInstance(selectedTime).show(supportFragmentManager, DATE_TIME_PICKER)
        }
    }

    private fun checkSavedDraft() {
        setBusy(true)
        val application = app
        val draftId = entryId
        // 同一 io 队列保证此查询排在旧页面已提交的保存之后，处理“保存中旋转”的情况。
        databaseTask({ runCatching { application.database.hasManualExpense(draftId) } }) { result ->
            setBusy(false)
            result.onSuccess { exists ->
                if (exists) { toast(getString(R.string.manual_already_saved)); finish() }
            }.onFailure { binding.result.setText(R.string.manual_restore_failed) }
        }
    }

    private fun saveExpense() {
        if (busy) return
        binding.amountLayout.error = null
        binding.sourceLayout.error = null
        binding.result.text = ""
        val amount = ManualExpense.parseAmountFen(binding.amount.text.toString())
        if (amount == null) {
            binding.amountLayout.error = getString(R.string.manual_amount_error)
            binding.amount.requestFocus()
            return
        }
        val source = binding.sourceName.text.toString().trim().ifBlank { getString(R.string.manual_default_source) }
        if (source.length > 40) {
            binding.sourceLayout.error = getString(R.string.manual_source_error)
            return
        }
        val timestamp = ManualExpenseTime.timestamp(selectedTime, ZoneId.systemDefault())
        if (timestamp == null) { binding.result.setText(R.string.manual_time_gap); return }
        metadataEditor.finishReimbursementEditing()
        val metadata = metadataEditor.metadata()
        metadata.validationError(amount)?.let { binding.result.text = it; return }
        val expense = ManualExpense(entryId, amount, source, timestamp)
        expense.validationError()?.let { binding.result.text = it; return }
        setBusy(true)
        val application = app
        databaseTask({
            runCatching {
                application.database.addManualExpense(expense, metadata).also {
                    // 即使旧页面销毁导致 done 回调被跳过，也要通知仍在展示的账本重新读取。
                    application.publishChange()
                }
            }
        }) { result ->
            result.onSuccess { inserted ->
                toast(getString(if (inserted) R.string.manual_saved else R.string.manual_already_saved))
                finish()
            }.onFailure {
                setBusy(false)
                binding.result.setText(R.string.manual_save_failed)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.saveButton.isEnabled = !value
        metadataEditor.setEnabled(!value)
        binding.amount.isEnabled = !value
        binding.sourceName.isEnabled = !value
        binding.dateButton.isEnabled = !value
        binding.timeButton.isEnabled = !value
        binding.backButton.isEnabled = !value
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 金额与来源由有 ID 的输入框恢复；保存稳定标识和选择的本地时间，不保存 Activity 引用。
        outState.putString("entryId", entryId.toString())
        outState.putString("selectedTime", selectedTime.toString())
        val metadata = metadataEditor.metadata()
        outState.putString("note", metadata.note)
        outState.putString("reimbursement", metadata.reimbursement.databaseValue)
        outState.putLong("reimbursedAmountFen", metadata.reimbursedAmountFen)
        outState.putString("reimbursedAmountText", metadataEditor.reimbursementAmountText())
        outState.putLongArray("tagIds", metadata.tagIds.toLongArray())
    }

    companion object {
        private const val DATE_TIME_PICKER = "manual_wheel_picker"
    }
}
