package com.moneytrack.core

/** 用户明确指定的主副通知组合；窗口为主通知前后各多少秒，默认关闭。 */
data class NotificationLinkRule(
    val mainPackageName: String,
    val secondaryPackageName: String,
    val windowSeconds: Int = 30,
    val enabled: Boolean = false,
) {
    /** 不允许同一来源与自己联动，时间范围限制为 1～300 秒。 */
    fun validationError(): String? = when {
        !PaymentRule.isValidPackageName(mainPackageName) || !PaymentRule.isValidPackageName(secondaryPackageName) -> "请选择完整的主副应用来源"
        mainPackageName == secondaryPackageName -> "主通知与副通知不能是同一应用"
        windowSeconds !in 1..300 -> "联动范围需要 1～300 秒"
        else -> null
    }

    companion object {
        /** 禁止启用互相交叉的主副角色，避免一条通知沿多个规则串联合并。 */
        fun collectionError(rules: List<NotificationLinkRule>): String? = when {
            rules.size > 100 -> "最多配置 100 条联动"
            rules.any { it.validationError() != null } -> rules.firstNotNullOf { it.validationError() }
            rules.map { it.mainPackageName to it.secondaryPackageName }.distinct().size != rules.size -> "存在重复的主副通知组合"
            rules.filter { it.enabled }.let { active ->
                active.map { it.mainPackageName }.toSet().intersect(active.map { it.secondaryPackageName }.toSet()).isNotEmpty()
            } -> "同一应用不能同时作为已启用联动的主来源与副来源"
            else -> null
        }
    }
}

/** 只携带来源、分金额和系统投递时间；不需要通知原文。 */
data class LinkNotification(val packageName: String, val amountFen: Long, val postedAt: Long)

/** 数据层仅提供仍待确认、尚未联动的单通知账单。 */
data class LinkCandidate(val expenseId: Long, val notification: LinkNotification)

/** 在已收到的通知中一对一选择；先选主通知之前的副通知，再按距离和账单 ID 排序。 */
object NotificationLinkMatcher {
    /** 返回可附加本次通知的单个账单；无有效主副来源、同额或窗口命中时返回 null。 */
    fun find(incoming: LinkNotification, candidates: List<LinkCandidate>, rules: List<NotificationLinkRule>): LinkCandidate? {
        if (incoming.amountFen <= 0 || incoming.postedAt < 0) return null
        data class Match(val candidate: LinkCandidate, val afterMain: Boolean, val distance: Long)
        return candidates.mapNotNull { candidate ->
            val other = candidate.notification
            if (other.amountFen != incoming.amountFen || other.postedAt < 0) return@mapNotNull null
            val rule = rules.firstOrNull {
                it.enabled && it.validationError() == null &&
                    ((incoming.packageName == it.mainPackageName && other.packageName == it.secondaryPackageName) ||
                        (incoming.packageName == it.secondaryPackageName && other.packageName == it.mainPackageName))
            } ?: return@mapNotNull null
            // 时间非负，较大减较小不会发生 Long 溢出；不使用 abs(a-b)。
            val distance = maxOf(incoming.postedAt, other.postedAt) - minOf(incoming.postedAt, other.postedAt)
            if (distance > rule.windowSeconds * 1000L) return@mapNotNull null
            val mainTime = if (incoming.packageName == rule.mainPackageName) incoming.postedAt else other.postedAt
            val secondaryTime = if (incoming.packageName == rule.secondaryPackageName) incoming.postedAt else other.postedAt
            Match(candidate, secondaryTime > mainTime, distance)
        }.minWithOrNull(compareBy<Match> { it.afterMain }.thenBy { it.distance }.thenBy { it.candidate.expenseId })?.candidate
    }
}
