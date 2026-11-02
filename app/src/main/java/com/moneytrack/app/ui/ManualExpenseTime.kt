package com.moneytrack.app.ui

import java.time.LocalDateTime
import java.time.ZoneId

/** 将滚轮选择的本地日期和时间转换成入账时间戳。 */
object ManualExpenseTime {
    /** 夏令时跳过的本地时间不自动挪动；回拨重叠时间采用系统默认的较早偏移。 */
    fun timestamp(time: LocalDateTime, zone: ZoneId): Long? {
        if (zone.rules.getValidOffsets(time).isEmpty()) return null
        return time.atZone(zone).toInstant().toEpochMilli()
    }
}
