package com.offline.tool.sample.ui.welcome

import com.offline.tool.PackageRecord
import com.offline.tool.sample.offline.OfflinePackageCandidate
import com.offline.tool.sample.offline.OfflinePackageSource
import java.io.InputStream

internal fun interface WelcomeRepository {
    suspend fun getOfflinePackage(): OfflinePackageCandidate
}

/** 默认使用随 APK 发布的合成包；接入服务端时在这一层复用宿主的 Retrofit/Moshi。 */
internal class WelcomeRepositoryImpl(private val openBuiltin: () -> InputStream) : WelcomeRepository {
    override suspend fun getOfflinePackage() = OfflinePackageCandidate(
        PackageRecord(10_000, "28069f115248f28f7bc5d8dc42799c2d75b9c641caa374549657042f2b3ab47b"),
        OfflinePackageSource.Builtin(openBuiltin),
    )
}
