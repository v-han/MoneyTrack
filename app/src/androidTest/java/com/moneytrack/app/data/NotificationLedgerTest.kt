package com.moneytrack.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.moneytrack.app.MoneyTrackApplication
import com.moneytrack.core.ExpenseMetadata
import com.moneytrack.core.NotificationLinkRule
import com.moneytrack.core.PaymentRule
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 联动依赖真实 SQLite 事务、迁移与持久化身份；测试始终在独立目录和 io 线程运行。 */
class NotificationLedgerTest {
    private val main = "com.example.wallet"
    private val bank = "com.example.bank"
    private val link = NotificationLinkRule(main, bank, enabled = true)
    private fun source(pkg: String) = PaymentRule(pkg, if (pkg == main) "虚构钱包" else "虚构银行卡", enabled = true)
    private fun record(db: LedgerDatabase, key: String, pkg: String, time: Long = 100_000, amount: Long = 1250) =
        db.recordNotification(key, pkg, source(pkg).displayName, amount, time)
    private fun pending(db: LedgerDatabase) = db.homeSnapshot(0, Long.MAX_VALUE, 100).pending
    private fun count(db: LedgerDatabase, table: String) = DatabaseUtils.longForQuery(db.readableDatabase, "SELECT COUNT(*) FROM $table", null)

    @Test fun bankFirstPreservesDraftAndBothIdentitiesAcrossReopen() = withDatabase { db, context ->
        assertEquals(NotificationSaveResult.CREATED, record(db, "bank-a", bank, 90_000))
        val id = pending(db).single().id
        val tag = db.tags().first().id
        db.saveMetadata(id, ExpenseMetadata(note = "虚构备注", tagIds = listOf(tag)))
        assertEquals(NotificationSaveResult.LINKED, record(db, "main-a", main))
        val expense = pending(db).single()
        assertEquals(id, expense.id)
        assertEquals("虚构钱包", expense.sourceName)
        assertEquals(100_000L, expense.receivedAt)
        assertEquals("虚构备注", expense.note)
        assertEquals(listOf(tag), expense.tags.map { it.id })
        assertEquals(listOf("虚构钱包", "虚构银行卡"), expense.linkedSources)
        db.close()
        LedgerDatabase(context).use { reopened ->
            assertEquals(NotificationSaveResult.DUPLICATE, record(reopened, "main-a", main))
            assertEquals(NotificationSaveResult.DUPLICATE, record(reopened, "bank-a", bank, 90_000))
            assertEquals(2L, count(reopened, "notification_events"))
            reopened.confirm(id)
            assertEquals(1250L, reopened.historySnapshot(0, 200_000, 100).totalFen)
            assertEquals(1L, reopened.historySnapshot(0, 200_000, 100).count)
        }
    }

    @Test fun mainFirstAndRepeatedEqualPaymentsRemainOneToOne() = withDatabase { db, _ ->
        record(db, "main-a", main)
        assertEquals(NotificationSaveResult.LINKED, record(db, "bank-a", bank, 110_000))
        assertEquals(NotificationSaveResult.CREATED, record(db, "bank-b", bank, 111_000))
        assertEquals(NotificationSaveResult.LINKED, record(db, "main-b", main, 112_000))
        assertEquals(2, pending(db).size)
        assertEquals(4L, count(db, "notification_events"))
        pending(db).forEach { db.confirm(it.id) }
        assertEquals(2500L, db.historySnapshot(0, 200_000, 100).totalFen)
    }

    @Test fun sameSourceNeverMergesAndBackwardCandidateWins() = withDatabase { db, _ ->
        record(db, "bank-future", bank, 101_000)
        record(db, "bank-before", bank, 98_000)
        assertEquals(2, pending(db).size)
        val expectedId = pending(db).first { it.receivedAt == 98_000L }.id
        record(db, "main-a", main)
        assertEquals(expectedId, pending(db).first { it.linkedSources.isNotEmpty() }.id)
        record(db, "main-b", main, 102_000)
        assertEquals(2, pending(db).size)
        assertTrue(pending(db).all { it.linkedSources.size == 2 })
    }

