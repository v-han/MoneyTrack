package com.moneytrack.core

import org.junit.Assert.*
import org.junit.Test

class ExpenseParserTest {
    private val parser = ExpenseParser()
    private val rule = PaymentRule("com.example.pay", "示例支付", enabled = true)
    private fun parse(text: String) = parser.parse(rule.packageName, text, rule)

    @Test fun `人民币小数精确换算成分`() {
        assertEquals(1250L, parse("支付成功 ￥12.50")?.amountFen)
        assertEquals(10L, parse("支出0.1元")?.amountFen)
        assertEquals(123456L, parse("消费：人民币1,234.56元")?.amountFen)
        assertEquals(2800L, parse("付款成功 CNY 28")?.amountFen)
    }

    @Test fun `只取支出金额不取余额`() {
        assertEquals(1250L, parse("支付成功12.50元，余额800元")?.amountFen)
    }

    @Test fun `退款收入失败与非支出不生成账单`() {
        listOf("退款 支出12元", "收款成功12元", "收入 支出12元", "支付成功12元但失败",
            "待支付12元", "订单123456", "余额12元", "支付成功订单123元")
            .forEach { assertNull(it, parse(it)) }
    }

    @Test fun `拒绝歧义非法精度负数外币和异常金额`() {
        listOf("支出12元，支出20元", "支出12元，支出12元", "支出0元", "支出-12元",
            "支出12.345元", "支出1,23元", "支出1,234.567元", "支出12", "支出12美元",
            "支出999999999999999999999元", "支出100000001元", "支出￥12.34.56")
            .forEach { assertNull(it, parse(it)) }
    }

    @Test fun `包名白名单与停用规则生效`() {
        assertNull(parser.parse("com.other.pay", "支出12元", rule))
        assertNull(parser.parse(rule.packageName, "支出12元", rule.copy(enabled = false)))
        assertNull(parse("支出12元" + "x".repeat(4096)))
    }

    @Test fun `支持自定义关键词且按字面匹配`() {
        val custom = rule.copy(expenseKeywords = listOf("扣款完成", "支出(成功)"))
        assertEquals(900L, parser.parse(rule.packageName, "扣款完成9元", custom)?.amountFen)
        assertEquals(900L, parser.parse(rule.packageName, "支出(成功)9元", custom)?.amountFen)
        assertNull(parser.parse(rule.packageName, "支出成功9元", custom))
    }

    @Test fun `规则输入校验`() {
        assertNotNull(rule.copy(packageName = "bad package").validationError())
        assertNotNull(rule.copy(expenseKeywords = emptyList()).validationError())
        assertEquals(listOf("支付", "消费"), PaymentRule.splitKeywords("支付，消费\n支付, "))
    }

    @Test fun `重复通知指纹稳定但独立同额消费不同`() {
        val first = NotificationIdentity.fingerprint(rule.packageName, "key1", 100, 1200)
        assertEquals(first, NotificationIdentity.fingerprint(rule.packageName, "key1", 100, 1200))
        assertNotEquals(first, NotificationIdentity.fingerprint(rule.packageName, "key2", 100, 1200))
        assertNotEquals(first, NotificationIdentity.fingerprint(rule.packageName, "key1", 101, 1200))
    }
}
