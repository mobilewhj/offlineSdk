package com.offline.tool

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 独立验收探针：断言描述应有行为，待审版本预期失败。
 * 通过临时源集注入，不修改 SDK 或 App 产品文件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingActiveFilesMustNotPermitDowngrade() = runTest {
        val saved = PackageRecord(100002, "a".repeat(64))
        val lowerBytes = zip("lower version")
        val lower = candidate(100001, lowerBytes)
        val store = MemoryStorage(saved)
        val sdk = manager(temporary.newFolder().canonicalFile, store, response(lower))
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            sdk.requestCheck()

            assertEquals("Missing files must not erase the saved version downgrade guard", saved, store.active)
            assertFalse("A lower candidate must not be activated", store.activeWrites.contains(lower.record))
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun privacyRevocationCancelsExternallyRequestedCheckBeforeActivation() = runTest {
        val bytes = zip("new version")
        val target = candidate(100001, bytes)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStorage().apply {
            activeWriteEntered = entered
            activeWriteRelease = release
        }
        val sdk = manager(temporary.newFolder().canonicalFile, store, response(target))
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            // UNDISPATCHED 先于已排定的轮询取得共享操作许可。
            // 调用者属于宿主作用域，不属于管理器的轮询作用域。
            val check = async(start = CoroutineStart.UNDISPATCHED) { sdk.requestCheck() }
            try {
                runCurrent()
                assertTrue("The public request must reach the activation storage boundary", entered.isCompleted)
                sdk.setConditions(privacyAllowed = false, foreground = true)
                runCurrent()
                release.complete(Unit)
                runCurrent()

                assertEquals("Revoked work must not activate a package", null, store.active)
                assertTrue("The in-flight public request must receive cancellation", check.isCancelled)
            } finally {
                release.complete(Unit)
                check.cancelAndJoin()
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
            val preparedBeforeConfig = sdk.preparePage()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val check = async(start = CoroutineStart.UNDISPATCHED) { sdk.requestCheck() }
            try {
                runCurrent()
                assertTrue("Configuration must already be applied before storage suspends", entered.isCompleted)
                val callbacks = PageCallbacks()
                val base = "https://offline.example/app/"
                val decision = sdk.commitPage(preparedBeforeConfig, base, base, callbacks)

                assertEquals("Known SHA conflict must invalidate an earlier page preparation immediately", PageDecision.Online, decision)
                assertEquals(0, callbacks.offlineLoads)
            } finally {
                release.complete(Unit)
                check.cancelAndJoin()
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
            val preparedBeforeConfig = sdk.preparePage()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val result = sdk.requestCheck()
            assertTrue("必须实际触发开关保存失败", result is CheckResult.Failed)
            assertEquals(ManagedStage.SAVE_ENABLED, (result as CheckResult.Failed).failure.stage)

            val callbacks = PageCallbacks()
            val base = "https://offline.example/app/"
            assertEquals(
                "开关保存失败不能掩盖已经取得的同版摘要冲突",
                PageDecision.Online,
                sdk.commitPage(preparedBeforeConfig, base, base, callbacks),
            )
            assertEquals(0, callbacks.offlineLoads)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun outcomeCallbackCancellationMustNotReplaceCommittedInstallResult() = runTest {
        val target = candidate(100001, zip("committed installation"))
        val store = MemoryStorage()
        var callbackCalls = 0
        val sdk = manager(
            temporary.newFolder().canonicalFile,
            store,
            response(target),
            onOutcome = {
                callbackCalls++
                throw CancellationException("宿主结果观察回调异常")
            },
        )
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            var result: CheckResult? = null
            var callbackCancellation: CancellationException? = null
            try {
                result = sdk.requestCheck()
            } catch (cancelled: CancellationException) {
                callbackCancellation = cancelled
            }

            // 证明这是观察回调自行抛出的异常，并非操作的 Job 被真实取消。
            assertTrue("实际操作 Job 应仍为活动状态", currentCoroutineContext().isActive)
            assertEquals("安装必须已持久化成功", target.record, store.active)
            assertEquals(1, callbackCalls)
            assertEquals("观察回调不能把已提交安装的结果改写为取消", null, callbackCancellation)
            assertEquals(CheckResult.Installed(target.record), result)
        } finally {
            sdk.shutdown()
        }
    }

    private fun TestScope.manager(
        root: File,
        storage: MemoryStorage,
        configuration: ConfigResponse,
        onOutcome: (InstallationOutcome) -> Unit = {},
    ): ManagedOfflineSdk {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ManagedOfflineSdk(
            root = root,
            storage = storage,
            configProvider = ManagedConfigProvider { configuration },
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
        override fun writeCacheDirty(dirty: Boolean): Boolean {
            cacheDirty = dirty
            return true
        }
    }

    private class PageCallbacks : ManagedPageCallbacks {
        var offlineLoads = 0
        override fun clearResourceCache() = true
        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
            offlineLoads++
        }
        override fun loadOnline(url: String) = Unit
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
