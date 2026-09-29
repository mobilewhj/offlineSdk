package com.offline.tool

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 外置审计探针：只将可观察的 active 写后取消窗口注入存储，不改 SDK 产品代码。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSaveCancellationProbe {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cancelledPersistedHigherActiveMustRemainTheDowngradeFloor() = runTest {
        val root = temporary.newFolder().canonicalFile
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = candidate(100000, "old")
        val higher = candidate(100002, "persisted higher")
        val lower = candidate(100001, "lower than persisted")
        assertTrue(PackageInstaller(root, ioDispatcher = dispatcher)
            .install(old.record) { zip("old").inputStream() } is InstallResult.Success)
        val store = Store(old.record)
        var offered = higher
        val requestedVersions = mutableListOf<Int>()
        val outcomes = mutableListOf<InstallationOutcome>()
        val sdk = ManagedOfflineSdk.forTest(
            root, store, ManagedConfigProvider {
                requestedVersions += it
                ConfigResponse.Success(OfflineConfiguration(true, offered.record.version, offered))
            }, minimumVersion = 100000, ioDispatcher = dispatcher, mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime }, onInstallationOutcome = { outcomes += it },
        )
        try {
            sdk.startupDecision()
            assertTrue(sdk.loadPage("https://site.test/index.html", "https://site.test/",
                PageCallbacks()) is PageDecision.Offline)
            store.pauseAfterSavingVersion = higher.record.version
            sdk.setConditions(true, true)
            runCurrent()
            assertTrue("先证明已实际保存高版 active，再撤回隐私", store.savedThenSuspended.isCompleted)
            assertEquals(higher.record, store.active)
            sdk.setConditions(false, true)
            runCurrent()
            assertEquals("真正取消不伪造安装终态", emptyList<InstallationOutcome>(), outcomes)
            assertEquals("当前实现仍持有旧内存 active", old.record, sdk.state.value.active)

            // 模拟服务端回退到一个低于已保存 active、但高于旧内存 active 的版本。
            offered = lower
            store.pauseAfterSavingVersion = null
            sdk.setConditions(true, true)
            advanceTimeBy(300_000)
            runCurrent()
            println("active-save-cancel requested=$requestedVersions stored=${store.active?.version} memory=${sdk.state.value.active?.version} outcomes=$outcomes")
            assertEquals("取消后恢复也不得把已保存的高版 active 降级", higher.record, store.active)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun cancelledSavedTargetRecoversNewPageWithoutReinstallingBoundDirectory() = runTest {
        val root = temporary.newFolder().canonicalFile
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = candidate(100000, "old")
        val target = candidate(100002, "persisted target")
        assertTrue(PackageInstaller(root, ioDispatcher = dispatcher)
            .install(old.record) { zip("old").inputStream() } is InstallResult.Success)
        val store = Store(old.record)
        val outcomes = mutableListOf<InstallationOutcome>()
        val sdk = ManagedOfflineSdk.forTest(
            root, store, ManagedConfigProvider {
                ConfigResponse.Success(OfflineConfiguration(true, target.record.version, target))
            }, minimumVersion = 100000, ioDispatcher = dispatcher, mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime }, onInstallationOutcome = { outcomes += it },
        )
        try {
            sdk.startupDecision()
            sdk.loadPage("https://site.test/index.html", "https://site.test/", PageCallbacks())
            store.pauseAfterSavingVersion = target.record.version
            sdk.setConditions(true, true)
            runCurrent()
            assertTrue(store.savedThenSuspended.isCompleted)
            sdk.setConditions(false, true)
            runCurrent()
            store.pauseAfterSavingVersion = null
            sdk.setConditions(true, true)
            assertEquals("已保存且文件有效的新版本可供新页面使用", PageDecision.Offline(target.record),
                sdk.loadPage("https://site.test/index.html", "https://site.test/", PageCallbacks()))
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(target.record, store.active)
            assertEquals(target.record, sdk.state.value.active)
            assertEquals(null, sdk.state.value.latestFailure)
            assertEquals(0, outcomes.size)
            assertTrue(root.resolve("${old.record.version}/index.html").isFile)
            advanceTimeBy(300_000)
            runCurrent()
            println("active-save-cancel same-target stored=${store.active?.version} memory=${sdk.state.value.active?.version} failure=${sdk.state.value.latestFailure?.reason} outcomes=${outcomes.size}")
            assertEquals("下一次同版检查无需重装，也不补发取消时的终态", 0, outcomes.size)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun cancellationBeforeActiveSideEffectAllowsNextNormalAttempt() = runTest {
        val root = temporary.newFolder().canonicalFile
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = candidate(100000, "old")
        val target = candidate(100002, "new")
        assertTrue(PackageInstaller(root, ioDispatcher = dispatcher)
            .install(old.record) { zip("old").inputStream() } is InstallResult.Success)
        val store = Store(old.record).apply { pauseBeforeSavingVersion = target.record.version }
        val outcomes = mutableListOf<InstallationOutcome>()
        val requested = mutableListOf<Int>()
        val sdk = ManagedOfflineSdk.forTest(
            root, store, ManagedConfigProvider {
                requested += it
                ConfigResponse.Success(OfflineConfiguration(true, target.record.version, target))
            }, minimumVersion = 100000, ioDispatcher = dispatcher, mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime }, onInstallationOutcome = { outcomes += it },
        )
        try {
            sdk.startupDecision()
            sdk.setConditions(true, true)
            runCurrent()
            assertTrue(store.beforeSaveSuspended.isCompleted)
            assertEquals(old.record, store.active)
            sdk.setConditions(false, true)
            runCurrent()
            assertEquals(emptyList<InstallationOutcome>(), outcomes)
            store.pauseBeforeSavingVersion = null
            sdk.setConditions(true, true)
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(listOf(old.record.version, old.record.version), requested)
            assertEquals(target.record, store.active)
            assertEquals(target.record, sdk.state.value.active)
            assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
        } finally { sdk.shutdown() }
    }

    @Test
    fun activeReadFailureAfterCancelledWriteCannotAuthorizeDowngrade() = runTest {
        val root = temporary.newFolder().canonicalFile
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = candidate(100000, "old")
        val high = candidate(100002, "high")
        val lower = candidate(100001, "lower")
        assertTrue(PackageInstaller(root, ioDispatcher = dispatcher)
            .install(old.record) { zip("old").inputStream() } is InstallResult.Success)
        val store = Store(old.record)
        var offered = high
        val requested = mutableListOf<Int>()
        val sdk = ManagedOfflineSdk.forTest(
            root, store, ManagedConfigProvider {
                requested += it
                ConfigResponse.Success(OfflineConfiguration(true, offered.record.version, offered))
            }, minimumVersion = 100000, ioDispatcher = dispatcher, mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
        try {
            sdk.startupDecision()
            store.pauseAfterSavingVersion = high.record.version
            sdk.setConditions(true, true)
            runCurrent()
            assertTrue(store.savedThenSuspended.isCompleted)
            sdk.setConditions(false, true)
            runCurrent()
            offered = lower
            store.pauseAfterSavingVersion = null
            store.failActiveRead = true
            sdk.setConditions(true, true)
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(listOf(old.record.version), requested)
            assertEquals(high.record, store.active)
            store.failActiveRead = false
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            runCurrent()
            assertEquals(high.record, store.active)
            assertEquals(high.record, sdk.state.value.active)
        } finally { sdk.shutdown() }
    }

    @Test
    fun missingFilesAfterCancelledWriteSendZeroButRetainVersionFloor() = runTest {
        val root = temporary.newFolder().canonicalFile
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = candidate(100000, "old")
        val high = candidate(100002, "high")
        val lower = candidate(100001, "lower")
        assertTrue(PackageInstaller(root, ioDispatcher = dispatcher)
            .install(old.record) { zip("old").inputStream() } is InstallResult.Success)
        val store = Store(old.record)
        var offered = high
        val requested = mutableListOf<Int>()
        val outcomes = mutableListOf<InstallationOutcome>()
        val sdk = ManagedOfflineSdk.forTest(
            root, store, ManagedConfigProvider {
                requested += it
                ConfigResponse.Success(OfflineConfiguration(true, offered.record.version, offered))
            }, minimumVersion = 100000, ioDispatcher = dispatcher, mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime }, onInstallationOutcome = { outcomes += it },
        )
        try {
            sdk.startupDecision()
            store.pauseAfterSavingVersion = high.record.version
            sdk.setConditions(true, true)
            runCurrent()
            assertTrue(store.savedThenSuspended.isCompleted)
            sdk.setConditions(false, true)
            runCurrent()
            assertTrue(root.resolve("${high.record.version}").deleteRecursively())
            offered = lower
            store.pauseAfterSavingVersion = null
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertEquals(high.record, sdk.state.value.active)
            assertEquals(false, sdk.state.value.usablePackage)
            sdk.setConditions(true, true)
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(listOf(old.record.version, 0), requested)
            assertEquals(high.record, store.active)
            assertEquals(emptyList<InstallationOutcome>(), outcomes)
        } finally { sdk.shutdown() }
    }

    private class Store(initial: PackageRecord) : ManagedOfflineStorage {
        var active: PackageRecord? = initial
        var enabled = true
        var history = PreparationHistory(initialPreparationFinished = true)
        var cacheDirty = false
        var pauseAfterSavingVersion: Int? = null
        var pauseBeforeSavingVersion: Int? = null
        var failActiveRead = false
        val savedThenSuspended = CompletableDeferred<Unit>()
        val beforeSaveSuspended = CompletableDeferred<Unit>()
        override suspend fun readActive(): PackageRecord? {
            if (failActiveRead) error("read unavailable")
            return active
        }
        override suspend fun writeActive(record: PackageRecord?): Boolean {
            if (record?.version == pauseBeforeSavingVersion) {
                beforeSaveSuspended.complete(Unit)
                awaitCancellation()
            }
            active = record
            if (record?.version == pauseAfterSavingVersion) {
                savedThenSuspended.complete(Unit)
                awaitCancellation()
            }
            return true
        }
        override suspend fun readEnabled() = enabled
        override suspend fun writeEnabled(enabled: Boolean): Boolean { this.enabled = enabled; return true }
        override suspend fun readHistory() = history
        override suspend fun writeHistory(history: PreparationHistory): Boolean { this.history = history; return true }
        override fun readCacheDirty() = cacheDirty
        override suspend fun writeCacheDirty(dirty: Boolean): Boolean { cacheDirty = dirty; return true }
    }

    private class PageCallbacks : ManagedPageCallbacks {
        override fun clearResourceCache() = true
        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) = Unit
        override fun loadOnline(url: String) = Unit
    }

    private fun candidate(version: Int, content: String): OfflineCandidate {
        val bytes = zip(content)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return OfflineCandidate(PackageRecord(version, sha), PackageSource.Local { bytes.inputStream() })
    }

    private fun zip(content: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("index.html").apply { time = 0L })
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
