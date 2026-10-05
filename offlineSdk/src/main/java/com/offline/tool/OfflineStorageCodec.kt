package com.offline.tool

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.StringReader

/** SDK 固定存储格式；未知诊断只丢明细，损坏的记录/关键类型必须作为读取故障。 */
object OfflineStorageCodec {
    fun encodeActive(record: PackageRecord): String = JSONObject().apply {
        put("version", record.version)
        put("sha256", record.sha256)
    }.toString()

    /** 仅解码已存事实，不检查业务下限、摘要资格或资源文件。 */
    fun decodeActive(json: String): PackageRecord {
        val value = objectValue(json)
        return PackageRecord(value.integer("version", required = true)!!,
            value.string("sha256", required = true)!!)
    }

    fun encodeHistory(history: PreparationHistory): String = JSONObject().apply {
        put("initialPreparationFinished", history.initialPreparationFinished)
        history.latestFailure?.let { failure ->
            put("latestFailure", JSONObject().apply {
                put("reason", reasons.first { it.value == failure.reason }.code)
                put("stage", stages.first { it.value == failure.stage }.code)
                failure.targetVersion?.let { put("targetVersion", it) }
                failure.targetSha256?.let { put("targetSha256", it) }
                failure.installReason?.let { reason -> put("installReason", installReasons.first { it.value == reason }.code) }
                failure.httpStatus?.let { put("httpStatus", it) }
                put("occurredAtMillis", failure.occurredAtMillis)
                put("activeRollbackFailed", failure.activeRollbackFailed)
            })
        }
    }.toString()

    fun decodeHistory(json: String): PreparationHistory {
        val value = objectValue(json)
        val finished = value.boolean("initialPreparationFinished", false)
        val encoded = value.optional("latestFailure")
        if (encoded != null && encoded !is JSONObject) throw JSONException("latestFailure must be an object")
        return PreparationHistory(finished, (encoded as? JSONObject)?.failure())
    }

    private fun JSONObject.failure(): ManagedFailure? {
        // 先检查类型，再判断未知编码，不能让坏字段因未知原因而被吞掉。
        val reasonCode = string("reason")
        val stageCode = string("stage")
        val version = integer("targetVersion")
        val sha = string("targetSha256")
        val installCode = string("installReason")
        val status = integer("httpStatus")
        val time = long("occurredAtMillis")
        val rollback = boolean("activeRollbackFailed", false)
        val reason = reasons.read(reasonCode) ?: return null
        val stage = stages.read(stageCode) ?: return null
        val install = installCode?.let { installReasons.read(it) ?: return null }
        return ManagedFailure(reason, stage, version, sha, install, status,
            occurredAtMillis = time ?: return null, activeRollbackFailed = rollback)
    }

