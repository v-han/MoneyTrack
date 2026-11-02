package com.moneytrack.core

/** 根据用户提供的通知格式建立的可编辑预设；不是平台 API，也不代表真机已经验证。 */
object PaymentRulePresets {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    // v3 曾只配置不带“的”的句式；保留完整旧列表，用于识别可安全补齐的旧默认配置。
    private val ALIPAY_V3_KEYWORDS = listOf("你有一笔{金额}元支出") + PaymentRule.DEFAULT_EXPENSE_KEYWORDS

    /** 按精确包名返回默认关闭的规则，其他来源不套用这些平台预设。 */
    fun forPackage(packageName: String): PaymentRule? = when (packageName) {
        WECHAT_PACKAGE -> PaymentRule(packageName, "微信",
            expenseKeywords = listOf("已支付") + PaymentRule.DEFAULT_EXPENSE_KEYWORDS)
        ALIPAY_PACKAGE -> PaymentRule(packageName, "支付宝",
            expenseKeywords = listOf("你有一笔{金额}元的支出") + ALIPAY_V3_KEYWORDS)
        else -> null
    }

    /** 仅更新与已知旧版默认列表完全一致的规则，保留自定义关键词、名称、排除词与启停选择。 */
    fun updateLegacyDefaults(rule: PaymentRule): PaymentRule {
        val preset = forPackage(rule.packageName) ?: return rule
        val usesOldDefaults = rule.expenseKeywords == PaymentRule.DEFAULT_EXPENSE_KEYWORDS ||
            (rule.packageName == ALIPAY_PACKAGE && rule.expenseKeywords == ALIPAY_V3_KEYWORDS)
        if (!usesOldDefaults) return rule
        return rule.copy(expenseKeywords = preset.expenseKeywords)
    }
}
