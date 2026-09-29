package com.offline.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManagedOfflineSdkTest {
    @get:Rule val temporary = TemporaryFolder()

    // 同步入口与挂起返回共用一个串行 owner，避免测试用 Unconfined 在 IO 返回后漂移线程。
    private val testMainDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "managed-test-main")
    }.asCoroutineDispatcher()

    @After fun closeTestMainDispatcher() = testMainDispatcher.close()

    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking(testMainDispatcher, block)

    private class MemoryStorage : ManagedOfflineStorage {
        @Volatile var active: PackageRecord? = null
        @Volatile var enabled: Boolean? = null
        @Volatile var history: PreparationHistory? = null
        @Volatile var cacheDirty: Boolean? = null
        @Volatile var activeWriteSucceeds = true
        @Volatile var historyWriteSucceeds = true
        @Volatile var cacheWriteSucceeds = true
        @Volatile var legacy: LegacyPreparationEvidence? = null
        @Volatile var historyWriteEntered: CompletableDeferred<Unit>? = null
        @Volatile var historyWriteGate: CompletableDeferred<Unit>? = null
        @Volatile var cancelCacheRead = false
        @Volatile var cancelCacheWrite: Boolean? = null
        @Volatile var cleanWriteEntered: CompletableDeferred<Unit>? = null
        @Volatile var cleanWriteGate: CompletableDeferred<Unit>? = null
        val activeWrites = CopyOnWriteArrayList<PackageRecord?>()
        val historyWrites = CopyOnWriteArrayList<PreparationHistory>()
        val cacheWrites = CopyOnWriteArrayList<Boolean>()

        override suspend fun readActive() = active
        override suspend fun writeActive(record: PackageRecord?): Boolean {
            activeWrites += record
            if (activeWriteSucceeds) active = record
            return activeWriteSucceeds
        }
        override suspend fun readEnabled() = enabled
        override suspend fun writeEnabled(enabled: Boolean): Boolean {
            this.enabled = enabled
            return true
        }
        override suspend fun readHistory() = history
        override suspend fun writeHistory(history: PreparationHistory): Boolean {
            historyWriteEntered?.complete(Unit)
            historyWriteGate?.await()
            historyWrites += history
            if (historyWriteSucceeds) this.history = history
            return historyWriteSucceeds
        }
        override fun readCacheDirty(): Boolean? {
            if (cancelCacheRead) throw CancellationException("cache read cancelled")
            return cacheDirty
        }
        override suspend fun writeCacheDirty(dirty: Boolean): Boolean {
            cacheWrites += dirty
            if (!dirty) {
                cleanWriteEntered?.complete(Unit)
                cleanWriteGate?.await()
            }
            if (cancelCacheWrite == dirty) throw CancellationException("cache write cancelled")
            if (cacheWriteSucceeds) cacheDirty = dirty
            return cacheWriteSucceeds
        }
        override suspend fun readLegacyEvidence() = legacy
    }

    private class ConfigSource(initial: suspend (Int) -> ConfigResponse) : ManagedConfigProvider {
        @Volatile var response: suspend (Int) -> ConfigResponse = initial
        val versions = CopyOnWriteArrayList<Int>()
        val requests = AtomicInteger()
        override suspend fun fetch(currentVersion: Int): ConfigResponse {
            versions += currentVersion
            requests.incrementAndGet()
            return response(currentVersion)
        }
    }

    private class Clock { @Volatile var now = 1_000_000L }

    private class PageCallbacks : ManagedPageCallbacks {
        var clears = 0
        var clearSucceeds = true
        var offlineDirectory: File? = null
        var interceptor: OfflineInterceptor? = null
        var loadedUrl: String? = null
        var onlineLoads = 0
        override fun clearResourceCache(): Boolean {
            clears++
            return clearSucceeds
        }
        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
            offlineDirectory = directory
            this.interceptor = interceptor
            loadedUrl = url
        }
        override fun loadOnline(url: String) {
            onlineLoads++
            loadedUrl = url
        }
    }

    private fun zip(content: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { archive ->
            archive.putNextEntry(ZipEntry("index.html"))
            archive.write(content.toByteArray())
            archive.closeEntry()
        }
    }.toByteArray()

    private fun candidate(version: Int, bytes: ByteArray): OfflineCandidate = OfflineCandidate(
        PackageRecord(version, sha256(bytes)), PackageSource.Local { bytes.inputStream() }
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun response(candidate: OfflineCandidate) = ConfigResponse.Success(
        OfflineConfiguration(enabled = true, onlineVersion = candidate.record.version, candidate = candidate)
    )

    private fun manager(
        root: File,
        storage: MemoryStorage,
        config: ConfigSource,
        clock: Clock,
        outcomes: MutableList<InstallationOutcome>,
        diagnostics: MutableList<ManagedFailure> = CopyOnWriteArrayList(),
    ) = ManagedOfflineSdk.forTest(
        root = root,
        storage = storage,
        configProvider = config,
        minimumVersion = 10000,
        onInstallationOutcome = { outcomes += it },
        onDiagnostic = { diagnostics += it },
        ioDispatcher = Dispatchers.IO,
        mainDispatcher = testMainDispatcher,
        checkIntervalMillis = 300_000L,
        monotonicMillis = { clock.now },
    )

    private suspend fun awaitRequests(config: ConfigSource, count: Int) {
        withTimeout(5_000L) {
            while (config.requests.get() < count) kotlinx.coroutines.delay(10L)
        }
    }

    private suspend fun awaitOutcomes(outcomes: List<InstallationOutcome>, count: Int) {
        withTimeout(5_000L) {
            while (outcomes.size < count) kotlinx.coroutines.delay(10L)
        }
    }

    private suspend fun awaitIdle(sdk: ManagedOfflineSdk) {
        withTimeout(5_000L) {
            while (sdk.state.value.activity != ManagedActivity.Idle) kotlinx.coroutines.delay(10L)
        }
    }

    private suspend fun awaitHistoryWrites(storage: MemoryStorage, count: Int) {
        withTimeout(5_000L) {
            while (storage.historyWrites.size < count) delay(10L)
        }
    }

    /** 重启前台等待，由 SDK 自有静默任务根据已到期的单调时钟发起检查。 */
    private suspend fun resumeForDueCheck(sdk: ManagedOfflineSdk, config: ConfigSource, requestCount: Int) {
        sdk.setConditions(privacyAllowed = true, foreground = false)
        sdk.setConditions(privacyAllowed = true, foreground = true)
        awaitRequests(config, requestCount)
    }

    @Test fun firstSuccessPersistsHistoryAndEmitsOneCompletedInstallation() = onMain {
        val root = temporary.newFolder().canonicalFile
        val storage = MemoryStorage()
        val config = ConfigSource { response(candidate(10000, zip("first"))) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, config, Clock(), outcomes)
        try {
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst()
            assertTrue(first is FirstPreparationResult.Finished)
            assertTrue((first as FirstPreparationResult.Finished).check is CheckResult.Installed)
            assertTrue(first.usablePackage)
            assertEquals(1, config.requests.get())
            assertEquals(1, outcomes.size)
            assertEquals(storage.active, (outcomes.single() as InstallationOutcome.Installed).record)
            assertTrue(storage.history!!.initialPreparationFinished)
            assertNull(storage.history!!.latestFailure)
            assertEquals(listOf(PreparationHistory(initialPreparationFinished = true)), storage.historyWrites)
            assertEquals("first", root.resolve("10000/index.html").readText())
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertEquals(FirstPreparationResult.AlreadyFinished, sdk.prepareFirst())
            assertEquals(1, outcomes.size)
        } finally { sdk.shutdown() }
    }

    @Test fun historyWritesFollowFirstCompletionAndLaterTerminalResultsOnly() = onMain {
        val root = temporary.newFolder().canonicalFile
        val storage = MemoryStorage()
        val firstCandidate = candidate(10000, zip("first history"))
        val config = ConfigSource { response(firstCandidate) }
        val clock = Clock()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, config, clock, outcomes)
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst() as FirstPreparationResult.Finished
            assertTrue(first.check is CheckResult.Installed)
            assertEquals(listOf(PreparationHistory(initialPreparationFinished = true)), storage.historyWrites)

            assertEquals(1, config.requests.get())
            clock.now += 300_001L
            resumeForDueCheck(sdk, config, 2)
            awaitIdle(sdk)
            assertEquals(1, storage.historyWrites.size)
            config.response = { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
            clock.now += 300_001L
            resumeForDueCheck(sdk, config, 3)
            awaitIdle(sdk)
            assertFalse(sdk.state.value.enabled)
            assertEquals(1, storage.historyWrites.size)

            config.response = { response(candidate(10001, "broken history ZIP".toByteArray())) }
            clock.now += 300_001L
            resumeForDueCheck(sdk, config, 4)
            awaitOutcomes(outcomes, 2)
            awaitHistoryWrites(storage, 2)
            val failure = (outcomes.last() as InstallationOutcome.Failed).failure
            assertEquals(2, storage.historyWrites.size)
            assertEquals(PreparationHistory(true, failure), storage.historyWrites.last())

            clock.now += 300_001L
            resumeForDueCheck(sdk, config, 5)
            awaitIdle(sdk)
            assertEquals(2, storage.historyWrites.size)
            assertEquals(2, outcomes.size)

            val nextCandidate = candidate(10002, zip("next history"))
            config.response = { response(nextCandidate) }
            clock.now += 300_001L
            resumeForDueCheck(sdk, config, 6)
            awaitOutcomes(outcomes, 3)
            awaitHistoryWrites(storage, 3)
            assertEquals(nextCandidate.record, (outcomes.last() as InstallationOutcome.Installed).record)
            assertEquals(3, storage.historyWrites.size)
            assertEquals(PreparationHistory(initialPreparationFinished = true), storage.historyWrites.last())
            assertEquals(3, outcomes.size)
        } finally { sdk.shutdown() }
    }

    @Test fun regularHistoryCancellationKeepsCommittedInstallationOutcomeOnce() = onMain {
        for ((bytes, success) in listOf(zip("regular installed") to true, "broken regular ZIP".toByteArray() to false)) {
            val root = temporary.newFolder().canonicalFile
            val storage = MemoryStorage()
            val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
            val clock = Clock()
            val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
            val sdk = manager(root, storage, config, clock, outcomes)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            try {
                sdk.setConditions(privacyAllowed = true, foreground = true)
                assertEquals(CheckResult.Disabled, (sdk.prepareFirst() as FirstPreparationResult.Finished).check)
                val completedHistory = storage.history
                storage.historyWriteEntered = entered
                storage.historyWriteGate = gate
                config.response = { response(candidate(10000, bytes)) }
                clock.now += 300_001L
                resumeForDueCheck(sdk, config, 2)
                try {
                    withTimeout(5_000L) { entered.await() }
                    assertTrue(outcomes.isEmpty())
                    sdk.setConditions(privacyAllowed = false, foreground = false)
                    awaitOutcomes(outcomes, 1)
                    if (success) {
                        assertTrue(outcomes.single() is InstallationOutcome.Installed)
                        assertEquals(10000, storage.active!!.version)
                        assertTrue(root.resolve("10000/index.html").isFile)
                    } else {
                        val failure = (outcomes.single() as InstallationOutcome.Failed).failure
                        assertEquals(10000, failure.targetVersion)
                        assertEquals(ManagedStage.EXTRACT, failure.stage)
                        assertNull(storage.active)
                    }
                    assertEquals(completedHistory, storage.history)
                    assertEquals(1, storage.historyWrites.size)
                    assertTrue(sdk.state.value.initialPreparationFinished)
                    // SDK 静默任务被撤回隐私取消后，状态读取不得重放既有终态。
                    assertNotNull(sdk.state.value)
                    assertEquals(1, outcomes.size)
                } finally {
                    gate.complete(Unit)
                }
            } finally { sdk.shutdown() }
        }
    }

    @Test fun regularDiagnosticHistoryCancellationDoesNotEmitInstallationOutcome() = onMain {
        val storage = MemoryStorage()
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val clock = Clock()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, clock, outcomes)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            sdk.prepareFirst()
            val completedHistory = storage.history
            storage.historyWriteEntered = entered
            storage.historyWriteGate = gate
            config.response = { ConfigResponse.Failure(ConfigFailureReason.REQUEST) }
            clock.now += 300_001L
            resumeForDueCheck(sdk, config, 2)
            try {
                withTimeout(5_000L) { entered.await() }
                sdk.setConditions(privacyAllowed = false, foreground = false)
                awaitIdle(sdk)
                assertEquals(completedHistory, storage.history)
                assertEquals(1, storage.historyWrites.size)
                assertEquals(ManagedFailureReason.CONFIG_REQUEST, sdk.state.value.latestFailure!!.reason)
                assertNull(storage.active)
                assertTrue(outcomes.isEmpty())
                assertNotNull(sdk.state.value)
                assertTrue(outcomes.isEmpty())
            } finally {
                gate.complete(Unit)
            }
        } finally { sdk.shutdown() }
    }

    @Test fun failedVersionBlocksSameAndLowerVersionsButHigherVersionCanRun() = onMain {
        val root = temporary.newFolder().canonicalFile
        val storage = MemoryStorage()
        val bad = "not a ZIP".toByteArray()
        val a = candidate(10000, bad)
        val b = candidate(10001, bad)
        val config = ConfigSource { response(a) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val clock = Clock()
        val sdk = manager(root, storage, config, clock, outcomes)
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            assertTrue((sdk.prepareFirst() as FirstPreparationResult.Finished).check is CheckResult.Failed)
            assertEquals(1, outcomes.size)
            val first = outcomes.single() as InstallationOutcome.Failed
            assertEquals(10000, first.failure.targetVersion)
            assertEquals(ManagedStage.EXTRACT, first.failure.stage)
            assertTrue(storage.history!!.initialPreparationFinished)
            assertEquals(1, storage.historyWrites.size)
            assertEquals(first.failure, storage.historyWrites.single().latestFailure)

            config.response = { response(candidate(10000, zip("same version, new digest"))) }
            clock.now += 300_001L
            sdk.setConditions(privacyAllowed = true, foreground = false)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitRequests(config, 2)
            awaitIdle(sdk)
            assertEquals(1, outcomes.size)

            config.response = { response(b) }
            clock.now += 300_001L
            sdk.setConditions(privacyAllowed = true, foreground = false)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitOutcomes(outcomes, 2)
            assertEquals(10001, (outcomes.last() as InstallationOutcome.Failed).failure.targetVersion)

            config.response = { response(a) }
            clock.now += 300_001L
            sdk.setConditions(privacyAllowed = true, foreground = false)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitRequests(config, 4)
            awaitIdle(sdk)
            assertEquals(2, outcomes.size)
        } finally { sdk.shutdown() }

        // 新进程不从 latestFailure 恢复失败版本门槛。
        val coldRoot = temporary.newFolder().canonicalFile
        val coldOutcomes = CopyOnWriteArrayList<InstallationOutcome>()
        config.response = { response(candidate(10000, zip("cold retry"))) }
        val cold = manager(coldRoot, storage, config, Clock(), coldOutcomes)
        try {
            assertEquals(StartupDecision.CONTINUE, cold.startupDecision())
            cold.setConditions(privacyAllowed = true, foreground = true)
            awaitOutcomes(coldOutcomes, 1)
            assertTrue(coldOutcomes.single() is InstallationOutcome.Installed)
            assertEquals("cold retry", coldRoot.resolve("10000/index.html").readText())
        } finally { cold.shutdown() }
    }

    @Test fun cancelledFirstPreparationDoesNotInventFailureOrCompletion() = onMain {
        val root = temporary.newFolder().canonicalFile
        val storage = MemoryStorage()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val config = ConfigSource {
            entered.complete(Unit)
            gate.await()
            response(candidate(10000, zip("after cancellation")))
        }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, config, Clock(), outcomes)
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = async { sdk.prepareFirst() }
            withTimeout(5_000L) { entered.await() }
            first.cancelAndJoin()
            assertNull(storage.history)
            assertTrue(outcomes.isEmpty())
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())

            config.response = { response(candidate(10000, zip("after cancellation"))) }
            val retry = sdk.prepareFirst()
            assertTrue((retry as FirstPreparationResult.Finished).check is CheckResult.Installed)
            assertEquals(1, outcomes.size)
        } finally { sdk.shutdown() }
    }

    @Test fun withdrawingPrivacyCancelsTheActiveFirstCallWhenAnotherCallerIsWaiting() = onMain {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val config = ConfigSource {
            entered.complete(Unit)
            gate.await()
            response(candidate(10000, zip("should not install")))
        }
        val storage = MemoryStorage()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), outcomes)
        sdk.setConditions(privacyAllowed = true, foreground = true)
        val active = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
        var waiting: kotlinx.coroutines.Deferred<FirstPreparationResult>? = null
        try {
            withTimeout(5_000L) { entered.await() }
            waiting = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            sdk.setConditions(privacyAllowed = false, foreground = false)
            withTimeout(3_000L) { active.join(); waiting.join() }
            assertTrue(active.isCancelled)
            assertTrue(waiting.isCancelled)
            assertNull(storage.history)
            assertTrue(outcomes.isEmpty())
        } finally {
            gate.complete(Unit)
            active.cancelAndJoin()
            waiting?.cancelAndJoin()
            sdk.shutdown()
        }
    }

    @Test fun concurrentFirstCallersShareOneConfigurationAndInstallation() = onMain {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val bytes = zip("one first installation")
        val config = ConfigSource {
            entered.complete(Unit)
            gate.await()
            response(candidate(10000, bytes))
        }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(
            temporary.newFolder().canonicalFile, MemoryStorage(), config, Clock(), outcomes,
        )
        val ownerProgress = CopyOnWriteArrayList<ManagedProgress>()
        val waiterProgress = CopyOnWriteArrayList<ManagedProgress>()
        sdk.setConditions(privacyAllowed = true, foreground = true)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            sdk.prepareFirst(onProgress = { ownerProgress += it })
        }
        var second: kotlinx.coroutines.Deferred<FirstPreparationResult>? = null
        try {
            withTimeout(5_000L) { entered.await() }
            second = async(start = CoroutineStart.UNDISPATCHED) {
                sdk.prepareFirst(onProgress = { waiterProgress += it })
            }
            gate.complete(Unit)
            val results = withTimeout(5_000L) { listOf(first.await(), second.await()) }
            assertEquals(2, results.count {
                it is FirstPreparationResult.Finished && it.check is CheckResult.Installed
            })
            assertEquals(results.first(), results.last())
            assertEquals(1, config.requests.get())
            assertEquals(1, outcomes.size)
            assertTrue(ownerProgress.isNotEmpty())
            assertTrue(waiterProgress.isEmpty())
            val stages = ownerProgress.toList()
            assertEquals(ManagedProgress.Checking, stages.first())
            val preparing = stages.indexOf(ManagedProgress.Preparing(10000))
            val saving = stages.indexOf(ManagedProgress.Saving)
            val confirming = stages.indexOf(ManagedProgress.Confirming)
            assertTrue("owner 须收到目标准备进度", preparing > 0)
            assertTrue("保存后才进入确认阶段", saving > preparing && confirming > saving)
            assertEquals("成功确认后才完成", ManagedProgress.Complete, stages.last())
            assertEquals("完成进度只发一次", 1, stages.count { it == ManagedProgress.Complete })
        } finally {
            gate.complete(Unit)
            first.cancelAndJoin()
            second?.cancelAndJoin()
            sdk.shutdown()
        }
    }

    @Test fun cancellingFirstWaiterDoesNotCancelOwnerOrHostSibling() = onMain {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val config = ConfigSource {
            entered.complete(Unit)
            gate.await()
            response(candidate(10000, zip("owner completes")))
        }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, MemoryStorage(), config, Clock(), outcomes)
        sdk.setConditions(privacyAllowed = true, foreground = true)
        val owner = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
        val sibling = async { "host sibling survived" }
        var waiter: kotlinx.coroutines.Deferred<FirstPreparationResult>? = null
        try {
            withTimeout(5_000L) { entered.await() }
            waiter = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled)
            assertFalse(owner.isCompleted)
            gate.complete(Unit)
            val result = withTimeout(5_000L) { owner.await() } as FirstPreparationResult.Finished
            assertTrue(result.check is CheckResult.Installed)
            assertEquals("host sibling survived", sibling.await())
            assertEquals(1, config.requests.get())
            assertEquals(1, outcomes.size)
        } finally {
            gate.complete(Unit)
            owner.cancelAndJoin()
            waiter?.cancelAndJoin()
            sdk.shutdown()
        }
    }

    @Test fun cancellingFirstOwnerEndsWaitingAttemptWithoutAutomaticRetry() = onMain {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val config = ConfigSource {
            entered.complete(Unit)
            gate.await()
            response(candidate(10000, zip("old owner")))
        }
        val storage = MemoryStorage()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), outcomes)
        sdk.setConditions(privacyAllowed = true, foreground = true)
        val owner = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
        val sibling = async { "host sibling survived" }
        var waiter: kotlinx.coroutines.Deferred<FirstPreparationResult>? = null
        try {
            withTimeout(5_000L) { entered.await() }
            waiter = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            owner.cancelAndJoin()
            withTimeout(5_000L) { waiter.join() }
            assertTrue(owner.isCancelled)
            assertTrue(waiter.isCancelled)
            assertEquals(1, config.requests.get())
            assertNull(storage.history)
            assertTrue(outcomes.isEmpty())
            assertEquals("host sibling survived", sibling.await())

            // 只有新的显式调用才会在旧 owner 清理后重新评估首装。
            config.response = { response(candidate(10000, zip("explicit retry"))) }
            val retry = sdk.prepareFirst() as FirstPreparationResult.Finished
            assertTrue(retry.check is CheckResult.Installed)
            assertEquals(2, config.requests.get())
            assertEquals(1, outcomes.size)
        } finally {
            gate.complete(Unit)
            owner.cancelAndJoin()
            waiter?.cancelAndJoin()
            sdk.shutdown()
        }
    }

    @Test fun cancelledBeforeFirstOwnerRunsAllowsLaterExplicitRetry() = onMain {
        val storage = MemoryStorage()
        val config = ConfigSource { response(candidate(10000, zip("later retry"))) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), outcomes)
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            // 同一串行 Main 任务尚未让出执行权；owner 已登记，但其 LAZY 子任务仍在队列中。
            val owner = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            sdk.setConditions(privacyAllowed = false, foreground = false)
            withTimeout(5_000L) { owner.join(); waiter.join() }
            assertTrue(owner.isCancelled)
            assertTrue(waiter.isCancelled)
            assertEquals(0, config.requests.get())
            assertNull(storage.history)
            assertFalse(sdk.state.value.initialPreparationFinished)
            assertTrue(outcomes.isEmpty())

            sdk.setConditions(privacyAllowed = true, foreground = true)
            val retry = withTimeout(5_000L) { sdk.prepareFirst() } as FirstPreparationResult.Finished
            assertTrue(retry.check is CheckResult.Installed)
            assertTrue(storage.history!!.initialPreparationFinished)
            assertEquals(1, config.requests.get())
            assertEquals(1, outcomes.size)
        } finally { sdk.shutdown() }
    }

    @Test fun cancellationDuringFirstHistoryWriteDoesNotFinishPreparation() = onMain {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val storage = MemoryStorage().apply {
            historyWriteEntered = entered
            historyWriteGate = gate
        }
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), outcomes)
        sdk.setConditions(privacyAllowed = true, foreground = true)
        val first = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
        try {
            withTimeout(5_000L) { entered.await() }
            first.cancelAndJoin()
            assertNull(storage.history)
            assertFalse(sdk.state.value.initialPreparationFinished)
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())
            assertTrue(outcomes.isEmpty())
        } finally {
            gate.complete(Unit)
            first.cancelAndJoin()
            sdk.shutdown()
        }
    }

    @Test fun completedInstallationOutcomeIsOfferedOnceIfFirstHistoryWriteIsCancelled() = onMain {
        for ((bytes, success) in listOf(zip("installed") to true, "broken ZIP".toByteArray() to false)) {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val storage = MemoryStorage().apply {
                historyWriteEntered = entered
                historyWriteGate = gate
            }
            val root = temporary.newFolder().canonicalFile
            val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
            val sdk = manager(root, storage, ConfigSource { response(candidate(10000, bytes)) }, Clock(), outcomes)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            try {
                withTimeout(5_000L) { entered.await() }
                assertTrue(outcomes.isEmpty())
                first.cancelAndJoin()
                assertEquals(1, outcomes.size)
                if (success) {
                    assertTrue(outcomes.single() is InstallationOutcome.Installed)
                    assertNotNull(storage.active)
                    assertTrue(root.resolve("10000/index.html").isFile)
                } else {
                    val failure = (outcomes.single() as InstallationOutcome.Failed).failure
                    assertEquals(10000, failure.targetVersion)
                    assertEquals(ManagedStage.EXTRACT, failure.stage)
                    assertNull(storage.active)
                }
                assertNull(storage.history)
                assertFalse(sdk.state.value.initialPreparationFinished)
                // 读取状态不得重放已经提交的一次终态事件。
                assertNotNull(sdk.state.value)
                assertEquals(1, outcomes.size)
            } finally {
                gate.complete(Unit)
                first.cancelAndJoin()
                sdk.shutdown()
            }
        }
    }

    @Test fun firstConfigurationReturningInBackgroundDoesNotStartInstallation() = onMain {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val bytes = zip("foreground only")
        val config = ConfigSource {
            entered.complete(Unit)
            gate.await()
            response(candidate(10000, bytes))
        }
        val root = temporary.newFolder().canonicalFile
        val storage = MemoryStorage()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, config, Clock(), outcomes)
        sdk.setConditions(privacyAllowed = true, foreground = true)
        val first = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
        try {
            withTimeout(5_000L) { entered.await() }
            sdk.setConditions(privacyAllowed = true, foreground = false)
            gate.complete(Unit)
            val result = try { withTimeout(5_000L) { first.await() } }
                catch (_: CancellationException) { null }
            if (result is FirstPreparationResult.Finished) {
                assertFalse(result.check is CheckResult.Installed)
            }
            assertNull(storage.active)
            assertFalse(root.resolve("10000").exists())
            assertTrue(outcomes.isEmpty())
        } finally {
            gate.complete(Unit)
            first.cancelAndJoin()
            sdk.shutdown()
        }
    }

    @Test fun activeSaveFailureReportsOneTargetFailureAndKeepsPreviousActive() = onMain {
        val root = temporary.newFolder().canonicalFile
        val oldBytes = zip("old")
        val old = candidate(10000, oldBytes).record
        assertTrue(PackageInstaller(root).install(old) { oldBytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            active = old
            history = PreparationHistory(initialPreparationFinished = true)
            activeWriteSucceeds = false
        }
        val next = candidate(10001, zip("new"))
        val config = ConfigSource { response(next) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, config, Clock(), outcomes)
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitOutcomes(outcomes, 1)
            val failure = (outcomes.single() as InstallationOutcome.Failed).failure
            assertEquals(10001, failure.targetVersion)
            assertEquals(ManagedStage.SAVE_ACTIVE, failure.stage)
            assertEquals(old, storage.active)
            assertEquals("old", root.resolve("10000/index.html").readText())
            assertNotNull(storage.history!!.latestFailure)
        } finally { sdk.shutdown() }
    }

    @Test fun configurationFailureIsASeparateDiagnosticAndFinishesFirstPreparation() = onMain {
        val storage = MemoryStorage()
        val config = ConfigSource { ConfigResponse.Failure(ConfigFailureReason.REQUEST, "offline") }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val diagnostics = CopyOnWriteArrayList<ManagedFailure>()
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), outcomes, diagnostics)
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst() as FirstPreparationResult.Finished
            assertTrue(first.check is CheckResult.Failed)
            assertFalse(first.usablePackage)
            assertTrue(storage.history!!.initialPreparationFinished)
            assertEquals(ManagedFailureReason.CONFIG_REQUEST, storage.history!!.latestFailure!!.reason)
            assertTrue(outcomes.isEmpty())
            assertEquals(1, diagnostics.size)
            assertEquals(ManagedStage.CONFIG, diagnostics.single().stage)
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
        } finally { sdk.shutdown() }
    }

    @Test fun failedHistoryWriteStillCompletesFirstPreparationInThisProcess() = onMain {
        val storage = MemoryStorage().apply { historyWriteSucceeds = false }
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val diagnostics = CopyOnWriteArrayList<ManagedFailure>()
        val root = temporary.newFolder().canonicalFile
        val sdk = manager(root, storage, config, Clock(), outcomes, diagnostics)
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            assertTrue(sdk.prepareFirst() is FirstPreparationResult.Finished)
            assertNull(storage.history)
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertTrue(diagnostics.any { it.stage == ManagedStage.HISTORY })
            assertTrue(outcomes.isEmpty())
        } finally { sdk.shutdown() }

        val next = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), CopyOnWriteArrayList())
        try { assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, next.startupDecision()) }
        finally { next.shutdown() }
    }

    @Test fun outcomeCallbackExceptionCannotChangeInstalledResultOrTriggerAnotherAttempt() = onMain {
        val root = temporary.newFolder().canonicalFile
        val storage = MemoryStorage()
        val bytes = zip("callback")
        val config = ConfigSource { response(candidate(10000, bytes)) }
        var offered = 0
        val sdk = ManagedOfflineSdk.forTest(
            root = root,
            storage = storage,
            configProvider = config,
            minimumVersion = 10000,
            onInstallationOutcome = {
                offered++
                throw IllegalStateException("host report scheduling failed")
            },
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = testMainDispatcher,
        )
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst() as FirstPreparationResult.Finished
            assertTrue(first.check is CheckResult.Installed)
            assertTrue(first.usablePackage)
            assertEquals(1, offered)
            assertEquals(storage.active, (first.check as CheckResult.Installed).record)
            assertNull(storage.history!!.latestFailure)
            // 状态读取不能重放安装终态回调。
            assertNotNull(sdk.state.value)
            assertNotNull(sdk.state.value)
            assertEquals(1, offered)
        } finally { sdk.shutdown() }
    }

    @Test fun pageLoadUsesCurrentEnabledStateAndConsumesCacheOnOnlinePath() = onMain {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("old page")
        val old = candidate(10000, bytes).record
        assertTrue(PackageInstaller(root).install(old) { bytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            active = old
            history = PreparationHistory(initialPreparationFinished = true)
            cacheDirty = true
        }
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val clock = Clock()
        val sdk = manager(root, storage, config, clock, CopyOnWriteArrayList())
        val base = "https://offline.example/demo/"
        val page = "${base}article"
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            val callbacks = PageCallbacks()
            assertEquals(
                PageDecision.Offline(old),
                sdk.loadPage(page, base, callbacks),
            )
            assertEquals(root.resolve("10000"), callbacks.offlineDirectory)
            assertEquals(page, callbacks.loadedUrl)
            assertEquals(1, callbacks.clears)
            withTimeout(5_000L) { while (storage.cacheDirty != false) delay(10L) }
            assertEquals(false, storage.cacheDirty)

            // 已绑定的旧拦截器也必须服从进程内关闭事实。
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitRequests(config, 1)
            withTimeout(5_000L) {
                while (sdk.state.value.enabled) kotlinx.coroutines.delay(10L)
            }
            assertEquals(PageDecision.Online, sdk.loadPage(page, base, callbacks))
            assertEquals(1, callbacks.onlineLoads)
            assertEquals(2, callbacks.clears)
            withTimeout(5_000L) { while (storage.cacheDirty != false) delay(10L) }
            assertEquals(false, storage.cacheDirty)
            assertNull(callbacks.interceptor!!.resolve("${base}index.html"))
        } finally { sdk.shutdown() }
    }

    @Test fun pageLoadAllowsHttpAndHttpsDefaultPortsButRejectsTraversalPaths() = onMain {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("safe page")
        val active = candidate(10000, bytes).record
        assertTrue(PackageInstaller(root).install(active) { bytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            this.active = active
            history = PreparationHistory(initialPreparationFinished = true)
        }
        val sdk = manager(
            root, storage,
            ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
            Clock(), CopyOnWriteArrayList(),
        )
        val base = "https://offline.example/demo/"
        val http = "http://offline.example/demo/route"
        val httpExplicitDefaultPort = "http://offline.example:80/demo/route"
        val callbacks = PageCallbacks()
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertEquals(
                PageDecision.Online,
                sdk.loadPage(http, base, callbacks),
            )
            assertEquals(
                PageDecision.Offline(active),
                sdk.loadPage(http, base, callbacks, allowHttpAndHttps = true),
            )
            assertEquals(
                PageDecision.Offline(active),
                sdk.loadPage(
                    httpExplicitDefaultPort, base, callbacks,
                    allowHttpAndHttps = true,
                ),
            )
            for (unsafe in listOf(
                "https://offline.example/demo/../outside",
                "https://offline.example/demo/%2e%2e/outside",
            )) {
                assertEquals(
                    PageDecision.Online,
                    sdk.loadPage(unsafe, base, callbacks, allowHttpAndHttps = true),
                )
                assertEquals(unsafe, callbacks.loadedUrl)
            }
            assertEquals(3, callbacks.onlineLoads)
        } finally { sdk.shutdown() }
    }

    @Test fun cancelledPageIoDoesNotLateLoadOrClearCache() = onMain {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("old page")
        val active = candidate(10000, bytes).record
        assertTrue(PackageInstaller(root).install(active) { bytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            this.active = active
            history = PreparationHistory(initialPreparationFinished = true)
            cacheDirty = true
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val sdk = ManagedOfflineSdk.forTest(
                root = root,
                storage = storage,
                configProvider = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = true)) },
                minimumVersion = 10000,
                ioDispatcher = io,
                mainDispatcher = testMainDispatcher,
            )
            val ioBusy = CountDownLatch(1)
            val releaseIo = CountDownLatch(1)
            val callbacks = PageCallbacks()
            try {
                sdk.startupDecision()
                val blocker = async(io) {
                    ioBusy.countDown()
                    check(releaseIo.await(5, TimeUnit.SECONDS))
                }
                assertTrue(ioBusy.await(5, TimeUnit.SECONDS))
                val page = async(start = CoroutineStart.UNDISPATCHED) {
                    sdk.loadPage("https://offline.example/demo/", "https://offline.example/demo/", callbacks)
                }
                assertFalse("页面观察应等待 IO", page.isCompleted)
                page.cancel()
                releaseIo.countDown()
                page.cancelAndJoin()
                blocker.await()
                assertNull("取消后不得迟到加载", callbacks.loadedUrl)
                assertEquals(0, callbacks.clears)
                assertEquals(0, callbacks.onlineLoads)
            } finally {
                releaseIo.countDown()
                sdk.shutdown()
            }
        }
    }

    @Test fun cancelledCacheCallbackDoesNotBindDirectoryBeforePageLoad() = onMain {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("same version repair")
        val target = candidate(10000, bytes)
        assertTrue(PackageInstaller(root).install(target.record) { bytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            active = target.record
            history = PreparationHistory(initialPreparationFinished = true)
            cacheDirty = true
        }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, ConfigSource { response(target) }, Clock(), outcomes)
        var loads = 0
        val callbacks = object : ManagedPageCallbacks {
            override fun clearResourceCache(): Boolean = throw CancellationException("页面已关闭")
            override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) { loads++ }
            override fun loadOnline(url: String) { loads++ }
        }
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            var cancelled = false
            try { sdk.loadPage("https://offline.example/demo/", "https://offline.example/demo/", callbacks) }
            catch (_: CancellationException) { cancelled = true }
            assertTrue("同步缓存回调取消须传播", cancelled)
            assertEquals("取消后不得加载页面", 0, loads)

            assertTrue(root.resolve("10000/index.html").delete())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitOutcomes(outcomes, 1)
            assertEquals("页面未加载时同版修复不得被目录占用阻断",
                InstallationOutcome.Installed(target.record), outcomes.single())
            assertEquals("same version repair", root.resolve("10000/index.html").readText())
        } finally { sdk.shutdown() }
    }

    @Test fun failedCacheFlagWriteIsRetriedOnNextOnlinePage() = onMain {
        val storage = MemoryStorage().apply {
            history = PreparationHistory(initialPreparationFinished = true)
            cacheDirty = true
            cacheWriteSucceeds = false
        }
        val sdk = manager(
            temporary.newFolder().canonicalFile,
            storage,
            ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
            Clock(), CopyOnWriteArrayList(),
        )
        val callbacks = PageCallbacks()
        val base = "https://offline.example/demo/"
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertEquals(PageDecision.Online, sdk.loadPage(base, base, callbacks))
            assertEquals(1, callbacks.clears)
            withTimeout(5_000L) { while (!storage.cacheWrites.contains(false)) delay(10L) }
            assertEquals(true, storage.cacheDirty)
            storage.cacheWriteSucceeds = true
            assertEquals(PageDecision.Online, sdk.loadPage(base, base, callbacks))
            assertEquals(2, callbacks.clears)
            withTimeout(5_000L) { while (storage.cacheDirty != false) delay(10L) }
            assertEquals(false, storage.cacheDirty)
        } finally { sdk.shutdown() }
    }

    @Test fun cacheReadCancellationPropagatesAndPageWriteCancellationRetainsDirtyFlag() = onMain {
        val readStorage = MemoryStorage().apply { cancelCacheRead = true }
        val readSdk = manager(
            temporary.newFolder().canonicalFile, readStorage,
            ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
            Clock(), CopyOnWriteArrayList(),
        )
        try {
            var cancelled = false
            try { readSdk.startupDecision() }
            catch (_: CancellationException) { cancelled = true }
            assertTrue("synchronous cache read cancellation was swallowed", cancelled)
        } finally { readSdk.shutdown() }

        val writeStorage = MemoryStorage().apply {
            history = PreparationHistory(initialPreparationFinished = true)
            cacheDirty = true
            cancelCacheWrite = false
        }
        val writeSdk = manager(
            temporary.newFolder().canonicalFile, writeStorage,
            ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
            Clock(), CopyOnWriteArrayList(),
        )
        val callbacks = PageCallbacks()
        val base = "https://offline.example/demo/"
        try {
            assertEquals(StartupDecision.CONTINUE, writeSdk.startupDecision())
            assertEquals(PageDecision.Online, writeSdk.loadPage(base, base, callbacks))
            withTimeout(5_000L) { while (!writeStorage.cacheWrites.contains(false)) delay(10L) }
            assertEquals(1, callbacks.onlineLoads)
            assertEquals(true, writeStorage.cacheDirty)
            writeStorage.cancelCacheWrite = null
            assertEquals(PageDecision.Online, writeSdk.loadPage(base, base, callbacks))
            withTimeout(5_000L) { while (writeStorage.cacheDirty != false) delay(10L) }
            assertEquals(false, writeStorage.cacheDirty)
        } finally { writeSdk.shutdown() }
    }

    @Test fun laterConfigurationDirtyWriteWinsOverEarlierPageClear() = onMain {
        val storage = MemoryStorage().apply {
            history = PreparationHistory(initialPreparationFinished = true)
            cacheDirty = true
            cleanWriteEntered = CompletableDeferred()
            cleanWriteGate = CompletableDeferred()
        }
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, Clock(), CopyOnWriteArrayList())
        val callbacks = PageCallbacks()
        val base = "https://offline.example/demo/"
        try {
            sdk.startupDecision()
            sdk.loadPage(base, base, callbacks)
            storage.cleanWriteEntered!!.await()
            sdk.setConditions(true, true)
            awaitRequests(config, 1)
            withTimeout(5_000L) { while (sdk.state.value.enabled) delay(10L) }
            storage.cleanWriteGate!!.complete(Unit)
            withTimeout(5_000L) { while (storage.cacheDirty != true || storage.cacheWrites.count { it } < 1) delay(10L) }
            assertEquals(true, storage.cacheDirty)
            storage.cleanWriteGate = null
            sdk.loadPage(base, base, callbacks)
            withTimeout(5_000L) { while (storage.cacheDirty != false) delay(10L) }
            assertEquals(2, callbacks.clears)
        } finally {
            storage.cleanWriteGate?.complete(Unit)
            sdk.shutdown()
        }
    }

    @Test(timeout = 20_000L)
    fun pageLoadDoesNotWaitForLongInstallationAndBoundDirectorySurvives() = onMain {
        val root = temporary.newFolder().canonicalFile
        val oldBytes = zip("old")
        val old = candidate(10000, oldBytes).record
        assertTrue(PackageInstaller(root).install(old) { oldBytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            active = old
            history = PreparationHistory(initialPreparationFinished = true)
        }
        val newBytes = zip("new")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val next = OfflineCandidate(candidate(10001, newBytes).record, PackageSource.Local {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            newBytes.inputStream()
        })
        val config = ConfigSource { response(next) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { io ->
            val sdk = ManagedOfflineSdk.forTest(
                root = root, storage = storage, configProvider = config, minimumVersion = 10000,
                onInstallationOutcome = { outcomes += it }, ioDispatcher = io,
                mainDispatcher = testMainDispatcher,
            )
            val base = "https://offline.example/demo/"
            val callbacks = PageCallbacks()
            try {
                assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
                assertEquals(
                    PageDecision.Offline(old),
                    sdk.loadPage(base, base, callbacks),
                )
                sdk.setConditions(privacyAllowed = true, foreground = true)
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                assertEquals(StartupDecision.CONTINUE, withTimeout(2_000L) { sdk.startupDecision() })
                assertEquals(PageDecision.Online, withTimeout(2_000L) { sdk.loadPage(base, base, callbacks) })
                release.countDown()
                awaitOutcomes(outcomes, 1)
                assertTrue(outcomes.single() is InstallationOutcome.Installed)
                assertEquals("old", root.resolve("10000/index.html").readText())
                assertEquals("new", root.resolve("10001/index.html").readText())
            } finally {
                release.countDown()
                sdk.shutdown()
            }
        }
    }

    @Test(timeout = 20_000L)
    fun firstPageLoadWaitsForLocalInitializationButNotTheFollowingLongInstall() = onMain {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("first install")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = OfflineCandidate(candidate(10000, bytes).record, PackageSource.Local {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            bytes.inputStream()
        })
        val config = ConfigSource { response(target) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { io ->
            val sdk = ManagedOfflineSdk.forTest(
                root = root, storage = MemoryStorage(), configProvider = config,
                minimumVersion = 10000, onInstallationOutcome = { outcomes += it },
                ioDispatcher = io, mainDispatcher = testMainDispatcher,
            )
            sdk.setConditions(privacyAllowed = true, foreground = true)
            // 尚未调用 startupDecision；prepareFirst 先完成本地准备，
            // 随后在 ZIP 来源处挂起，页面不应等待整个安装许可。
            val first = async(start = CoroutineStart.UNDISPATCHED) { sdk.prepareFirst() }
            val base = "https://offline.example/demo/"
            val callbacks = PageCallbacks()
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                assertEquals(PageDecision.Online, withTimeout(2_000L) { sdk.loadPage(base, base, callbacks) })
                assertEquals(1, callbacks.onlineLoads)
                release.countDown()
                val result = withTimeout(5_000L) { first.await() }
                assertTrue((result as FirstPreparationResult.Finished).check is CheckResult.Installed)
                assertEquals(1, outcomes.size)
            } finally {
                release.countDown()
                first.cancelAndJoin()
                sdk.shutdown()
            }
        }
    }

    @Test fun fiveMinuteLimitUsesActualRequestStartAndBackgroundDoesNotStartChecks() = onMain {
        val clock = Clock()
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val sdk = manager(
            temporary.newFolder().canonicalFile, MemoryStorage(), config, clock,
            CopyOnWriteArrayList(),
        )
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            assertTrue(sdk.prepareFirst() is FirstPreparationResult.Finished)
            assertEquals(1, config.requests.get())
            sdk.setConditions(privacyAllowed = true, foreground = false)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            delay(50L)
            assertEquals(1, config.requests.get())
            clock.now += 299_999L
            sdk.setConditions(privacyAllowed = true, foreground = false)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            delay(50L)
            assertEquals(1, config.requests.get())
            clock.now++
            resumeForDueCheck(sdk, config, 2)
            awaitIdle(sdk)
            assertFalse(sdk.state.value.enabled)
            assertEquals(2, config.requests.get())
            sdk.setConditions(privacyAllowed = true, foreground = false)
            clock.now += 300_000L
            delay(50L)
            assertEquals(2, config.requests.get())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitRequests(config, 3)
        } finally { sdk.shutdown() }
    }

    @Test fun resumedPollingWaitsForCancelledSilentCheckToFinishCleanup() = onMain {
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val config = ConfigSource {
            if (attempts.incrementAndGet() == 1) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    // 模拟 provider 取消后必须完成的不可取消资源收尾。
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            } else {
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            }
        }
        val clock = Clock()
        val sdk = manager(
            temporary.newFolder().canonicalFile,
            MemoryStorage().apply { history = PreparationHistory(initialPreparationFinished = true) },
            config, clock, CopyOnWriteArrayList(),
        )
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            withTimeout(5_000L) { entered.await() }
            assertEquals(1, config.requests.get())
            clock.now += 300_001L
            sdk.setConditions(privacyAllowed = false, foreground = false)
            withTimeout(5_000L) { cleanupEntered.await() }
            sdk.setConditions(privacyAllowed = true, foreground = true)
            delay(100L)
            assertEquals("旧任务清理完毕前不能进入第二次 provider", 1, config.requests.get())

            releaseCleanup.complete(Unit)
            awaitRequests(config, 2)
            awaitIdle(sdk)
            assertEquals(2, config.requests.get())
        } finally {
            releaseCleanup.complete(Unit)
            sdk.shutdown()
        }
    }

    @Test fun cancelledProviderPastTtlCanBeCheckedAgainWithoutExtraCooldown() = onMain {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val config = ConfigSource {
            if (attempts.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
                throw CancellationException("provider stopped its first request")
            }
            ConfigResponse.Success(OfflineConfiguration(enabled = false))
        }
        val clock = Clock()
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val storage = MemoryStorage().apply { history = PreparationHistory(initialPreparationFinished = true) }
        val sdk = manager(temporary.newFolder().canonicalFile, storage, config, clock, outcomes)
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            withTimeout(5_000L) { entered.await() }
            assertEquals(1, config.requests.get())
            clock.now += 300_001L
            release.complete(Unit)
            awaitRequests(config, 2)
            awaitIdle(sdk)
            assertEquals(2, config.requests.get())
            assertTrue(outcomes.isEmpty())
            assertNull(storage.history!!.latestFailure)
        } finally {
            release.complete(Unit)
            sdk.shutdown()
        }
    }

    @Test(timeout = 20_000L)
    fun sustainedForegroundPollsAndBackgroundStopsFutureRequests() = onMain {
        val config = ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) }
        val sdk = ManagedOfflineSdk.forTest(
            root = temporary.newFolder().canonicalFile,
            storage = MemoryStorage(),
            configProvider = config,
            minimumVersion = 10000,
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = testMainDispatcher,
            checkIntervalMillis = 75L,
        )
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            sdk.prepareFirst()
            awaitRequests(config, 3)
            sdk.setConditions(privacyAllowed = true, foreground = false)
            delay(150L)
            val stoppedAt = config.requests.get()
            delay(250L)
            assertEquals(stoppedAt, config.requests.get())
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitRequests(config, stoppedAt + 1)
        } finally { sdk.shutdown() }
    }

    @Test fun distinctDownloadVerifyAndPublishFailuresHaveOneTypedOutcomeEach() = onMain {
        val good = zip("stage test")
        val cases = listOf(
            Triple(
                ManagedStage.DOWNLOAD,
                { _: File -> OfflineCandidate(
                    PackageRecord(10000, sha256(good)),
                    PackageSource.Local { throw IOException("cannot read source") },
                ) },
                FailureReason.FILE_IO,
            ),
            Triple(
                ManagedStage.DOWNLOAD,
                { _: File -> OfflineCandidate(
                    PackageRecord(10000, sha256(good)),
                    PackageSource.Local { throw IllegalStateException("local source crashed") },
                ) },
                FailureReason.FILE_IO,
            ),
            Triple(
                ManagedStage.VERIFY,
                { _: File -> OfflineCandidate(
                    PackageRecord(10000, "0".repeat(64)),
                    PackageSource.Local { good.inputStream() },
                ) },
                FailureReason.HASH_MISMATCH,
            ),
            Triple(
                ManagedStage.PUBLISH,
                { root: File -> OfflineCandidate(
                    PackageRecord(10000, sha256(good)),
                    PackageSource.Local {
                        check(root.resolve("10000").mkdir())
                        good.inputStream()
                    },
                ) },
                FailureReason.TARGET_EXISTS,
            ),
        )
        for ((expectedStage, makeCandidate, expectedReason) in cases) {
            val root = temporary.newFolder().canonicalFile
            val target = makeCandidate(root)
            val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
            val sdk = manager(
                root, MemoryStorage(), ConfigSource { response(target) }, Clock(), outcomes,
            )
            try {
                sdk.setConditions(privacyAllowed = true, foreground = true)
                val first = sdk.prepareFirst() as FirstPreparationResult.Finished
                assertTrue("$expectedStage: ${first.check}", first.check is CheckResult.Failed)
                assertEquals("$expectedStage", 1, outcomes.size)
                val failure = (outcomes.single() as InstallationOutcome.Failed).failure
                assertEquals(expectedStage, failure.stage)
                assertEquals(expectedReason, failure.installReason)
                assertEquals(10000, failure.targetVersion)
            } finally { sdk.shutdown() }
        }
    }

    @Test fun legacyFailureMigratesCompletionButDoesNotBecomeAProcessBan() = onMain {
        val oldFailure = ManagedFailure(
            ManagedFailureReason.INSTALL, ManagedStage.DOWNLOAD,
            targetVersion = 10000, targetSha256 = "a".repeat(64),
        )
        val storage = MemoryStorage().apply { legacy = LegacyPreparationEvidence(oldFailure) }
        val bytes = zip("legacy retry")
        val config = ConfigSource { response(candidate(10000, bytes)) }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val root = temporary.newFolder().canonicalFile
        val sdk = manager(root, storage, config, Clock(), outcomes)
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertTrue(storage.history!!.initialPreparationFinished)
            assertEquals(oldFailure, storage.history!!.latestFailure)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitOutcomes(outcomes, 1)
            assertTrue(outcomes.single() is InstallationOutcome.Installed)
            assertEquals("legacy retry", root.resolve("10000/index.html").readText())
            assertNull(storage.history!!.latestFailure)
        } finally { sdk.shutdown() }
    }

    @Test fun validOldActiveRecordKeepsFirstPreparationFinishedWhenFileIsMissing() = onMain {
        val storage = MemoryStorage().apply {
            active = PackageRecord(10000, "a".repeat(64))
        }
        val sdk = manager(
            temporary.newFolder().canonicalFile, storage,
            ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
            Clock(), CopyOnWriteArrayList(),
        )
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertFalse(sdk.state.value.usablePackage)
            assertTrue(storage.history!!.initialPreparationFinished)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            assertEquals(FirstPreparationResult.AlreadyFinished, sdk.prepareFirst())
        } finally { sdk.shutdown() }
    }

    @Test fun disabledConfigurationWinsBeforeInvalidCandidateValidation() = onMain {
        val invalid = OfflineCandidate(
            PackageRecord(1, "bad"), PackageSource.Remote("file:///unsupported.zip"),
        )
        val config = ConfigSource {
            ConfigResponse.Success(OfflineConfiguration(enabled = false, candidate = invalid))
        }
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(
            temporary.newFolder().canonicalFile, MemoryStorage(), config, Clock(), outcomes,
        )
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst() as FirstPreparationResult.Finished
            assertEquals(CheckResult.Disabled, first.check)
            assertFalse(sdk.state.value.enabled)
            assertTrue(outcomes.isEmpty())
        } finally { sdk.shutdown() }
    }

    @Test fun boundPageProtectsAnExistingTargetDirectoryFromReplacement() = onMain {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("active")
        val active = candidate(10000, bytes).record
        assertTrue(PackageInstaller(root).install(active) { bytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            this.active = active
            history = PreparationHistory(initialPreparationFinished = true)
        }
        val targetBytes = zip("must stay")
        val target = candidate(10001, targetBytes)
        val targetDir = root.resolve("10001")
        val outcomes = CopyOnWriteArrayList<InstallationOutcome>()
        val sdk = manager(root, storage, ConfigSource { response(target) }, Clock(), outcomes)
        val base = "https://offline.example/demo/"
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertEquals(
                PageDecision.Offline(active),
                sdk.loadPage(base, base, PageCallbacks()),
            )
            // 模拟冷清理后出现残留版本目录；一旦页面绑定，
            // 管理器在本进程内不得替换任何版本目录。
            assertTrue(targetDir.mkdir())
            targetDir.resolve("index.html").writeText("must stay")
            sdk.setConditions(privacyAllowed = true, foreground = true)
            awaitOutcomes(outcomes, 1)
            val failure = (outcomes.single() as InstallationOutcome.Failed).failure
            assertEquals(ManagedFailureReason.TARGET_IN_USE, failure.reason)
            assertEquals(ManagedStage.PREPARE, failure.stage)
            assertEquals("must stay", targetDir.resolve("index.html").readText())
        } finally { sdk.shutdown() }
    }

    @Test fun managedRootRejectsOtherLowLevelMutationsButKeepsReadOnlyCompatibility() = onMain {
        val root = temporary.newFolder().canonicalFile
        val originalBytes = zip("existing package")
        val original = candidate(10000, originalBytes).record
        val legacyInstaller = PackageInstaller(root)
        assertTrue(legacyInstaller.install(original) { originalBytes.inputStream() } is InstallResult.Success)
        val storage = MemoryStorage().apply {
            active = original
            history = PreparationHistory(initialPreparationFinished = true)
        }
        val sdk = manager(
            root, storage,
            ConfigSource { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
            Clock(), CopyOnWriteArrayList(),
        )
        try {
            // Main 轻量构造后，首次合法入口在 IO 准备并接管根目录。
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            // 接管前后创建的底层安装器都服从管理器的独占写入权，
            // 被拒绝的操作不得打开本地来源或发起 HTTP 请求。
            val outsider = PackageInstaller(root)
            val nextBytes = zip("unmanaged write")
            val next = candidate(10001, nextBytes).record
            var opened = 0
            val local = legacyInstaller.install(next) {
                opened++
                nextBytes.inputStream()
            }
            val builtin = outsider.installBuiltin(10001) {
                opened++
                nextBytes.inputStream()
            }
            val remote = outsider.install(next, "https://offline.example/package.zip")
            for (result in listOf(local, builtin, remote)) {
                assertTrue(result is InstallResult.Failure)
                val denied = result as InstallResult.Failure
                assertEquals(FailureReason.MANAGED_ROOT, denied.reason)
                assertEquals(InstallStage.PREPARE, denied.stage)
                assertFalse(denied.requestStarted)
            }
            assertEquals(0, opened)
            assertFalse(legacyInstaller.clearOldVersions(null))
            assertFalse(outsider.discardUnboundVersion(10000))
            assertTrue(legacyInstaller.isUsable(10000))
            assertTrue(outsider.isUsable(10000))
            assertEquals("existing package", root.resolve("10000/index.html").readText())
            assertFalse(root.resolve("10001").exists())
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
        } finally { sdk.shutdown() }
    }
}
