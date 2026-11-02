package com.moneytrack.app.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 使用设备时区的自然日、周一到周日和自然月；不能用固定毫秒数计算跨夏令时日期。 */
enum class HistoryPeriod {
    DAY, WEEK, MONTH;

    /** 返回归一化后的区间起点和下一段起点，后者不包含在查询结果中。 */
    fun dates(anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val start = when (this) {
            DAY -> anchor
            WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            MONTH -> anchor.withDayOfMonth(1)
        }
        val end = when (this) {
            DAY -> start.plusDays(1)
            WEEK -> start.plusWeeks(1)
            MONTH -> start.plusMonths(1)
        }
        return start to end
    }

    /** 相邻区间从归一化起点移动，避免 1 月 31 日加月后再减月产生漂移。 */
    fun move(anchor: LocalDate, direction: Long): LocalDate {
        val start = dates(anchor).first
        return when (this) {
            DAY -> start.plusDays(direction)
            WEEK -> start.plusWeeks(direction)
            MONTH -> start.plusMonths(direction)
        }
    }

    /** 先按本地日期取边界，再转换成时间戳。 */
    fun timestamps(anchor: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val (start, end) = dates(anchor)
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to end.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
