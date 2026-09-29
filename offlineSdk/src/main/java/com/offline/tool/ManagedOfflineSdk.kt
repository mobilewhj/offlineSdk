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
import java.io.IOException
import java.net.URI

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
    private val root: File,
    private val storage: ManagedOfflineStorage,
    private val configProvider: ManagedConfigProvider,
    private val minimumVersion: Int = 10_000,
    private val onInstallationOutcome: (InstallationOutcome) -> Unit = {},
    private val onDiagnostic: (ManagedFailure) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher,
    private val checkIntervalMillis: Long = 300_000L,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val downloadClient: OkHttpClient = OkHttpClient(),
    private val downloadTimeoutMillis: Long = 120_000L,
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

    private var preparedInstaller: PackageInstaller? = null
    private val installer: PackageInstaller get() = checkNotNull(preparedInstaller)
    // 更新许可覆盖配置、安装、active 与 History 收尾；页面 IO 不等待这段长操作。
    private val operation = Mutex()
    // 协调首次读取、状态提交及初始化收尾，不覆盖长安装流程。
    private val initialization = Mutex()
    private val cacheWrite = Mutex()
    private val processScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val mutableState = MutableStateFlow(ManagedSnapshot())
    val state: StateFlow<ManagedSnapshot> = mutableState.asStateFlow()

    private var initialized = false
    private var coldCleaned = false
    private var cleanupInProgress = false
    private var history = PreparationHistory()
    private val initialFinished: Boolean get() = history.initialPreparationFinished
    private var active: PackageRecord? = null
    private var usable = false
    @Volatile private var enabled = true
    private var onlineVersion: Int? = null
    private var shaConflict = false
    private var configSeen = false
    private var cacheDirty = true
    private var cacheGeneration = 0L
    private var activeNeedsRefresh = false
    private var pagesBound = false
    private var failedVersion: Int? = null
    private var lastCheck: Long? = null
    private var privacyAllowed = false
    private var foreground = false
    private var closed = false
    private var monitorJob: Job? = null
    private var firstJob: Deferred<FirstPreparationResult>? = null
    private var silentJob: Deferred<Unit>? = null
    private var directoryGeneration = 0L
    private var activity: ManagedActivity = ManagedActivity.Idle
    private var lastInitializationFailure = localFailure(ManagedFailureReason.STORAGE_READ, ManagedStage.LOCAL)

    /** 可从任意协程上下文调用；在 Main 读取本地事实。读取失败放行在线，下次合法入口可重试。 */
    suspend fun startupDecision(): StartupDecision = withContext(mainDispatcher) {
        if (!initializeLocked()) return@withContext StartupDecision.CONTINUE
        val decision = if (!initialFinished && !usable) StartupDecision.NEEDS_FIRST_PREPARATION
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
        if (!privacyAllowed || closed) return@withContext FirstPreparationResult.PrivacyRequired
        if (!foreground) return@withContext FirstPreparationResult.NotForeground
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
                if (!initializeLocked()) return@withLock FirstPreparationResult.Finished(initializationFailure(), false)
                if (initialFinished || usable) return@withLock FirstPreparationResult.AlreadyFinished
                if (!privacyAllowed) throw CancellationException("Privacy withdrawn")
                val check = if (coldCleaned) {
                    executeCheck(first = true, onProgress)
                } else {
                    recordCheckFailure(localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.LOCAL))
                }
                checkResult = check
                // 首次将完成标记与本次诊断一次保存；取消时不提前提交首次完成事实。
                if (coldCleaned) offerCheckDiagnostic(check)
                finishFirst()
                FirstPreparationResult.Finished(check, usable)
            }
        } catch (cancelled: CancellationException) {
            activity = ManagedActivity.Idle
            publishState()
            throw cancelled
        } finally {
            checkResult?.installationOutcome()?.let(::offerOutcome)
        }
    }

    /** 必须在 Main 同步调用；后台停止后续调度，隐私撤回取消首装和 SDK 静默任务。 */
    @MainThread
    fun setConditions(privacyAllowed: Boolean, foreground: Boolean) {
        if (closed) return
        this.privacyAllowed = privacyAllowed
        this.foreground = foreground
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
                if (!initializeLocked()) return@withLock
                if (!isPollingEligible() || millisUntilNextCheck() > 0L) return@withLock
                val cleanedForCheck = coldCleaned
                val check = if (cleanedForCheck) {
                    executeCheck(first = false, onProgress = {})
                } else {
                    recordCheckFailure(localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.LOCAL))
                }
                checkResult = check
                // 常规检查只保存本次安装成功或真实失败；关闭及其他跳过不重写历史。
                if (check is CheckResult.Installed || check is CheckResult.Failed) writeHistoryBestEffort(history)
                // History 挂起期间其他入口可恢复清理；诊断仍按本次检查的清理结果决定。
                if (cleanedForCheck) offerCheckDiagnostic(check)
            }
        } catch (cancelled: CancellationException) {
            activity = ManagedActivity.Idle
            publishState()
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
        val ready = initializeLocked()
        // 清理期间开始的观察始终无效，即使 IO 返回时清理刚结束也不能使用。
        val observable = ready && coldCleaned && !cleanupInProgress
        val record = active?.takeIf { observable }
        val generation = directoryGeneration
        val directory = if (observable) withContext(ioDispatcher) {
            if (record != null && installer.isUsable(record.version)) {
                try { installer.directory(record.version).canonicalFile }
                catch (_: IOException) { null }
                catch (_: SecurityException) { null }
            } else null
        } else null
        val unchanged = observable && active == record && directoryGeneration == generation && !cleanupInProgress
        if (unchanged) {
            usable = directory != null
            publishState()
        }
        if (observable) startMonitorIfEligible()
        currentCoroutineContext().ensureActive()
        var cacheFailure: ManagedFailure? = null
        val result = run {
            val eligible = unchanged && !closed && coldCleaned && enabled && !shaConflict && record != null &&
                active == record && (onlineVersion == null || onlineVersion == record.version) &&
                directory != null &&
                OfflineUrlRules.matchesPage(url, baseUrl, allowHttpAndHttps)
            val interceptor = if (eligible) {
                OfflineInterceptor.fromPreparedDirectory(
                    checkNotNull(directory), baseUrl,
                    allowHttpAndHttps = allowHttpAndHttps,
                    onResourceFailure = onResourceFailure,
                    isEnabled = { enabled },
                )
            } else null
            if (cacheDirty) {
                val cleared = try { callbacks.clearResourceCache() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (cleared && initialized) {
                    val generation = cacheGeneration
                    processScope.launch { persistCleanCache(generation) }
                } else if (!cleared) {
                    cacheFailure = localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.CACHE)
                }
                // 初始化事实未知时只清 WebView 资源缓存；不在 Main 打开尚未就绪的存储。
            }
            currentCoroutineContext().ensureActive()
            if (interceptor != null) {
                pagesBound = true
                callbacks.loadOffline(checkNotNull(directory), interceptor, url)
                PageDecision.Offline(checkNotNull(record))
            } else {
                callbacks.loadOnline(url)
                PageDecision.Online
            }
        }
        cacheFailure?.let(::offerDiagnostic)
        result
    }

    /** 必须在 Main 同步调用；取消 SDK 首装与静默任务，不取消宿主的其他任务。根目录独占持续到进程结束。 */
    @MainThread
    fun shutdown() {
        if (closed) return
        closed = true
        firstJob?.cancel()
        silentJob?.cancel()
        processScope.coroutineContext[Job]?.cancel()
    }

    private fun initializationFailure() = CheckResult.Failed(
        lastInitializationFailure,
    )

    private suspend fun initializeLocked(attemptCleanup: Boolean = true): Boolean = initialization.withLock {
        if (closed) return@withLock false
        if (initialized) {
            if (activeNeedsRefresh && !refreshActiveAfterCancellation()) return@withLock false
            // 事实已提交但初始化收尾曾被取消时，下一合法入口仍要完成冷清理。
            if (attemptCleanup) attemptColdCleanup()
            return@withLock true
        }
        if (preparedInstaller == null) {
            val created = try {
                withContext(ioDispatcher) {
                    PackageInstaller(root, downloadClient, downloadTimeoutMillis, ioDispatcher)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 运行故障保留同一管理器；下一合法入口会重新准备，页面先走线上。
                reportInitializationFailure(ManagedFailureReason.LOCAL_PREPARATION)
                return@withLock false
            }
            currentCoroutineContext().ensureActive()
            if (closed) return@withLock false
            // Main 上没有挂起点：claim 成功后立即保存所有者，后续读取失败可复用。
            created.claimManagedRoot()
            preparedInstaller = created
        }
        val facts = when (val read = readInitialLocalFacts(
            root, storage, minimumVersion, ioDispatcher, { validRecord(it, minimumVersion) }, installer::isUsable,
        )) {
            InitialLocalRead.Failed -> {
                // 事实未知时只给出诊断；不提交空状态、不保存迁移记录、不授予清理资格。
                reportInitializationFailure(ManagedFailureReason.STORAGE_READ)
                return@withLock false
            }
            is InitialLocalRead.Ready -> read
        }
        currentCoroutineContext().ensureActive()
        if (closed) return@withLock false
        if (facts.cacheReadFailed) offerDiagnostic(localFailure(ManagedFailureReason.STORAGE_READ, ManagedStage.CACHE))
        active = facts.active
        usable = facts.usablePackage
        history = facts.history
        enabled = facts.enabled
        cacheDirty = facts.storedCacheDirty != false
        initialized = true
        activity = ManagedActivity.Idle
        publishState()
        if (facts.historyMissing && facts.history.initialPreparationFinished) writeHistoryBestEffort(facts.history)
        if (facts.storedCacheDirty == null) {
            val saved = persistCacheDirty(true)
            if (!saved) offerDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.CACHE))
        }
        // 只有完整事实已提交，管理器才可依据 active 与页面保护授予冷清理资格。
        if (attemptCleanup) attemptColdCleanup()
        true
    }

    /** 每个入口只尝试一次；清理失败已有诊断，检查入口仅负责正常失败收尾。 */
    private suspend fun attemptColdCleanup() {
        if (!closed && !coldCleaned && !ensureColdCleanup()) {
            offerDiagnostic(localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.CLEANUP))
        }
    }

    /** active 写入被取消后，先重新确认持久记录及目录；读取故障不能授权降级或清理。 */
    private suspend fun refreshActiveAfterCancellation(): Boolean {
        val stored = try { withContext(ioDispatcher) { storage.readActive() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                reportInitializationFailure(ManagedFailureReason.STORAGE_READ)
                return false
            }
        if (stored != null && !validRecord(stored, minimumVersion)) {
            reportInitializationFailure(ManagedFailureReason.STORAGE_READ)
            return false
        }
        val available = try { stored != null && installer.isUsable(stored.version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                reportInitializationFailure(ManagedFailureReason.LOCAL_PREPARATION)
                return false
            }
        currentCoroutineContext().ensureActive()
        if (closed) return false
        if (active != stored || usable != available) directoryGeneration++
        active = stored
        usable = available
        activeNeedsRefresh = false
        activity = ManagedActivity.Idle
        publishState()
        return true
    }

    /** 缓存标记的 Boolean 只代表 IO 提交确认；Main 不等待 SharedPreferences.commit。 */
    private suspend fun persistCacheDirty(dirty: Boolean): Boolean = cacheWrite.withLock {
        try { withContext(ioDispatcher) { storage.writeCacheDirty(dirty) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private suspend fun persistCleanCache(generation: Long) {
        val saved = try {
            cacheWrite.withLock {
                if (cacheGeneration != generation || !cacheDirty) return@withLock null
                val result = try { withContext(ioDispatcher) { storage.writeCacheDirty(false) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                result
            }
        } catch (_: CancellationException) { return }
        if (cacheGeneration != generation) return
        if (saved == true) cacheDirty = false
        else if (saved == false) offerDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.CACHE))
    }

    private fun reportInitializationFailure(reason: ManagedFailureReason) {
        // 本次来源同时用于状态、诊断与首装返回，下一合法入口成功后会清除失败活动。
        val failure = localFailure(reason, ManagedStage.LOCAL)
        lastInitializationFailure = failure
        activity = ManagedActivity.Failed(failure)
        publishState()
        offerDiagnostic(failure)
    }

    private suspend fun ensureColdCleanup(): Boolean {
        if (coldCleaned) return true
        if (!initialized || pagesBound || cleanupInProgress) return false
        cleanupInProgress = true
        directoryGeneration++
        val record = active?.takeIf { usable }
        var cleaned = false
        try {
            cleaned = installer.clearOldVersions(record?.version)
            return cleaned
        } finally {
            // 清理结果与授权释放一起提交，避免其他入口在两次状态更新之间再次获准删除。
            coldCleaned = cleaned
            cleanupInProgress = false
        }
    }

    private suspend fun executeCheck(
        first: Boolean,
        onProgress: (ManagedProgress) -> Unit,
    ): CheckResult {
        setActivity(ManagedActivity.Checking)
        safelyProgress(onProgress, ManagedProgress.Checking)
        val current = active
        val currentUsable = current != null && installer.isUsable(current.version)
        usable = currentUsable
        publishState()
        if (!canStartUpdate()) return interruptedCheck(first)
        // provider 是 Main-safe 挂起端口；请求起点紧邻调用，适配器自行处理阻塞 IO。
        lastCheck = monotonicMillis()
        val response = try { configProvider.fetch(if (currentUsable) checkNotNull(current).version else 0) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ConfigResponse.Failure(ConfigFailureReason.REQUEST, error.message) }
        currentCoroutineContext().ensureActive()
        if (response is ConfigResponse.Failure) {
            val reason = when (response.reason) {
                ConfigFailureReason.REQUEST -> ManagedFailureReason.CONFIG_REQUEST
                ConfigFailureReason.PARSE -> ManagedFailureReason.CONFIG_PARSE
                ConfigFailureReason.UNAVAILABLE -> ManagedFailureReason.CONFIG_UNAVAILABLE
            }
            return recordCheckFailure(localFailure(reason, ManagedStage.CONFIG))
        }
        val config = (response as ConfigResponse.Success).config
        val applied = applyConfiguration(config)
        if (applied != null) return recordCheckFailure(applied)
        // 关闭先于元数据校验；启用配置在存储挂起后仍须重新检查启动资格。
        if (config.enabled && !canStartUpdate()) return interruptedCheck(first)
        return when (val decision = decideCandidate(
            config, current, currentUsable, minimumVersion, failedVersion,
        )) {
            is CandidateDecision.Install -> installCandidate(decision.candidate, onProgress)
            is CandidateDecision.Skip -> {
                setActivity(ManagedActivity.Idle)
                decision.result
            }
            CandidateDecision.InvalidConfiguration -> recordCheckFailure(
                localFailure(ManagedFailureReason.INVALID_CONFIG, ManagedStage.CONFIG),
            )
        }
    }

    /** 同一资格丢失在首次调用中传播取消，在常规检查中只结束本次检查，不生成安装终态。 */
    private fun interruptedCheck(first: Boolean): CheckResult {
        if (first) throw CancellationException("First preparation left foreground")
        setActivity(ManagedActivity.Idle)
        return CheckResult.Backgrounded
    }

    private fun canStartUpdate(): Boolean = !closed && privacyAllowed && foreground

    /** 内存配置事实必须一起生效；存储挂起或失败都不能让页面继续绑定已观察到摘要冲突的旧包。 */
    private suspend fun applyConfiguration(config: OfflineConfiguration): ManagedFailure? {
        val validOnlineVersion = config.onlineVersion?.takeIf { it >= minimumVersion }
        val effective = !config.enabled || validOnlineVersion != null
        val changed = enabled != config.enabled || (effective &&
            (!configSeen || (config.enabled && onlineVersion != validOnlineVersion)))
        enabled = config.enabled
        shaConflict = config.enabled && hasShaConflict(active, config.candidate?.record)
        if (config.enabled && validOnlineVersion != null) onlineVersion = validOnlineVersion
        if (effective) configSeen = true
        publishState()
        val dirtyWriteFailed = if (changed) {
            cacheGeneration++
            cacheDirty = true
            !persistCacheDirty(true)
        } else false
        if (dirtyWriteFailed) offerDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.CACHE))
        val saved = try { withContext(ioDispatcher) { storage.writeEnabled(config.enabled) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        return if (saved) null else localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.SAVE_ENABLED)
    }

    private suspend fun installCandidate(
        candidate: OfflineCandidate,
        onProgress: (ManagedProgress) -> Unit,
    ): CheckResult {
        val target = candidate.record
        // 进入合法目标的准备阶段即视为安装开始；此前退后台不启动，此后普通后台允许收尾。
        val mayStart = canStartUpdate()
        // 同版修复即使整个目录已丢失，也必须作废修复前的页面观察。
        if (mayStart && active == target) directoryGeneration++
        currentCoroutineContext().ensureActive()
        if (!mayStart) return CheckResult.Backgrounded
        setActivity(ManagedActivity.Installing(target.version))
        safelyProgress(onProgress, ManagedProgress.Preparing(target.version))
        prepareTargetDirectory(target)?.let { return recordInstallationFailure(it) }
        installPackage(candidate, onProgress)?.let { return recordInstallationFailure(it) }
        val previous = active
        val saveFailure = try { saveAndConfirmActive(target, previous, onProgress) }
            catch (cancelled: CancellationException) {
                // 挂起写入可能已生效；取消后的下一次合法决策必须重读存储及文件。
                activeNeedsRefresh = true
                throw cancelled
            }
        saveFailure?.let { return recordInstallationFailure(it) }
        val result = commitInstallationSuccess(target)
        activeNeedsRefresh = false
        safelyFinishedCallback { onProgress(ManagedProgress.Complete) }
        return result
    }

    /** 只准备目标目录；Main 同步判定页面保护与删除授权，挂起清理后必须释放授权。 */
    private suspend fun prepareTargetDirectory(target: PackageRecord): ManagedFailure? {
        val exists = withContext(ioDispatcher) { installer.directory(target.version).exists() }
        if (!exists) return null
        val mayDiscard = !pagesBound
        if (mayDiscard) {
            cleanupInProgress = true
            directoryGeneration++
        }
        if (!mayDiscard) return targetFailure(ManagedFailureReason.TARGET_IN_USE, ManagedStage.PREPARE, target)
        val discarded = try { installer.discardUnboundVersion(target.version) }
            finally { cleanupInProgress = false }
        return if (discarded) null
        else targetFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.PREPARE, target)
    }

    /** 下载、校验、解压及发布仍由安装器负责；此处仅适配来源、进度和失败类型，取消直接传播。 */
    private suspend fun installPackage(
        candidate: OfflineCandidate,
        onProgress: (ManagedProgress) -> Unit,
    ): ManagedFailure? {
        val target = candidate.record
        val installed = try {
            when (val source = candidate.source) {
                is PackageSource.Remote -> installer.install(
                    target, source.url,
                    onDownloadProgress = { bytes, total -> safelyProgress(onProgress, ManagedProgress.Downloading(bytes, total)) },
                    onExtractProgress = { percent -> safelyProgress(onProgress, ManagedProgress.Extracting(percent)) },
                )
                is PackageSource.Local -> installer.install(
                    target,
                    onExtractProgress = { percent -> safelyProgress(onProgress, ManagedProgress.Extracting(percent)) },
                    openZip = {
                        try { source.openZip() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { throw IOException("Cannot open local package", error) }
                    },
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                return targetFailure(ManagedFailureReason.INSTALL, ManagedStage.PREPARE, target)
            }
        return when (installed) {
            is InstallResult.Success -> null
            is InstallResult.Failure -> {
                val stage = when (installed.stage) {
                    InstallStage.INSTALL, InstallStage.PREPARE -> ManagedStage.PREPARE
                    InstallStage.DOWNLOAD -> ManagedStage.DOWNLOAD
                    InstallStage.VERIFY -> ManagedStage.VERIFY
                    InstallStage.EXTRACT -> ManagedStage.EXTRACT
                    InstallStage.PUBLISH -> ManagedStage.PUBLISH
                    InstallStage.CLEANUP -> ManagedStage.CLEANUP
                }
                targetFailure(
                    ManagedFailureReason.INSTALL, stage, target,
                    installReason = installed.reason, httpStatus = installed.httpStatus,
                )
            }
        }
    }

    /** 在 IO 保存 active 并复核入口，复核失败时尝试恢复传入的旧记录；不提交管理器内存状态。
     * 安装已经开始，普通退后台允许完成保存；调用方或隐私取消仍由挂起边界传播。 */
    private suspend fun saveAndConfirmActive(
        target: PackageRecord,
        previous: PackageRecord?,
        onProgress: (ManagedProgress) -> Unit,
    ): ManagedFailure? {
        if (!installer.isUsable(target.version)) {
            return targetFailure(ManagedFailureReason.PACKAGE_UNUSABLE, ManagedStage.VERIFY_ACTIVE, target)
        }
        safelyProgress(onProgress, ManagedProgress.Saving)
        currentCoroutineContext().ensureActive()
        val saved = try { withContext(ioDispatcher) { storage.writeActive(target) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        if (!saved) return targetFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.SAVE_ACTIVE, target)
        safelyProgress(onProgress, ManagedProgress.Confirming)
        if (!installer.isUsable(target.version)) {
            val rolledBack = try { withContext(ioDispatcher) { storage.writeActive(previous) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            return targetFailure(ManagedFailureReason.PACKAGE_UNUSABLE, ManagedStage.VERIFY_ACTIVE, target)
                .copy(activeRollbackFailed = !rolledBack)
        }
        return null
    }

    /** 激活已经保存且确认可用，完整成功快照只发布一次；随后 History 保存取消不能撤销此终态。 */
    private fun commitInstallationSuccess(target: PackageRecord): CheckResult.Installed {
        active = target
        usable = true
        history = history.copy(latestFailure = null)
        activity = ManagedActivity.Idle
        publishState()
        return CheckResult.Installed(target)
    }

    /** 检查诊断没有安装目标，不提升失败版本门槛，也不生成安装终态。 */
    private fun recordCheckFailure(failure: ManagedFailure): CheckResult.Failed {
        require(failure.targetVersion == null)
        return recordFailure(failure)
    }

    private fun recordInstallationFailure(failure: ManagedFailure): CheckResult.Failed {
        failedVersion = maxOf(failedVersion ?: Int.MIN_VALUE, checkNotNull(failure.targetVersion))
        return recordFailure(failure)
    }

    /** 两类失败共用 Main 内存提交；持久化只由各入口的收尾负责。 */
    private fun recordFailure(failure: ManagedFailure): CheckResult.Failed {
        history = history.copy(latestFailure = failure)
        activity = ManagedActivity.Failed(failure)
        publishState()
        return CheckResult.Failed(failure)
    }

    private fun offerCheckDiagnostic(check: CheckResult) {
        val failure = (check as? CheckResult.Failed)?.failure ?: return
        if (failure.targetVersion == null) offerDiagnostic(failure)
    }

    private suspend fun finishFirst() {
        currentCoroutineContext().ensureActive()
        val completed = history.copy(initialPreparationFinished = true)
        val saved = writeHistory(completed)
        history = completed
        publishState()
        if (!saved) offerDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.HISTORY))
    }

    private suspend fun writeHistoryBestEffort(value: PreparationHistory) {
        if (!writeHistory(value)) offerDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.HISTORY))
    }

    private suspend fun writeHistory(value: PreparationHistory): Boolean =
        try { withContext(ioDispatcher) { storage.writeHistory(value) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }

    private fun setActivity(value: ManagedActivity) {
        activity = value
        publishState()
    }

    private fun CheckResult.installationOutcome(): InstallationOutcome? = when (this) {
        is CheckResult.Installed -> InstallationOutcome.Installed(record)
        is CheckResult.Failed -> if (failure.targetVersion != null) InstallationOutcome.Failed(failure) else null
        else -> null
    }

    private fun safelyProgress(callback: (ManagedProgress) -> Unit, value: ManagedProgress) {
        try { callback(value) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 界面观察异常不改变安装事实。 */ }
    }

    private fun safelyFinishedCallback(block: () -> Unit) {
        try { block() } catch (_: Exception) { /* 已提交结果不能被界面回调撤销。 */ }
    }

    private fun publishState() {
        mutableState.value = ManagedSnapshot(active, usable, enabled, initialFinished, activity, history.latestFailure)
    }

    private fun offerOutcome(value: InstallationOutcome) {
        // 同步接收器抛出的取消异常属于宿主报告，不代表更新任务被取消；真实取消仍由挂起边界传播。
        try { onInstallationOutcome(value) }
        catch (_: Exception) { /* 报告任务在安装许可之外，任何接收异常都不能改写既成终态。 */ }
    }

    private fun offerDiagnostic(value: ManagedFailure) {
        if (!privacyAllowed) return
        try { onDiagnostic(value) } catch (_: Exception) { /* 尽力通知，不递归上报诊断故障。 */ }
    }

    /** 轮询始终使用同一资格；等待后重新查询，调度任务不拥有在途静默安装。 */
    private fun isPollingEligible(): Boolean =
        !closed && initialized && (initialFinished || (active != null && usable)) && privacyAllowed && foreground

    private fun startMonitorIfEligible() {
        if (closed || !privacyAllowed || !foreground || monitorJob?.isActive == true) return
        monitorJob = processScope.launch {
            try {
                if (!initializeLocked(attemptCleanup = false) || !isPollingEligible()) return@launch
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
                        // 本轮没有发出请求时才让出一个周期；长请求结束后按其真实起点重算 TTL。
                        delay(checkIntervalMillis)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                offerDiagnostic(localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.LOCAL))
            } finally {
                if (monitorJob === currentCoroutineContext()[Job]) {
                    monitorJob = null
                }
            }
        }
    }

    private fun millisUntilNextCheck(): Long {
        val last = lastCheck ?: return 0L
        return (checkIntervalMillis - (monotonicMillis() - last)).coerceAtLeast(0L)
    }

    private fun localFailure(reason: ManagedFailureReason, stage: ManagedStage) = ManagedFailure(reason, stage)

    private fun targetFailure(
        reason: ManagedFailureReason,
        stage: ManagedStage,
        target: PackageRecord,
        installReason: FailureReason? = null,
        httpStatus: Int? = null,
    ) = ManagedFailure(reason, stage, target.version, target.sha256, installReason, httpStatus)

    private sealed interface CandidateDecision {
        data class Install(val candidate: OfflineCandidate) : CandidateDecision
        data class Skip(val result: CheckResult) : CandidateDecision
        data object InvalidConfiguration : CandidateDecision
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

        /** 只解释本次传入的事实，不读运行状态、不执行 IO，也不生成含时钟事实的失败记录。 */
        private fun decideCandidate(
            config: OfflineConfiguration,
            current: PackageRecord?,
            currentUsable: Boolean,
            minimumVersion: Int,
            failedVersion: Int?,
        ): CandidateDecision {
            if (!config.enabled) return CandidateDecision.Skip(CheckResult.Disabled)
            val version = config.onlineVersion
            if (version == null || version < minimumVersion) return CandidateDecision.InvalidConfiguration
            val candidate = config.candidate
            if (candidate == null) {
                return if (current != null && currentUsable && current.version >= version) {
                    CandidateDecision.Skip(CheckResult.UpToDate)
                } else CandidateDecision.InvalidConfiguration
            }
            val target = candidate.record
            if (!validRecord(target, minimumVersion) || target.version != version || !validSource(candidate.source) ||
                hasShaConflict(current, target)) return CandidateDecision.InvalidConfiguration
            // active 记录承担防降级；文件可用性只决定同版是否需要修复。
            if (current != null && (current.version > target.version || (currentUsable && current.version == target.version))) {
                return CandidateDecision.Skip(CheckResult.UpToDate)
            }
            if (failedVersion != null && target.version <= failedVersion) {
                return CandidateDecision.Skip(CheckResult.BlockedVersion)
            }
            return CandidateDecision.Install(candidate)
        }

        private fun hasShaConflict(current: PackageRecord?, candidate: PackageRecord?): Boolean =
            current != null && candidate != null && current.version == candidate.version && current.sha256 != candidate.sha256

        private fun validRecord(record: PackageRecord, minimumVersion: Int): Boolean =
            record.version >= minimumVersion && SHA256.matches(record.sha256)

        private fun validSource(source: PackageSource): Boolean = when (source) {
            is PackageSource.Local -> true
            is PackageSource.Remote -> try {
                val uri = URI(source.url)
                (uri.scheme == "http" || uri.scheme == "https") && uri.host != null && uri.userInfo == null
            } catch (_: Exception) { false }
        }

        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
