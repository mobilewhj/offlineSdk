package com.offline.demo.sample.offline

import com.offline.demo.InstallResult
import com.offline.demo.PackageInstaller
import com.offline.demo.PackageRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

internal sealed interface OfflineInstallResult {
    data class Success(val record: PackageRecord?) : OfflineInstallResult
    data class Failure(
        val reason: String,
        val stage: String = "local",
        val cause: Throwable? = null,
        val httpStatus: Int? = null,
    ) : OfflineInstallResult
}

internal data class LocalPreparationProgress(val stage: Stage, val percent: Int? = null) {
    enum class Stage { CHECKING, DOWNLOADING, EXTRACTING, SAVING }
}

/** Builtin 只用于随 APK 分发的可信资源；远端包必须提供可信摘要。 */
internal sealed interface OfflinePackageSource {
    data class Builtin(val openZip: () -> InputStream) : OfflinePackageSource
    data class Remote(val url: String) : OfflinePackageSource
}

internal data class OfflinePackageCandidate(val record: PackageRecord, val source: OfflinePackageSource)

/** 无独立作用域或全局进度，只编排一次安装及保存，取消继续向上传播。 */
internal class InstallOfflinePackageUseCase(
    private val packages: DemoOfflinePackages,
    private val installer: PackageInstaller,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(
        candidate: OfflinePackageCandidate,
        onProgress: (LocalPreparationProgress) -> Unit = {},
    ): OfflineInstallResult = withContext(ioDispatcher) {
        packages.prepareTarget(candidate.record)?.let { return@withContext it }
        val installed = when (val source = candidate.source) {
            is OfflinePackageSource.Builtin -> {
                onProgress(LocalPreparationProgress(LocalPreparationProgress.Stage.EXTRACTING))
                // 内置包仍走可信摘要校验；此 SDK 重载不提供字节百分比，界面显示不定进度。
                installer.install(candidate.record, source.openZip)
            }

            is OfflinePackageSource.Remote -> {
                onProgress(LocalPreparationProgress(LocalPreparationProgress.Stage.DOWNLOADING))
                installer.install(
                    candidate.record, source.url,
                    onDownloadProgress = { bytes, total ->
                        val percent = total?.takeIf { it > 0 }?.let {
                            (bytes.toDouble() / it * 100).toInt().coerceIn(0, 100)
                        }
                        onProgress(LocalPreparationProgress(LocalPreparationProgress.Stage.DOWNLOADING, percent))
                    },
                    onExtractProgress = {
                        onProgress(LocalPreparationProgress(LocalPreparationProgress.Stage.EXTRACTING, it))
                    },
                )
            }
        }
        when (installed) {
            is InstallResult.Failure -> OfflineInstallResult.Failure(
                "INSTALL_${installed.reason}", installed.stage.name.lowercase(Locale.ROOT),
                installed.cause, installed.httpStatus,
            )

            is InstallResult.Success -> {
                onProgress(LocalPreparationProgress(LocalPreparationProgress.Stage.SAVING))
                packages.save(installed.record)
            }
        }
    }
}
