package com.offline.tool

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 吸收独立验收的六项回归，并补充取消归属、静默调度、线程、页面与下载注入的行为证据。
 * 虚拟时间和挂起门闩只控制边界，不依赖线上服务或真实轮询等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagedOfflineRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun firstAndRegularActivationPublishOnlyCompleteSuccessSnapshots() = runTest {
        for (firstFinished in listOf(false, true)) {
            val target = candidate(100001, zip("complete success snapshot"))
            val previousFailure = ManagedFailure(ManagedFailureReason.CONFIG_REQUEST, ManagedStage.CONFIG)
            val store = MemoryStorage().apply {
                history = PreparationHistory(firstFinished, previousFailure)
            }
            val outcomes = mutableListOf<InstallationOutcome>()
            val sdk = manager(temporary.newFolder().canonicalFile, store, response(target), { outcomes += it })
            val snapshots = mutableListOf<ManagedSnapshot>()
            // 同步收集每次公开快照，避免 StateFlow 合并中间值掩盖半提交状态。
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                sdk.state.collect { snapshots += it }
            }
            try {
                sdk.startupDecision()
                assertEquals(previousFailure, sdk.state.value.latestFailure)
                sdk.setConditions(privacyAllowed = true, foreground = true)
                if (firstFinished) {
                    runCurrent()
                } else {
                    assertEquals(
                        CheckResult.Installed(target.record),
                        (sdk.prepareFirst() as FirstPreparationResult.Finished).check,
                    )
                }

                assertEquals(target.record, store.active)
                val activated = snapshots.filter { it.active == target.record }
                assertTrue("至少收到一次新 active 的成功快照", activated.isNotEmpty())
                activated.forEach { snapshot ->
                    assertTrue("新 active 必须同时确认可用", snapshot.usablePackage)
                    assertEquals("激活提交不暴露 Installing 中间状态", ManagedActivity.Idle, snapshot.activity)
                    assertEquals("成功提交同时清除历史失败", null, snapshot.latestFailure)
                    if (firstFinished) assertTrue(snapshot.initialPreparationFinished)
                }
                assertEquals(PreparationHistory(initialPreparationFinished = true), store.history)
                assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
            } finally {
                observer.cancelAndJoin()
                sdk.shutdown()
            }
        }
    }

    @Test
    fun invalidHigherCandidateAndSkipsKeepTheInstallationFailureThreshold() = runTest {
        val store = MemoryStorage()
        val failed = candidate(100002, "not a ZIP".toByteArray())
        var configuration = response(failed)
        var now = 0L
        val outcomes = mutableListOf<InstallationOutcome>()
        val diagnostics = mutableListOf<ManagedFailure>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sdk = ManagedOfflineSdk.forTest(
            root = temporary.newFolder().canonicalFile,
            storage = store,
            configProvider = ManagedConfigProvider { configuration },
            minimumVersion = 100000,
            onInstallationOutcome = { outcomes += it },
            onDiagnostic = { diagnostics += it },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            monotonicMillis = { now },
        )
        fun check(next: ConfigResponse.Success): ManagedSnapshot {
            configuration = next
            now += 300_001L
            // 重新进入前台让 SDK 自己按实际 provider 请求时间判断 TTL。
            sdk.setConditions(privacyAllowed = true, foreground = false)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            return sdk.state.value
        }
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            val failedResult = sdk.state.value.latestFailure!!
            assertEquals(ManagedStage.EXTRACT, failedResult.stage)
            assertEquals(1, outcomes.size)

            val invalidHigher = OfflineCandidate(
                PackageRecord(100004, "a".repeat(64)), PackageSource.Remote("file:///invalid.zip"),
            )
            val invalidResult = check(response(invalidHigher)).latestFailure!!
            assertEquals(ManagedFailureReason.INVALID_CONFIG, invalidResult.reason)
            assertEquals(null, invalidResult.targetVersion)
            assertEquals(listOf(invalidResult), diagnostics)
            assertEquals(1, outcomes.size)

            // 配置诊断覆盖最近失败，但既不清除旧门槛，也不把非法的更高版本纳入门槛。
            check(response(candidate(100002, zip("same repaired"))))
            check(response(candidate(100001, zip("lower"))))
            assertEquals(invalidResult, sdk.state.value.latestFailure)
            assertFalse(check(ConfigResponse.Success(OfflineConfiguration(enabled = false, candidate = invalidHigher))).enabled)
            assertTrue(store.activeWrites.isEmpty())
            assertEquals(1, outcomes.size)

            val higher = candidate(100003, zip("higher allowed"))
            assertEquals(higher.record, check(response(higher)).active)
            check(response(higher))
            assertEquals(listOf(higher.record), store.activeWrites)
            assertEquals(2, outcomes.size)
            assertEquals(InstallationOutcome.Installed(higher.record), outcomes.last())
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun finalUsabilityFailureRollsBackActiveAndReportsTheRollbackResultOnce() = runTest {
        for (rollbackSucceeds in listOf(true, false)) {
            val root = temporary.newFolder().canonicalFile
            val oldBytes = zip("previous active")
            val old = candidate(100001, oldBytes).record
            installBeforeManager(root, old, oldBytes)
            val target = candidate(100002, zip("lost after active save"))
            val store = MemoryStorage(old).apply {
                activeWriteAction = { record ->
                    if (record == target.record) {
                        // 模拟保存 active 成功后入口文件丢失，强制经过最终可用性与回滚边界。
                        assertTrue(root.resolve("100002/index.html").delete())
                        true
                    } else rollbackSucceeds
                }
            }
            val outcomes = mutableListOf<InstallationOutcome>()
            val requestedVersions = mutableListOf<Int>()
            var configuration: ConfigResponse = response(target)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sdk = ManagedOfflineSdk.forTest(
                root = root,
                storage = store,
                configProvider = ManagedConfigProvider { currentVersion ->
                    requestedVersions += currentVersion
                    configuration
                },
                minimumVersion = 100000,
                onInstallationOutcome = { outcomes += it },
                ioDispatcher = dispatcher,
                mainDispatcher = dispatcher,
                monotonicMillis = { testScheduler.currentTime },
            )
            try {
                sdk.startupDecision()
                sdk.setConditions(privacyAllowed = true, foreground = true)
                runCurrent()
                val failure = sdk.state.value.latestFailure!!

                assertEquals(ManagedFailureReason.PACKAGE_UNUSABLE, failure.reason)
                assertEquals(ManagedStage.VERIFY_ACTIVE, failure.stage)
                assertEquals(target.record.version, failure.targetVersion)
                assertEquals(!rollbackSucceeds, failure.activeRollbackFailed)
                assertEquals(listOf(target.record, old), store.activeWrites)
                assertEquals(if (rollbackSucceeds) old else target.record, store.active)
                assertEquals(old, sdk.state.value.active)
                assertTrue(sdk.state.value.usablePackage)
                assertEquals(failure, sdk.state.value.latestFailure)
                assertEquals(listOf(InstallationOutcome.Failed(failure)), outcomes)
                assertEquals("previous active", root.resolve("100001/index.html").readText())

                val base = "https://offline.example/app/"
                assertEquals("远端仍指向失败目标时新页走线上", PageDecision.Online,
                    sdk.loadPage(base, base, PageCallbacks()))
                configuration = ConfigResponse.Success(OfflineConfiguration(enabled = true, onlineVersion = old.version))
                advanceTimeBy(300_000L)
                runCurrent()
                assertEquals("普通失败后下一次配置请求仍上报旧可用 active", listOf(old.version, old.version), requestedVersions)
                assertEquals("远端恢复旧版后页面应恢复本地可用包", PageDecision.Offline(old),
                    sdk.loadPage(base, base, PageCallbacks()))
                assertEquals("普通失败仅发一次安装终态", listOf(InstallationOutcome.Failed(failure)), outcomes)
            } finally {
                sdk.shutdown()
            }
        }
    }

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
            runCurrent()

            assertEquals("文件缺失不能抹去已保存版本的防回退限制", saved, store.active)
            assertFalse("更低版本候选不能激活", store.activeWrites.contains(lower.record))
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun missingActiveFilesStillAllowSameDigestRepairOrHigherVersion() = runTest {
        for (version in listOf(100002, 100003)) {
            val root = temporary.newFolder().canonicalFile
            val bytes = zip("repair or upgrade")
            val target = candidate(version, bytes)
            val store = MemoryStorage(PackageRecord(100002, sha256(bytes)))
            val reportedVersions = mutableListOf<Int>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sdk = ManagedOfflineSdk.forTest(
                root = root,
                storage = store,
                configProvider = ManagedConfigProvider { currentVersion ->
                    reportedVersions += currentVersion
                    response(target)
                },
                minimumVersion = 100000,
                ioDispatcher = dispatcher,
                mainDispatcher = dispatcher,
                monotonicMillis = { testScheduler.currentTime },
            )
            try {
                sdk.startupDecision()
                sdk.setConditions(privacyAllowed = true, foreground = true)
                runCurrent()
                assertEquals("缺文件时对后端报告可用版本 0，同时保留本地防回退记录", listOf(0), reportedVersions)
                assertEquals(target.record, store.active)
                assertEquals("repair or upgrade", root.resolve("$version/index.html").readText())
            } finally {
                sdk.shutdown()
            }
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
        val sdk = manager(temporary.newFolder().canonicalFile, store, response(target))
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            try {
                runCurrent()
                assertTrue("SDK 静默任务必须实际到达 active 保存边界", entered.isCompleted)
                sdk.setConditions(privacyAllowed = false, foreground = true)
                runCurrent()
                release.complete(Unit)
                runCurrent()

                assertEquals("隐私撤回后的操作不能激活包", null, store.active)
                assertTrue("取消必须发生在 active 写入前", store.activeWrites.isEmpty())
            } finally {
                release.complete(Unit)
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
                assertTrue("存储挂起前应已应用远端配置事实", entered.isCompleted)
                val callbacks = PageCallbacks()
                val base = "https://offline.example/app/"
                val decision = sdk.loadPage(base, base, callbacks)

                assertEquals("已知摘要冲突必须立即使旧页面走线上", PageDecision.Online, decision)
                assertEquals(0, callbacks.offlineLoads)
            } finally {
                release.complete(Unit)
            }
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun pageLoadRechecksDisabledConfigAfterIoObservation() {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("old offline page")
        val active = candidate(100002, bytes).record
        val base = "https://offline.example/app/"
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { mainDelegate ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ioDelegate ->
                runBlocking(mainDelegate) {
                    assertTrue(PackageInstaller(root, ioDispatcher = ioDelegate)
                        .install(active) { bytes.inputStream() } is InstallResult.Success)
                    val enabledWriteEntered = CompletableDeferred<Unit>()
                    val enabledWriteRelease = CompletableDeferred<Unit>()
                    val store = MemoryStorage(active).apply {
                        this.enabledWriteEntered = enabledWriteEntered
                        this.enabledWriteRelease = enabledWriteRelease
                    }
                    val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
                    val main = object : CoroutineDispatcher() {
                        @Volatile var hold = false
                        override fun dispatch(context: CoroutineContext, block: Runnable) {
                            if (hold) synchronized(held) { held += context to block }
                            else mainDelegate.dispatch(context, block)
                        }
                        fun queued() = synchronized(held) { held.size }
                        fun releaseOthers() {
                            val others = synchronized(held) {
                                val rest = held.drop(1)
                                val first = held.first()
                                held.clear()
                                held += first
                                rest
                            }
                            others.forEach { (context, block) -> mainDelegate.dispatch(context, block) }
                        }
                        fun releaseFirst() {
                            val first = synchronized(held) { held.removeAt(0) }
                            mainDelegate.dispatch(first.first, first.second)
                        }
                        fun releaseAll() {
                            val rest = synchronized(held) { held.toList().also { held.clear() } }
                            rest.forEach { (context, block) -> mainDelegate.dispatch(context, block) }
                        }
                    }
                    val observingIo = AtomicBoolean(false)
                    val ioDispatched = CountDownLatch(1)
                    val io = object : CoroutineDispatcher() {
                        override fun dispatch(context: CoroutineContext, block: Runnable) {
                            if (observingIo.get()) ioDispatched.countDown()
                            ioDelegate.dispatch(context, block)
                        }
                    }
                    val sdk = ManagedOfflineSdk.forTest(
                        root = root, storage = store,
                        configProvider = ManagedConfigProvider { ConfigResponse.Success(OfflineConfiguration(enabled = false)) },
                        minimumVersion = 100000,
                        ioDispatcher = io, mainDispatcher = main,
                    )
                    val ioBusy = CountDownLatch(1)
                    val freeIo = CountDownLatch(1)
                    val callbacks = object : ManagedPageCallbacks {
                        var onlineLoads = 0
                        var offlineLoads = 0
                        override fun clearResourceCache() = true
                        override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) { offlineLoads++ }
                        override fun loadOnline(url: String) { onlineLoads++ }
                    }
                    var blocker: kotlinx.coroutines.Job? = null
                    var page: kotlinx.coroutines.Deferred<PageDecision>? = null
                    try {
                        sdk.startupDecision()
                        blocker = launch(ioDelegate) {
                            ioBusy.countDown()
                            check(freeIo.await(5, TimeUnit.SECONDS))
                        }
                        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { ioBusy.await(5, TimeUnit.SECONDS) })
                        observingIo.set(true)
                        page = async { sdk.loadPage(base, base, callbacks) }
                        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { ioDispatched.await(5, TimeUnit.SECONDS) })
                        main.hold = true
                        freeIo.countDown()
                        withTimeout(5_000L) { while (main.queued() == 0) kotlinx.coroutines.delay(10L) }
                        assertEquals(0, callbacks.onlineLoads + callbacks.offlineLoads)

                        sdk.setConditions(privacyAllowed = true, foreground = true)
                        withTimeout(5_000L) {
                            while (!enabledWriteEntered.isCompleted) {
                                main.releaseOthers()
                                kotlinx.coroutines.delay(10L)
                            }
                        }
                        assertFalse("配置事实须先于存储写入提交", sdk.state.value.enabled)
                        main.releaseFirst()
                        assertEquals(PageDecision.Online, withTimeout(5_000L) { checkNotNull(page).await() })
                        assertEquals("配置关闭后须真实加载线上页面", 1, callbacks.onlineLoads)
                        assertEquals("旧 IO 观察不得绑定离线目录", 0, callbacks.offlineLoads)
                    } finally {
                        freeIo.countDown()
                        enabledWriteRelease.complete(Unit)
                        main.hold = false
                        main.releaseAll()
                        page?.cancelAndJoin()
                        blocker?.cancelAndJoin()
                        sdk.shutdown()
                    }
                }
            }
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

            assertTrue("active 读取失败处于未知状态，不能授权清理其目录", entry.isFile)
            assertEquals("持久 active 记录仍可恢复", saved, store.readActive())
            assertEquals("recoverable current package", entry.readText())
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun activeReadFailureCanRecoverOnTheNextLocalPreparation() = runTest {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("read recovered")
        val saved = candidate(100002, bytes).record
        installBeforeManager(root, saved, bytes)
        val store = MemoryStorage(saved).apply { activeReadFailuresRemaining = 1 }
        val sdk = manager(root, store, ConfigResponse.Success(OfflineConfiguration(enabled = false)))
        try {
            sdk.startupDecision()
            val base = "https://offline.example/app/"
            assertEquals(
                "读取失败不得永久提交空 active，后续合法入口可以重新读取",
                PageDecision.Offline(saved),
                sdk.loadPage(base, base, PageCallbacks()),
            )
            assertEquals(saved, sdk.state.value.active)
            assertEquals("read recovered", root.resolve("100002/index.html").readText())
        } finally {
            sdk.shutdown()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun firstEntryAttemptsFailedColdCleanupOnlyOnceAndLaterEntryRecovers() = runTest {
        val root = temporary.newFolder().canonicalFile
        val residue = root.resolve("old-version")
        assertTrue(residue.mkdir())
        val store = MemoryStorage().apply { history = PreparationHistory() }
        val diagnostics = mutableListOf<ManagedFailure>()
        val requests = mutableListOf<Int>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var sdk: ManagedOfflineSdk
        sdk = ManagedOfflineSdk.forTest(
            root = root, storage = store,
            configProvider = ManagedConfigProvider { version ->
                requests += version
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            },
            minimumVersion = 100000,
            onDiagnostic = {
                diagnostics += it
                if (it.stage == ManagedStage.CLEANUP) sdk.setConditions(privacyAllowed = true, foreground = false)
            },
            ioDispatcher = dispatcher, mainDispatcher = dispatcher,
        )
        val previous = System.getSecurityManager()
        var deletionAttempts = 0
        val probe = object : SecurityManager() {
            override fun checkPermission(permission: Permission) {
                previous?.checkPermission(permission)
            }
            override fun checkDelete(file: String) {
                if (file == residue.path) {
                    deletionAttempts++
                    throw SecurityException("模拟冷清理失败")
                }
                previous?.checkDelete(file)
            }
        }
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            System.setSecurityManager(probe)
            val first = sdk.prepareFirst() as FirstPreparationResult.Finished
            assertEquals(1, deletionAttempts)
            assertEquals(ManagedFailureReason.LOCAL_PREPARATION, (first.check as CheckResult.Failed).failure.reason)
            assertTrue("事实已知时冷清理失败仍正常收尾首次准备", store.history.initialPreparationFinished)
            assertTrue("清理失败不能进入 provider", requests.isEmpty())
            assertEquals(1, diagnostics.count { it.stage == ManagedStage.CLEANUP })
            assertTrue(residue.exists())

            var onlineLoads = 0
            var offlineLoads = 0
            val callbacks = object : ManagedPageCallbacks {
                override fun clearResourceCache() = true
                override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) { offlineLoads++ }
                override fun loadOnline(url: String) { onlineLoads++ }
            }
            val pageUrl = "https://offline.example/app/"
            assertEquals(PageDecision.Online, sdk.loadPage(pageUrl, pageUrl, callbacks))
            assertEquals("清理失败必须实际加载线上页面", 1, onlineLoads)
            assertEquals("清理失败不得绑定目录", 0, offlineLoads)
            assertEquals("页面是下一合法入口，也只尝试一次清理", 2, deletionAttempts)
            assertTrue("页面重试失败仍不能进入 provider", requests.isEmpty())

            System.setSecurityManager(previous)
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            assertFalse("下一合法入口可恢复冷清理", residue.exists())
        } finally {
            System.setSecurityManager(previous)
            sdk.shutdown()
        }
    }

    @Test
    fun shutdownCancelsSdkSilentCheckBeforeActivation() = runTest {
        val target = candidate(100001, zip("shutdown must prevent activation"))
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
            try {
                runCurrent()
                assertTrue(entered.isCompleted)
                sdk.shutdown()
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertEquals(null, store.active)
                assertTrue(store.activeWrites.isEmpty())
            } finally {
                release.complete(Unit)
            }
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun privacyWithdrawalCancelsOnlyOperationAndLeavesHostSiblingActive() = runTest {
        for (firstPreparation in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = MemoryStorage().apply {
                history = PreparationHistory(initialPreparationFinished = !firstPreparation)
                activeWriteEntered = entered
                activeWriteRelease = release
            }
            val sdk = manager(
                temporary.newFolder().canonicalFile, store,
                response(candidate(100001, zip("operation child ownership"))),
            )
            val siblingRelease = CompletableDeferred<Unit>()
            val revoked = CompletableDeferred<Unit>()
            try {
                sdk.startupDecision()
                sdk.setConditions(privacyAllowed = true, foreground = true)
                val host = async(start = CoroutineStart.UNDISPATCHED) {
                    coroutineScope {
                        val sibling = async { siblingRelease.await(); "sibling completed" }
                        if (firstPreparation) {
                            var cancelled = false
                            try {
                                sdk.prepareFirst()
                            } catch (_: CancellationException) {
                                cancelled = true
                            }
                            assertTrue("首装实际任务应取消", cancelled)
                        } else {
                            // 静默检查归 SDK 所有；宿主没有可取消的常规检查调用。
                            entered.await()
                            revoked.await()
                        }
                        assertTrue("取消归属仅限实际任务，宿主调用任务仍活动", currentCoroutineContext().isActive)
                        assertTrue("宿主无关兄弟任务不得被撤回隐私取消", sibling.isActive)
                        siblingRelease.complete(Unit)
                        sibling.await()
                    }
                }
                try {
                    runCurrent()
                    assertTrue("必须在实际激活之前挂起", entered.isCompleted)
                    sdk.setConditions(privacyAllowed = false, foreground = true)
                    runCurrent()
                    revoked.complete(Unit)
                    assertEquals("sibling completed", host.await())
                    assertEquals(null, store.active)
                } finally {
                    release.complete(Unit)
                    siblingRelease.complete(Unit)
                    revoked.complete(Unit)
                    host.cancelAndJoin()
                }
            } finally {
                sdk.shutdown()
            }
        }
    }

    @Test
    fun customDownloadClientIsUsedForTheManagedInstallation() = runTest {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("injected download client")
        val record = candidate(100001, bytes).record
        var interceptedRequests = 0
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // 由注入客户端返回真实 ZIP 响应，无需联网或另外开放安装器写入权。
                interceptedRequests++
                assertEquals("https://offline.example/custom-client.zip", chain.request().url.toString())
                assertEquals(20_000, chain.connectTimeoutMillis())
                assertEquals(20_000, chain.readTimeoutMillis())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody())
                    .build()
            }.build()
        val store = MemoryStorage()
        val sdk = manager(
            root, store,
            response(OfflineCandidate(record, PackageSource.Remote("https://offline.example/custom-client.zip"))),
            downloadClient = client,
        )
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            assertEquals(1, interceptedRequests)
            assertEquals(record, store.active)
            assertEquals("injected download client", root.resolve("100001/index.html").readText())
        } finally {
            sdk.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun completedActivationMakesNewPageUseCurrentActive() = runTest {
        val root = temporary.newFolder().canonicalFile
        val oldBytes = zip("old page observation")
        val old = candidate(100001, oldBytes)
        val target = candidate(100002, zip("new activation"))
        installBeforeManager(root, old.record, oldBytes)
        val store = MemoryStorage(old.record)
        val sdk = manager(root, store, response(target))
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            assertEquals(target.record, store.active)
            val base = "https://offline.example/app/"
            assertEquals(
                "新页面须使用已激活的 active",
                PageDecision.Offline(target.record),
                sdk.loadPage(base, base, PageCallbacks()),
            )
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun sameVersionRepairInvalidatesPageObservedBeforeDirectoryReplacement() {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("same version repaired")
        val target = candidate(100002, bytes)
        val base = "https://offline.example/app/"
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { mainDelegate ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ioDelegate ->
                runBlocking(mainDelegate) {
                    assertTrue(PackageInstaller(root, ioDispatcher = ioDelegate)
                        .install(target.record) { bytes.inputStream() } is InstallResult.Success)
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val outcomes = mutableListOf<InstallationOutcome>()
                    val store = MemoryStorage(target.record).apply {
                        activeWriteEntered = entered
                        activeWriteRelease = release
                    }
                    val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
                    val main = object : CoroutineDispatcher() {
                        @Volatile var hold = false
                        override fun dispatch(context: CoroutineContext, block: Runnable) {
                            if (hold) synchronized(held) { held += context to block }
                            else mainDelegate.dispatch(context, block)
                        }
                        fun queued() = synchronized(held) { held.size }
                        fun releaseOthers() {
                            val others = synchronized(held) {
                                val rest = held.drop(1)
                                held.retainAll(held.take(1))
                                rest
                            }
                            others.forEach { (context, block) -> mainDelegate.dispatch(context, block) }
                        }
                        fun releaseFirst() {
                            val first = synchronized(held) { held.removeAt(0) }
                            mainDelegate.dispatch(first.first, first.second)
                        }
                        fun releaseAll() {
                            val rest = synchronized(held) { held.toList().also { held.clear() } }
                            rest.forEach { (context, block) -> mainDelegate.dispatch(context, block) }
                        }
                    }
                    val observingIo = AtomicBoolean(false)
                    val ioDispatched = CountDownLatch(1)
                    val io = object : CoroutineDispatcher() {
                        override fun dispatch(context: CoroutineContext, block: Runnable) {
                            if (observingIo.get()) ioDispatched.countDown()
                            ioDelegate.dispatch(context, block)
                        }
                    }
                    val sdk = ManagedOfflineSdk.forTest(
                        root = root, storage = store,
                        configProvider = ManagedConfigProvider { response(target) },
                        minimumVersion = 100000,
                        onInstallationOutcome = { outcomes += it },
                        ioDispatcher = io, mainDispatcher = main,
                    )
                    val callbacks = PageCallbacks()
                    val ioBusy = CountDownLatch(1)
                    val freeIo = CountDownLatch(1)
                    var blocker: kotlinx.coroutines.Job? = null
                    var page: kotlinx.coroutines.Deferred<PageDecision>? = null
                    try {
                        sdk.startupDecision()
                        blocker = launch(ioDelegate) {
                            ioBusy.countDown()
                            check(freeIo.await(5, TimeUnit.SECONDS))
                        }
                        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { ioBusy.await(5, TimeUnit.SECONDS) })
                        observingIo.set(true)
                        page = async { sdk.loadPage(base, base, callbacks) }
                        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { ioDispatched.await(5, TimeUnit.SECONDS) })
                        main.hold = true
                        freeIo.countDown()
                        withTimeout(5_000L) { while (main.queued() == 0) kotlinx.coroutines.delay(10L) }
                        assertEquals("IO 已观察旧目录，Main 提交尚未运行", 0, callbacks.offlineLoads)

                        // 旧目录在观察后损坏并由同版更新替换；只推进静默任务，暂留页面的 Main 恢复。
                        assertTrue(root.resolve("100002").deleteRecursively())
                        sdk.setConditions(privacyAllowed = true, foreground = true)
                        withTimeout(5_000L) {
                            while (!entered.isCompleted) {
                                main.releaseOthers()
                                kotlinx.coroutines.delay(10L)
                            }
                        }
                        main.releaseFirst()
                        assertEquals("旧 IO 观察必须被目录代际废弃", PageDecision.Online,
                            withTimeout(5_000L) { checkNotNull(page).await() })
                        assertEquals(0, callbacks.offlineLoads)
                        release.complete(Unit)
                        main.hold = false
                        main.releaseAll()
                        withTimeout(5_000L) { while (outcomes.isEmpty()) kotlinx.coroutines.delay(10L) }
                        assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
                        assertEquals(PageDecision.Offline(target.record), sdk.loadPage(base, base, PageCallbacks()))
                    } finally {
                        freeIo.countDown()
                        release.complete(Unit)
                        main.hold = false
                        main.releaseAll()
                        page?.cancelAndJoin()
                        blocker?.cancelAndJoin()
                        sdk.shutdown()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun pageLoadKeepsPackageFileReadsOffMain() = runTest {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("prepared off main")
        val saved = candidate(100001, bytes).record
        installBeforeManager(root, saved, bytes)
        val previousSecurityManager = System.getSecurityManager()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val sdk = ManagedOfflineSdk.forTest(
                root = root,
                storage = MemoryStorage(saved),
                configProvider = ManagedConfigProvider { response(candidate(100002, zip("unused"))) },
                minimumVersion = 100000,
                ioDispatcher = io,
                mainDispatcher = StandardTestDispatcher(testScheduler),
            )
            try {
                sdk.startupDecision()
                val mainThread = Thread.currentThread()
                val rootPath = root.path
                val packageReads = mutableListOf<String>()
                // JVM 17 文件探针只记录 Main 上对包根的读取，不代替设备 StrictMode 验收。
                val probe = object : SecurityManager() {
                    override fun checkPermission(permission: Permission) {
                        previousSecurityManager?.checkPermission(permission)
                    }

                    override fun checkRead(file: String) {
                        if (Thread.currentThread() === mainThread &&
                            (file == rootPath || file.startsWith(rootPath + File.separator))
                        ) packageReads += file
                        previousSecurityManager?.checkRead(file)
                    }
                }
                val base = "https://offline.example/app/"
                val decision: PageDecision
                System.setSecurityManager(probe)
                try {
                    decision = sdk.loadPage(base, base, PageCallbacks())
                } finally {
                    System.setSecurityManager(previousSecurityManager)
                }
                assertEquals(PageDecision.Offline(saved), decision)
                assertEquals("最终 Main 提交只允许复核已观察的内存事实", emptyList<String>(), packageReads)
            } finally {
                System.setSecurityManager(previousSecurityManager)
                sdk.shutdown()
            }
        }
    }

    @Test
    fun backgroundAfterInstallingStartsStillFinishesTargetPreparationAndActivation() = runTest {
        val root = temporary.newFolder().canonicalFile
        val store = MemoryStorage().apply { history = PreparationHistory() }
        var installingSeen = false
        var sourceOpenedInBackground = false
        val bytes = zip("target preparation started")
        val target = candidate(100001, bytes).copy(source = PackageSource.Local {
            sourceOpenedInBackground = installingSeen
            bytes.inputStream()
        })
        val sdk = manager(root, store, response(target))
        try {
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, sdk.startupDecision())
            // 冷清理之后才放入残留目标，覆盖“目标准备已开始、尚未清理”的后台切换边界。
            val targetDirectory = root.resolve("100001")
            assertTrue(targetDirectory.mkdir())
            targetDirectory.resolve("index.html").writeText("stale target")
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = sdk.prepareFirst(onProgress = { progress ->
                if (progress is ManagedProgress.Preparing) {
                    assertEquals("stale target", targetDirectory.resolve("index.html").readText())
                    installingSeen = true
                    sdk.setConditions(privacyAllowed = true, foreground = false)
                }
            })
            assertTrue(installingSeen)
            assertTrue(sourceOpenedInBackground)
            assertEquals(CheckResult.Installed(target.record), (first as FirstPreparationResult.Finished).check)
            assertEquals(target.record, store.active)
            assertEquals("target preparation started", targetDirectory.resolve("index.html").readText())
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
            runCurrent()

            // 证明这是观察回调自行抛出的异常，并非操作的 Job 被真实取消。
            assertTrue("宿主测试任务仍为活动状态", currentCoroutineContext().isActive)
            assertEquals("安装必须已持久化成功", target.record, store.active)
            assertEquals(1, callbackCalls)
            assertEquals("观察回调不能把已提交安装的状态改写为失败", null, sdk.state.value.latestFailure)
            assertEquals(target.record, sdk.state.value.active)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun silentChecksUseActualProviderEntryForFiveMinuteInterval() = runTest {
        val calls = mutableListOf<Long>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sdk = ManagedOfflineSdk.forTest(
            root = temporary.newFolder().canonicalFile,
            storage = MemoryStorage(),
            configProvider = ManagedConfigProvider {
                calls += testScheduler.currentTime
                ConfigResponse.Failure(ConfigFailureReason.REQUEST)
            },
            minimumVersion = 100000,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            assertEquals(listOf(0L), calls)

            // 重复生命周期事件不排队；一次失败同样占满从 provider 入口起的五分钟。
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            advanceTimeBy(299_999L)
            runCurrent()
            assertEquals(listOf(0L), calls)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(0L, 300_000L), calls)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun providerSelfCancellationDoesNotStopLaterSdkSilentChecks() = runTest {
        val target = candidate(100001, zip("after provider cancellation"))
        val calls = mutableListOf<Long>()
        val outcomes = mutableListOf<InstallationOutcome>()
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sdk = ManagedOfflineSdk.forTest(
            root = temporary.newFolder().canonicalFile,
            storage = storage,
            configProvider = ManagedConfigProvider {
                calls += testScheduler.currentTime
                if (calls.size == 1) throw CancellationException("配置适配器自行取消")
                response(target)
            },
            minimumVersion = 100000,
            onInstallationOutcome = { outcomes += it },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
        try {
            sdk.startupDecision()
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            assertEquals(listOf(0L), calls)
            assertTrue("配置请求取消没有成立安装终态", outcomes.isEmpty())
            assertEquals(null, storage.active)

            // 被取消的是一次静默任务；持续前台的 SDK 轮询仍按首次请求的 TTL 继续。
            advanceTimeBy(299_999L)
            runCurrent()
            assertEquals(listOf(0L), calls)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(0L, 300_000L), calls)
            assertEquals(target.record, storage.active)
            assertEquals(listOf(InstallationOutcome.Installed(target.record)), outcomes)
        } finally {
            sdk.shutdown()
        }
    }

    @Test
    fun suspendEntriesKeepManagedFactsOnMainAndBlockingWorkOnIo() = runBlocking {
        val mainThread = AtomicReference<Thread>()
        val ioThread = AtomicReference<Thread>()
        val main = Executors.newSingleThreadExecutor { task ->
            Thread(task, "managed-test-main").also(mainThread::set)
        }
            .asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "managed-test-io").also(ioThread::set)
        }
            .asCoroutineDispatcher()
        val bytes = zip("thread boundary")
        var zipOpenThread: Thread? = null
        val target = candidate(100001, bytes).copy(source = PackageSource.Local {
            zipOpenThread = Thread.currentThread()
            bytes.inputStream()
        })
        val storage = MemoryStorage()
        val observed = CompletableDeferred<InstallationOutcome>()
        val providerThread = AtomicReference<Thread>()
        val outcomeThread = AtomicReference<Thread>()
        val sdk = ManagedOfflineSdk.forTest(
            root = temporary.newFolder().canonicalFile,
            storage = storage,
            configProvider = ManagedConfigProvider {
                providerThread.set(Thread.currentThread())
                response(target)
            },
            minimumVersion = 100000,
            onInstallationOutcome = {
                outcomeThread.set(Thread.currentThread())
                observed.complete(it)
            },
            ioDispatcher = io,
            mainDispatcher = main,
        )
        try {
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            withContext(main) { sdk.setConditions(privacyAllowed = true, foreground = true) }
            assertEquals(
                InstallationOutcome.Installed(target.record),
                withTimeout(5_000L) { observed.await() },
            )
            assertSame(mainThread.get(), providerThread.get())
            assertSame(mainThread.get(), outcomeThread.get())
            assertSame(ioThread.get(), storage.activeReadThread)
            assertSame(ioThread.get(), storage.activeWriteThread)
            assertSame(ioThread.get(), zipOpenThread)
        } finally {
            withContext(main) { sdk.shutdown() }
            io.close()
            main.close()
        }
    }

    private fun TestScope.manager(
        root: File,
        storage: MemoryStorage,
        configuration: ConfigResponse,
        onOutcome: (InstallationOutcome) -> Unit = {},
        downloadClient: OkHttpClient = OkHttpClient(),
    ): ManagedOfflineSdk {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ManagedOfflineSdk.forTest(
            root = root,
            storage = storage,
            configProvider = ManagedConfigProvider { configuration },
            minimumVersion = 100000,
            onInstallationOutcome = onOutcome,
            downloadClient = downloadClient,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
    }

    private suspend fun TestScope.installBeforeManager(root: File, record: PackageRecord, bytes: ByteArray) {
        val result = PackageInstaller(root, ioDispatcher = StandardTestDispatcher(testScheduler))
            .install(record) { bytes.inputStream() }
        assertTrue("测试夹具必须在管理器接管目录前完成安装", result is InstallResult.Success)
    }

    private class MemoryStorage(initialActive: PackageRecord? = null) : ManagedOfflineStorage {
        var active: PackageRecord? = initialActive
        var activeReadThread: Thread? = null
        var activeWriteThread: Thread? = null
        var enabled = true
        var history = PreparationHistory(initialPreparationFinished = true)
        var cacheDirty = false
        var activeReadFailuresRemaining = 0
        var activeWriteEntered: CompletableDeferred<Unit>? = null
        var activeWriteRelease: CompletableDeferred<Unit>? = null
        var activeWriteAction: ((PackageRecord?) -> Boolean)? = null
        var enabledWriteEntered: CompletableDeferred<Unit>? = null
        var enabledWriteRelease: CompletableDeferred<Unit>? = null
        var enabledWriteSucceeds = true
        val activeWrites = mutableListOf<PackageRecord?>()

        override suspend fun readActive(): PackageRecord? {
            activeReadThread = Thread.currentThread()
            if (activeReadFailuresRemaining > 0) {
                activeReadFailuresRemaining--
                throw IOException("模拟 active 记录短暂读取失败")
            }
            return active
        }
        override suspend fun writeActive(record: PackageRecord?): Boolean {
            activeWriteThread = Thread.currentThread()
            activeWriteEntered?.complete(Unit)
            activeWriteRelease?.await()
            activeWrites += record
            val succeeds = activeWriteAction?.invoke(record) ?: true
            if (succeeds) active = record
            return succeeds
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
