package com.offline.tool.sample.offline

import com.offline.tool.sample.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class OfflinePagePackage(
    val directory: File,
    val baseUrl: String,
    val onResourceFailure: ((String, String) -> Unit)? = null,
)

/** 每个 WebView 获取一次固定目录；静默升级不切换正在浏览的页面。 */
internal class OfflinePageAdapter(
    private val packages: DemoOfflinePackages,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun acquireForPage(url: String, configuredBase: String): OfflinePagePackage? =
        withContext(ioDispatcher) {
            val base = matchingOfflineBase(url, configuredBase) ?: return@withContext null
            val record = packages.current() ?: return@withContext null
            if (!packages.isUsable(record)) return@withContext null
            val directory = packages.bindDirectory(record)
            if (!BuildConfig.DEBUG) return@withContext OfflinePagePackage(directory, base)
            val reported = ConcurrentHashMap.newKeySet<String>()
            OfflinePagePackage(directory, base) { reason, path ->
                if (reported.add("$reason:$path")) {
                    logOffline("resource=$reason version=${record.version} path=$path")
                }
            }
        }
}
