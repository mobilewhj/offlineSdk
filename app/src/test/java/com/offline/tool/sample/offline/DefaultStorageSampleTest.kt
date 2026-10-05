package com.offline.tool.sample.offline

import com.offline.tool.ConfigResponse
import com.offline.tool.ManagedConfigProvider
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.OfflineConfiguration
import com.offline.tool.OfflineInterceptor
import com.offline.tool.PreparationHistory
import com.offline.tool.StartupResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 公开默认存储使用 test-only Os 夹具完成真实文件提交和冷读，不计 Android 设备通过。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultStorageSampleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultStorageSampleUsesOneStartupAndOriginalPageUrlWithoutCustomStorage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val noBackupDirectory = temporary.newFolder("no-backup")
        val sample = DefaultStorageSample(
            temporary.newFolder("packages"),
            noBackupDirectory,
            ManagedConfigProvider {
                requests++
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            },
        )
        val callbacks = PageCallbacks()
        try {
            sample.manager.setConditions(privacyAllowed = true, foreground = true)
            val call = async { sample.open(URL, BASE, callbacks) }
            awaitCompletion(call)
            assertEquals(StartupResult.Continue, call.await())
            assertEquals(1, requests)
            assertEquals(listOf(URL), callbacks.onlineUrls)
            assertEquals(1, callbacks.clears)
            assertTrue(sample.manager.state.value.initialPreparationFinished)
            sample.manager.shutdown()
            // 新工厂对象不共享 manager 的完成事实，必须读回已确认落盘的同 namespace。
            val coldStorage = ManagedOfflineStorage.default(noBackupDirectory, namespace = "new-app-sample")
            withContext(Dispatchers.IO) {
                assertEquals(PreparationHistory(initialPreparationFinished = true), coldStorage.readHistory())
                assertEquals(false, coldStorage.readEnabled())
            }
        } finally {
            sample.manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    @Test fun defaultStorageSampleDefersWithoutConsentAndDoesNotLoadPageOrRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val sample = DefaultStorageSample(
            temporary.newFolder("packages"),
            temporary.newFolder("no-backup"),
            ManagedConfigProvider {
                requests++
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            },
        )
        val callbacks = PageCallbacks()
        try {
            sample.manager.setConditions(privacyAllowed = false, foreground = true)
            val call = async { sample.open(URL, BASE, callbacks) }
            awaitCompletion(call)
            assertEquals(StartupResult.Deferred, call.await())
            assertEquals(0, requests)
            assertTrue(callbacks.onlineUrls.isEmpty())
            assertEquals(0, callbacks.clears)
        } finally {
            sample.manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    private class PageCallbacks : ManagedPageCallbacks {
        var clears = 0
        val onlineUrls = mutableListOf<String>()
        override fun clearResourceCache(): Boolean { clears++; return true }
        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
            error("关闭或未同意时不应加载离线包")
        }
        override fun loadOnline(url: String) { onlineUrls += url }
    }

    private fun TestScope.awaitCompletion(call: Deferred<*>) {
        repeat(300) {
            runCurrent()
            if (call.isCompleted) return
            Thread.sleep(10)
        }
        assertTrue("默认存储样例入口未完成", call.isCompleted)
    }

    private companion object {
        const val BASE = "https://offline.example/new-app/"
        const val URL = "https://offline.example/new-app/?original=1#route"
    }
}
