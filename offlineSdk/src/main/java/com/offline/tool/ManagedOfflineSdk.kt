package com.offline.tool

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * 主进程中固定环境、固定包目录的唯一管理入口，宿主不能再通过其他入口修改此目录。
 *
 * 构造只保存端口和参数，可在 Main 创建；首次合法入口在 IO 准备安装器并规范化根目录。
 * 之后挂起入口从任意协程上下文切回 Main 管理状态，
 * 同步条件与关闭必须在 Main 调用；页面入口在 Main 完成最终绑定与加载。
 * 首装由首个调用者的子任务拥有，重复调用只等待同一次结果；静默更新由 SDK 进程任务拥有。
 * 普通退后台只停止后续调度，隐私撤回取消两类更新，不取消宿主无关任务。
 * 文件、网络与主要存储工作在 IO；底层下载/解压进度可从 IO 回调，其他结果与诊断从 Main 通知。
 */
class ManagedOfflineSdk private constructor(
    root: File,
    storage: ManagedOfflineStorage,
    configProvider: ManagedConfigProvider,
    minimumVersion: Int = 10_000,
    private val onInstallationOutcome: (InstallationOutcome) -> Unit = {},
    private val onDiagnostic: (ManagedFailure) -> Unit = {},
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher,
    private val checkIntervalMillis: Long = 300_000L,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    downloadClient: OkHttpClient = OkHttpClient(),
    downloadTimeoutMillis: Long = 120_000L,
) {
    /** Main 可轻量构造；路径、记录和安装器在首次挂起入口由 SDK 准备。 */
    constructor(
        root: File,
        storage: ManagedOfflineStorage,
        configProvider: ManagedConfigProvider,
        minimumVersion: Int = 10_000,
        onInstallationOutcome: (InstallationOutcome) -> Unit = {},
        onDiagnostic: (ManagedFailure) -> Unit = {},
        downloadClient: OkHttpClient = OkHttpClient(),
    ) : this(
        root, storage, configProvider, minimumVersion, onInstallationOutcome, onDiagnostic,
        Dispatchers.IO, Dispatchers.Main.immediate, 300_000L,
        { System.nanoTime() / 1_000_000L }, downloadClient, 120_000L,
    )

    init {
        require(minimumVersion >= 10_000)
        require(checkIntervalMillis > 0)
        require(downloadTimeoutMillis > 0)
    }

    // 更新许可覆盖配置、安装、active 与 History 收尾；页面 IO 不等待长操作。
    private val operation = Mutex()
    private val processScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val conditions = MutableStateFlow(RunConditions())
    private var lastCheck: Long? = null
    private val session = ManagedPackageSession(
        root, storage,
        ManagedConfigProvider { currentVersion ->
            // 仅实际 provider 调用写时钟，首次及失败请求也计入五分钟间隔。
            lastCheck = monotonicMillis()
            configProvider.fetch(currentVersion)
        },
        minimumVersion, ioDispatcher, downloadClient, downloadTimeoutMillis,
        processScope, conditions.asStateFlow(), ::offerDiagnostic,
    )
    val state: StateFlow<ManagedSnapshot> = session.state

    private var monitorJob: Job? = null
    private var firstJob: Deferred<FirstPreparationResult>? = null
    private var silentJob: Deferred<Unit>? = null

    /**
     * 结束本次离线启动等待，不代表安装成功或业务可以导航。
     * 已完成分支直接放行，不新增隐私/前台门，也不等待静默更新。
     * 真首装复用原调用者所有权、共享进度和终态；临时条件返回 Deferred，取消原样传播。
     */
    suspend fun prepareStartup(
        onProgress: (ManagedProgress) -> Unit = {},
    ): StartupResult = when (startupDecision()) {
        StartupDecision.CONTINUE -> StartupResult.Continue
        StartupDecision.NEEDS_FIRST_PREPARATION -> when (val result = prepareFirst(onProgress)) {
            is FirstPreparationResult.Finished -> if (result.check == CheckResult.Backgrounded) {
                StartupResult.Deferred
            } else StartupResult.Continue
            FirstPreparationResult.AlreadyFinished -> StartupResult.Continue
            FirstPreparationResult.PrivacyRequired,
            FirstPreparationResult.NotForeground -> StartupResult.Deferred
        }
    }

    /** 可从任意协程上下文调用；在 Main 读取本地事实。读取失败放行在线，下次合法入口可重试。 */
    suspend fun startupDecision(): StartupDecision = withContext(mainDispatcher) {
        val local = session.prepareLocal()
        if (local !is LocalPreparation.Ready) return@withContext StartupDecision.CONTINUE
        val decision = if (local.needsFirstPreparation) StartupDecision.NEEDS_FIRST_PREPARATION
        else StartupDecision.CONTINUE
        startMonitorIfEligible()
        decision
    }

    /**
     * 首个调用者的子任务拥有首装；并发调用只等待同一次结果，不接管进度或重发终态。
     * 等待者取消仅结束自身等待；owner、隐私或关闭取消真实首装并向所有等待者传播。
     */
    suspend fun prepareFirst(
        onProgress: (ManagedProgress) -> Unit = {},
    ): FirstPreparationResult = withContext(mainDispatcher) {
        firstJob?.let { return@withContext it.await() }
        val current = conditions.value
        if (!current.privacyAllowed || current.closed) return@withContext FirstPreparationResult.PrivacyRequired
        if (!current.foreground) return@withContext FirstPreparationResult.NotForeground
        var owner: Deferred<FirstPreparationResult>? = null
        try {
            coroutineScope {
                // 懒启动保证共享 handle 先发布；子任务仍归首个调用方所有。
                val task = async(start = CoroutineStart.LAZY) { runFirstPreparation(onProgress) }
                owner = task
                firstJob = task
                task.start()
                task.await()
            }
        } finally {
            // coroutineScope 已等待 owner 的取消收尾；此时才允许新首装取得唯一 handle。
            if (firstJob === owner) firstJob = null
            startMonitorIfEligible()
        }
    }

    private suspend fun runFirstPreparation(onProgress: (ManagedProgress) -> Unit): FirstPreparationResult {
        var checkResult: CheckResult? = null
        try {
            return operation.withLock {
                when (val local = session.prepareLocal()) {
                    is LocalPreparation.Failed -> FirstPreparationResult.Finished(CheckResult.Failed(local.failure), false)
                    LocalPreparation.Closed -> throw CancellationException("SDK closed")
                    is LocalPreparation.Ready -> {
                        if (!local.needsFirstPreparation) return@withLock FirstPreparationResult.AlreadyFinished
                        if (!conditions.value.privacyAllowed) throw CancellationException("Privacy withdrawn")
                        val check = session.checkOnce(first = true, onProgress)
                        checkResult = check
                        // 已成立安装结果先保存；History 挂起取消也须在锁外报告一次。
                        if (local.cleanupComplete) offerCheckDiagnostic(check)
                        session.finishFirst()
                        FirstPreparationResult.Finished(check, state.value.usablePackage)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            session.operationCancelled()
            throw cancelled
        } finally {
            checkResult?.installationOutcome()?.let(::offerOutcome)
        }
    }

    /** 必须在 Main 同步调用；后台停止后续调度，隐私撤回取消首装和 SDK 静默任务。 */
    @MainThread
    fun setConditions(privacyAllowed: Boolean, foreground: Boolean) {
        if (conditions.value.closed) return
        conditions.value = RunConditions(privacyAllowed, foreground)
        if (!privacyAllowed || !foreground) {
            monitorJob?.cancel()
            monitorJob = null
        }
        if (!privacyAllowed) {
            firstJob?.cancel()
            silentJob?.cancel()
        }
        startMonitorIfEligible()
    }

    /** SDK 自有的一次静默更新；宿主不持有检查 Job，也不通过结果返回驱动再次尝试。 */
    private suspend fun runSilentCheck() {
        var checkResult: CheckResult? = null
        try {
            operation.withLock {
                if (!isPollingEligible()) return@withLock
                val local = session.prepareLocal()
                if (local !is LocalPreparation.Ready) return@withLock
                if (!isPollingEligible() || millisUntilNextCheck() > 0L) return@withLock
                val check = session.checkOnce(first = false, onProgress = {})
                checkResult = check
                // History 挂起期间其他入口可恢复清理；诊断仍按本次准备结果决定。
                session.finishRegular(check)
                if (local.cleanupComplete) offerCheckDiagnostic(check)
            }
        } catch (cancelled: CancellationException) {
            session.operationCancelled()
            throw cancelled
        } finally {
            checkResult?.installationOutcome()?.let(::offerOutcome)
        }
    }

    /**
     * 在 IO 观察目录，回 Main 无挂起地复核状态、保护目录并加载原 URL；不等待长安装许可。
     * 资源缺失按拦截器规则回源，缓存标记由 SDK 稍后在 IO 确认持久结果。
     */
    suspend fun loadPage(
        url: String,
        baseUrl: String,
        callbacks: ManagedPageCallbacks,
        allowHttpAndHttps: Boolean = false,
        onResourceFailure: ((String, String) -> Unit)? = null,
    ): PageDecision = withContext(mainDispatcher) {
        session.loadPage(
            url, baseUrl, callbacks, allowHttpAndHttps, onResourceFailure,
            onObservationReady = ::startMonitorIfEligible,
        )
    }

    /** 必须在 Main 同步调用；取消 SDK 首装与静默任务，不取消宿主其他任务。根目录独占持续到进程结束。 */
    @MainThread
    fun shutdown() {
        if (conditions.value.closed) return
        conditions.value = conditions.value.copy(closed = true)
        firstJob?.cancel()
        silentJob?.cancel()
        processScope.coroutineContext[Job]?.cancel()
    }

    private fun CheckResult.installationOutcome(): InstallationOutcome? = when (this) {
        is CheckResult.Installed -> InstallationOutcome.Installed(record)
        is CheckResult.Failed -> if (failure.targetVersion != null) InstallationOutcome.Failed(failure) else null
        else -> null
    }

    private fun offerCheckDiagnostic(check: CheckResult) {
        val failure = (check as? CheckResult.Failed)?.failure ?: return
        if (failure.targetVersion == null) offerDiagnostic(failure)
    }

    private fun offerOutcome(value: InstallationOutcome) {
        // 同步接收器抛出的取消异常属于宿主报告，不代表更新任务被取消。
        try { onInstallationOutcome(value) }
        catch (_: Exception) { /* 报告故障不能改写既成终态。 */ }
    }

    private fun offerDiagnostic(value: ManagedFailure) {
        if (!conditions.value.privacyAllowed) return
        try { onDiagnostic(value) } catch (_: Exception) { /* 尽力通知，不递归上报诊断故障。 */ }
    }

    /** 轮询始终使用同一资格；等待后重新查询，调度任务不拥有在途静默安装。 */
    private fun isPollingEligible(): Boolean {
        val current = conditions.value
        val snapshot = state.value
        return !current.closed && current.privacyAllowed && current.foreground &&
            (snapshot.initialPreparationFinished || (snapshot.active != null && snapshot.usablePackage))
    }

    private fun startMonitorIfEligible() {
        val current = conditions.value
        if (current.closed || !current.privacyAllowed || !current.foreground || monitorJob?.isActive == true) return
        monitorJob = processScope.launch {
            try {
                if (session.prepareLocal(attemptCleanup = false) !is LocalPreparation.Ready || !isPollingEligible()) return@launch
                while (isPollingEligible()) {
                    val wait = millisUntilNextCheck()
                    if (wait > 0) delay(wait)
                    if (!isPollingEligible()) break
                    val running = silentJob
                    if (running != null && !running.isCompleted) {
                        running.join()
                        continue
                    }
                    val priorRequest = lastCheck
                    // 静默任务是进程作用域中的兄弟任务；普通后台只取消轮询等待。
                    val task = processScope.async(start = CoroutineStart.LAZY) {
                        try { runSilentCheck() }
                        finally { if (silentJob === currentCoroutineContext()[Job]) silentJob = null }
                    }
                    silentJob = task
                    task.start()
                    try { task.await() }
                        catch (_: CancellationException) {
                            // 静默任务自身取消不取消其兄弟轮询；轮询被停时仍传播真实取消。
                            currentCoroutineContext().ensureActive()
                        }
                    if (lastCheck == priorRequest && millisUntilNextCheck() == 0L) {
                        // 本轮没有发出请求时才让出一个周期；长请求结束后按真实起点重算 TTL。
                        delay(checkIntervalMillis)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                offerDiagnostic(ManagedFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.LOCAL))
            } finally {
                if (monitorJob === currentCoroutineContext()[Job]) monitorJob = null
            }
        }
    }

    private fun millisUntilNextCheck(): Long {
        val last = lastCheck ?: return 0L
        return (checkIntervalMillis - (monotonicMillis() - last)).coerceAtLeast(0L)
    }

    companion object {
        /** SDK 模块内测试可控制线程与时间；宿主只能使用七项参数的公开构造。 */
        @JvmSynthetic
        internal fun forTest(
            root: File,
            storage: ManagedOfflineStorage,
            configProvider: ManagedConfigProvider,
            minimumVersion: Int = 10_000,
            onInstallationOutcome: (InstallationOutcome) -> Unit = {},
            onDiagnostic: (ManagedFailure) -> Unit = {},
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            mainDispatcher: CoroutineDispatcher,
            checkIntervalMillis: Long = 300_000L,
            monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
            downloadClient: OkHttpClient = OkHttpClient(),
            downloadTimeoutMillis: Long = 120_000L,
        ) = ManagedOfflineSdk(
            root, storage, configProvider, minimumVersion, onInstallationOutcome, onDiagnostic,
            ioDispatcher, mainDispatcher, checkIntervalMillis, monotonicMillis,
            downloadClient, downloadTimeoutMillis,
        )
    }
}
