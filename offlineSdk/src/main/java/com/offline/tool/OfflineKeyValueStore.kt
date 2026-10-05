package com.offline.tool

import android.content.Context
import java.io.File

/** 由 SDK 的 IO 边界调用。null 只表示确认缺键，读取故障抛出；写入完成同步提交才返回 true。 */
interface OfflineKeyValueStore {
    fun readString(key: String): String?
    /** null 表示删除；普通失败返回 false，取消原样传播。 */
    fun writeString(key: String, value: String?): Boolean
    fun readBoolean(key: String): Boolean?
    fun writeBoolean(key: String, value: Boolean): Boolean
}

/** 保留接入方原有键名与 String/Boolean 类型；四个值分别提交，没有跨键事务。 */
data class OfflineStorageKeys(
    val active: String,
    val enabled: String,
    val history: String,
    val cacheDirty: String,
)

/** 不新增宿主状态，只将固定编码与四原语连接到原有端口。 */
internal class KeyValueOfflineStorage(
    private val values: OfflineKeyValueStore,
    private val keys: OfflineStorageKeys,
    private val legacyEvidence: suspend () -> LegacyPreparationEvidence?,
) : ManagedOfflineStorage {
    override suspend fun readActive(): PackageRecord? =
        values.readString(keys.active)?.let(OfflineStorageCodec::decodeActive)
    override suspend fun writeActive(record: PackageRecord?): Boolean = write {
        values.writeString(keys.active, record?.let(OfflineStorageCodec::encodeActive))
    }
    override suspend fun readEnabled(): Boolean? = values.readBoolean(keys.enabled)
    override suspend fun writeEnabled(enabled: Boolean): Boolean = write { values.writeBoolean(keys.enabled, enabled) }
    override suspend fun readHistory(): PreparationHistory? =
        values.readString(keys.history)?.let(OfflineStorageCodec::decodeHistory)
    override suspend fun writeHistory(history: PreparationHistory): Boolean = write {
        values.writeString(keys.history, OfflineStorageCodec.encodeHistory(history))
    }
    override fun readCacheDirty(): Boolean? = values.readBoolean(keys.cacheDirty)
    override suspend fun writeCacheDirty(dirty: Boolean): Boolean = write { values.writeBoolean(keys.cacheDirty, dirty) }
    override suspend fun readLegacyEvidence(): LegacyPreparationEvidence? = legacyEvidence()

    private inline fun write(block: () -> Boolean): Boolean = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/** 默认存储首次 IO 校验安装目录；不能把状态文件放到 SDK 会清理的安装根目录中。 */
internal interface InstallationRootBoundStorage {
    fun validateInstallationRoot(root: File)
}

internal fun defaultOfflineStorage(context: Context, namespace: String): ManagedOfflineStorage {
    val application = context.applicationContext
    validateStorageNamespace(namespace)
    // Context 的 noBackupFilesDir getter 可能建目录，必须推迟到第一次 IO。
    return FileManagedOfflineStorage(FileOfflineValues({
        File(application.noBackupFilesDir, "offline-sdk-state/$namespace")
    }))
}

internal fun defaultOfflineStorage(noBackupDirectory: File, namespace: String): ManagedOfflineStorage {
    validateStorageNamespace(namespace)
    return FileManagedOfflineStorage(FileOfflineValues({ File(noBackupDirectory, "offline-sdk-state/$namespace") }))
}

private fun validateStorageNamespace(namespace: String) {
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(namespace)) { "Invalid offline storage namespace" }
}
