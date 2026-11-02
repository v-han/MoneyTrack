package com.moneytrack.core

import java.security.MessageDigest

/**
 * 使用系统通知标识、发送时间和金额去重，不使用“金额 + 分钟”以免吞掉连续同额消费。
 * 数据库只保存摘要，不保存可能包含账号的通知 key。
 * 发送方若更新了时间/ID，仍可能产生另一候选记录，因此需要用户确认。
 * 用户另行启用主副联动时可让两个来源归于同一账单，但仍须分别保留两个摘要身份。
 */
object NotificationIdentity {
    fun fingerprint(packageName: String, key: String, eventTime: Long, amountFen: Long): String {
        val parts = listOf(packageName, key, eventTime.toString(), amountFen.toString())
        val payload = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
