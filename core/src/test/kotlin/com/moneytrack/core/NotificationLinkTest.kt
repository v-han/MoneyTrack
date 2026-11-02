package com.moneytrack.core

import org.junit.Assert.*
import org.junit.Test

class NotificationLinkTest {
    private val rule = NotificationLinkRule("com.example.wallet", "com.example.bank", enabled = true)
    private fun main(time: Long = 100_000, amount: Long = 1250) = LinkNotification(rule.mainPackageName, amount, time)
    private fun bank(id: Long, time: Long, amount: Long = 1250) = LinkCandidate(id, LinkNotification(rule.secondaryPackageName, amount, time))

    @Test fun matchesBothArrivalOrdersAndWindowBoundaries() {
        assertEquals(1L, NotificationLinkMatcher.find(main(), listOf(bank(1, 70_000)), listOf(rule))?.expenseId)
        assertEquals(2L, NotificationLinkMatcher.find(main(), listOf(bank(2, 130_000)), listOf(rule))?.expenseId)
        assertNull(NotificationLinkMatcher.find(main(), listOf(bank(1, 69_999), bank(2, 130_001)), listOf(rule)))
        assertEquals(3L, NotificationLinkMatcher.find(bank(0, 120_000).notification, listOf(LinkCandidate(3, main())), listOf(rule))?.expenseId)
    }

    @Test fun prefersBeforeMainThenNearestThenStableId() {
        val candidates = listOf(bank(1, 100_001), bank(3, 99_000), bank(2, 99_000), bank(4, 90_000))
        assertEquals(2L, NotificationLinkMatcher.find(main(), candidates, listOf(rule))?.expenseId)
        val incoming = bank(0, 100_000).notification
        assertEquals(6L, NotificationLinkMatcher.find(incoming,
            listOf(LinkCandidate(5, main(99_999)), LinkCandidate(6, main(105_000))), listOf(rule))?.expenseId)
    }

    @Test fun requiresEnabledExactPairAndEqualPositiveAmount() {
        assertNull(NotificationLinkMatcher.find(main(), listOf(bank(1, 100_000)), listOf(rule.copy(enabled = false))))
        assertNull(NotificationLinkMatcher.find(main(), listOf(bank(1, 100_000, 1251)), listOf(rule)))
        assertNull(NotificationLinkMatcher.find(main(), listOf(LinkCandidate(1, main())), listOf(rule)))
        assertNull(NotificationLinkMatcher.find(main(), listOf(LinkCandidate(1, LinkNotification("com.example.bank.extra",1250,100_000))), listOf(rule)))
        assertNull(NotificationLinkMatcher.find(main(amount = 0), listOf(bank(1, 100_000, 0)), listOf(rule)))
    }

    @Test fun handlesExtremeTimesWithoutOverflow() {
        assertNull(NotificationLinkMatcher.find(main(Long.MAX_VALUE), listOf(bank(1, 0)), listOf(rule)))
        assertNull(NotificationLinkMatcher.find(main(-1), listOf(bank(1, 0)), listOf(rule)))
        assertEquals(1L, NotificationLinkMatcher.find(main(Long.MAX_VALUE), listOf(bank(1, Long.MAX_VALUE - 1000)), listOf(rule))?.expenseId)
    }

    @Test fun validatesWindowPairAndNoActiveChains() {
        assertNull(rule.validationError())
        assertNotNull(rule.copy(windowSeconds = 0).validationError())
        assertNotNull(rule.copy(windowSeconds = 301).validationError())
        assertNotNull(rule.copy(secondaryPackageName = rule.mainPackageName).validationError())
        assertNotNull(NotificationLinkRule.collectionError(listOf(rule, rule)))
        val chain = NotificationLinkRule(rule.secondaryPackageName,"com.example.other", enabled = true)
        assertNotNull(NotificationLinkRule.collectionError(listOf(rule, chain)))
        assertNull(NotificationLinkRule.collectionError(listOf(rule, chain.copy(enabled = false))))
        assertNull(NotificationLinkRule.collectionError(listOf(rule, rule.copy(secondaryPackageName = "com.example.other"))))
    }
}
