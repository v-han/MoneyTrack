package com.moneytrack.core

import java.math.BigDecimal

/** 金额用“分”的 Long 保存。Double 的二进制小数不能精确表示所有金额。 */
data class ParsedExpense(val amountFen: Long, val matchedKeyword: String)

/**
 * 保守解析器：接受关键词、金额模板和带有受限无关文字的扩展句式。
 * 不从订单号、余额或一条通知中的多笔交易猜测支出；无法确定时返回 null。
 * 此类不依赖 Android，因此单元测试与手机上的解析逻辑完全相同。
 */
class ExpenseParser {
    /** 仅解析已启用且包名精确匹配的来源；格式异常或无法唯一确定金额时返回 null。 */
    fun parse(packageName: String, text: String, rule: PaymentRule): ParsedExpense? {
        if (!rule.enabled || rule.packageName != packageName || rule.validationError() != null) return null
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return null
        val normalized = ExpensePattern.normalize(text)
        if (rule.excludedKeywords.any { it.isNotBlank() && normalized.contains(ExpensePattern.normalize(it), ignoreCase = true) }) return null

        val matches = rule.expenseKeywords.sortedByDescending(String::length).flatMap { keyword ->
            val pattern = ExpensePattern.compile(keyword)
            pattern.regex.findAll(normalized).map { AmountMatch(keyword, it, pattern.literalUnit, pattern.extended) }.toList()
        // 同一金额同时命中普通与扩展条件时，保留扩展条件的严格歧义检查。
        }.sortedByDescending { it.extended }.distinctBy { it.result.groups["integer"]!!.range.first }
        // 分别搜索，避免模板消费掉后缀“支出”时漏掉紧接着的另一笔。
        // 重叠关键词命中同一数字起点只算一次；不同位置的同额金额仍算两笔。
        if (matches.size != 1) return null
        val candidate = matches.single()
        val match = candidate.result
        if (!candidate.literalUnit && match.groups["currency"]?.value.isNullOrEmpty() && match.groups["unit"]?.value.isNullOrEmpty()) return null
        if (candidate.extended) {
            val ignored = match.groups["ignored"]?.value.orEmpty()
            // 通配段不能吞掉负号、货币符号或半截数字后，再把后面的数当成合法支出。
            if (AMBIGUOUS_END.containsMatchIn(ignored)) return null
            val integer = match.groups["integer"]!!.range
            val last = match.groups["fraction"]?.range?.last ?: integer.last
            // 扩展句式不允许通配段跨过其他金额，也不猜测额外金额是不是余额。
            if (MONEY_LIKE.findAll(normalized).any { it.range.last < integer.first || it.range.first > last }) return null
        }
        val amount = match.groups["integer"]!!.value.replace(",", "") + match.groups["fraction"]?.value.orEmpty()
        val fen = try {
            BigDecimal(amount).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
        // 首版仅处理人民币正数，单笔最高一亿元，避免异常文本和溢出。
        if (fen !in 1..MAX_AMOUNT_FEN) return null
        return ParsedExpense(fen, candidate.keyword)
    }

    private data class AmountMatch(val keyword: String, val result: MatchResult, val literalUnit: Boolean, val extended: Boolean)

    companion object {
        private val AMBIGUOUS_END = Regex("""(?:[+\-−＋－–—\p{Sc}]|CNY|USD|HKD|EUR|[0-9][.,eExXbBoO]|0[xX][0-9a-fA-F]*|0[bB][01]*)\s*$|[0-9]\s+$""", RegexOption.IGNORE_CASE)
        private val MONEY_LIKE = Regex("""(?:\p{Sc}|CNY|USD|EUR|HKD)\s*[+\-−＋－]?\s*[0-9][0-9.,]*\s*(?:元|CNY|USD|EUR|HKD)?|[+\-−＋－]?\s*[0-9][0-9.,]*\s*(?:元|CNY|USD|EUR|HKD|美元|港元|欧元)""", RegexOption.IGNORE_CASE)
        const val MAX_TEXT_LENGTH = 4096
        const val MAX_AMOUNT_FEN = 10_000_000_000L
    }
}
