package com.moneytrack.core

/** 一个来源包含多条支出条件与排除词；条件之间是“或”，仅支持受限模板，不执行用户正则。 */
data class PaymentRule(
    val packageName: String,
    val displayName: String,
    val expenseKeywords: List<String> = DEFAULT_EXPENSE_KEYWORDS,
    val excludedKeywords: List<String> = DEFAULT_EXCLUDED_KEYWORDS,
    val enabled: Boolean = false,
) {
    /** 返回可直接展示给用户的错误；null 表示规则可保存。 */
    fun validationError(): String? = when {
        !isValidPackageName(packageName) -> "请输入完整包名，例如 com.example.pay"
        displayName.isBlank() || displayName.length > 40 -> "应用名称需要 1～40 个字符"
        expenseKeywords.isEmpty() -> "请至少填写一条支出条件"
        expenseKeywords.any { it.isBlank() || it.length > 40 } ->
            "每条支出条件需要 1～40 个字符"
        excludedKeywords.any { it.isNotBlank() && it.length > 40 } -> "每个排除词最多 40 个字符"
        expenseKeywords.any { expenseKeywordError(it) != null } ->
            expenseKeywords.firstNotNullOf { expenseKeywordError(it) }
        expenseKeywords.size > 20 || excludedKeywords.count(String::isNotBlank) > 20 -> "每类条件最多 20 条"
        else -> null
    }

    companion object {
        const val AMOUNT_PLACEHOLDER = "{金额}"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val DEFAULT_EXPENSE_KEYWORDS = listOf("支付成功", "付款成功", "成功付款", "支出", "消费")
        val DEFAULT_EXCLUDED_KEYWORDS = listOf("退款", "收款", "收入", "到账", "失败", "取消", "待支付", "待付款")

        /** 识别规则与联动规则共用精确来源包名校验。 */
        fun isValidPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

        /** 单条编辑行与整份规则使用同一语法校验。 */
        fun expenseKeywordError(value: String): String? =
            if (value.isBlank() || value.length > 40) "每条支出条件需要 1～40 个字符"
            else ExpensePattern.validationError(value)

        /** 兼容旧版分隔输入的辅助方法；新版逐条编辑直接保存列表，不调用此方法。 */
        fun splitKeywords(value: String): List<String> =
            value.split(',', '，', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
