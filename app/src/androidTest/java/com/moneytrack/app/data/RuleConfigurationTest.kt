package com.moneytrack.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.moneytrack.app.MoneyTrackApplication
import com.moneytrack.core.PaymentRule
import com.moneytrack.core.NotificationLinkRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 在 Android 上验证真实 org.json 和 SQLite；全部使用虚构配置与隔离数据库。 */
class RuleConfigurationTest {
    private fun rule(packageName: String = "com.example.fixture", enabled: Boolean = false) = PaymentRule(
        packageName, "虚构应用", listOf("支出(消费{无关文字}){金额}元", "spent,done"), emptyList(), enabled,
    )

    @Test fun roundTripPreservesConditionsAndEnabledState() {
        val rules = listOf(rule(enabled = true), rule("com.example.second"))
        val text = RuleConfiguration.encode(rules)
        assertEquals(rules, RuleConfiguration.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        val root = JSONObject(text)
        assertEquals(setOf("format", "version", "rules", "links"), root.keys().asSequence().toSet())
        assertEquals(0, root.getJSONArray("rules").getJSONObject(0).getJSONArray("excludedKeywords").length())
    }

    @Test fun blankExcludedRowsAreOptional() {
        val source = rule().copy(excludedKeywords = listOf(" ", "退款", ""))
        assertEquals(listOf("退款"), RuleConfiguration.decode(RuleConfiguration.encode(listOf(source))).single().excludedKeywords)
    }

    @Test fun rejectsUnsupportedFormatAndVersionAndTrailingContent() {
        val valid = JSONObject(RuleConfiguration.encode(listOf(rule())))
        rejects { RuleConfiguration.decode(JSONObject(valid.toString()).put("format", "other").toString()) }
        rejects { RuleConfiguration.decode(JSONObject(valid.toString()).put("version", 99).toString()) }
        rejects { RuleConfiguration.decode(JSONObject(valid.toString()).put("version", "1").toString()) }
        rejects { RuleConfiguration.decode(JSONObject(valid.toString()).put("version", 1.5).toString()) }
        rejects { RuleConfiguration.decode("$valid {}") }
        rejects { RuleConfiguration.decode("not-json") }
    }

    @Test fun rejectsInvalidAndDuplicateRulesBeforeWriting() {
        rejects { RuleConfiguration.encode(listOf(rule(), rule())) }
        rejects { RuleConfiguration.encode(listOf(rule().copy(packageName = "bad"))) }
        rejects { RuleConfiguration.encode(listOf(rule().copy(expenseKeywords = listOf("{无关文字}{金额}元")))) }
        rejects { RuleConfiguration.encode(List(101) { rule("com.example.source$it") }) }
        val root = JSONObject(RuleConfiguration.encode(listOf(rule())))
        val row = root.getJSONArray("rules").getJSONObject(0)
        row.put("enabled", "true")
        rejects { RuleConfiguration.decode(root.toString()) }
        row.put("enabled", false).put("expenseKeywords", JSONArray().put(12))
        rejects { RuleConfiguration.decode(root.toString()) }
        row.put("expenseKeywords", JSONArray().put("spent")).put("excludedKeywords", JSONObject.NULL)
        rejects { RuleConfiguration.decode(root.toString()) }
    }

    @Test fun rejectsOversizedAndInvalidUtf8FilesAndAcceptsBom() {
        rejects { RuleConfiguration.read(ByteArrayInputStream(ByteArray(RuleConfiguration.MAX_BYTES + 1))) }
        rejects { RuleConfiguration.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        val text = "\uFEFF" + RuleConfiguration.encode(listOf(rule()))
        assertEquals(listOf(rule()), RuleConfiguration.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        assertTrue(RuleConfiguration.decode(RuleConfiguration.encode(emptyList())).isEmpty())
    }

    @Test fun mergePreservesOtherRulesAndLedgerAndRollsBackOnWriteFailure() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MoneyTrackApplication
        application.io.submit { verifyMergeAndRollback(application) }.get()
    }

    private fun verifyMergeAndRollback(context: Context) {
        // 固定独立目录且只删除指定测试数据库，绝不打开应用账本。
        val directory = context.getDir("configuration-test", Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        isolated.deleteDatabase("moneytrack.db")
        val database = LedgerDatabase(isolated)
        try {
            val original = rule()
            val other = rule("com.example.other")
            database.saveRule(original)
            database.saveRule(other)
            database.addCandidate("fictional-notification-identity", "虚构来源", 1250, 1000)
            val changed = original.copy(displayName = "修改后", enabled = true)
            database.importRules(listOf(changed, rule("com.example.added")))
            assertEquals(changed, database.rule(original.packageName))
            assertEquals(other, database.rule(other.packageName))
            assertNotNull(database.rule("com.example.added"))
            assertEquals(1L, android.database.DatabaseUtils.longForQuery(database.readableDatabase, "SELECT COUNT(*) FROM expenses", null))
            assertEquals(4L, android.database.DatabaseUtils.longForQuery(database.readableDatabase, "SELECT COUNT(*) FROM tags", null))
            database.writableDatabase.execSQL("CREATE TRIGGER reject_fixture BEFORE INSERT ON rules WHEN NEW.package_name = 'com.example.rejected' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            val failed = runCatching {
                database.importRules(listOf(original, rule("com.example.rejected")))
            }
            assertTrue(failed.isFailure)
            assertEquals(changed, database.rule(original.packageName))
            assertNull(database.rule("com.example.rejected"))
            assertEquals(8, database.readableDatabase.version)
        } finally {
            database.close()
            isolated.deleteDatabase("moneytrack.db")
        }
    }

    private fun rejects(work: () -> Unit) {
        assertTrue("无效文件应整体拒绝", runCatching(work).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun configurationIncludesLinksAndReadsLegacyVersionOne() {
        val sources = listOf(rule(), rule("com.example.bank"))
        val links = listOf(NotificationLinkRule(sources[0].packageName, sources[1].packageName, 30, true))
        val configuration = NotificationConfiguration(sources, links)
        assertEquals(configuration, RuleConfiguration.decodeConfiguration(RuleConfiguration.encode(configuration)))
        val legacy = JSONObject(RuleConfiguration.encode(sources)).put("version", 1).apply { remove("links") }
        assertEquals(NotificationConfiguration(sources), RuleConfiguration.decodeConfiguration(legacy.toString()))
        val malformed = JSONObject(RuleConfiguration.encode(configuration))
        malformed.getJSONArray("links").getJSONObject(0).put("windowSeconds", "30")
        rejects { RuleConfiguration.decodeConfiguration(malformed.toString()) }
        malformed.getJSONArray("links").getJSONObject(0).put("windowSeconds", 0)
        rejects { RuleConfiguration.decodeConfiguration(malformed.toString()) }
    }
}
