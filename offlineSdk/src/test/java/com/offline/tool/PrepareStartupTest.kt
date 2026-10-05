package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 验证新增启动投影与原首装所有权的边界，不重复安装/版本算法套件。 */
class PrepareStartupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @After fun closeDispatcher() = main.close()
    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking(main, block)

    private class Storage : ManagedOfflineStorage {
        @Volatile var active: PackageRecord? = null
        @Volatile var enabled: Boolean? = null
        @Volatile var history: PreparationHistory? = null
        @Volatile var dirty: Boolean? = null
        var activeWriteSucceeds = true
        var historyEntered: CompletableDeferred<Unit>? = null
        var historyGate: CompletableDeferred<Unit>? = null
        override suspend fun readActive() = active
        override suspend fun writeActive(record: PackageRecord?): Boolean {
            if (activeWriteSucceeds) active = record
            return activeWriteSucceeds
        }
        override suspend fun readEnabled() = enabled
        override suspend fun writeEnabled(enabled: Boolean): Boolean { this.enabled = enabled; return true }
        override suspend fun readHistory() = history
        override suspend fun writeHistory(history: PreparationHistory): Boolean {
            historyEntered?.complete(Unit)
            historyGate?.await()
            this.history = history
            return true
        }
        override fun readCacheDirty() = dirty
        override suspend fun writeCacheDirty(dirty: Boolean): Boolean { this.dirty = dirty; return true }
    }

    private class Source(initial: suspend () -> ConfigResponse) : ManagedConfigProvider {
        var response = initial
        val requests = AtomicInteger()
        override suspend fun fetch(currentVersion: Int): ConfigResponse {
            requests.incrementAndGet()
            return response()
        }
    }

    private fun manager(
        root: File,
        storage: Storage,
        source: Source,
        outcomes: MutableList<InstallationOutcome> = CopyOnWriteArrayList(),
    ) = ManagedOfflineSdk.forTest(
        root, storage, source, mainDispatcher = main, ioDispatcher = Dispatchers.IO,
        onInstallationOutcome = { outcomes += it }, monotonicMillis = { 1_000L },
    )

    private fun candidate(broken: Boolean = false): OfflineCandidate {
        val bytes = if (broken) "invalid archive".toByteArray() else ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("index.html"))
                zip.write("startup".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return OfflineCandidate(PackageRecord(10_000, digest), PackageSource.Local { bytes.inputStream() })
    }

    private fun configured(candidate: OfflineCandidate) = ConfigResponse.Success(
        OfflineConfiguration(true, candidate.record.version, candidate),
    )
    private fun disabled() = ConfigResponse.Success(OfflineConfiguration(false))
    private suspend fun entered(gate: CompletableDeferred<Unit>) = withTimeout(5_000L) { gate.await() }

    @Test fun successContinuesAfterActualCompletionWithOneOutcome() = onMain {
        val storage = Storage()
        val target = candidate()
        val source = Source { configured(target) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder(), storage, source, outcomes)
        val progress = CopyOnWriteArrayList<ManagedProgress>()
        try {
            sdk.setConditions(true, true)
            assertEquals(StartupResult.Continue, sdk.prepareStartup { progress += it })
            assertEquals(target.record, storage.active)
            assertTrue(storage.history!!.initialPreparationFinished)
            assertEquals(ManagedProgress.Complete, progress.last())
            assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
            assertEquals(StartupResult.Continue, sdk.prepareStartup())
            assertEquals(1, source.requests.get())
            assertEquals(1, outcomes.size)
        } finally { sdk.shutdown() }
    }

    @Test fun ordinaryConfigInstallAndSaveFailuresReleaseOnlyTheStartupWait() = onMain {
        for (failure in listOf("config", "install", "save")) {
            val storage = Storage().apply { activeWriteSucceeds = failure != "save" }
            val source = Source {
                if (failure == "config") ConfigResponse.Failure(ConfigFailureReason.REQUEST)
                else configured(candidate(broken = failure == "install"))
            }
            val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
            val sdk = manager(temporary.newFolder(), storage, source, outcomes)
            val progress = CopyOnWriteArrayList<ManagedProgress>()
            try {
                sdk.setConditions(true, true)
                assertEquals(StartupResult.Continue, sdk.prepareStartup { progress += it })
                assertTrue(storage.history!!.initialPreparationFinished)
                assertNull(storage.active)
                assertFalse(progress.contains(ManagedProgress.Complete))
                if (failure == "config") assertTrue(outcomes.isEmpty())
                else assertTrue(outcomes.single() is InstallationOutcome.Failed)
                assertEquals(StartupResult.Continue, sdk.prepareStartup())
                assertEquals(1, source.requests.get())
            } finally { sdk.shutdown() }
        }
    }

    @Test fun disabledConfigurationContinuesWithoutInstallationOutcomeOrCompletionProgress() = onMain {
        val storage = Storage()
        val source = Source { disabled() }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder(), storage, source, outcomes)
        val progress = CopyOnWriteArrayList<ManagedProgress>()
        try {
            sdk.setConditions(true, true)
            assertEquals(StartupResult.Continue, sdk.prepareStartup { progress += it })
            assertEquals(false, storage.enabled)
            assertTrue(storage.history!!.initialPreparationFinished)
            assertTrue(outcomes.isEmpty())
            assertFalse(progress.contains(ManagedProgress.Complete))
        } finally { sdk.shutdown() }
    }

    @Test fun completedHistoryAndUsablePackageBypassNewConditionsAndDoNotWaitForSilentWork() = onMain {
        for (hasPackage in listOf(false, true)) {
            val root = temporary.newFolder()
            val storage = Storage().apply {
                if (hasPackage) {
                    active = PackageRecord(10_000, "a".repeat(64))
                    root.resolve("10000").mkdir()
                    root.resolve("10000/index.html").writeText("existing")
                } else history = PreparationHistory(initialPreparationFinished = true)
            }
            val requested = CompletableDeferred<Unit>()
            val source = Source { requested.complete(Unit); awaitCancellation() }
            val sdk = manager(root, storage, source)
            try {
                assertEquals(StartupResult.Continue, sdk.prepareStartup())
                assertEquals(0, source.requests.get())
                sdk.setConditions(true, true)
                entered(requested)
                assertEquals(StartupResult.Continue, withTimeout(1_000L) { sdk.prepareStartup() })
                assertEquals(1, source.requests.get())
            } finally { sdk.shutdown() }
        }
    }

    @Test fun temporaryPrivacyAndForegroundConditionsAreDeferredWithoutRequestsAndCanRecover() = onMain {
        val source = Source { disabled() }
        val sdk = manager(temporary.newFolder(), Storage(), source)
        try {
            assertEquals(StartupResult.Deferred, sdk.prepareStartup())
            sdk.setConditions(true, false)
            assertEquals(StartupResult.Deferred, sdk.prepareStartup())
            assertEquals(0, source.requests.get())
            sdk.setConditions(true, true)
            assertEquals(StartupResult.Continue, sdk.prepareStartup())
            assertEquals(1, source.requests.get())
        } finally { sdk.shutdown() }
    }

    @Test fun newStartupCallsAndOldInterfacesShareOnePreparationProgressAndOutcome() = onMain {
        for ((newOwner, newWaiter) in listOf(true to true, true to false, false to true)) {
            val requested = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source = Source { requested.complete(Unit); release.await(); configured(candidate()) }
            val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
            val sdk = manager(temporary.newFolder(), Storage(), source, outcomes)
            val ownerProgress = CopyOnWriteArrayList<ManagedProgress>()
            val waiterProgress = CopyOnWriteArrayList<ManagedProgress>()
            try {
                sdk.setConditions(true, true)
                val owner = async {
                    if (newOwner) sdk.prepareStartup { ownerProgress += it }
                    else sdk.prepareFirst { ownerProgress += it }
                }
                entered(requested)
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    if (newWaiter) sdk.prepareStartup { waiterProgress += it }
                    else sdk.prepareFirst { waiterProgress += it }
                }
                release.complete(Unit)
                val ownerResult = owner.await()
                val waiterResult = waiter.await()
                if (newOwner) assertEquals(StartupResult.Continue, ownerResult)
                else assertTrue(ownerResult is FirstPreparationResult.Finished)
                if (newWaiter) assertEquals(StartupResult.Continue, waiterResult)
                else assertTrue(waiterResult is FirstPreparationResult.Finished)
                assertEquals(1, source.requests.get())
                assertEquals(1, outcomes.size)
                assertEquals(ManagedProgress.Complete, ownerProgress.last())
                assertTrue(waiterProgress.isEmpty())
            } finally { sdk.shutdown() }
        }
    }

    @Test fun cancellingAnOldWaiterDoesNotCancelTheNewOwner() = onMain {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = Source { requested.complete(Unit); release.await(); configured(candidate()) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder(), Storage(), source, outcomes)
        try {
            sdk.setConditions(true, true)
            val owner = async { sdk.prepareStartup() }
            entered(requested)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            waiter.cancelAndJoin()
            assertFalse(owner.isCompleted)
            release.complete(Unit)
            assertEquals(StartupResult.Continue, owner.await())
            assertEquals(1, source.requests.get())
            assertEquals(1, outcomes.size)
        } finally { sdk.shutdown() }
    }

    @Test fun cancellingTheOldOwnerCancelsNewWaitersAndTheLiveCallerCanExplicitlyRecover() = onMain {
        val requested = CompletableDeferred<Unit>()
        val source = Source { requested.complete(Unit); awaitCancellation() }
        val storage = Storage()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder(), storage, source, outcomes)
        try {
            sdk.setConditions(true, true)
            val owner = async { sdk.prepareFirst() }
            entered(requested)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareStartup() }
            // 已初始化时本地准备无额外 IO；UNDISPATCHED 确保等待者先进入共享 firstJob。
            owner.cancelAndJoin()
            try { waiter.await(); throw AssertionError("等待者不得把取消映射为 Continue") }
            catch (_: CancellationException) { }
            assertNull(storage.history)
            assertTrue(outcomes.isEmpty())
            source.response = { disabled() }
            assertEquals(StartupResult.Continue, sdk.prepareStartup())
            assertEquals(2, source.requests.get())
        } finally { sdk.shutdown() }
    }

    @Test fun privacyWithdrawalCancelsNewOwnerAndOldWaiterWithoutInventingATerminalResult() = onMain {
        val requested = CompletableDeferred<Unit>()
        val source = Source { requested.complete(Unit); awaitCancellation() }
        val storage = Storage()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder(), storage, source, outcomes)
        try {
            sdk.setConditions(true, true)
            val owner = async { sdk.prepareStartup() }
            entered(requested)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            sdk.setConditions(false, true)
            for (call in listOf(owner, waiter)) {
                try { call.await(); throw AssertionError("撤回隐私不得转换为正常启动结果") }
                catch (_: CancellationException) { }
            }
            assertNull(storage.history)
            assertTrue(outcomes.isEmpty())
            assertEquals(1, source.requests.get())
            source.response = { disabled() }
            sdk.setConditions(true, true)
            assertEquals(StartupResult.Continue, sdk.prepareStartup())
            assertEquals(2, source.requests.get())
        } finally { sdk.shutdown() }
    }

    @Test fun cancelledHistoryAfterInstallationStillOffersTheOriginalOutcomeOnce() = onMain {
        val written = CompletableDeferred<Unit>()
        val storage = Storage().apply {
            historyEntered = written
            historyGate = CompletableDeferred()
        }
        val target = candidate()
        val source = Source { configured(target) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder(), storage, source, outcomes)
        try {
            sdk.setConditions(true, true)
            val owner = async { sdk.prepareStartup() }
            entered(written)
            assertEquals(target.record, storage.active)
            assertTrue(outcomes.isEmpty())
            owner.cancelAndJoin()
            assertTrue(owner.isCancelled)
            assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
            assertEquals(StartupResult.Continue, sdk.prepareStartup())
            assertEquals(1, source.requests.get())
            assertEquals(1, outcomes.size)
        } finally { sdk.shutdown() }
    }
}
