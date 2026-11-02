package com.moneytrack.app.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moneytrack.app.R
import com.moneytrack.app.databinding.DialogWheelDateTimeBinding
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** 日期和时间全部用滚轮选择，禁止键盘编辑；DialogFragment 在旋转时恢复所选值。 */
class WheelDateTimeDialog : DialogFragment() {
    private lateinit var binding: DialogWheelDateTimeBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogWheelDateTimeBinding.inflate(layoutInflater)
        val restored = LocalDateTime.parse(savedInstanceState?.getString("selection") ?: requireArguments().getString("selection"))
        val initial = restored.withYear(restored.year.coerceIn(1970, 2100))
        setup(binding.year, 1970, 2100, initial.year)
        setup(binding.month, 1, 12, initial.monthValue)
        setup(binding.day, 1, initial.toLocalDate().lengthOfMonth(), initial.dayOfMonth)
        setup(binding.hour, 0, 23, initial.hour)
        setup(binding.minute, 0, 59, initial.minute)
        val updateDays = NumberPicker.OnValueChangeListener { _, _, _ ->
            val day = binding.day.value
            binding.day.maxValue = LocalDate.of(binding.year.value, binding.month.value, 1).lengthOfMonth()
            binding.day.value = day.coerceAtMost(binding.day.maxValue)
        }
        binding.year.setOnValueChangedListener(updateDays)
        binding.month.setOnValueChangedListener(updateDays)
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.wheel_time_title)
            .setView(binding.root).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.choose_time) { _, _ ->
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf("selection" to selection().toString()))
            }.create()
    }

    // 监听只协调滚动且返回 false；点击和无障碍动作继续由 NumberPicker 自己处理。
    @SuppressLint("ClickableViewAccessibility")
    private fun setup(picker: NumberPicker, min: Int, max: Int, value: Int) {
        picker.minValue = min
        picker.maxValue = max
        picker.value = value.coerceIn(min, max)
        picker.wrapSelectorWheel = true
        // NumberPicker 默认允许点击中间数字输入；屏蔽其子输入框，仍保留滚动和无障碍增减操作。
        picker.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // 横屏时外层可滚动，手势落在滚轮内仍由滚轮处理，避免整页抢走滑动。
        picker.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> picker.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> picker.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        picker.setFormatter { number -> String.format(Locale.getDefault(), "%02d", number) }
    }

    private fun selection(): LocalDateTime = LocalDateTime.of(binding.year.value, binding.month.value,
        binding.day.value, binding.hour.value, binding.minute.value)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) outState.putString("selection", selection().toString())
    }

    companion object {
        const val RESULT_KEY = "manual_wheel_time"
        fun newInstance(time: LocalDateTime) = WheelDateTimeDialog().apply {
            arguments = bundleOf("selection" to time.toString())
        }
    }
}
