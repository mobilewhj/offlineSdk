package com.offline.tool.sample

import com.offline.tool.InstallationOutcome
import com.offline.tool.ManagedConfigProvider
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.OfflineInterceptor
import com.offline.tool.PackageInstaller
import com.offline.tool.PackageRecord
import com.offline.tool.PageDecision
import com.offline.tool.StartupDecision
import java.io.File
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅用于本地 Maven AAR 消费编译；Main 可直接创建，首次入口由 SDK 切 IO。 */
internal fun createManagedSdkFromAar(
    root: File,
    storage: ManagedOfflineStorage,
    configProvider: ManagedConfigProvider,
    downloadClient: OkHttpClient,
): ManagedOfflineSdk =
    ManagedOfflineSdk(
        root = root,
        storage = storage,
        configProvider = configProvider,
        minimumVersion = 10_000,
        downloadClient = downloadClient,
        onInstallationOutcome = { outcome ->
            when (outcome) {
                is InstallationOutcome.Installed -> outcome.record.version
                is InstallationOutcome.Failed -> outcome.failure.targetVersion
            }
        },
        onDiagnostic = { failure -> failure.reason },
    )

/** 三项必需参数也能独立消费已发布 AAR。 */
internal fun createMinimalManagedSdkFromAar(
    root: File,
    storage: ManagedOfflineStorage,
    configProvider: ManagedConfigProvider,
): ManagedOfflineSdk = ManagedOfflineSdk(root, storage, configProvider)

internal suspend fun consumeManagedSdkFromAar(
    manager: ManagedOfflineSdk,
    callbacks: ManagedPageCallbacks,
    url: String,
    baseUrl: String,
): Boolean = withContext(Dispatchers.Main.immediate) {
    manager.setConditions(privacyAllowed = true, foreground = true)
    if (manager.startupDecision() == StartupDecision.NEEDS_FIRST_PREPARATION) {
        manager.prepareFirst()
    }
    val decision = manager.loadPage(url, baseUrl, callbacks)
    return@withContext manager.state.value.initialPreparationFinished &&
        decision is PageDecision.Offline
}

/** 缓存标记端口在 0.3.0 中改为 IO 挂起确认，消费侧必须按新签名重编。 */
internal suspend fun consumeCacheStoragePortFromAar(storage: ManagedOfflineStorage): Boolean =
    withContext(Dispatchers.IO) { storage.writeCacheDirty(true) }

/** 在独立旧根目录编译已发布低层调用；托管根目录不可由此安装器写入。 */
internal suspend fun consumePublishedLowLevelApiFromAar(
    legacyRoot: File,
    record: PackageRecord,
    baseUrl: String,
    downloadClient: OkHttpClient,
): Boolean = withContext(Dispatchers.IO) {
    val installer = PackageInstaller(legacyRoot, downloadClient, 20_000L, Dispatchers.IO)
    val interceptor = OfflineInterceptor(
        installer.directory(record.version), baseUrl,
        allowHttpAndHttps = true,
        debugLogging = false,
        onResourceFailure = { _, _ -> },
    )
    installer.isUsable(record.version) && interceptor.resolve(baseUrl) != null
}
