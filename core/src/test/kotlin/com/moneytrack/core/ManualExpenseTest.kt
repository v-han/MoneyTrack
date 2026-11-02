package com.moneytrack.core

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ManualExpenseTest {
    @Test fun `手工金额精确换算成人民币分`() {
        assertEquals(1L, ManualExpense.parseAmountFen("0.01"))
        assertEquals(10L, ManualExpense.parseAmountFen("0.1"))
        assertEquals(1250L, ManualExpense.parseAmountFen("12.50"))
        assertEquals(1200L, ManualExpense.parseAmountFen(" 12 "))
        assertEquals(1250L, ManualExpense.parseAmountFen("0012.50"))
        assertEquals(10_000_000_000L, ManualExpense.parseAmountFen("100000000.00"))
    }

    @Test fun `拒绝零负数超限和溢出金额`() {
        listOf("0", "0.00", "-12.50", "+12.50", "100000000.01", "100000001",
            "9999999999999999999999999999.99").forEach { assertNull(it, ManualExpense.parseAmountFen(it)) }
    }

    @Test fun `不截断非法小数或猜测金额格式`() {
        listOf("", " ", "12.501", "0.001", "12.", ".50", "1,234.56", "1,23", "12,50",
            "￥12.50", "12.50元", "1e2", "1 2", "12\n50", "１２.５０", "NaN", "Infinity")
            .forEach { assertNull(it, ManualExpense.parseAmountFen(it)) }
    }

    @Test fun `模型阻止绕过表单的无效记录`() {
        val expense = ManualExpense(UUID.randomUUID(), 1250, "虚构现金", 1)
        assertNull(expense.validationError())
        assertNotNull(expense.copy(amountFen = 0).validationError())
        assertNotNull(expense.copy(amountFen = ExpenseParser.MAX_AMOUNT_FEN + 1).validationError())
        assertNotNull(expense.copy(sourceName = " ").validationError())
        assertNotNull(expense.copy(sourceName = "x".repeat(41)).validationError())
        assertNotNull(expense.copy(occurredAt = 0).validationError())
    }

    @Test fun `草稿重试身份稳定而独立同额记录身份不同`() {
        val firstId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val first = ManualExpense(firstId, 1250, "虚构现金", 1000)
        val second = first.copy(entryId = secondId)
        assertEquals(ManualExpense.fingerprint(first.entryId), ManualExpense.fingerprint(first.copy().entryId))
        assertNotEquals(ManualExpense.fingerprint(first.entryId), ManualExpense.fingerprint(second.entryId))
        assertNotEquals(ManualExpense.fingerprint(first.entryId), NotificationIdentity.fingerprint("com.example.pay", "test", 1000, 1250))
        assertTrue(ManualExpense.fingerprint(first.entryId).startsWith(ManualExpense.FINGERPRINT_PREFIX))
    }
}
