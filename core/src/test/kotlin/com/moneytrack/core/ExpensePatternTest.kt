package com.moneytrack.core

import org.junit.Assert.*
import org.junit.Test

class ExpensePatternTest {
    private val pattern = "支出消费{无关文字}{金额}元"
    private val rule = PaymentRule("com.example.pattern", "虚构来源", listOf(pattern), enabled = true)
    private fun parse(text: String, current: PaymentRule = rule) = ExpenseParser().parse(current.packageName, text, current)

    @Test fun `括号和消费文字都必须保留只有说明可变`() {
        val current = rule.copy(expenseKeywords = listOf("支出(消费{无关文字}){金额}元"))
        assertNull(current.validationError())
        for (text in listOf("支出(消费)12元", "支出(消费虚构商户)12元", "提醒\n支出(消费虚构\n说明)12元")) {
            assertEquals(text, 1200L, parse(text, current)?.amountFen)
        }
        for (text in listOf("支出12元", "支出消费12元", "支出消费虚构商户12元", "支出()12元", "消费虚构商户12元")) {
            assertNull(text, parse(text, current))
        }
    }

    @Test fun `扩展句式沿用人民币分精度与千分位边界`() {
        assertEquals(1L, parse("支出消费虚构0.01元")?.amountFen)
        assertEquals(123456L, parse("支出消费虚构1,234.56元")?.amountFen)
        assertEquals(123456L, parse("支出消费虚构1，234.56元")?.amountFen)
        assertEquals(ExpenseParser.MAX_AMOUNT_FEN, parse("支出消费虚构100000000.00元")?.amountFen)
    }

    @Test fun `通配不能吞掉负号货币符号或半截异常数字`() {
        for (value in listOf("-12", "- 12", "−12", "+12", "12.345", "12. 34", "1,23", "1, 23", "￥12.345", "1e2", "1E2", "1e+2", "1e-2", "0x12", "0xFF12", "USD12", "$12", "€12", "₹12", "－12", "＋12", "—12", "0", "100000001", "999999999999999999999")) {
            assertNull(value, parse("支出消费虚构${value}元"))
        }
    }

    @Test fun `扩展句式不跨过或忽略其他金额`() {
        for (text in listOf("支出消费12元，20元", "支出消费12元，12元", "支出消费说明12元以及20元", "支出消费12元，余额800元", "支出消费虚构￥2元说明12元", "支出消费12元支出消费20元", "支出消费12元，USD 20", "支出消费12元，$20", "支出消费12元，20美元")) {
            assertNull(text, parse(text))
        }
    }

    @Test fun `排除词包名和停用仍优先`() {
        assertNull(parse("退款 支出消费虚构12元"))
        assertNull(parse("支出消费收入说明12元"))
        assertNull(parse("支出消费虚构12元", rule.copy(enabled = false)))
        assertNull(ExpenseParser().parse("com.example.other", "支出12元", rule))
    }

    @Test fun `说明最多128字符不使用无界通配`() {
        assertEquals(1200L, parse("支出消费" + "字".repeat(128) + "12元")?.amountFen)
        assertNull(parse("支出消费" + "字".repeat(129) + "12元"))
    }

    @Test fun `占位符格式须明确而括号始终是字面字符`() {
        for (value in listOf("支出{无关文字}{无关文字}{金额}元", "支出{金额}{无关文字}元", "{无关文字}{金额}元", "支出{无关文字}{金额}{金额}元", "支出{无关文字}", "支出{其他}{无关文字}{金额}元")) {
            assertNotNull(value, PaymentRule.expenseKeywordError(value))
        }
        val nested = rule.copy(expenseKeywords = listOf("支出((消费{无关文字})){金额}元"))
        assertNull(nested.validationError())
        assertEquals(1200L, parse("支出((消费虚构))12元", nested)?.amountFen)
        assertNull(parse("支出消费虚构12元", nested))
        val unmatched = rule.copy(expenseKeywords = listOf("支出(消费{无关文字}{金额}元"))
        assertNull(unmatched.validationError())
        assertEquals(1200L, parse("支出(消费虚构12元", unmatched)?.amountFen)
    }

    @Test fun `旧关键词和模板保持字面匹配且不截断数字`() {
        val previous = rule.copy(expenseKeywords = listOf("支出(成功){金额}元"))
        assertEquals(1200L, parse("支出(成功)12元", previous)?.amountFen)
        assertNull(parse("支出成功12元", previous))
        assertNull(parse("支出12元", previous))
        val card = rule.copy(expenseKeywords = listOf("卡1234"))
        assertEquals(1200L, parse("卡1234 ￥12元", card)?.amountFen)
        assertEquals(1200L, parse("卡1234 12元", card)?.amountFen)
        assertNull(parse("卡123412元", card))
    }

    @Test fun `扩展句式其他符号不执行正则`() {
        val current = rule.copy(expenseKeywords = listOf("支出.[完成](消费{无关文字}){金额}元"))
        assertEquals(1200L, parse("支出.[完成](消费虚构)12元", current)?.amountFen)
        assertNull(parse("支出X完成消费虚构12元", current))
    }

    @Test fun `逐条规则内部的逗号作为完整字面内容保留`() {
        val current = rule.copy(expenseKeywords = listOf("spent,done"), excludedKeywords = listOf("refund,done"))
        assertEquals(1250L, parse("spent,done CNY 12.50", current)?.amountFen)
        assertNull(parse("done CNY 12.50", current))
        assertNull(parse("refund,done spent,done CNY 12.50", current))
        assertEquals(1200L, parse("spent，done12元", current)?.amountFen)
    }

    @Test fun `排除词为空或含空白行时不误拦截全部通知`() {
        for (excluded in listOf(emptyList(), listOf("", " ", "\n", " ".repeat(50)))) {
            val current = rule.copy(excludedKeywords = excluded)
            assertNull(current.validationError())
            assertEquals(1200L, parse("支出消费虚构12元", current)?.amountFen)
            assertEquals(1200L, parse("退款 支出消费虚构12元", current)?.amountFen)
        }
        val mixed = rule.copy(excludedKeywords = listOf("", "退款", " "))
        assertEquals(1200L, parse("支出消费虚构12元", mixed)?.amountFen)
        assertNull(parse("退款 支出消费虚构12元", mixed))
        assertNotNull(rule.copy(excludedKeywords = listOf("字".repeat(41))).validationError())
    }

    @Test fun `重叠普通条件不能绕过扩展句式的多金额检查`() {
        val literal = "支出消费这是一个非常非常非常长的虚构商户说明"
        val current = rule.copy(expenseKeywords = listOf(literal, pattern, "支出"))
        assertEquals(1200L, parse(literal + "12元", current)?.amountFen)
        assertNull(parse(literal + "12元，余额800元", current))
    }
}
