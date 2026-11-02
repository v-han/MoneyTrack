package com.moneytrack.app.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.moneytrack.core.ExpenseMetadata
import com.moneytrack.core.ManualExpense
import com.moneytrack.core.PaymentRule
import com.moneytrack.core.PaymentRulePresets
import com.moneytrack.core.ReimbursementStatus
import com.moneytrack.core.NotificationLinkRule
import com.moneytrack.core.NotificationLinkMatcher
import com.moneytrack.core.LinkNotification
import com.moneytrack.core.LinkCandidate
import java.util.UUID
import org.json.JSONArray

data class Expense(
    val id: Long,
    val sourceName: String,
    val amountFen: Long,
    val receivedAt: Long,
    val confirmed: Boolean,
    val manual: Boolean = false,
    val note: String = "",
    val reimbursement: ReimbursementStatus = ReimbursementStatus.NONE,
    val tags: List<ExpenseTag> = emptyList(),
    val reimbursedAmountFen: Long = 0,
    val linkedSources: List<String> = emptyList(),
)

/** 新建候选、附加到已有候选和身份重复是不同结果，便于通知诊断。 */
enum class NotificationSaveResult { CREATED, LINKED, DUPLICATE }

/** archived 为 true 表示管理列表中已删除、但该账单仍保留的标签快照。 */
data class ExpenseTag(val id: Long, val name: String, val archived: Boolean = false)

/** 首页独立查询待确认和当天已确认，避免历史记录挤掉待处理项。 */
data class HomeSnapshot(
    val pending: List<Expense>, val today: List<Expense>, val todayTotalFen: Long,
    val pendingCount: Long, val todayCount: Long,
)

/** 历史总额与数量覆盖整个区间，列表按需加载。 */
data class HistorySnapshot(val expenses: List<Expense>, val totalFen: Long, val count: Long)

/**
 * SQLiteOpenHelper 演示最基本的本地持久化。SQL 参数全部通过绑定传入，避免拼接用户文本。
 * 调用方必须在 Application.io 上执行；将来可替换为 Room 而不改变 core 解析器。
 */
