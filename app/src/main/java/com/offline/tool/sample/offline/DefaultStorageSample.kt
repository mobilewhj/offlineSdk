package com.offline.tool.sample.offline

import com.offline.tool.ManagedConfigProvider
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.ManagedProgress
import com.offline.tool.StartupResult
import java.io.File

/** 新 App 的默认存储样例：Application 持有一个实例，namespace 与旧 Demo 数据隔离。
 * 调用者在 Main 更新 manager 条件，在页面生命周期协程调用 open；取消保持异常。
 * 真实 App 应在 Continue 后先完成业务配置/广告门禁，再从自己的页面调用 loadPage。 */
internal class DefaultStorageSample(
    packageRoot: File,
    noBackupDirectory: File,
    configProvider: ManagedConfigProvider,
) {
    val manager = ManagedOfflineSdk(
        root = packageRoot,
        storage = ManagedOfflineStorage.default(noBackupDirectory, namespace = "new-app-sample"),
        configProvider = configProvider,
    )

    /** 样例没有业务门禁；只消费等待资格，不解读安装结果，也不实现存储方法。 */
    suspend fun open(
        url: String,
        baseUrl: String,
        callbacks: ManagedPageCallbacks,
        onProgress: (ManagedProgress) -> Unit = {},
    ): StartupResult {
        val result = manager.prepareStartup(onProgress)
        if (result == StartupResult.Continue) manager.loadPage(url, baseUrl, callbacks)
        return result
    }
}
