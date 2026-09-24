package com.offline.tool.sample

import com.offline.tool.InstallResult
import com.offline.tool.PackageInstaller

/** 仅在本地 Maven 消费验证时注入示例编译，覆盖本轮新增的公开 API。 */
internal suspend fun consumeNewSdkApi(installer: PackageInstaller, result: InstallResult): Boolean =
    when (result) {
        is InstallResult.Success -> result.requestStarted && installer.isUsable(result.record.version)
        is InstallResult.Failure -> result.requestStarted || result.publishedRecord != null
    }
