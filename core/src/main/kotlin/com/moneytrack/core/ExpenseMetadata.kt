package com.moneytrack.core

/** 报销不代替用户确认；已报销计零，部分报销只统计未报销金额。 */
enum class ReimbursementStatus(val databaseValue: String) {
    NONE("none"), PENDING("pending"), REIMBURSED("reimbursed"), PARTIAL("partial");

    companion object {
        fun fromDatabase(value: String): ReimbursementStatus = entries.firstOrNull { it.databaseValue == value } ?: NONE
    }
}

/** 账单的用户附加信息；仅保存主动填写的文字和标签 ID，不从通知原文提取备注。 */
data class ExpenseMetadata(
    val note: String = "",
    val reimbursement: ReimbursementStatus = ReimbursementStatus.NONE,
    val tagIds: List<Long> = emptyList(),
    val reimbursedAmountFen: Long = 0,
) {
    /** 持久化的部分报销须小于原金额；完成编辑时可先调用 completeReimbursementIfCovered。 */
    fun validationError(amountFen: Long? = null): String? = when {
        note.length > 500 -> "备注最多 500 个字符"
        tagIds.size > 8 -> "每条账单最多选择 8 个标签"
        tagIds.any { it <= 0 } || tagIds.distinct().size != tagIds.size -> "标签选择无效，请重新选择"
        reimbursement == ReimbursementStatus.PARTIAL && reimbursedAmountFen !in 1..ExpenseParser.MAX_AMOUNT_FEN ->
            "请输入有效的已报销金额，最多两位小数"
        reimbursement == ReimbursementStatus.PARTIAL && amountFen != null && reimbursedAmountFen >= amountFen ->
            "部分报销金额须小于原支出金额；全额报销请选择已报销"
        reimbursement != ReimbursementStatus.PARTIAL && reimbursedAmountFen != 0L -> "当前报销状态不应填写部分报销金额"
        else -> null
    }

    /** 返回这笔已确认账单计入统计的金额（分），原始支出金额始终保留。 */
    fun countedExpenseFen(amountFen: Long): Long {
        require(amountFen > 0 && validationError(amountFen) == null)
        return when (reimbursement) {
            ReimbursementStatus.NONE, ReimbursementStatus.PENDING -> amountFen
            ReimbursementStatus.REIMBURSED -> 0
            ReimbursementStatus.PARTIAL -> amountFen - reimbursedAmountFen
        }
    }

    /** 结束金额编辑时，合法金额达到原支出即转为已报销；非法输入留给校验，不产生负支出或额外收入。 */
    fun completeReimbursementIfCovered(amountFen: Long): ExpenseMetadata =
        if (reimbursement == ReimbursementStatus.PARTIAL && amountFen in 1..ExpenseParser.MAX_AMOUNT_FEN &&
            reimbursedAmountFen in amountFen..ExpenseParser.MAX_AMOUNT_FEN) {
            copy(reimbursement = ReimbursementStatus.REIMBURSED, reimbursedAmountFen = 0)
        } else this
}
