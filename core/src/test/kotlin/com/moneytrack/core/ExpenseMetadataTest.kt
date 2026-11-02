package com.moneytrack.core

import org.junit.Assert.*
import org.junit.Test

class ExpenseMetadataTest {
    @Test fun `备注和标签可以为空`() {
        assertNull(ExpenseMetadata().validationError())
        assertNull(ExpenseMetadata("虚构测试备注", ReimbursementStatus.REIMBURSED, listOf(1, 2)).validationError())
    }

    @Test fun `备注长度边界不截断用户文字`() {
        assertNull(ExpenseMetadata(note = "字".repeat(500)).validationError())
        assertNotNull(ExpenseMetadata(note = "字".repeat(501)).validationError())
    }

    @Test fun `最多八个有效且独立的标签`() {
        assertNull(ExpenseMetadata(tagIds = (1L..8L).toList()).validationError())
        assertNotNull(ExpenseMetadata(tagIds = (1L..9L).toList()).validationError())
        assertNotNull(ExpenseMetadata(tagIds = listOf(1, 1)).validationError())
        assertNotNull(ExpenseMetadata(tagIds = listOf(0)).validationError())
        assertNotNull(ExpenseMetadata(tagIds = listOf(-1)).validationError())
    }

    @Test fun `报销标记使用稳定的数据库值`() {
        for (status in ReimbursementStatus.entries) {
            assertEquals(status, ReimbursementStatus.fromDatabase(status.databaseValue))
        }
        assertEquals(ReimbursementStatus.NONE, ReimbursementStatus.fromDatabase("unknown"))
    }

    @Test fun `无报销与待报销计全额且已报销计零`() {
        assertEquals(1250L, ExpenseMetadata().countedExpenseFen(1250))
        assertEquals(1250L, ExpenseMetadata(reimbursement = ReimbursementStatus.PENDING).countedExpenseFen(1250))
        assertEquals(0L, ExpenseMetadata(reimbursement = ReimbursementStatus.REIMBURSED).countedExpenseFen(1250))
    }

    @Test fun `部分报销保留精确到分的未报销支出`() {
        val metadata = ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = 901)
        assertNull(metadata.validationError(1250))
        assertEquals(349L, metadata.countedExpenseFen(1250))
        assertEquals(1L, metadata.copy(reimbursedAmountFen = ExpenseParser.MAX_AMOUNT_FEN - 1)
            .countedExpenseFen(ExpenseParser.MAX_AMOUNT_FEN))
        assertEquals(1L, metadata.copy(reimbursedAmountFen = 1).countedExpenseFen(2))
    }

    @Test fun `持久化拒绝尚未归一的全额超额或非法部分报销`() {
        for (amount in listOf(-1L, 0L, 1250L, 1251L, Long.MAX_VALUE)) {
            assertNotNull(ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = amount)
                .validationError(1250))
        }
    }

    @Test fun `其他报销状态不能遗留抵扣金额`() {
        for (status in listOf(ReimbursementStatus.NONE, ReimbursementStatus.PENDING, ReimbursementStatus.REIMBURSED)) {
            assertNotNull(ExpenseMetadata(reimbursement = status, reimbursedAmountFen = 100).validationError(1250))
            assertNull(ExpenseMetadata(reimbursement = status).validationError(1250))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法报销不能产生负支出`() {
        ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = 1251).countedExpenseFen(1250)
    }

    @Test fun `状态切换后按新的口径重新计算且原金额不变`() {
        val metadata = ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = 500)
        assertEquals(750L, metadata.countedExpenseFen(1250))
        assertEquals(0L, metadata.copy(reimbursement = ReimbursementStatus.REIMBURSED, reimbursedAmountFen = 0).countedExpenseFen(1250))
        assertEquals(1250L, metadata.copy(reimbursement = ReimbursementStatus.NONE, reimbursedAmountFen = 0).countedExpenseFen(1250))
    }

    @Test fun `结束编辑时达到或超过支出转为已报销`() {
        for (amount in listOf(1250L, 1251L, ExpenseParser.MAX_AMOUNT_FEN)) {
            val result = ExpenseMetadata("虚构备注", ReimbursementStatus.PARTIAL, listOf(1), amount)
                .completeReimbursementIfCovered(1250)
            assertEquals(ReimbursementStatus.REIMBURSED, result.reimbursement)
            assertEquals(0L, result.reimbursedAmountFen)
            assertEquals(0L, result.countedExpenseFen(1250))
            assertEquals("虚构备注", result.note)
            assertEquals(listOf(1L), result.tagIds)
        }
    }

    @Test fun `未达到原金额保持部分报销与分精度`() {
        val metadata = ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = 1249)
        assertEquals(metadata, metadata.completeReimbursementIfCovered(1250))
        assertEquals(1L, metadata.completeReimbursementIfCovered(1250).countedExpenseFen(1250))
    }

    @Test fun `非法金额不通过自动转换掩盖错误`() {
        for (amount in listOf(-1L, 0L, Long.MAX_VALUE)) {
            val metadata = ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = amount)
            assertEquals(metadata, metadata.completeReimbursementIfCovered(1250))
            assertNotNull(metadata.validationError(1250))
        }
    }

    @Test fun `其他状态和未知原支出不自动改变`() {
        for (status in listOf(ReimbursementStatus.NONE, ReimbursementStatus.PENDING, ReimbursementStatus.REIMBURSED)) {
            val metadata = ExpenseMetadata(reimbursement = status)
            assertEquals(metadata, metadata.completeReimbursementIfCovered(1250))
        }
        val metadata = ExpenseMetadata(reimbursement = ReimbursementStatus.PARTIAL, reimbursedAmountFen = 1250)
        assertEquals(metadata, metadata.completeReimbursementIfCovered(0))
    }
}
