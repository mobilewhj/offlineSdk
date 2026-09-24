package com.offline.tool

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Source
import okio.source
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException

/**
 * 离线包安装入口，串行协调临时文件、摘要校验、解压和目录发布。
 *
 * 下载交给 PackageDownloader，ZIP 处理交给 PackageArchive；本类不读取或保存版本记录。
 * Success 仅表示目录已发布，调用方保存返回的 PackageRecord 成功后才能切换当前版本。
 *
 * @param ioDispatcher 执行阻塞网络和文件操作的后台调度器，默认使用 Dispatchers.IO。
 */
class PackageInstaller(
    private val root: File,
    httpClient: OkHttpClient = OkHttpClient(),
    downloadTimeoutMillis: Long = 120_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val downloader = PackageDownloader(httpClient, downloadTimeoutMillis, ioDispatcher)
    private val mutex = Mutex()

    /** 返回版本目录但不创建它；保留读取 10000 起历史版本的能力。 */
    fun directory(version: Int): File {
        require(version >= MIN_PACKAGE_VERSION)
        return File(root, version.toString())
    }

    /**
     * 下载并安装指定版本。下载与解压回调均在 ioDispatcher 上执行，默认使用 IO，未知下载总量用 null 表示。
     * requestStarted 在进入 HTTP Call.execute 前置为 true，与响应和进度回调无关。
     * 取消以 CancellationException 向上传播，同时关闭本次请求并清理临时文件。
     */
    suspend fun install(
        record: PackageRecord,
        url: String,
        onDownloadProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null,
        onExtractProgress: ((Int) -> Unit)? = null,
    ): InstallResult {
        val attempt = DownloadAttempt()
        return try {
            val result = downloader.withSource(url, onDownloadProgress, attempt) { openSource ->
                installZip(record.version, record.sha256, openSource, onExtractProgress)
            }
            when (result) {
                is InstallResult.Success -> result.copy(requestStarted = attempt.requestStarted)
                is InstallResult.Failure -> result.copy(requestStarted = attempt.requestStarted)
            }
        } catch (error: InstallException) {
            currentCoroutineContext().ensureActive()
            // URL 在进入安装流程前校验；请求读取错误由 installZip 按实际阶段转换。
            InstallResult.Failure(
                reason = error.reason,
                cause = error.cause,
                httpStatus = error.status,
                stage = InstallStage.DOWNLOAD,
                message = error.message,
                requestStarted = attempt.requestStarted,
            )
        }
    }

    /**
     * 只检查版本目录的 index.html 是否为可读、非空的普通文件；不提供安装可信证明。
     * 非法版本、不安全路径、目录/入口缺失和读取失败均返回 false；取消正常传播。
     * 不创建、删除、下载或保存任何内容。文件操作在 ioDispatcher 上执行。
     * 检查不等待安装锁；结果只反映本次观察，文件在检查过程中或返回后均可能变化。
     * 页面使用期间的目录保护由调用方负责。
     */
    suspend fun isUsable(version: Int): Boolean = withContext(ioDispatcher) {
        try {
            val operationRoot = checkedRoot()
            val usable = PackageEntry.isUsable(File(operationRoot, directory(version).name))
            currentCoroutineContext().ensureActive()
            usable
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (_: SecurityException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (_: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /** 安装本地 ZIP 并计算摘要；摘要只标识输入内容，不提供独立可信校验。输入流由 SDK 关闭。 */
    suspend fun installBuiltin(version: Int, openZip: () -> InputStream): InstallResult =
        installZip(version, expectedSha256 = null, openZip = { openZip().source() })

    /** 本地安装的进度重载。在 ioDispatcher 上按实际解压字节回调 0..99，100 留给调用方确认保存成功。 */
    suspend fun installBuiltin(
        version: Int,
        onExtractProgress: (Int) -> Unit,
        openZip: () -> InputStream,
    ): InstallResult = installZip(
        version = version,
        expectedSha256 = null,
        openZip = { openZip().source() },
        onExtractProgress = onExtractProgress,
    )

    /** 校验并安装调用方提供的 ZIP；工厂在 ioDispatcher 上调用，返回的流由 SDK 关闭。 */
    suspend fun install(record: PackageRecord, openZip: () -> InputStream): InstallResult =
        installZip(record.version, record.sha256, openZip = { openZip().source() })

    /** 持锁完成一次安装；所有来源复用同一发布顺序和失败清理。 */
    private suspend fun installZip(
        version: Int,
        expectedSha256: String?,
        openZip: () -> Source,
        onExtractProgress: ((Int) -> Unit)? = null,
    ): InstallResult = withContext(ioDispatcher) {
        mutex.withLock {
            val directoryName = try {
                directory(version).name
            } catch (error: IllegalArgumentException) {
                return@withLock InstallResult.Failure(reason = FailureReason.INVALID_RECORD, cause = error)
            }
            if (expectedSha256 != null && !SHA256_PATTERN.matches(expectedSha256)) {
                return@withLock InstallResult.Failure(reason = FailureReason.INVALID_RECORD)
            }
            val operationRoot = try {
                checkedRoot()
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                return@withLock failure(error, InstallStage.PREPARE)
            } catch (error: SecurityException) {
                return@withLock InstallResult.Failure(
                    reason = FailureReason.FILE_IO, cause = error, stage = InstallStage.PREPARE
                )
            }
            val target = File(operationRoot, directoryName)
            val zip = File(operationRoot, "${version}.zip.tmp")
            val temp = File(operationRoot, "${version}_temp")
            var stage = InstallStage.PREPARE
            var result: InstallResult? = null
            var thrown: Throwable? = null
            try {
                result = try {
                    if (target.exists()) throw InstallException(FailureReason.TARGET_EXISTS, "离线包版本目录已存在")
                    if (!operationRoot.isDirectory && !operationRoot.mkdirs()) throw IOException("无法创建离线包根目录")
                    deleteChecked(zip)
                    deleteChecked(temp)
                    stage = InstallStage.DOWNLOAD
                    val digest = PackageArchive.write(zip, openZip)
                    stage = InstallStage.VERIFY
                    if (expectedSha256 != null && digest != expectedSha256) {
                        throw InstallException(FailureReason.HASH_MISMATCH, "离线包 SHA-256 校验不一致")
                    }
                    stage = InstallStage.EXTRACT
                    val content = PackageArchive.extract(zip, temp, onExtractProgress)
                    currentCoroutineContext().ensureActive()
                    // 校验全部完成后才发布；目标与临时目录同根，旧版本目录始终不被覆盖。
                    stage = InstallStage.PUBLISH
                    if (target.exists()) throw InstallException(FailureReason.TARGET_EXISTS, "离线包版本目录已存在")
                    if (!content.renameTo(target)) throw InstallException(
                        FailureReason.PUBLISH, "离线包版本目录发布失败"
                    )
                    InstallResult.Success(PackageRecord(version, digest))
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    failure(error, stage)
                } catch (error: SecurityException) {
                    InstallResult.Failure(reason = FailureReason.FILE_IO, cause = error, stage = stage)
                }
            } catch (error: Throwable) {
                // 取消及调用方回调等未转换异常直接传播，finally 仅向其附加清理错误。
                thrown = error
                throw error
            } finally {
                // 取消后也要完成临时文件清理；清理错误附加到原异常，不覆盖取消或安装失败。
                // 两个路径分别尝试；deleteChecked 不跟随符号链接。
                for (file in listOf(zip, temp)) {
                    try {
                        deleteChecked(file)
                    } catch (error: IOException) {
                        val currentResult = result
                        val previous = thrown ?: (currentResult as? InstallResult.Failure)?.cause
                        when {
                            previous != null -> previous.addSuppressed(error)
                            currentResult is InstallResult.Success -> {
                                val cleanupFailure = failure(error, InstallStage.CLEANUP)
                                result = cleanupFailure.copy(publishedRecord = currentResult.record)
                            }
                            currentResult is InstallResult.Failure -> {
                                // 原失败没有 cause 时，保留其分类和阶段，同时挂载清理异常。
                                result = currentResult.copy(cause = error)
                            }
                        }
                    }
                }
            }
            checkNotNull(result)
        }
    }

    /**
     * 保留 activeVersion 并清理其他目录，仅可在调用方确认尚无页面使用资源时执行。
     * null 表示没有当前版本，会清理全部遗留安装；当前版本入口缺失时返回 false，不执行清理。
     */
    suspend fun clearOldVersions(activeVersion: Int?): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                val operationRoot = checkedRoot()
                val active = activeVersion?.let { File(operationRoot, directory(it).name) }
                if (active != null) {
                    val usable = PackageEntry.isUsable(active)
                    currentCoroutineContext().ensureActive()
                    if (!usable) {
                        return@withLock false
                    }
                }
                if (operationRoot.exists()) children(operationRoot).filter { it != active }.forEach {
                    currentCoroutineContext().ensureActive()
                    deleteChecked(it)
                }
                true
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    /** 仅删除指定版本残留。调用方须确认该目录未交付页面，本方法不跟踪页面生命周期。 */
    suspend fun discardUnboundVersion(version: Int): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                val operationRoot = checkedRoot()
                currentCoroutineContext().ensureActive()
                deleteChecked(File(operationRoot, directory(version).name))
                true
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    /** 父目录允许系统路径别名；根目录自身不能是重定向到其他目录的链接。仅在 IO 操作内调用。 */
    private fun checkedRoot(): File {
        val absolute = root.absoluteFile
        val normalized = absolute.parentFile?.let { File(it.canonicalFile, absolute.name) } ?: absolute
        if (normalized.canonicalFile != normalized) {
            throw InstallException(FailureReason.FILE_IO, "离线包根目录自身不能是符号链接或非规范路径")
        }
        return normalized
    }

    private fun children(file: File): Array<File> = file.listFiles()
        ?: throw InstallException(FailureReason.CLEANUP, "无法读取离线包目录内容：$file")

    private fun deleteChecked(file: File) {
        try {
            // 链接只删除链接自身，绝不递归进入其目标。
            if (file.canonicalFile == file.absoluteFile && file.isDirectory) children(file).forEach(::deleteChecked)
            if (!file.delete() && file.exists()) throw InstallException(
                FailureReason.CLEANUP, "无法删除离线包文件或目录：$file"
            )
        } catch (error: SecurityException) {
            throw InstallException(FailureReason.CLEANUP, "无权访问离线包文件或目录：$file", error)
        }
    }

    private fun failure(error: IOException, stage: InstallStage): InstallResult.Failure = when (error) {
        is InstallException -> InstallResult.Failure(
            reason = error.reason,
            cause = error.cause ?: error,
            httpStatus = error.status,
            stage = if (error.reason == FailureReason.CLEANUP) InstallStage.CLEANUP else stage,
            message = error.message,
        )

        is ZipException, is EOFException -> InstallResult.Failure(
            reason = FailureReason.INVALID_ARCHIVE,
            cause = error,
            stage = stage,
        )

        else -> InstallResult.Failure(reason = FailureReason.FILE_IO, cause = error, stage = stage)
    }

    private companion object {
        const val MIN_PACKAGE_VERSION = 10_000
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
