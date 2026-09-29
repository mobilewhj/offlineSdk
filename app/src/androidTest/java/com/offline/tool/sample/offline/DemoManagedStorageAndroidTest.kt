package com.offline.tool.sample.offline

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offline.tool.ConfigResponse
import com.offline.tool.FirstPreparationResult
import com.offline.tool.ManagedConfigProvider
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.OfflineConfiguration
import com.offline.tool.OfflineInterceptor
import com.offline.tool.PageDecision
import com.offline.tool.StartupDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 真实 Demo 存储和管理器链的 Main 磁盘边界；仅操作测试私有目录与偏好文件。 */
@RunWith(AndroidJUnit4::class)
class DemoManagedStorageAndroidTest {
    @Test fun initializationConfigurationAndPageLoadKeepDemoPersistenceOffMain() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "offline_strict_$suffix"
        val testFiles = File(context.cacheDir, "offline_strict_files_$suffix")
        val root = File(context.cacheDir, "offline_strict_packages_$suffix")
        val scoped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testFiles
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferencesName, mode)
        }
        val storage = DemoManagedStorage(scoped)
        val violations = CopyOnWriteArrayList<String>()
        val original = AtomicReference<StrictMode.ThreadPolicy>()
        val sdk = ManagedOfflineSdk(root, storage, ManagedConfigProvider {
            ConfigResponse.Success(OfflineConfiguration(enabled = false))
        })
        try {
            instrumentation.runOnMainSync {
                original.set(StrictMode.getThreadPolicy())
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(original.get())
                        .detectDiskReads().detectDiskWrites()
                        .penaltyListener({ command -> command.run() }, { violations += it.toString() })
                        .build()
                )
            }
            runBlocking {
                withContext(Dispatchers.Main) {
                    assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())
                    sdk.setConditions(true, true)
                    assertTrue(sdk.prepareFirst(onProgress = {}) is FirstPreparationResult.Finished)
                    var onlineLoads = 0
                    val callbacks = object : ManagedPageCallbacks {
                        override fun clearResourceCache() = true
                        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
                            error("已关闭离线功能却加载了离线页面")
                        }
                        override fun loadOnline(url: String) { onlineLoads++ }
                    }
                    val base = "https://offline.example/demo/"
                    assertEquals(PageDecision.Online, sdk.loadPage(base, base, callbacks))
                    assertEquals(1, onlineLoads)
                }
                withTimeout(5_000L) {
                    while (withContext(Dispatchers.IO) { storage.readCacheDirty() } != false) delay(10L)
                }
            }
            val drained = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertTrue("Demo 存储与管理器 Main 链发生磁盘 IO: $violations", violations.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                sdk.shutdown()
                original.get()?.let(StrictMode::setThreadPolicy)
            }
            root.deleteRecursively()
            testFiles.deleteRecursively()
            context.deleteSharedPreferences(preferencesName)
        }
    }
}
