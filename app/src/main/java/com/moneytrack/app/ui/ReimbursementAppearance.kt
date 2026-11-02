package com.moneytrack.app.ui

import com.moneytrack.app.R
import com.moneytrack.core.ReimbursementStatus

/** 报销使用独立的玫瑰、紫、蓝配色，不复用应用主题绿或待确认的黄色。 */
object ReimbursementAppearance {
    /** 返回状态对应的背景与前景颜色资源，列表、详情和筛选共用。 */
    fun colors(status: ReimbursementStatus?): Pair<Int, Int> = when (status) {
        ReimbursementStatus.PENDING -> R.color.reimbursement_pending_container to R.color.on_reimbursement_pending
        ReimbursementStatus.PARTIAL -> R.color.reimbursement_partial_container to R.color.on_reimbursement_partial
        ReimbursementStatus.REIMBURSED -> R.color.reimbursement_done_container to R.color.on_reimbursement_done
        else -> R.color.surface to R.color.on_surface
    }
}
