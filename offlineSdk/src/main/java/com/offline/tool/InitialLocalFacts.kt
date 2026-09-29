package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/** 只描述一次读取的结果；失败不能被解释为没有 active，也不能授予目录清理资格。 */
internal sealed interface InitialLocalRead {
    data object Failed : InitialLocalRead

    data class Ready(
        val active: PackageRecord?,
        val usablePackage: Boolean,
        val history: PreparationHistory,
        val enabled: Boolean,
        val storedCacheDirty: Boolean?,
        val cacheReadFailed: Boolean,
        val historyMissing: Boolean,
    ) : InitialLocalRead
}

/**
 * 在 IO 中读取并解释首次启动的本地事实。只返回不可变快照，不写存储、不删除目录，
 * 也不保存运行状态；状态提交、迁移保存及冷清理资格仍由管理器统一决定。
 * 包校验以只读函数传入，辅助实现不会获得安装器的目录修改权限。
 */
internal suspend fun readInitialLocalFacts(
    root: File,
    storage: ManagedOfflineStorage,
    minimumVersion: Int,
    ioDispatcher: CoroutineDispatcher,
    isValidRecord: (PackageRecord) -> Boolean,
    isPackageUsable: suspend (Int) -> Boolean,
): InitialLocalRead = withContext(ioDispatcher) {
    val storedActive: PackageRecord?
    val storedHistory: PreparationHistory?
    val legacy: LegacyPreparationEvidence?
    val storedEnabled: Boolean?
    try {
        storedActive = storage.readActive()
        storedHistory = storage.readHistory()
        legacy = if (storedHistory == null) storage.readLegacyEvidence() else null
        storedEnabled = storage.readEnabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return@withContext InitialLocalRead.Failed
    }

    val validActive = storedActive?.takeIf(isValidRecord)
    val oldUsable = validActive == null && storedHistory == null &&
        hasOldUsableDirectory(root, minimumVersion, isPackageUsable)
    val activeUsable = validActive != null && isPackageUsable(validActive.version)
    val interpretedHistory = storedHistory ?: PreparationHistory(
        initialPreparationFinished = validActive != null || oldUsable || legacy != null,
        latestFailure = legacy?.latestFailure,
    )
    val completed = interpretedHistory.initialPreparationFinished || validActive != null || oldUsable

    // 缓存标记失败只触发保守清缓存，不否定已经完整读取的包事实；真实取消仍归调用任务所有。
    var cacheReadFailed = false
    val storedDirty = try { storage.readCacheDirty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cacheReadFailed = true; null }

    InitialLocalRead.Ready(
        active = validActive,
        usablePackage = activeUsable,
        history = interpretedHistory.copy(initialPreparationFinished = completed),
        enabled = storedEnabled != false,
        storedCacheDirty = storedDirty,
        cacheReadFailed = cacheReadFailed,
        historyMissing = storedHistory == null,
    )
}

/** 旧目录只证明历史首装已经尝试完成，不会据此伪造缺失的 active 记录。 */
private suspend fun hasOldUsableDirectory(
    root: File,
    minimumVersion: Int,
    isPackageUsable: suspend (Int) -> Boolean,
): Boolean {
    val children = try { root.listFiles() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return false
    for (child in children) {
        val version = child.name.toIntOrNull() ?: continue
        if (version >= minimumVersion && isPackageUsable(version)) return true
    }
    return false
}