    @Test fun disabledLinkDifferentAmountsAndOutsideWindowStaySeparate() = withDatabase { db, _ ->
        db.saveLink(link.copy(enabled = false), main to bank)
        record(db, "bank-a", bank)
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-a", main))
        db.clearExpenses()
        assertEquals(0L, count(db, "notification_events"))
        db.saveLink(link, main to bank)
        record(db, "bank-b", bank)
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-b", main, amount = 1251))
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-c", main, time = 130_001))
        assertEquals(3, pending(db).size)
        db.clearExpenses()
        record(db, "bank-before-disabled", bank)
        db.saveRule(source(bank).copy(enabled = false))
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-after-disabled", main))
    }

    @Test fun confirmedDismissedAndLegacyRecordsAreNotReinterpreted() = withDatabase { db, _ ->
        record(db, "bank-confirmed", bank)
        db.confirm(pending(db).single().id)
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-a", main))
        db.clearExpenses()
        record(db, "bank-dismissed", bank)
        db.dismiss(pending(db).single().id)
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-b", main))
        db.clearExpenses()
        db.addCandidate("old-fingerprint", "旧虚构来源", 1250, 100_000)
        assertEquals(NotificationSaveResult.DUPLICATE, record(db, "old-fingerprint", bank))
        assertEquals(NotificationSaveResult.CREATED, record(db, "main-c", main))
        assertEquals(2, pending(db).size)
    }

    @Test fun importIsAtomicAndDeletingConfigurationKeepsLedgerIdentities() = withDatabase { db, _ ->
        record(db, "bank-a", bank)
        record(db, "main-a", main)
        val changed = source(main).copy(displayName = "不应写入")
        val missing = NotificationLinkRule(main, "com.example.missing", enabled = true)
        assertTrue(runCatching { db.importConfiguration(NotificationConfiguration(listOf(changed), listOf(missing))) }.isFailure)
        assertEquals(source(main), db.rule(main))
        assertEquals(listOf(link), db.links())
        db.writableDatabase.execSQL("CREATE TRIGGER reject_link BEFORE INSERT ON notification_links WHEN NEW.window_seconds = 45 BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching {
            db.importConfiguration(NotificationConfiguration(listOf(changed), listOf(link.copy(windowSeconds = 45))))
        }.isFailure)
        assertEquals(source(main), db.rule(main))
        assertEquals(listOf(link), db.links())
        db.deleteRule(bank)
        assertTrue(db.links().isEmpty())
        assertEquals(1, pending(db).size)
        assertEquals(2L, count(db, "notification_events"))
        db.saveRule(source(bank))
        assertEquals(NotificationSaveResult.DUPLICATE, record(db, "bank-a", bank))
    }

    @Test fun migrationFromSevenPreservesBillsTagsSnapshotsRulesAndDeduplication() = withDatabase { db, context ->
        db.addCandidate("old-fingerprint", "旧虚构来源", 1250, 100_000)
        val id = pending(db).single().id
        val tag = db.tags().first().id
        db.saveMetadata(id, ExpenseMetadata(note = "迁移测试", tagIds = listOf(tag)))
        db.deleteTag(tag)
        val before = db.expense(id)
        val sources = db.rules()
        val tags = db.tags()
        db.writableDatabase.execSQL("DROP TABLE notification_events")
        db.writableDatabase.execSQL("DROP TABLE notification_links")
        db.writableDatabase.version = 7
        db.close()
        LedgerDatabase(context).use { upgraded ->
            assertEquals(8, upgraded.readableDatabase.version)
            assertEquals(before, upgraded.expense(id))
            assertEquals(sources, upgraded.rules())
            assertEquals(tags, upgraded.tags())
            assertTrue(upgraded.links().isEmpty())
            assertEquals(NotificationSaveResult.DUPLICATE, record(upgraded, "old-fingerprint", bank))
        }
    }

    private fun withDatabase(work: (LedgerDatabase, Context) -> Unit) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MoneyTrackApplication
        application.io.submit {
            val directory = application.getDir("notification-link-tests", Context.MODE_PRIVATE)
            val isolated = object : ContextWrapper(application) {
                override fun getDatabasePath(name: String) = File(directory, name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
                override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            }
            isolated.deleteDatabase("moneytrack.db")
            val database = LedgerDatabase(isolated)
            try {
                database.saveRule(source(main)); database.saveRule(source(bank)); database.saveLink(link)
                work(database, isolated)
            } finally {
                database.close()
                isolated.deleteDatabase("moneytrack.db")
            }
        }.get()
    }
}
