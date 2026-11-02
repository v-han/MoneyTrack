package com.moneytrack.core

import java.math.BigDecimal
import java.util.UUID

/** 用户主动填写并保存的支出。金额单位为分；entryId 标识一次表单提交，不按金额去重。 */
data class ManualExpense(
    val entryId: UUID,
    val amountFen: Long,
    val sourceName: String,
    val occurredAt: Long,
) {
    /** 返回不可保存的原因；手工记录与通知识别使用相同的单笔金额上限。 */
    fun validationError(): String? = when {
        amountFen !in 1..ExpenseParser.MAX_AMOUNT_FEN -> "请输入大于零且不超过一亿元的金额"
        sourceName.isBlank() || sourceName.length > 40 -> "来源名称需要 1～40 个字符"
        occurredAt <= 0 -> "请选择 1970 年之后的支出时间"
        else -> null
    }

    companion object {
        const val FINGERPRINT_PREFIX = "manual:"
        private val AMOUNT_PATTERN = Regex("[0-9]{1,9}(\\.[0-9]{1,2})?")

        /** 仅接受元为单位的十进制数字，最多两位小数；非法格式或越界返回 null，不做舍入。 */
        fun parseAmountFen(input: String): Long? {
            val text = input.trim()
            // 先限制格式和长度，拒绝符号、科学计数法及超长数值，避免猜测用户意图。
            if (!AMOUNT_PATTERN.matches(text)) return null
            val fen = try {
                BigDecimal(text).movePointRight(2).longValueExact()
            } catch (_: ArithmeticException) {
                return null
            }
            return fen.takeIf { it in 1..ExpenseParser.MAX_AMOUNT_FEN }
        }

        /** 前缀与通知的 SHA-256 指纹分开；同一草稿重试复用 UUID，新表单生成新 UUID。 */
        fun fingerprint(entryId: UUID): String = FINGERPRINT_PREFIX + entryId
    }
}