class LedgerDatabase(context: Context) : SQLiteOpenHelper(context, "moneytrack.db", null, 8) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 清空账本级联清除标签关联与历史快照，避免残留失效数据。
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE rules (
                package_name TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                expense_keywords TEXT NOT NULL,
                excluded_keywords TEXT NOT NULL,
                enabled INTEGER NOT NULL CHECK(enabled IN (0, 1))
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fingerprint TEXT NOT NULL UNIQUE,
                source_name TEXT NOT NULL,
                amount_fen INTEGER NOT NULL CHECK(amount_fen > 0),
                received_at INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('pending', 'confirmed', 'dismissed'))
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX expenses_time ON expenses(received_at DESC)")
        addExpenseMetadata(db)
        addReimbursementAmounts(db)
        addTagSnapshots(db)
        addTagOrder(db)
        addNotificationLinks(db)
        // 预设仅是可编辑示例，默认关闭；它们不是对真实通知格式的兼容承诺。
        listOfNotNull(PaymentRulePresets.forPackage(PaymentRulePresets.WECHAT_PACKAGE),
            PaymentRulePresets.forPackage(PaymentRulePresets.ALIPAY_PACKAGE))
            .forEach { db.insertOrThrow("rules", null, ruleValues(it)) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper 在事务中升级；保留原账单和去重身份，只更新尚未自定义的支付预设。
        if (oldVersion < 2) addExpenseMetadata(db)
        // v4 同时识别最初的通用默认与 v3 的支付宝默认；一次升级即可补齐，账本表不改动。
        if (oldVersion < 4) updatePaymentPresets(db)
        if (oldVersion < 5) addReimbursementAmounts(db)
        if (oldVersion < 6) addTagSnapshots(db)
        if (oldVersion < 7) addTagOrder(db)
        if (oldVersion < 8) addNotificationLinks(db)
    }

    private fun addNotificationLinks(db: SQLiteDatabase) {
        // 旧账单没有可靠的来源包名，不能凭名称猜测。只为今后通知记录身份与来源。
        db.execSQL("CREATE TABLE notification_links (main_package TEXT NOT NULL, secondary_package TEXT NOT NULL, window_seconds INTEGER NOT NULL CHECK(window_seconds BETWEEN 1 AND 300), enabled INTEGER NOT NULL CHECK(enabled IN (0,1)), PRIMARY KEY(main_package, secondary_package), CHECK(main_package != secondary_package))")
        db.execSQL("CREATE TABLE notification_events (fingerprint TEXT PRIMARY KEY, expense_id INTEGER NOT NULL REFERENCES expenses(id) ON DELETE CASCADE, package_name TEXT NOT NULL, source_name TEXT NOT NULL, posted_at INTEGER NOT NULL, is_primary INTEGER NOT NULL DEFAULT 0 CHECK(is_primary IN (0,1)), UNIQUE(expense_id, package_name))")
        db.execSQL("CREATE INDEX notification_events_time ON notification_events(posted_at)")
    }

    private fun addTagOrder(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE tags ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0 CHECK(sort_order >= 0)")
        // 升级时沿用原先按名称展示的顺序，不打乱用户已经熟悉的位置。
        val ids = db.query("tags", arrayOf("id"), null, null, null, null, "name COLLATE NOCASE, id")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        ids.forEachIndexed { position, id ->
            db.update("tags", ContentValues().apply { put("sort_order", position) }, "id = ?", arrayOf(id.toString()))
        }
    }

    private fun addTagSnapshots(db: SQLiteDatabase) {
        // 不引用 tags：删除标签后仍须保留账单上的名称；expense_id 仍随账单真正删除而清理。
        db.execSQL("CREATE TABLE expense_tag_snapshots (expense_id INTEGER NOT NULL REFERENCES expenses(id) ON DELETE CASCADE, original_tag_id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(expense_id, original_tag_id))")
    }

    private fun addReimbursementAmounts(db: SQLiteDatabase) {
        // 旧列的 CHECK 不接受 partial。追加新列并复制状态，避免重建账本破坏指纹、ID 和标签外键。
        // reimbursement 旧列仅保留供迁移核对，v5 的所有读写统一使用 reimbursement_status。
        db.execSQL("ALTER TABLE expenses ADD COLUMN reimbursement_status TEXT NOT NULL DEFAULT 'none' CHECK(reimbursement_status IN ('none', 'pending', 'reimbursed', 'partial'))")
        db.execSQL("ALTER TABLE expenses ADD COLUMN reimbursed_amount_fen INTEGER NOT NULL DEFAULT 0 CHECK(reimbursed_amount_fen >= 0)")
        db.execSQL("UPDATE expenses SET reimbursement_status = reimbursement")
    }

    private fun updatePaymentPresets(db: SQLiteDatabase) {
        listOf(PaymentRulePresets.WECHAT_PACKAGE, PaymentRulePresets.ALIPAY_PACKAGE).forEach { packageName ->
            val existing = db.query("rules", null, "package_name = ?", arrayOf(packageName), null, null, null)
                .use { cursor -> if (cursor.moveToFirst()) readRule(cursor) else null } ?: return@forEach
            val updated = PaymentRulePresets.updateLegacyDefaults(existing)
            if (updated.expenseKeywords != existing.expenseKeywords) {
                // 只更新未自定义的旧预设关键词；不重新添加用户删除的规则，不改启停与排除词。
                db.update("rules", ContentValues().apply {
                    put("expense_keywords", JSONArray(updated.expenseKeywords).toString())
                }, "package_name = ?", arrayOf(packageName))
            }
        }
    }

    private fun addExpenseMetadata(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE expenses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE expenses ADD COLUMN reimbursement TEXT NOT NULL DEFAULT 'none' CHECK(reimbursement IN ('none', 'pending', 'reimbursed'))")
        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(name) BETWEEN 1 AND 20))")
        db.execSQL("CREATE TABLE expense_tags (expense_id INTEGER NOT NULL REFERENCES expenses(id) ON DELETE CASCADE, tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY(expense_id, tag_id))")
        db.execSQL("CREATE INDEX expense_tags_tag ON expense_tags(tag_id)")
        listOf("吃饭", "服饰", "交通", "日用品").forEach { name ->
            db.insertOrThrow("tags", null, ContentValues().apply { put("name", name) })
        }
    }

    fun rules(): List<PaymentRule> = readableDatabase.query("rules", null, null, null, null, null, "display_name")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(readRule(cursor)) } }

    fun rule(packageName: String): PaymentRule? = readableDatabase.query(
        "rules", null, "package_name = ?", arrayOf(packageName), null, null, null,
    ).use { if (it.moveToFirst()) readRule(it) else null }

    fun saveRule(rule: PaymentRule) {
        require(rule.validationError() == null)
        writableDatabase.insertWithOnConflict("rules", null, ruleValues(rule), SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) }
    }

    /** 删除来源时同时删除失效联动；历史通知身份和账单保留。 */
    @SuppressLint("UseKtx")
    fun deleteRule(packageName: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("notification_links", "main_package = ? OR secondary_package = ?", arrayOf(packageName, packageName))
            db.delete("rules", "package_name = ?", arrayOf(packageName))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 在 io 线程合并导入；同包名覆盖，其余保留，事务保证失败时不会只导入一半。 */
    fun importRules(rules: List<PaymentRule>) {
        importConfiguration(NotificationConfiguration(rules))
    }

    /** 通知识别和联动规则一次合并，先校验完整结果，任何失败都回滚。 */
    @SuppressLint("UseKtx")
    fun importConfiguration(configuration: NotificationConfiguration) {
        RuleConfiguration.validate(configuration.rules)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val incomingKeys = configuration.links.map { it.mainPackageName to it.secondaryPackageName }.toSet()
            val mergedLinks = links().filterNot { (it.mainPackageName to it.secondaryPackageName) in incomingKeys } + configuration.links
            validateLinks(mergedLinks, (rules() + configuration.rules).map { it.packageName }.toSet())
            configuration.rules.forEach { saveRule(it) }
            configuration.links.forEach { writeLink(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** io 线程读取全部联动，包含尚未启用的草稿。 */
    fun links(): List<NotificationLinkRule> = readableDatabase.query("notification_links", null, null, null, null, null,
        "main_package, secondary_package").use { cursor -> buildList {
        while (cursor.moveToNext()) add(NotificationLinkRule(cursor.string("main_package"), cursor.string("secondary_package"),
            cursor.getInt(cursor.getColumnIndexOrThrow("window_seconds")), cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1))
    } }

    /** 保存/更换主副来源时原子替换旧组合；交叉角色和缺失来源不能保存。 */
    @SuppressLint("UseKtx")
    fun saveLink(value: NotificationLinkRule, original: Pair<String, String>? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val remaining = links().filterNot { (it.mainPackageName to it.secondaryPackageName) == original }
            validateLinks(remaining + value, rules().map { it.packageName }.toSet())
            original?.let { deleteLink(it.first, it.second) }
            writeLink(value)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 删除配置不拆散历史账单，也不清除它的两个持久化通知身份。 */
    fun deleteLink(mainPackage: String, secondaryPackage: String) {
        writableDatabase.delete("notification_links", "main_package = ? AND secondary_package = ?", arrayOf(mainPackage, secondaryPackage))
    }

    private fun validateLinks(values: List<NotificationLinkRule>, packages: Set<String>) {
        require(NotificationLinkRule.collectionError(values) == null) { NotificationLinkRule.collectionError(values).orEmpty() }
        require(values.all { it.mainPackageName in packages && it.secondaryPackageName in packages }) { "请先配置主副应用的通知识别规则" }
    }

    private fun writeLink(value: NotificationLinkRule) {
        writableDatabase.insertWithOnConflict("notification_links", null, ContentValues().apply {
            put("main_package", value.mainPackageName); put("secondary_package", value.secondaryPackageName)
            put("window_seconds", value.windowSeconds); put("enabled", if (value.enabled) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    /** 同一事务内去重、寻找单通知候选和附加身份；绝不将同来源或已经联动的账单再次合并。 */
    @SuppressLint("UseKtx")
    fun recordNotification(fingerprint: String, packageName: String, sourceName: String, amountFen: Long, postedAt: Long): NotificationSaveResult {
        require(amountFen > 0 && postedAt >= 0 && rule(packageName)?.enabled == true)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val duplicate = db.rawQuery("SELECT 1 FROM notification_events WHERE fingerprint = ? UNION ALL SELECT 1 FROM expenses WHERE fingerprint = ? LIMIT 1",
                arrayOf(fingerprint, fingerprint)).use { it.moveToFirst() }
            if (duplicate) { db.setTransactionSuccessful(); return NotificationSaveResult.DUPLICATE }
            val enabledPackages = rules().filter { it.enabled }.map { it.packageName }.toSet()
            val active = links().filter { it.enabled && it.mainPackageName in enabledPackages && it.secondaryPackageName in enabledPackages &&
                (it.mainPackageName == packageName || it.secondaryPackageName == packageName) }
            val window = active.maxOfOrNull { it.windowSeconds * 1000L } ?: 0L
            val start = maxOf(0, postedAt - window)
            val end = if (Long.MAX_VALUE - postedAt < window) Long.MAX_VALUE else postedAt + window
            val candidates = if (active.isEmpty()) emptyList() else db.rawQuery("""
                SELECT e.id, n.package_name, e.amount_fen, n.posted_at FROM notification_events n
                JOIN expenses e ON e.id = n.expense_id
                WHERE e.status = 'pending' AND e.amount_fen = ? AND n.posted_at BETWEEN ? AND ?
                AND (SELECT COUNT(*) FROM notification_events other WHERE other.expense_id = e.id) = 1
            """.trimIndent(), arrayOf(amountFen.toString(), start.toString(), end.toString())).use { cursor -> buildList {
                while (cursor.moveToNext()) add(LinkCandidate(cursor.getLong(0), LinkNotification(cursor.getString(1), cursor.getLong(2), cursor.getLong(3))))
            } }
            val match = NotificationLinkMatcher.find(LinkNotification(packageName, amountFen, postedAt), candidates, active)
            val id: Long
            val incomingIsMain: Boolean
            if (match == null) {
                check(addCandidate(fingerprint, sourceName, amountFen, postedAt))
                id = db.rawQuery("SELECT id FROM expenses WHERE fingerprint = ?", arrayOf(fingerprint)).use { it.moveToFirst(); it.getLong(0) }
                incomingIsMain = false
            } else {
                id = match.expenseId
                incomingIsMain = active.any { it.mainPackageName == packageName && it.secondaryPackageName == match.notification.packageName }
                if (incomingIsMain) db.update("expenses", ContentValues().apply {
                    put("source_name", sourceName); put("received_at", postedAt)
                }, "id = ?", arrayOf(id.toString()))
                db.update("notification_events", ContentValues().apply { put("is_primary", if (incomingIsMain) 0 else 1) },
                    "expense_id = ?", arrayOf(id.toString()))
            }
            db.insertOrThrow("notification_events", null, ContentValues().apply {
                put("fingerprint", fingerprint); put("expense_id", id); put("package_name", packageName)
                put("source_name", sourceName); put("posted_at", postedAt); put("is_primary", if (incomingIsMain) 1 else 0)
            })
            db.setTransactionSuccessful()
            return if (match == null) NotificationSaveResult.CREATED else NotificationSaveResult.LINKED
        } finally { db.endTransaction() }
    }

    /** 唯一索引保证重启之后重复通知依然不会重复入库。 */
    fun addCandidate(fingerprint: String, sourceName: String, amountFen: Long, receivedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("fingerprint", fingerprint)
            put("source_name", sourceName)
            put("amount_fen", amountFen)
            put("received_at", receivedAt)
            put("status", "pending")
        }
        return writableDatabase.insertWithOnConflict("expenses", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** 手工保存就是用户确认；在 io 线程调用，返回 false 表示这份草稿已经保存过。 */
    // 保留显式 begin/commit/finally，便于学习两个写入步骤如何一起回滚。
    @SuppressLint("UseKtx")
    fun addManualExpense(expense: ManualExpense, metadata: ExpenseMetadata = ExpenseMetadata()): Boolean {
        require(expense.validationError() == null && metadata.validationError(expense.amountFen) == null)
        val values = metadataValues(metadata).apply {
            put("fingerprint", ManualExpense.fingerprint(expense.entryId))
            put("source_name", expense.sourceName)
            put("amount_fen", expense.amountFen)
            put("received_at", expense.occurredAt)
            put("status", "confirmed")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertWithOnConflict("expenses", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            // 同一草稿重试不覆盖第一次保存的附加信息，也不重复建立标签关联。
            if (id != -1L) replaceTags(db, id, metadata.tagIds)
            db.setTransactionSuccessful()
            return id != -1L
        } finally { db.endTransaction() }
    }

    /** 旋转或进程重建后核对草稿是否已经落库；移除后的指纹也保留，防止旧表单再次入账。 */
    fun hasManualExpense(entryId: UUID): Boolean = readableDatabase.query(
        "expenses", arrayOf("id"), "fingerprint = ?", arrayOf(ManualExpense.fingerprint(entryId)),
        null, null, null, "1",
    ).use { it.moveToFirst() }

    fun confirm(id: Long) {
        writableDatabase.update("expenses", ContentValues().apply { put("status", "confirmed") },
            "id = ? AND status = 'pending'", arrayOf(id.toString()))
    }

    fun dismiss(id: Long) {
        // 保留指纹作为去重标记；重新投递同一通知时不让被忽略的记录再次出现。
        writableDatabase.update("expenses", ContentValues().apply { put("status", "dismissed") },
            "id = ?", arrayOf(id.toString()))
    }

    /** 时间范围为左闭右开；仅已确认记录进入当日和历史统计。 */
    fun homeSnapshot(start: Long, end: Long, limit: Int): HomeSnapshot {
        val pending = entries("status = 'pending'", emptyArray(), limit)
        val today = historySnapshot(start, end, limit)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM expenses WHERE status = 'pending'", null)
            .use { it.moveToFirst(); it.getLong(0) }
        return HomeSnapshot(pending, today.expenses, today.totalFen, count, today.count)
    }

    /** 列表、笔数与净支出总额使用相同筛选；报销仍按原支出日期抵扣，不生成收入。 */
    fun historySnapshot(start: Long, end: Long, limit: Int, reimbursement: ReimbursementStatus? = null): HistorySnapshot {
        require(start < end && limit > 0)
        val where = "status = 'confirmed' AND received_at >= ? AND received_at < ?" +
            if (reimbursement == null) "" else " AND reimbursement_status = ?"
        val args = (listOf(start.toString(), end.toString()) + listOfNotNull(reimbursement?.databaseValue)).toTypedArray()
        val records = entries(where, args, limit)
        val summary = readableDatabase.rawQuery(
            // 与 core.countedExpenseFen 保持一致；聚合全部筛选结果，不受已加载条数影响。
            """SELECT COALESCE(SUM(CASE reimbursement_status
                WHEN 'reimbursed' THEN 0
                WHEN 'partial' THEN amount_fen - reimbursed_amount_fen
                ELSE amount_fen END), 0), COUNT(*) FROM expenses WHERE $where""", args,
        ).use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
        return HistorySnapshot(records, summary.first, summary.second)
    }

    private fun entries(where: String, args: Array<String>, limit: Int): List<Expense> {
        require(limit > 0)
        val records = readableDatabase.query("expenses", null, where, args, null, null,
            "received_at DESC, id DESC", limit.toString()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Expense(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")), cursor.string("source_name"),
                    cursor.getLong(cursor.getColumnIndexOrThrow("amount_fen")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("received_at")), cursor.string("status") == "confirmed",
                    cursor.string("fingerprint").startsWith(ManualExpense.FINGERPRINT_PREFIX),
                    cursor.string("note"), ReimbursementStatus.fromDatabase(cursor.string("reimbursement_status")),
                    reimbursedAmountFen = cursor.getLong(cursor.getColumnIndexOrThrow("reimbursed_amount_fen")),
                ))
            }
        }
        val tags = tagsForExpenses(records.map { it.id })
        val sources = linkedSourcesForExpenses(records.map { it.id })
        return records.map { it.copy(tags = tags[it.id].orEmpty(), linkedSources = sources[it.id].orEmpty()) }
    }

    private fun linkedSourcesForExpenses(ids: List<Long>): Map<Long, List<String>> {
        val result = mutableMapOf<Long, MutableList<String>>()
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT expense_id, source_name FROM notification_events WHERE expense_id IN ($placeholders) ORDER BY is_primary DESC, posted_at, fingerprint",
                chunk.map(Long::toString).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(cursor.getString(1))
            }
        }
        return result.filterValues { it.size > 1 }
    }

    /** 读取单条未移除账单及附加信息，供详情页编辑。 */
    fun expense(id: Long): Expense? = entries("id = ? AND status != 'dismissed'", arrayOf(id.toString()), 1).singleOrNull()

    /** 备注、报销标记和标签在同一事务提交，不改变金额、时间或指纹；confirm 为 true 时同时确认支出。 */
    @SuppressLint("UseKtx")
    fun saveMetadata(id: Long, metadata: ExpenseMetadata, confirm: Boolean = false): Boolean {
        require(metadata.validationError() == null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 在同一事务读取原金额后校验，任何入口都不能写入超额报销。
            val amountFen = db.query("expenses", arrayOf("amount_fen"), "id = ? AND status != 'dismissed'",
                arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.getLong(0) else null }
                ?: return false
            require(metadata.validationError(amountFen) == null)
            val values = metadataValues(metadata).apply { if (confirm) put("status", "confirmed") }
            val changed = db.update("expenses", values, "id = ? AND status != 'dismissed'", arrayOf(id.toString())) > 0
            if (changed) replaceTags(db, id, metadata.tagIds)
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    private fun metadataValues(metadata: ExpenseMetadata) = ContentValues().apply {
        put("note", metadata.note)
        put("reimbursement_status", metadata.reimbursement.databaseValue)
        put("reimbursed_amount_fen", metadata.reimbursedAmountFen)
    }

    private fun replaceTags(db: SQLiteDatabase, expenseId: Long, tagIds: List<Long>) {
        val archivedIds = db.query("expense_tag_snapshots", arrayOf("original_tag_id"), "expense_id = ?",
            arrayOf(expenseId.toString()), null, null, null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        // 保存备注或报销时保留选中的历史标签；用户在账单内取消选择，才移除对应快照。
        (archivedIds - tagIds.toSet()).forEach { tagId ->
            db.delete("expense_tag_snapshots", "expense_id = ? AND original_tag_id = ?",
                arrayOf(expenseId.toString(), tagId.toString()))
        }
        db.delete("expense_tags", "expense_id = ?", arrayOf(expenseId.toString()))
        tagIds.filterNot { it in archivedIds }.forEach { tagId ->
            db.insertOrThrow("expense_tags", null, ContentValues().apply {
                put("expense_id", expenseId)
                put("tag_id", tagId)
            })
        }
    }

    private fun tagsForExpenses(ids: List<Long>): Map<Long, List<ExpenseTag>> {
        val result = mutableMapOf<Long, MutableList<ExpenseTag>>()
        // 分批绑定参数，兼容 SQLite 变量数量限制，也避免加载更多时触发超限。
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT et.expense_id, t.id, t.name FROM expense_tags et JOIN tags t ON t.id = et.tag_id WHERE et.expense_id IN ($placeholders) ORDER BY t.sort_order, t.id", chunk.map(Long::toString).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { mutableListOf() }
                    .add(ExpenseTag(cursor.getLong(1), cursor.getString(2)))
            }
            // 分别查询两张表，每次仍只绑定最多 500 个 ID，不因 UNION 重复绑定而超过旧 SQLite 上限。
            readableDatabase.rawQuery("SELECT expense_id, original_tag_id, name FROM expense_tag_snapshots WHERE expense_id IN ($placeholders) ORDER BY name",
                chunk.map(Long::toString).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) result.getOrPut(cursor.getLong(0)) { mutableListOf() }
                    .add(ExpenseTag(cursor.getLong(1), cursor.getString(2), archived = true))
            }
        }
        return result
    }

    /** 标签名称唯一；所有标签操作同样只能在 io 线程调用。 */
    fun tags(): List<ExpenseTag> = readableDatabase.query("tags", null, null, null, null, null, "sort_order, id")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(ExpenseTag(cursor.getLong(cursor.getColumnIndexOrThrow("id")), cursor.string("name"))) } }

    /** 返回 false 表示重名或标签已不存在，不覆盖其他标签。 */
    fun saveTag(id: Long?, name: String): Boolean {
        require(name.isNotBlank() && name == name.trim() && name.length <= 20)
        val values = ContentValues().apply { put("name", name) }
        return if (id == null) {
            // 新标签排在末尾；更名保留原位置，删除不改变其他标签的相对顺序。
            val nextOrder = readableDatabase.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM tags", null)
                .use { it.moveToFirst(); it.getLong(0) }
            values.put("sort_order", nextOrder)
            writableDatabase.insertWithOnConflict("tags", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
        else writableDatabase.updateWithOnConflict("tags", values, "id = ?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_IGNORE) > 0
    }

    /** 一次保存完整标签顺序；列表已变动时返回 false，避免旧页面覆盖新标签。 */
    @SuppressLint("UseKtx")
    fun reorderTags(ids: List<Long>): Boolean {
        require(ids.distinct().size == ids.size && ids.all { it > 0 })
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (ids.toSet() != tags().map { it.id }.toSet()) return false
            ids.forEachIndexed { position, id ->
                db.update("tags", ContentValues().apply { put("sort_order", position) }, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** 从管理列表删除标签；先为已有账单保存名称，之后更名或新建同名标签都不影响历史快照。 */
    @SuppressLint("UseKtx")
    fun deleteTag(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("""INSERT INTO expense_tag_snapshots (expense_id, original_tag_id, name)
                SELECT et.expense_id, t.id, t.name FROM expense_tags et JOIN tags t ON t.id = et.tag_id WHERE t.id = ?""",
                arrayOf(id))
            db.delete("tags", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearExpenses() { writableDatabase.delete("expenses", null, null) }

    private fun ruleValues(rule: PaymentRule) = ContentValues().apply {
        put("package_name", rule.packageName)
        put("display_name", rule.displayName)
        put("expense_keywords", JSONArray(rule.expenseKeywords).toString())
        put("excluded_keywords", JSONArray(rule.excludedKeywords).toString())
        put("enabled", if (rule.enabled) 1 else 0)
    }

    private fun readRule(cursor: Cursor) = PaymentRule(
        cursor.string("package_name"), cursor.string("display_name"),
        decodeList(cursor.string("expense_keywords")), decodeList(cursor.string("excluded_keywords")),
        cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1,
    )

    private fun decodeList(value: String): List<String> {
        val array = JSONArray(value)
        return List(array.length()) { array.getString(it) }
    }

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
}
