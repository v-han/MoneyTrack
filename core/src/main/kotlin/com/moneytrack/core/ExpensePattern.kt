package com.moneytrack.core

/** 只编译金额与无关文字占位符；用户输入的其余字符全部转义，不执行用户正则。 */
internal object ExpensePattern {
    const val IGNORE_PLACEHOLDER = "{无关文字}"
    private const val AMOUNT_PATTERN = "\\s*:?\\s*(?:人民币\\s*)?(?<currency>[¥￥]|CNY)?\\s*" +
        "(?<![0-9.,+\\-−])(?<integer>[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?<fraction>\\.[0-9]{1,2})?(?![0-9.,])"

    data class Compiled(val regex: Regex, val literalUnit: Boolean, val extended: Boolean)

    /** 仅 {无关文字} 可匹配变化的说明，括号和其他固定字符都按字面匹配。 */
    fun validationError(value: String): String? {
        val parts = value.split(PaymentRule.AMOUNT_PLACEHOLDER)
        val extended = value.contains(IGNORE_PLACEHOLDER)
        if (parts.size == 1) return if (extended) "{无关文字}需要与一个{金额}配合使用" else null
        if (parts.size != 2 || parts.any(String::isBlank)) return "句式须有一个{金额}，且前后都有文字"
        if (!extended) return null
        if (value.split(IGNORE_PLACEHOLDER).size != 2 || !parts[0].contains(IGNORE_PLACEHOLDER)) {
            return "每条句式只支持一个{无关文字}，放在金额之前"
        }
        if (parts[1].any { it in "{}" } || wildcardPrefix(parts[0]) == null) {
            return "无关文字外须有固定文字，不支持其他花括号占位符"
        }
        return null
    }

    /** 金额使用命名分组；说明变化不影响金额提取，固定部分始终转义。 */
    fun compile(value: String): Compiled {
        require(validationError(value) == null)
        val parts = normalize(value).split(PaymentRule.AMOUNT_PLACEHOLDER)
        val suffix = parts.getOrElse(1) { "" }
        val extended = value.contains(IGNORE_PLACEHOLDER)
        val prefix = if (extended) wildcardPrefix(parts[0])!! else Regex.escape(parts[0])
        val literalUnit = suffix.startsWith("元") || suffix.startsWith("CNY", ignoreCase = true)
        val ending = if (literalUnit) "\\s*" else "\\s*(?<unit>元|CNY)?"
        return Compiled(Regex(prefix + AMOUNT_PATTERN + ending + Regex.escape(suffix), RegexOption.IGNORE_CASE), literalUnit, extended)
    }

    /** 通知与条件使用同一标点归一化，逐条条件里的中文逗号仍可按字面命中。 */
    fun normalize(value: String): String = value.replace('，', ',').replace('：', ':').replace('\u00a0', ' ')

    private fun wildcardPrefix(value: String): String? {
        val parts = value.split(IGNORE_PLACEHOLDER)
        if (parts.size != 2 || parts.all(String::isBlank) || parts.any { part -> part.any { it in "{}" } }) return null
        // 只替换占位符，括号等文字整体转义；最长 128 字符，避免无界通配及任意正则。
        return Regex.escape(parts[0]) + "(?<ignored>[\\s\\S]{0,128}?)" + Regex.escape(parts[1])
    }
}
