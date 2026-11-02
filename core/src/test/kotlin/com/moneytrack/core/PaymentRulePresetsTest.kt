package com.moneytrack.core

import org.junit.Assert.*
import org.junit.Test

class PaymentRulePresetsTest {
    private val parser = ExpenseParser()
    private val alipay = PaymentRulePresets.forPackage(PaymentRulePresets.ALIPAY_PACKAGE)!!.copy(enabled = true)
    private val wechat = PaymentRulePresets.forPackage(PaymentRulePresets.WECHAT_PACKAGE)!!.copy(enabled = true)
    private fun parse(text: String, rule: PaymentRule = alipay) = parser.parse(rule.packageName, text, rule)

    @Test fun `按提供格式匹配支付宝与微信的虚构支出`() {
        assertEquals(1250L, parse("你有一笔12.50元支出，虚构商户")?.amountFen)
        assertEquals(880L, parse("已支付8.80元，虚构商户", wechat)?.amountFen)
        assertEquals(123456L, parse("你有一笔1,234.56元支出，虚构商户")?.amountFen)
        assertEquals(1L, parse("已支付0.01元，虚构商户", wechat)?.amountFen)
        assertEquals(10_000_000_000L, parse("你有一笔100000000.00元支出，虚构商户")?.amountFen)
    }

    @Test fun `支付宝带的字句式可与交易提醒标题一起识别`() {
        // 使用虚构金额和说明，只复现截图中的句式，不保存用户的真实通知。
        assertEquals(2L, parse("交易提醒\n你有一笔0.02元的支出，虚构测试")?.amountFen)
        assertEquals(123456L, parse("你有一笔1,234.56元的支出，虚构测试")?.amountFen)
        assertEquals("你有一笔{金额}元的支出", parse("你有一笔8元的支出，虚构测试")?.matchedKeyword)
    }

    @Test fun `支付宝完整句式缺少支出后缀或人民币单位不猜测`() {
        listOf("你有一笔12.50元收入，虚构说明", "你有一笔12.50元，虚构说明", "你有一笔12.50支出，虚构说明",
            "你有一笔12.50美元支出，虚构说明", "你有一笔12.50元元支出，虚构说明",
            "你有一笔12.50元的收入，虚构说明", "你有一笔12.50的支出，虚构说明",
            "你有一笔12.50元元的支出，虚构说明")
            .forEach { assertNull(it, parse(it)) }
    }

    @Test fun `新格式仍优先排除退款收入失败`() {
        listOf("你有一笔12.50元支出，退款", "你有一笔12.50元支出，收入", "你有一笔12.50元支出，失败",
            "你有一笔12.50元的支出，退款", "你有一笔12.50元的支出，收入", "你有一笔12.50元的支出，失败")
            .forEach { assertNull(it, parse(it)) }
        listOf("已支付12.50元，退款", "已支付12.50元，收入", "已支付12.50元，失败", "待付款，已支付12.50元")
            .forEach { assertNull(it, parse(it, wechat)) }
    }

    @Test fun `新格式拒绝负数零非法精度千分位和溢出`() {
        listOf("0", "-12.50", "12.501", "1,23", "100000000.01", "99999999999999999999999.99")
            .forEach { amount ->
                assertNull(amount, parse("你有一笔${amount}元支出，虚构说明"))
                assertNull(amount, parse("你有一笔${amount}元的支出，虚构说明"))
                assertNull(amount, parse("已支付${amount}元，虚构说明", wechat))
            }
    }

    @Test fun `多笔金额和模板后紧接另一支出都拒绝`() {
        listOf("你有一笔12元支出，另有你有一笔20元支出", "你有一笔12元支出，另有你有一笔12元支出",
            "你有一笔12元支出12元", "支出12元，你有一笔8元支出",
            "你有一笔12元的支出，另有你有一笔12元的支出", "你有一笔12元的支出12元",
            "你有一笔12元的支出，另有你有一笔8元支出")
            .forEach { assertNull(it, parse(it)) }
        assertNull(parse("已支付12元，已支付12元", wechat))
        assertNull(parse("已支付12元，支出8元", wechat))
    }

    @Test fun `重叠关键词匹配同一个金额只算一笔`() {
        val aliases = wechat.copy(expenseKeywords = listOf("支付", "已支付"))
        val result = parse("已支付12.50元，虚构说明", aliases)
        assertEquals(1250L, result?.amountFen)
        assertEquals("已支付", result?.matchedKeyword)
    }

    @Test fun `预设默认停用且仍严格按包名匹配`() {
        for (rule in listOf(alipay, wechat)) {
            val preset = PaymentRulePresets.forPackage(rule.packageName)!!
            assertFalse(preset.enabled)
            assertNull(parser.parse(preset.packageName, "已支付12元，你有一笔12元支出", preset))
            assertNull(parser.parse("com.example.fake", "你有一笔12元支出，已支付12元", rule))
        }
        assertNull(PaymentRulePresets.forPackage("com.example.fake"))
    }

    @Test fun `模板前后文字按字面处理且需要一个金额占位`() {
        val rule = alipay.copy(expenseKeywords = listOf("付款(成功){金额}元支出"))
        assertEquals(1234L, parse("付款(成功)12.34元支出", rule)?.amountFen)
        assertNull(parse("付款成功12.34元支出", rule))
        for (keyword in listOf("{金额}", "你有一笔{金额}", "{金额}元支出", "你有一笔{金额}{金额}元支出")) {
            assertNotNull(keyword, rule.copy(expenseKeywords = listOf(keyword)).validationError())
        }
    }

    @Test fun `升级旧默认只更新关键词保留用户设置`() {
        val old = PaymentRule(PaymentRulePresets.ALIPAY_PACKAGE, "我的虚构来源",
            excludedKeywords = listOf("退款", "自定义排除"), enabled = true)
        val updated = PaymentRulePresets.updateLegacyDefaults(old)
        assertTrue(updated.expenseKeywords.contains("你有一笔{金额}元支出"))
        assertEquals(old.displayName, updated.displayName)
        assertEquals(old.excludedKeywords, updated.excludedKeywords)
        assertEquals(old.enabled, updated.enabled)
        val custom = old.copy(expenseKeywords = listOf("自定义扣款"))
        assertEquals(custom, PaymentRulePresets.updateLegacyDefaults(custom))
        val other = PaymentRule("com.example.other", "其他来源")
        assertEquals(other, PaymentRulePresets.updateLegacyDefaults(other))
        assertEquals(updated, PaymentRulePresets.updateLegacyDefaults(updated))
    }

    @Test fun `v3支付宝默认补上带的字句式且不覆盖自定义关键词`() {
        val v3Keywords = listOf("你有一笔{金额}元支出") + PaymentRule.DEFAULT_EXPENSE_KEYWORDS
        for (enabled in listOf(false, true)) {
            val previous = PaymentRule(PaymentRulePresets.ALIPAY_PACKAGE, "虚构别名", v3Keywords,
                listOf("退款", "自定义排除"), enabled)
            val updated = PaymentRulePresets.updateLegacyDefaults(previous)
            assertTrue(updated.expenseKeywords.contains("你有一笔{金额}元的支出"))
            assertTrue(updated.expenseKeywords.contains("你有一笔{金额}元支出"))
            assertEquals(previous.copy(expenseKeywords = updated.expenseKeywords), updated)
            assertEquals(updated, PaymentRulePresets.updateLegacyDefaults(updated))
            val customized = previous.copy(expenseKeywords = v3Keywords + "自定义扣款")
            assertEquals(customized, PaymentRulePresets.updateLegacyDefaults(customized))
        }
    }
}
