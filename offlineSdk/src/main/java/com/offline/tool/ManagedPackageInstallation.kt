package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** 复用底层安装器，只适配来源、进度和失败阶段；取消保持原样向上传播。 */
internal suspend fun installManagedPackage(
    installer: PackageInstaller,
    candidate: OfflineCandidate,
    onProgress: (ManagedProgress) -> Unit,
): ManagedFailure? {
    val target = candidate.record
    val installed = try {
        when (val source = candidate.source) {
            is PackageSource.Remote -> installer.install(
                target, source.url,
                onDownloadProgress = { bytes, total -> safelyProgress(onProgress, ManagedProgress.Downloading(bytes, total)) },
                onExtractProgress = { percent -> safelyProgress(onProgress, ManagedProgress.Extracting(percent)) },
            )
            is PackageSource.Local -> installer.install(
                target,
                onExtractProgress = { percent -> safelyProgress(onProgress, ManagedProgress.Extracting(percent)) },
                openZip = {
                    try { source.openZip() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { throw IOException("Cannot open local package", error) }
                },
            )
        }
    } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return targetFailure(ManagedFailureReason.INSTALL, ManagedStage.PREPARE, target)
        }
    return when (installed) {
        is InstallResult.Success -> null
        is InstallResult.Failure -> {
            val stage = when (installed.stage) {
                InstallStage.INSTALL, InstallStage.PREPARE -> ManagedStage.PREPARE
                InstallStage.DOWNLOAD -> ManagedStage.DOWNLOAD
                InstallStage.VERIFY -> ManagedStage.VERIFY
                InstallStage.EXTRACT -> ManagedStage.EXTRACT
                InstallStage.PUBLISH -> ManagedStage.PUBLISH
                InstallStage.CLEANUP -> ManagedStage.CLEANUP
            }
            targetFailure(
                ManagedFailureReason.INSTALL, stage, target,
                installReason = installed.reason, httpStatus = installed.httpStatus,
            )
        }
    }
}

/** 保存 active 并最终复核入口；失败时尝试恢复传入的旧记录，不提交内存包事实。 */
internal suspend fun saveAndConfirmActive(
    installer: PackageInstaller,
    storage: ManagedOfflineStorage,
    target: PackageRecord,
    previous: PackageRecord?,
    ioDispatcher: CoroutineDispatcher,
    onProgress: (ManagedProgress) -> Unit,
): ManagedFailure? {
    if (!installer.isUsable(target.version)) {
        return targetFailure(ManagedFailureReason.PACKAGE_UNUSABLE, ManagedStage.VERIFY_ACTIVE, target)
    }
    safelyProgress(onProgress, ManagedProgress.Saving)
    currentCoroutineContext().ensureActive()
    val saved = try { withContext(ioDispatcher) { storage.writeActive(target) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    if (!saved) return targetFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.SAVE_ACTIVE, target)
    safelyProgress(onProgress, ManagedProgress.Confirming)
    if (!installer.isUsable(target.version)) {
        val rolledBack = try { withContext(ioDispatcher) { storage.writeActive(previous) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        return targetFailure(ManagedFailureReason.PACKAGE_UNUSABLE, ManagedStage.VERIFY_ACTIVE, target)
            .copy(activeRollbackFailed = !rolledBack)
    }
    return null
}

/** 界面进度观察故障不改变安装事实；取消仍结束尚未完成的安装。 */
internal fun safelyProgress(callback: (ManagedProgress) -> Unit, value: ManagedProgress) {
    try { callback(value) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 界面观察异常不改变安装事实。 */ }
}

/** 结果已经提交后，完成进度回调不能撤销安装终态。 */
internal fun safelyFinishedCallback(block: () -> Unit) {
    try { block() } catch (_: Exception) { /* 已提交结果不能被界面回调撤销。 */ }
}

internal fun targetFailure(
    reason: ManagedFailureReason,
    stage: ManagedStage,
    target: PackageRecord,
    installReason: FailureReason? = null,
    httpStatus: Int? = null,
) = ManagedFailure(reason, stage, target.version, target.sha256, installReason, httpStatus)
