package com.moneytrack.app.ui

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPeriodTest {
    @Test fun `自然日跨闰年二月边界`() {
        assertEquals(LocalDate.of(2024, 2, 29) to LocalDate.of(2024, 3, 1), HistoryPeriod.DAY.dates(LocalDate.of(2024, 2, 29)))
    }

    @Test fun `自然周从周一开始并跨年`() {
        assertEquals(LocalDate.of(2025, 12, 29) to LocalDate.of(2026, 1, 5), HistoryPeriod.WEEK.dates(LocalDate.of(2026, 1, 4)))
        assertEquals(LocalDate.of(2026, 1, 5) to LocalDate.of(2026, 1, 12), HistoryPeriod.WEEK.dates(LocalDate.of(2026, 1, 5)))
    }

    @Test fun `自然月覆盖闰年二月且移动不漂移`() {
        assertEquals(LocalDate.of(2024, 2, 1) to LocalDate.of(2024, 3, 1), HistoryPeriod.MONTH.dates(LocalDate.of(2024, 2, 29)))
        val next = HistoryPeriod.MONTH.move(LocalDate.of(2026, 1, 31), 1)
        assertEquals(LocalDate.of(2026, 2, 1), next)
        assertEquals(LocalDate.of(2026, 1, 1), HistoryPeriod.MONTH.move(next, -1))
    }

    @Test fun `夏令时日界不能固定为二十四小时`() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = HistoryPeriod.DAY.timestamps(LocalDate.of(2026, 3, 29), zone)
        val autumn = HistoryPeriod.DAY.timestamps(LocalDate.of(2026, 10, 25), zone)
        assertEquals(23 * 60 * 60 * 1000L, spring.second - spring.first)
        assertEquals(25 * 60 * 60 * 1000L, autumn.second - autumn.first)
    }

    @Test fun `相邻区间边界相接无重叠`() {
        val zone = ZoneId.of("Asia/Shanghai")
        for (period in HistoryPeriod.entries) {
            val anchor = LocalDate.of(2026, 12, 31)
            val first = period.timestamps(anchor, zone)
            val second = period.timestamps(period.move(anchor, 1), zone)
            assertEquals(first.second, second.first)
        }
    }
}
