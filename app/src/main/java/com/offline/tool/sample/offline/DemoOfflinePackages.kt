package com.offline.tool.sample.offline

import com.offline.tool.PackageInstaller
import com.offline.tool.PackageRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException

/** 示例的本地存储入口；安装任务由 Welcome 单入口顺序编排。 */
internal class DemoOfflinePackages(
    private val installer: PackageInstaller,
    private val recordFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var prepared = false

    @Volatile
    private var pagesBound = false

    suspend fun current(): PackageRecord? = withContext(ioDispatcher) {
        try {
            if (!recordFile.isFile) return@withContext null
            recordFile.source().buffer().use { source ->
                val version = source.readUtf8LineStrict().toIntOrNull() ?: return@use null
                val digest = source.readUtf8LineStrict()
                if (version < 10_000 || !Regex("[0-9a-f]{64}").matches(digest)) null
                else PackageRecord(version, digest)
            }
        } catch (_: IOException) {
            null
        }
    }

    suspend fun hasUsablePackage(): Boolean = current()?.let { isUsable(it) } == true

    suspend fun isUsable(record: PackageRecord): Boolean = withContext(ioDispatcher) {
        val entry = installer.directory(record.version).resolve("index.html")
        entry.isFile && entry.canRead() && entry.length() > 0L
    }

    fun bindDirectory(record: PackageRecord): File {
        pagesBound = true
        return installer.directory(record.version)
    }

    suspend fun prepareLocal(): OfflineInstallResult = withContext(ioDispatcher) {
        val record = current()
        if (!prepared && !pagesBound) {
            val usable = record != null && isUsable(record)
            if (!installer.clearOldVersions(record?.takeIf { usable }?.version)) {
                return@withContext OfflineInstallResult.Failure("CLEANUP_FAILED", "cleanup")
            }
        }
        prepared = true
        OfflineInstallResult.Success(record)
    }

    suspend fun prepareTarget(record: PackageRecord): OfflineInstallResult.Failure? = withContext(ioDispatcher) {
        val exists = installer.directory(record.version).exists()
        when {
            pagesBound && (current()?.version == record.version || exists) ->
                OfflineInstallResult.Failure("TARGET_IN_USE", "publish")

            exists && !installer.discardUnboundVersion(record.version) ->
                OfflineInstallResult.Failure("CLEANUP_FAILED", "cleanup")

            else -> null
        }
    }

    /** 先关闭临时文件，再原子替换记录；失败时保留旧记录，已发布目录留待下次重试处理。 */
    suspend fun save(record: PackageRecord): OfflineInstallResult = withContext(ioDispatcher) {
        try {
            val parent = checkNotNull(recordFile.parentFile)
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create record directory")
            val temp = File(parent, "${recordFile.name}.tmp")
            temp.sink().buffer().use { it.writeUtf8("${record.version}\n${record.sha256}\n") }
            FileSystem.SYSTEM.atomicMove(temp.toOkioPath(), recordFile.toOkioPath())
            OfflineInstallResult.Success(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OfflineInstallResult.Failure("RECORD_SAVE_FAILED", "save", error)
        }
    }
}
