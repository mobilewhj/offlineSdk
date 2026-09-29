package com.offline.tool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 从公开入口验证本地事实解释和提交边界，避免测试依赖内部辅助的字段布局。 */
class ManagedInitializationTest {
    @get:Rule val temporary = TemporaryFolder()

    // 同步控制入口和挂起初始化提交始终在同一个串行 owner 上执行。
    private val testMainDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "initial-test-main")
    }.asCoroutineDispatcher()

    @After fun closeTestMainDispatcher() = testMainDispatcher.close()

    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking(testMainDispatcher, block)

    private enum class FailedRead { ACTIVE, HISTORY, LEGACY, ENABLED, CACHE }

    private class Storage : ManagedOfflineStorage {
        var active: PackageRecord? = null
        var history: PreparationHistory? = null
        var cacheDirty: Boolean? = null
        var failedRead: FailedRead? = null
        var historyWrites = 0
        var cacheWrites = 0
        val readThreads = CopyOnWriteArrayList<Pair<FailedRead, Thread>>()

        private fun read(kind: FailedRead) {
            readThreads += kind to Thread.currentThread()
            if (failedRead == kind) throw IOException("read failed: $kind")
        }

        override suspend fun readActive(): PackageRecord? {
            read(FailedRead.ACTIVE)
            return active
        }
        override suspend fun writeActive(record: PackageRecord?): Boolean {
            active = record
            return true
        }
        override suspend fun readEnabled(): Boolean? {
            read(FailedRead.ENABLED)
            return true
        }
        override suspend fun writeEnabled(enabled: Boolean) = true
        override suspend fun readHistory(): PreparationHistory? {
            read(FailedRead.HISTORY)
            return history
        }
        override suspend fun writeHistory(history: PreparationHistory): Boolean {
            historyWrites++
            this.history = history
            return true
        }
        override fun readCacheDirty(): Boolean? {
            read(FailedRead.CACHE)
            return cacheDirty
        }
        override suspend fun writeCacheDirty(dirty: Boolean): Boolean {
            cacheWrites++
            cacheDirty = dirty
            return true
        }
        override suspend fun readLegacyEvidence(): LegacyPreparationEvidence? {
            read(FailedRead.LEGACY)
            return null
        }
    }

    private fun makeEntry(root: File, version: Int) = root.resolve("$version/index.html").apply {
        parentFile!!.mkdirs()
        writeText("version $version")
    }

    @Test fun incompleteLocalReadsPreserveDirectoriesAndRetryBeforeCommitting() = onMain {
        for (failedRead in listOf(FailedRead.ACTIVE, FailedRead.HISTORY, FailedRead.LEGACY, FailedRead.ENABLED)) {
            val root = temporary.newFolder()
            val activeEntry = makeEntry(root, 10000)
            val otherEntry = makeEntry(root, 10001)
            val record = PackageRecord(10000, "a".repeat(64))
            val storage = Storage().apply { active = record; this.failedRead = failedRead }
            val diagnostics = mutableListOf<ManagedFailure>()
            val sdk = ManagedOfflineSdk.forTest(
                root, storage, ManagedConfigProvider { error("初始化不能请求配置") },
                onDiagnostic = { diagnostics += it },
                mainDispatcher = testMainDispatcher,
            )
            try {
                sdk.setConditions(privacyAllowed = true, foreground = false)
                assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
                val failure = (sdk.state.value.activity as ManagedActivity.Failed).failure
                assertEquals(ManagedFailureReason.STORAGE_READ, failure.reason)
                assertEquals(ManagedStage.LOCAL, failure.stage)
                assertEquals(failure, diagnostics.single())
                assertTrue("$failedRead 必须保留 active 文件", activeEntry.isFile)
                assertTrue("$failedRead 必须保留其他候选文件", otherEntry.isFile)
                assertNull(sdk.state.value.active)
                assertFalse(sdk.state.value.initialPreparationFinished)
                assertEquals(0, storage.historyWrites)
                assertEquals(0, storage.cacheWrites)

                storage.failedRead = null
                assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
                assertEquals(record, sdk.state.value.active)
                assertTrue(sdk.state.value.usablePackage)
                assertTrue(activeEntry.isFile)
                assertFalse(otherEntry.exists())
                assertTrue(storage.history!!.initialPreparationFinished)
            } finally { sdk.shutdown() }
        }
    }

    @Test fun pageEntryCanRecoverAfterAnEarlierLocalReadFailure() = onMain {
        val root = temporary.newFolder()
        val entry = makeEntry(root, 10000)
        val record = PackageRecord(10000, "a".repeat(64))
        val storage = Storage().apply {
            active = record
            history = PreparationHistory(initialPreparationFinished = true)
            failedRead = FailedRead.ACTIVE
        }
        val sdk = ManagedOfflineSdk.forTest(
            root, storage, ManagedConfigProvider { error("页面本地恢复不能请求配置") },
            mainDispatcher = testMainDispatcher,
        )
        try {
            var loaded: File? = null
            var onlineLoads = 0
            val callbacks = object : ManagedPageCallbacks {
                override fun clearResourceCache() = true
                override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
                    loaded = directory
                }
                override fun loadOnline(url: String) { onlineLoads++ }
            }
            val url = "https://example.com/app/"
            // 故障仍存在时加载页面，必须真实调用线上加载，不能只返回降级枚举。
            assertEquals(PageDecision.Online, sdk.loadPage(url, url, callbacks))
            assertNull(sdk.state.value.active)
            assertTrue(entry.isFile)
            assertEquals(0, storage.historyWrites)
            assertEquals(1, onlineLoads)
            assertEquals(0, storage.cacheWrites)

            storage.failedRead = null
            assertEquals(PageDecision.Offline(record), sdk.loadPage(url, url, callbacks))
            assertEquals(1, onlineLoads)
            assertEquals(root.resolve("10000").canonicalFile, loaded)
            assertTrue(entry.isFile)
        } finally { sdk.shutdown() }
    }

    @Test fun mainConstructionDefersCanonicalIoAndSameManagerRecoversAfterPreparationFault() = onMain {
        val diskRoot = temporary.newFolder()
        makeEntry(diskRoot, 10000)
        val record = PackageRecord(10000, "a".repeat(64))
        val storage = Storage().apply {
            active = record
            history = PreparationHistory(initialPreparationFinished = true)
        }
        var canonicalCalls = 0
        var failCanonical = true
        val diagnostics = mutableListOf<ManagedFailure>()
        val firstThread = AtomicReference<Thread>()
        val root = object : File(diskRoot.path) {
            override fun getCanonicalPath(): String {
                canonicalCalls++
                firstThread.compareAndSet(null, Thread.currentThread())
                if (failCanonical) throw IOException("temporary root failure")
                return super.getCanonicalPath()
            }
        }
        val sdk = ManagedOfflineSdk.forTest(
            root, storage, ManagedConfigProvider { error("页面本地准备不能请求配置") },
            onDiagnostic = { diagnostics += it },
            mainDispatcher = testMainDispatcher,
        )
        try {
            assertEquals(0, canonicalCalls)
            sdk.setConditions(privacyAllowed = true, foreground = false)
            var onlineLoads = 0
            var offlineLoads = 0
            val callbacks = object : ManagedPageCallbacks {
                override fun clearResourceCache() = true
                override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) { offlineLoads++ }
                override fun loadOnline(url: String) { onlineLoads++ }
            }
            val url = "https://example.com/app/"
            assertEquals(PageDecision.Online, sdk.loadPage(url, url, callbacks))
            val pageFailure = (sdk.state.value.activity as ManagedActivity.Failed).failure
            assertEquals(ManagedFailureReason.LOCAL_PREPARATION, pageFailure.reason)
            assertEquals(ManagedStage.LOCAL, pageFailure.stage)
            assertEquals(pageFailure, diagnostics.single())
            assertEquals(1, onlineLoads)
            assertTrue(canonicalCalls > 0)
            assertTrue(firstThread.get() !== Thread.currentThread())
            assertNull(sdk.state.value.active)

            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst() as FirstPreparationResult.Finished
            val firstFailure = (first.check as CheckResult.Failed).failure
            assertEquals(ManagedFailureReason.LOCAL_PREPARATION, firstFailure.reason)
            assertEquals(ManagedStage.LOCAL, firstFailure.stage)
            assertEquals(firstFailure, (sdk.state.value.activity as ManagedActivity.Failed).failure)
            assertTrue(diagnostics.contains(firstFailure))

            failCanonical = false
            assertEquals(PageDecision.Offline(record), sdk.loadPage(url, url, callbacks))
            assertEquals(1, offlineLoads)
            assertEquals(record, sdk.state.value.active)
        } finally { sdk.shutdown() }
    }

    @Test fun cancelledOrClosedInitializationDoesNotClaimRootAfterIoReturns() = onMain {
        for (close in listOf(false, true)) {
            val diskRoot = temporary.newFolder()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val waitingRoot = object : File(diskRoot.path) {
                override fun getCanonicalPath(): String {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return super.getCanonicalPath()
                }
            }
            val first = ManagedOfflineSdk.forTest(
                waitingRoot, Storage(), ManagedConfigProvider { error("不应请求配置") },
                mainDispatcher = testMainDispatcher,
            )
            val call = async { first.startupDecision() }
            try {
                assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                if (close) first.shutdown() else call.cancel()
                release.countDown()
                if (close) assertEquals(StartupDecision.CONTINUE, call.await()) else call.join()

                // 第一个入口没有晚到 claim；新管理器可以正常接管相同 canonical 根。
                val second = ManagedOfflineSdk.forTest(
                    diskRoot, Storage(), ManagedConfigProvider { error("不应请求配置") },
                    mainDispatcher = testMainDispatcher,
                )
                try { assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, second.startupDecision()) }
                finally { second.shutdown() }
            } finally {
                release.countDown()
                first.shutdown()
            }
        }
    }

    @Test fun concurrentManagersOfSameCanonicalRootOnlyOneMayInitialize() = onMain {
        val root = temporary.newFolder()
        val first = ManagedOfflineSdk.forTest(
            root, Storage(), ManagedConfigProvider { error("不应请求配置") },
            mainDispatcher = testMainDispatcher,
        )
        val second = ManagedOfflineSdk.forTest(
            File(root.parentFile, "${root.name}/../${root.name}"), Storage(),
            ManagedConfigProvider { error("不应请求配置") },
            mainDispatcher = testMainDispatcher,
        )
        try {
            val attempts = listOf(first, second).map { sdk ->
                async { runCatching { sdk.startupDecision() } }
            }.map { it.await() }
            assertEquals(1, attempts.count { it.getOrNull() == StartupDecision.NEEDS_FIRST_PREPARATION })
            assertEquals(1, attempts.count { it.exceptionOrNull() is IllegalArgumentException })
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    @Test fun allInitialStorageReadsUseIoAndExistingHistorySkipsLegacy() = onMain {
        val ioThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "initial-facts-io").also { ioThread.set(it) }
        }
        executor.asCoroutineDispatcher().use { io ->
            for (hasHistory in listOf(false, true)) {
                val storage = Storage().apply {
                    if (hasHistory) {
                        history = PreparationHistory(initialPreparationFinished = true)
                        failedRead = FailedRead.LEGACY
                    }
                }
                val sdk = ManagedOfflineSdk.forTest(
                    temporary.newFolder(), storage, ManagedConfigProvider { error("初始化不能请求配置") },
                    ioDispatcher = io,
                    mainDispatcher = testMainDispatcher,
                )
                try {
                    assertEquals(
                        if (hasHistory) StartupDecision.CONTINUE else StartupDecision.NEEDS_FIRST_PREPARATION,
                        sdk.startupDecision(),
                    )
                    val readKinds = storage.readThreads.map { it.first }.toSet()
                    assertEquals(
                        if (hasHistory) setOf(FailedRead.ACTIVE, FailedRead.HISTORY, FailedRead.ENABLED, FailedRead.CACHE)
                        else FailedRead.entries.toSet(),
                        readKinds,
                    )
                    assertTrue(
                        "本地读取实际线程：${storage.readThreads.map { it.first to it.second.name }}",
                        storage.readThreads.all { it.second === ioThread.get() },
                    )
                } finally { sdk.shutdown() }
            }
        }
    }

    @Test fun cacheReadFailureKeepsActiveAndRequiresResourceCacheClearing() = onMain {
        val root = temporary.newFolder()
        val activeEntry = makeEntry(root, 10000)
        val record = PackageRecord(10000, "a".repeat(64))
        val storage = Storage().apply {
            active = record
            history = PreparationHistory(initialPreparationFinished = true)
            failedRead = FailedRead.CACHE
        }
        val diagnostics = CopyOnWriteArrayList<ManagedFailure>()
        val sdk = ManagedOfflineSdk.forTest(
            root, storage, ManagedConfigProvider { error("后台本地准备不能请求配置") },
            onDiagnostic = { diagnostics += it },
            mainDispatcher = testMainDispatcher,
        )
        try {
            sdk.setConditions(privacyAllowed = true, foreground = false)
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertEquals(record, sdk.state.value.active)
            assertTrue(activeEntry.isFile)
            assertEquals(true, storage.cacheDirty)
            assertTrue(diagnostics.any { it.reason == ManagedFailureReason.STORAGE_READ && it.stage == ManagedStage.CACHE })
            var cleared = false
            val callbacks = object : ManagedPageCallbacks {
                override fun clearResourceCache(): Boolean { cleared = true; return true }
                override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) = Unit
                override fun loadOnline(url: String) = error("可用 active 应继续离线")
            }
            val decision = sdk.loadPage("https://example.com/app/", "https://example.com/app/", callbacks)
            assertEquals(PageDecision.Offline(record), decision)
            assertTrue(cleared)
            withTimeout(5_000L) { while (storage.cacheDirty != false) kotlinx.coroutines.delay(10L) }
            assertEquals(false, storage.cacheDirty)
        } finally { sdk.shutdown() }
    }

    @Test fun oldUsableDirectoryFinishesPreparationWithoutInventingActiveRecord() = onMain {
        val root = temporary.newFolder()
        makeEntry(root, 10000)
        val storage = Storage()
        val sdk = ManagedOfflineSdk.forTest(
            root, storage, ManagedConfigProvider { error("本地迁移不能请求配置") },
            mainDispatcher = testMainDispatcher,
        )
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertNull(sdk.state.value.active)
            assertFalse(sdk.state.value.usablePackage)
            assertTrue(sdk.state.value.initialPreparationFinished)
            assertTrue(storage.history!!.initialPreparationFinished)
        } finally { sdk.shutdown() }
    }

    @Test fun validActiveCompletesInitialQualificationWithoutRewritingExistingHistory() = onMain {
        val storage = Storage().apply {
            active = PackageRecord(10000, "a".repeat(64))
            history = PreparationHistory(initialPreparationFinished = false)
        }
        val sdk = ManagedOfflineSdk.forTest(
            temporary.newFolder(), storage, ManagedConfigProvider { error("本地资格推导不能请求配置") },
            mainDispatcher = testMainDispatcher,
        )
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertTrue(sdk.state.value.initialPreparationFinished)
            assertFalse(sdk.state.value.usablePackage)
            assertEquals(0, storage.historyWrites)
            assertEquals(PreparationHistory(initialPreparationFinished = false), storage.history)
        } finally { sdk.shutdown() }
    }
}
