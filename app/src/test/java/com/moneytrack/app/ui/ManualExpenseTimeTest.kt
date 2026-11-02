package com.moneytrack.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ManualExpenseTimeTest {
    @Test fun `正负时区的手工支出都归入选择的日期`() {
        val date = LocalDate.of(2026, 11, 1)
        val selected = date
        for (zone in listOf(ZoneId.of("Asia/Shanghai"), ZoneId.of("America/Los_Angeles"))) {
            for (hour in listOf(0, 12, 23)) {
                val timestamp = ManualExpenseTime.timestamp(selected.atTime(hour, 30), zone)!!
                val (start, end) = HistoryPeriod.DAY.timestamps(date, zone)
                assertTrue(timestamp in start until end)
            }
        }
    }

    @Test fun `拒绝夏令时跳过的时间而不改动支出时间`() {
        assertNull(ManualExpenseTime.timestamp(LocalDateTime.of(2026, 3, 29, 2, 30), ZoneId.of("Europe/Berlin")))
    }

    @Test fun `夏令时回拨时采用较早偏移`() {
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
            ManualExpenseTime.timestamp(LocalDateTime.of(2026, 10, 25, 2, 30), ZoneId.of("Europe/Berlin")))
    }
}