    private fun objectValue(json: String): JSONObject {
        if (json.isBlank()) throw JSONException("Empty stored JSON")
        // JSONObject/JSONTokener 接受部分非法语法；先严格消费全文，不能把坏存储转换为可清理的事实。
        try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                JsonParser.parseReader(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw JSONException("Stored JSON must be one object")
            }
        } catch (_: JsonParseException) {
            // 不附带原文或 parser cause，避免读取故障诊断泄漏持久内容。
            throw JSONException("Stored JSON is invalid")
        } catch (_: IOException) {
            throw JSONException("Stored JSON is invalid")
        }
        val reader = JSONTokener(json)
        val value = reader.nextValue()
        if (value !is JSONObject || reader.nextClean() != '\u0000') throw JSONException("Stored JSON must be one object")
        return value
    }

    private fun JSONObject.optional(key: String): Any? =
        if (!has(key) || isNull(key)) null else get(key)

    private fun JSONObject.string(key: String, required: Boolean = false): String? {
        val value = optional(key)
        if (value == null && !required) return null
        if (value !is String) throw JSONException("$key must be a string")
        return value
    }

    private fun JSONObject.long(key: String): Long? {
        val value = optional(key) ?: return null
        if (value !is Int && value !is Long) throw JSONException("$key must be an integer")
        return (value as Number).toLong()
    }

    private fun JSONObject.integer(key: String, required: Boolean = false): Int? {
        val value = long(key)
        if (value == null && !required) return null
        if (value == null || value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw JSONException("$key must fit Int")
        return value.toInt()
    }

    private fun JSONObject.boolean(key: String, absent: Boolean): Boolean {
        if (!has(key)) return absent
        val value = get(key)
        if (value !is Boolean) throw JSONException("$key must be a boolean")
        return value
    }

    /** 大写码是既有 Demo 的明确读取别名；写入始终使用固定小写码，不生成 enum.name/ordinal。 */
    private data class Code<T>(val value: T, val code: String, val legacy: String)
    private fun <T> List<Code<T>>.read(code: String?): T? = firstOrNull { it.code == code || it.legacy == code }?.value

    private val reasons = listOf(
        Code(ManagedFailureReason.CONFIG_REQUEST, "config_request", "CONFIG_REQUEST"),
        Code(ManagedFailureReason.CONFIG_PARSE, "config_parse", "CONFIG_PARSE"),
        Code(ManagedFailureReason.CONFIG_UNAVAILABLE, "config_unavailable", "CONFIG_UNAVAILABLE"),
        Code(ManagedFailureReason.INVALID_CONFIG, "invalid_config", "INVALID_CONFIG"),
        Code(ManagedFailureReason.LOCAL_PREPARATION, "local_preparation", "LOCAL_PREPARATION"),
        Code(ManagedFailureReason.TARGET_IN_USE, "target_in_use", "TARGET_IN_USE"),
        Code(ManagedFailureReason.STORAGE_READ, "storage_read", "STORAGE_READ"),
        Code(ManagedFailureReason.STORAGE_WRITE, "storage_write", "STORAGE_WRITE"),
        Code(ManagedFailureReason.INSTALL, "install", "INSTALL"),
        Code(ManagedFailureReason.PACKAGE_UNUSABLE, "package_unusable", "PACKAGE_UNUSABLE"),
    )
    private val stages = listOf(
        Code(ManagedStage.LOCAL, "local", "LOCAL"),
        Code(ManagedStage.CONFIG, "config", "CONFIG"),
        Code(ManagedStage.PREPARE, "prepare", "PREPARE"),
        Code(ManagedStage.DOWNLOAD, "download", "DOWNLOAD"),
        Code(ManagedStage.VERIFY, "verify", "VERIFY"),
        Code(ManagedStage.EXTRACT, "extract", "EXTRACT"),
        Code(ManagedStage.PUBLISH, "publish", "PUBLISH"),
        Code(ManagedStage.CLEANUP, "cleanup", "CLEANUP"),
        Code(ManagedStage.SAVE_ACTIVE, "save_active", "SAVE_ACTIVE"),
        Code(ManagedStage.VERIFY_ACTIVE, "verify_active", "VERIFY_ACTIVE"),
        Code(ManagedStage.SAVE_ENABLED, "save_enabled", "SAVE_ENABLED"),
        Code(ManagedStage.HISTORY, "history", "HISTORY"),
        Code(ManagedStage.CACHE, "cache", "CACHE"),
    )
    private val installReasons = listOf(
        Code(FailureReason.INVALID_RECORD, "invalid_record", "INVALID_RECORD"),
        Code(FailureReason.INVALID_URL, "invalid_url", "INVALID_URL"),
        Code(FailureReason.DOWNLOAD, "download", "DOWNLOAD"),
        Code(FailureReason.DOWNLOAD_TIMEOUT, "download_timeout", "DOWNLOAD_TIMEOUT"),
        Code(FailureReason.HASH_MISMATCH, "hash_mismatch", "HASH_MISMATCH"),
        Code(FailureReason.INVALID_ARCHIVE, "invalid_archive", "INVALID_ARCHIVE"),
        Code(FailureReason.SIZE_LIMIT, "size_limit", "SIZE_LIMIT"),
        Code(FailureReason.TARGET_EXISTS, "target_exists", "TARGET_EXISTS"),
        Code(FailureReason.FILE_IO, "file_io", "FILE_IO"),
        Code(FailureReason.CLEANUP, "cleanup", "CLEANUP"),
        Code(FailureReason.PUBLISH, "publish", "PUBLISH"),
        Code(FailureReason.MANAGED_ROOT, "managed_root", "MANAGED_ROOT"),
    )
}
