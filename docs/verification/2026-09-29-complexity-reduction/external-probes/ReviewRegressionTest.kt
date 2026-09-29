package com.offline.tool

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本轮独立验收探针：保留原七项行为断言，改用单页面入口与单首装进度回调。
 * 通过临时源集注入，不修改 SDK 或 App 产品文件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplexityReductionReviewRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingActiveFilesMustNotPermitDowngrade() = runTest {
        val saved = PackageRecord(100002, "a".repeat(64))
        val lowerBytes = zip("lower version")
        val lower = candidate(100001, lowerBytes)
        val store = MemoryStorage(saved)
        val requestedVersions = mutableListOf<Int>()
        val sdk = manager(
            temporary.newFolder().canonicalFile,
            store,
            response(lower),
            configProvider = ManagedConfigProvider { currentVersion ->
                requestedVersions += currentVersion
                response(lower)
            },
        )
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()

            assertEquals("必须实际请求配置，且缺包时上送可用版本 0", listOf(0), requestedVersions)
            assertEquals("Missing files must not erase the saved version downgrade guard", saved, store.active)
            assertFalse("A lower candidate must not be activated", store.activeWrites.contains(lower.record))
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun privacyRevocationCancelsSdkSilentCheckBeforeActivation() = runTest {
        val bytes = zip("new version")
        val target = candidate(100001, bytes)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStorage().apply {
            activeWriteEntered = entered
            activeWriteRelease = release
        }
        val outcomes = mutableListOf<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, store, response(target), onOutcome = { outcomes += it })
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            // 无关宿主任务仍归调用者所有，隐私撤回只取消 SDK 实际静默检查。
            val hostSibling = async { release.await() }
            try {
                runCurrent()
                assertTrue("The SDK silent task must reach the activation storage boundary", entered.isCompleted)
                sdk.setConditions(privacyAllowed = false, foreground = true)
                runCurrent()
                assertTrue("Privacy withdrawal must leave the host sibling active", hostSibling.isActive)
                release.complete(Unit)
                runCurrent()

                assertEquals("Revoked work must not activate a package", null, store.active)
                assertTrue("Cancellation must occur before writing active", store.activeWrites.isEmpty())
                assertTrue("Cancellation must not fabricate an installation terminal", outcomes.isEmpty())
                assertTrue("Unrelated host sibling must finish normally", hostSibling.isCompleted && !hostSibling.isCancelled)
            } finally {
                release.complete(Unit)
                hostSibling.cancelAndJoin()
            }
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun observedSameVersionShaConflictMustBlockPageDuringEnabledStorageWait() = runTest {
        val root = temporary.newFolder().canonicalFile
        val oldBytes = zip("old content")
        val old = candidate(100002, oldBytes)
        val conflicting = candidate(100002, zip("different content"))
        installBeforeManager(root, old.record, oldBytes)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStorage(old.record).apply {
            enabledWriteEntered = entered
            enabledWriteRelease = release
        }
        val sdk = manager(root, store, response(conflicting))
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            try {
                runCurrent()
                assertTrue("Configuration must already be applied before storage suspends", entered.isCompleted)
                val callbacks = PageCallbacks()
                val base = "https://offline.example/app/"
                val decision = sdk.loadPage(base, base, callbacks)

                assertEquals("Known SHA conflict must block page binding while enabled storage waits", PageDecision.Online, decision)
                assertEquals(0, callbacks.offlineLoads)
                assertEquals(1, callbacks.onlineLoads)
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun activeReadFailureMustNotDeleteRecoverableInstalledDirectory() = runTest {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("recoverable current package")
        val saved = candidate(100002, bytes).record
        installBeforeManager(root, saved, bytes)
        val entry = root.resolve("100002/index.html")
        val store = MemoryStorage(saved).apply { activeReadFailuresRemaining = 1 }
        val sdk = manager(root, store, ConfigResponse.Success(OfflineConfiguration(enabled = false)))
        try {
            sdk.startupDecision()

            assertTrue("Unknown active after storage IO failure must not authorize clearing its directory", entry.isFile)
            assertEquals("The durable active record is still recoverable", saved, store.readActive())
            assertEquals("recoverable current package", entry.readText())
            val base = "https://offline.example/app/"
            val callbacks = PageCallbacks()
            val decision = sdk.loadPage(base, base, callbacks)
            assertEquals("The next legal entry must recover the durable active", saved, sdk.state.value.active)
            assertEquals(PageDecision.Offline(saved), decision)
            assertEquals(1, callbacks.offlineLoads)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun enabledWriteFailureMustNotHideObservedSameVersionShaConflict() = runTest {
        val root = temporary.newFolder().canonicalFile
        val oldBytes = zip("old content")
        val old = candidate(100002, oldBytes)
        val conflicting = candidate(100002, zip("different content"))
        installBeforeManager(root, old.record, oldBytes)
        val store = MemoryStorage(old.record).apply { enabledWriteSucceeds = false }
        val sdk = manager(root, store, response(conflicting))
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            val failure = sdk.state.value.latestFailure
            assertTrue("必须实际触发开关保存失败", failure != null)
            assertEquals(ManagedStage.SAVE_ENABLED, failure!!.stage)

            val callbacks = PageCallbacks()
            val base = "https://offline.example/app/"
            assertEquals(
                "开关保存失败不能掩盖已经取得的同版摘要冲突",
                PageDecision.Online,
                sdk.loadPage(base, base, callbacks),
            )
            assertEquals(0, callbacks.offlineLoads)
            assertEquals(1, callbacks.onlineLoads)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun outcomeCallbackCancellationMustNotReplaceCommittedInstallResult() = runTest {
        val target = candidate(100001, zip("committed installation"))
        val store = MemoryStorage().apply {
            history = PreparationHistory(initialPreparationFinished = false)
        }
        var callbackCalls = 0
        var observedOutcome: InstallationOutcome? = null
        val progresses = mutableListOf<ManagedProgress>()
        val sdk = manager(
            temporary.newFolder().canonicalFile,
            store,
            response(target),
            onOutcome = { outcome ->
                callbackCalls++
                observedOutcome = outcome
                throw CancellationException("宿主结果观察回调异常")
            },
        )
        try {
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            var result: FirstPreparationResult? = null
            var callbackCancellation: CancellationException? = null
            try {
                result = sdk.prepareFirst(onProgress = { progresses += it })
            } catch (cancelled: CancellationException) {
                callbackCancellation = cancelled
            }

            // 使用仍公开的首次挂起结果，不能以宿主 Job 活跃替代实际调用正常返回。
            assertTrue("宿主调用方未被取消", currentCoroutineContext().isActive)
            assertEquals("安装必须已持久化成功", target.record, store.active)
            assertEquals(1, callbackCalls)
            assertEquals("观察回调不能把已提交安装的返回值改写为取消", null, callbackCancellation)
            assertEquals(FirstPreparationResult.Finished(CheckResult.Installed(target.record), true), result)
            assertEquals(InstallationOutcome.Installed(target.record), observedOutcome)
            assertEquals(ManagedProgress.Complete, progresses.last())
            assertEquals("成功状态不能被观察回调改写", null, sdk.state.value.latestFailure)
            assertEquals(target.record, sdk.state.value.active)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun firstPreparationReentryDuringCancellationCleanupMustNotCreateAnotherOwner() = runTest {
        val target = candidate(100001, zip("explicit retry after cleanup"))
        val store = MemoryStorage().apply {
            history = PreparationHistory(initialPreparationFinished = false)
        }
        val providerEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<InstallationOutcome>()
        var requests = 0
        val sdk = manager(
            temporary.newFolder().canonicalFile,
            store,
            response(target),
            onOutcome = { outcomes += it },
            configProvider = ManagedConfigProvider {
                requests++
                if (requests == 1) {
                    try {
                        providerEntered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        // 将取消后的资源清理停在确定边界，不依赖真实延时或手动轮询。
                        withContext(NonCancellable) {
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                } else {
                    response(target)
                }
            },
        )
        var owner: Deferred<FirstPreparationResult>? = null
        var duringCleanup: Deferred<FirstPreparationResult>? = null
        try {
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = async { sdk.prepareFirst(onProgress = {}) }
            owner = first
            runCurrent()
            assertTrue("首装必须实际进入配置请求", providerEntered.isCompleted)
            assertEquals(1, requests)

            first.cancel()
            runCurrent()
            assertTrue("旧首装必须进入不可取消清理", cleanupEntered.isCompleted)
            assertFalse("清理挂起期间旧 owner 尚未收尾", first.isCompleted)
            val reentry = async { sdk.prepareFirst(onProgress = {}) }
            duringCleanup = reentry
            runCurrent()
            assertEquals("旧清理未结束时不能另发配置请求", 1, requests)
            assertFalse("取消不能设置首次完成", store.history.initialPreparationFinished)
            assertTrue("取消不能伪造安装终态", outcomes.isEmpty())

            releaseCleanup.complete(Unit)
            runCurrent()
            assertTrue("旧 owner 必须以取消结束", first.isCompleted && first.isCancelled)
            assertTrue("清理期间的重入必须继承旧取消，不能排队成为新 owner", reentry.isCompleted && reentry.isCancelled)
            assertEquals("释放旧清理后仍不能自动创建新尝试", 1, requests)
            assertTrue("取消前没有写入 active", store.activeWrites.isEmpty())

            // 只有清理完成后的新显式调用才允许创建下一次首装。
            val retry = sdk.prepareFirst(onProgress = {})
            assertEquals(FirstPreparationResult.Finished(CheckResult.Installed(target.record), true), retry)
            assertEquals(2, requests)
            assertEquals(target.record, store.active)
            assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
        } finally {
            releaseCleanup.complete(Unit)
            owner?.cancelAndJoin()
            duringCleanup?.cancelAndJoin()
            sdk.shutdown()
        }
    }

    private fun TestScope.manager(
        root: File,
        storage: MemoryStorage,
        configuration: ConfigResponse,
        onOutcome: (InstallationOutcome) -> Unit = {},
        configProvider: ManagedConfigProvider = ManagedConfigProvider { configuration },
    ): ManagedOfflineSdk {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ManagedOfflineSdk.forTest(
            root = root,
            storage = storage,
            configProvider = configProvider,
            minimumVersion = 100000,
            onInstallationOutcome = onOutcome,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
    }

    private suspend fun TestScope.installBeforeManager(root: File, record: PackageRecord, bytes: ByteArray) {
        val result = PackageInstaller(root, ioDispatcher = StandardTestDispatcher(testScheduler))
            .install(record) { bytes.inputStream() }
        assertTrue("Test fixture must be installed before the manager claims the root", result is InstallResult.Success)
    }

    private class MemoryStorage(initialActive: PackageRecord? = null) : ManagedOfflineStorage {
        var active: PackageRecord? = initialActive
        var enabled = true
        var history = PreparationHistory(initialPreparationFinished = true)
        var cacheDirty = false
        var activeReadFailuresRemaining = 0
        var activeWriteEntered: CompletableDeferred<Unit>? = null
        var activeWriteRelease: CompletableDeferred<Unit>? = null
        var enabledWriteEntered: CompletableDeferred<Unit>? = null
        var enabledWriteRelease: CompletableDeferred<Unit>? = null
        var enabledWriteSucceeds = true
        val activeWrites = mutableListOf<PackageRecord?>()

        override suspend fun readActive(): PackageRecord? {
            if (activeReadFailuresRemaining > 0) {
                activeReadFailuresRemaining--
                throw IOException("Simulated transient active-record read failure")
            }
            return active
        }
        override suspend fun writeActive(record: PackageRecord?): Boolean {
            activeWriteEntered?.complete(Unit)
            activeWriteRelease?.await()
            activeWrites += record
            active = record
            return true
        }
        override suspend fun readEnabled() = enabled
        override suspend fun writeEnabled(enabled: Boolean): Boolean {
            enabledWriteEntered?.complete(Unit)
            enabledWriteRelease?.await()
            if (enabledWriteSucceeds) this.enabled = enabled
            return enabledWriteSucceeds
        }
        override suspend fun readHistory() = history
        override suspend fun writeHistory(history: PreparationHistory): Boolean {
            this.history = history
            return true
        }
        override fun readCacheDirty() = cacheDirty
        override suspend fun writeCacheDirty(dirty: Boolean): Boolean {
            cacheDirty = dirty
            return true
        }
    }

    private class PageCallbacks : ManagedPageCallbacks {
        var offlineLoads = 0
        var onlineLoads = 0
        override fun clearResourceCache() = true
        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
            offlineLoads++
        }
        override fun loadOnline(url: String) { onlineLoads++ }
    }

    private fun candidate(version: Int, bytes: ByteArray) = OfflineCandidate(
        PackageRecord(version, sha256(bytes)),
        PackageSource.Local { bytes.inputStream() },
    )

    private fun response(candidate: OfflineCandidate) = ConfigResponse.Success(
        OfflineConfiguration(true, candidate.record.version, candidate),
    )

    private fun zip(content: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("index.html").apply { time = 0L })
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }.toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
