package com.moneytrack.app.data

import com.moneytrack.core.PaymentRule
import com.moneytrack.core.NotificationLinkRule
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 识别规则与主副联动作为一份通知配置传输。 */
data class NotificationConfiguration(val rules: List<PaymentRule>, val links: List<NotificationLinkRule> = emptyList())

/** 仅传输通知配置。格式版本独立于应用/数据库版本，不包含账单或系统授权。 */
object RuleConfiguration {
    const val MAX_BYTES = 128 * 1024
    private const val FORMAT = "moneytrack-notification-rules"
    private const val VERSION = 2

    /** 文件由用户选择；限制读取量并严格解码，避免大文件或损坏文本占用页面内存。 */
    fun readConfiguration(input: InputStream): NotificationConfiguration {
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            require(count > 0) { "无法读取配置文件" }
            size += count
        }
        require(size <= MAX_BYTES) { "配置文件不能超过 128 KB" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, size)).toString() }
            .getOrElse { throw IllegalArgumentException("配置文件须使用 UTF-8 编码") }
        return decodeConfiguration(text.removePrefix("\uFEFF"))
    }

    /** 兼容仅需要识别规则的调用方；完整导入请使用 readConfiguration。 */
    fun read(input: InputStream): List<PaymentRule> = readConfiguration(input).rules

    /** 兼容旧的规则列表调用方。 */
    fun decode(text: String): List<PaymentRule> = decodeConfiguration(text).rules

    /** 所有条目先校验；错误只指出位置，不把文件全文写入日志或错误提示。 */
    fun decodeConfiguration(text: String): NotificationConfiguration {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件不能超过 128 KB" }
        val parser = JSONTokener(text)
        val root = runCatching { parser.nextValue() as? JSONObject }
            .getOrElse { throw IllegalArgumentException("配置文件不是有效的 JSON") }
        require(root != null && parser.nextClean() == '\u0000') { "配置文件不是有效的 JSON 对象" }
        require(root.opt("format") == FORMAT) { "这不是 MoneyTrack 规则配置" }
        val version = root.opt("version")
        require(version is Int && version in 1..VERSION) { "不支持此配置格式版本" }
        val entries = root.opt("rules") as? JSONArray
            ?: throw IllegalArgumentException("配置缺少规则列表")
        require(entries.length() <= 100) { "一次最多导入 100 个应用的配置" }
        val rules = (0 until entries.length()).map { index ->
            val row = entries.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("第 ${index + 1} 项不是应用配置")
            val prefix = "第 ${index + 1} 项："
            fun string(key: String): String = row.opt(key) as? String
                ?: throw IllegalArgumentException(prefix + "缺少或无效的 $key")
            fun strings(key: String): List<String> {
                val values = row.opt(key) as? JSONArray
                    ?: throw IllegalArgumentException(prefix + "缺少或无效的 $key")
                require(values.length() <= 20) { prefix + "每类规则最多 20 条" }
                return (0 until values.length()).map {
                    values.opt(it) as? String ?: throw IllegalArgumentException(prefix + "规则必须是文字")
                }
            }
            val enabled = row.opt("enabled") as? Boolean
                ?: throw IllegalArgumentException(prefix + "启停状态必须是布尔值")
            PaymentRule(string("packageName"), string("displayName"), strings("expenseKeywords"),
                strings("excludedKeywords").filter(String::isNotBlank), enabled).also {
                require(it.validationError() == null) { prefix + it.validationError() }
            }
        }
        validate(rules)
        val links = if (version == 1) {
            // 旧版本不能声明新字段，避免把联动静默当成不存在。
            require(!root.has("links")) { "含联动的配置须使用格式版本 2" }
            emptyList()
        } else {
            val rows = root.opt("links") as? JSONArray ?: throw IllegalArgumentException("配置缺少联动列表")
            require(rows.length() <= 100) { "最多配置 100 条联动" }
            (0 until rows.length()).map { index ->
                val row = rows.opt(index) as? JSONObject ?: throw IllegalArgumentException("联动配置项无效")
                NotificationLinkRule(
                    row.opt("mainPackageName") as? String ?: throw IllegalArgumentException("联动缺少主来源"),
                    row.opt("secondaryPackageName") as? String ?: throw IllegalArgumentException("联动缺少副来源"),
                    row.opt("windowSeconds") as? Int ?: throw IllegalArgumentException("联动秒数须为整数"),
                    row.opt("enabled") as? Boolean ?: throw IllegalArgumentException("联动启停须为布尔值"),
                )
            }.also { require(NotificationLinkRule.collectionError(it) == null) { NotificationLinkRule.collectionError(it).orEmpty() } }
        }
        return NotificationConfiguration(rules, links)
    }

    /** 旧接口仍可输出规则列表，联动为空；读取兼容 v1 文件。 */
    fun encode(rules: List<PaymentRule>): String = encode(NotificationConfiguration(rules))

    /** 使用 JSON 数组保留逐条规则、逗号和占位符，不重新拆分或编译用户的条件。 */
    fun encode(configuration: NotificationConfiguration): String {
        val rules = configuration.rules
        validate(rules)
        require(NotificationLinkRule.collectionError(configuration.links) == null) { NotificationLinkRule.collectionError(configuration.links).orEmpty() }
        val rows = JSONArray()
        rules.forEach { rule ->
            rows.put(JSONObject().put("packageName", rule.packageName).put("displayName", rule.displayName)
                .put("expenseKeywords", JSONArray(rule.expenseKeywords))
                .put("excludedKeywords", JSONArray(rule.excludedKeywords.filter(String::isNotBlank)))
                .put("enabled", rule.enabled))
        }
        val links = JSONArray()
        configuration.links.forEach { link -> links.put(JSONObject()
            .put("mainPackageName", link.mainPackageName).put("secondaryPackageName", link.secondaryPackageName)
            .put("windowSeconds", link.windowSeconds).put("enabled", link.enabled)) }
        val text = JSONObject().put("format", FORMAT).put("version", VERSION).put("rules", rows).put("links", links).toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置超过 128 KB，暂不能导出" }
        return text
    }

    /** 批量写入也调用同一校验，避免绕过文件入口写入重复或无效来源。 */
    fun validate(rules: List<PaymentRule>) {
        require(rules.size <= 100) { "一次最多处理 100 个应用的配置" }
        require(rules.map { it.packageName }.distinct().size == rules.size) { "配置包含重复的应用包名" }
        require(rules.all { it.validationError() == null }) { "配置包含无效规则" }
    }
}
