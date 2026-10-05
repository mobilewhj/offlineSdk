package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** Manager 唯一写入的运行条件；Session 只在原有检查点读取最新值。 */
internal data class RunConditions(
    val privacyAllowed: Boolean = false,
    val foreground: Boolean = false,
    val closed: Boolean = false,
)

/** 一次本地准备的结果；失败原因不借助可变字段转交。 */
internal sealed interface LocalPreparation {
    data class Ready(val needsFirstPreparation: Boolean, val cleanupComplete: Boolean) : LocalPreparation
    data class Failed(val failure: ManagedFailure) : LocalPreparation
    data object Closed : LocalPreparation
}

/** 固定根目录的包事实所有者；任务生命周期及更新许可仍由管理器负责。 */
internal class ManagedPackageSession(
    private val root: File,
    private val storage: ManagedOfflineStorage,
    private val configProvider: ManagedConfigProvider,
    private val minimumVersion: Int,
    private val ioDispatcher: CoroutineDispatcher,
    private val downloadClient: OkHttpClient,
    private val downloadTimeoutMillis: Long,
    processScope: CoroutineScope,
    private val conditions: StateFlow<RunConditions>,
    private val onDiagnostic: (ManagedFailure) -> Unit,
) {
    private var preparedInstaller: PackageInstaller? = null
    private val installer: PackageInstaller get() = checkNotNull(preparedInstaller)
    private val initialization = Mutex()
    private val mutableState = MutableStateFlow(ManagedSnapshot())
    val state: StateFlow<ManagedSnapshot> = mutableState.asStateFlow()

    private val cache = ResourceCacheState(ioDispatcher, storage::writeCacheDirty, processScope) {
        onDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.CACHE))
    }
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
    private var activeNeedsRefresh = false
    private var pagesBound = false
    private var failedVersion: Int? = null
    private var directoryGeneration = 0L
    private var activity: ManagedActivity = ManagedActivity.Idle

    /** 初始化、未知写入后的重读和冷清理在同一把锁内完成；Closed 不生成存储故障。 */
    suspend fun prepareLocal(attemptCleanup: Boolean = true): LocalPreparation = initialization.withLock {
        if (conditions.value.closed) return@withLock LocalPreparation.Closed
        if (initialized) {
            if (activeNeedsRefresh) {
                refreshActiveAfterCancellation()?.let { return@withLock it }
            }
            if (attemptCleanup) attemptColdCleanup()
            return@withLock ready()
        }
        if (preparedInstaller == null) {
            val created = try {
                withContext(ioDispatcher) {
                    // 首次 IO 在安装器创建/清理前拒绝目录重叠；普通目录 IO 故障沿原准备失败路径。
                    (storage as? InstallationRootBoundStorage)?.validateInstallationRoot(root)
                    PackageInstaller(root, downloadClient, downloadTimeoutMillis, ioDispatcher)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                throw invalid
            } catch (_: Exception) {
                return@withLock reportInitializationFailure(ManagedFailureReason.LOCAL_PREPARATION)
            }
            currentCoroutineContext().ensureActive()
            if (conditions.value.closed) return@withLock LocalPreparation.Closed
            // Main 上没有挂起点：根目录认领后立即保存安装器，后续读取失败仍可复用。
            created.claimManagedRoot()
            preparedInstaller = created
        }
        val facts = when (val read = readInitialLocalFacts(
            root, storage, minimumVersion, ioDispatcher, { validRecord(it, minimumVersion) }, installer::isUsable,
        )) {
            InitialLocalRead.Failed -> {
                // 事实未知时不提交空快照，也不取得清理资格。
                return@withLock reportInitializationFailure(ManagedFailureReason.STORAGE_READ)
            }
            is InitialLocalRead.Ready -> read
        }
        currentCoroutineContext().ensureActive()
        if (conditions.value.closed) return@withLock LocalPreparation.Closed
        if (facts.cacheReadFailed) onDiagnostic(localFailure(ManagedFailureReason.STORAGE_READ, ManagedStage.CACHE))
        active = facts.active
        usable = facts.usablePackage
        history = facts.history
        enabled = facts.enabled
        cache.restore(facts.storedCacheDirty)
        initialized = true
        activity = ManagedActivity.Idle
        publishState()
        if (facts.historyMissing && facts.history.initialPreparationFinished) writeHistoryBestEffort(facts.history)
        if (facts.storedCacheDirty == null) cache.persistInitialDirty()
        // 完整事实先提交；必要的迁移、缓存与清理收尾完成后才交付 Ready。
        if (attemptCleanup) attemptColdCleanup()
        ready()
    }

    private fun ready() = LocalPreparation.Ready(!initialFinished && !usable, coldCleaned)

    /** 每个入口只尝试一次冷清理；失败已有诊断，检查入口仍正常结束本次结果。 */
    private suspend fun attemptColdCleanup() {
        if (!conditions.value.closed && !coldCleaned && !ensureColdCleanup()) {
            onDiagnostic(localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.CLEANUP))
        }
    }

    /** 取消后的 active 写入未知；重读失败保留旧快照，由本次 Failed 阻断检查。 */
    private suspend fun refreshActiveAfterCancellation(): LocalPreparation? {
        val stored = try { withContext(ioDispatcher) { storage.readActive() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return reportInitializationFailure(ManagedFailureReason.STORAGE_READ) }
        if (stored != null && !validRecord(stored, minimumVersion)) {
            return reportInitializationFailure(ManagedFailureReason.STORAGE_READ)
        }
        val available = try { stored != null && installer.isUsable(stored.version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return reportInitializationFailure(ManagedFailureReason.LOCAL_PREPARATION) }
        currentCoroutineContext().ensureActive()
        if (conditions.value.closed) return LocalPreparation.Closed
        if (active != stored || usable != available) directoryGeneration++
        active = stored
        usable = available
        activeNeedsRefresh = false
        activity = ManagedActivity.Idle
        publishState()
        return null
    }

    private fun reportInitializationFailure(reason: ManagedFailureReason): LocalPreparation.Failed {
        val failure = localFailure(reason, ManagedStage.LOCAL)
        activity = ManagedActivity.Failed(failure)
        publishState()
        onDiagnostic(failure)
        return LocalPreparation.Failed(failure)
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
            // 清理结果与授权释放一起提交，防止并发入口在中途再次取得删除许可。
            coldCleaned = cleaned
            cleanupInProgress = false
        }
    }

    /** Manager 在 Main 且持有 operation 时调用；首次与静默共用包业务顺序，History 和终态仍由 Manager 收尾。 */
    suspend fun checkOnce(first: Boolean, onProgress: (ManagedProgress) -> Unit): CheckResult {
        if (!coldCleaned) return recordCheckFailure(localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.LOCAL))
        setActivity(ManagedActivity.Checking)
        safelyProgress(onProgress, ManagedProgress.Checking)
        val current = active
        val currentUsable = current != null && installer.isUsable(current.version)
        usable = currentUsable
        publishState()
        if (!canStartUpdate()) return interruptedCheck(first)
        // 包装后的原配置端口在真正调用 fetch 的紧邻位置记录请求时钟。
        val response = try { configProvider.fetch(if (currentUsable) checkNotNull(current).version else 0) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ConfigResponse.Failure(ConfigFailureReason.REQUEST, "exception") }
        currentCoroutineContext().ensureActive()
        if (response is ConfigResponse.Failure) {
            val reason = when (response.reason) {
                ConfigFailureReason.REQUEST -> ManagedFailureReason.CONFIG_REQUEST
                ConfigFailureReason.PARSE -> ManagedFailureReason.CONFIG_PARSE
                ConfigFailureReason.UNAVAILABLE -> ManagedFailureReason.CONFIG_UNAVAILABLE
            }
            return recordCheckFailure(localFailure(reason, ManagedStage.CONFIG, providerFailureDetail(response.detail)))
        }
        val config = (response as ConfigResponse.Success).config
        val applied = applyConfiguration(config)
        if (applied != null) return recordCheckFailure(applied)
        if (config.enabled && !canStartUpdate()) return interruptedCheck(first)
        return when (val decision = decideCandidate(
            config, current, currentUsable, minimumVersion, failedVersion,
        )) {
            is CandidateDecision.Install -> installCandidate(decision.candidate, onProgress)
            is CandidateDecision.Skip -> {
                setActivity(ManagedActivity.Idle)
                decision.result
            }
            is CandidateDecision.InvalidConfiguration -> recordCheckFailure(
                localFailure(ManagedFailureReason.INVALID_CONFIG, ManagedStage.CONFIG, decision.reason.detail),
            )
        }
    }

    /** 首次失去许可传播取消；常规检查仅跳过，不产生安装终态。 */
    private fun interruptedCheck(first: Boolean): CheckResult {
        if (first) throw CancellationException("First preparation left foreground")
        setActivity(ManagedActivity.Idle)
        return CheckResult.Backgrounded
    }

    private fun canStartUpdate(): Boolean {
        val current = conditions.value
        return !current.closed && current.privacyAllowed && current.foreground
    }

    /** 配置内存事实先一起发布，缓存置脏与开关持久写入随后按原顺序执行。 */
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
        if (changed) cache.invalidate()
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
        // 合法目标准备开始后允许普通后台收尾；同版修复先作废旧页面观察。
        val mayStart = canStartUpdate()
        if (mayStart && active == target) directoryGeneration++
        currentCoroutineContext().ensureActive()
        if (!mayStart) return CheckResult.Backgrounded
        setActivity(ManagedActivity.Installing(target.version))
        safelyProgress(onProgress, ManagedProgress.Preparing(target.version))
        prepareTargetDirectory(target)?.let { return recordInstallationFailure(it) }
        installManagedPackage(installer, candidate, onProgress)?.let { return recordInstallationFailure(it) }
        val previous = active
        val saveFailure = try {
            saveAndConfirmActive(installer, storage, target, previous, ioDispatcher, onProgress)
        } catch (cancelled: CancellationException) {
            // 只在 active 保存或确认的取消路径重读；下载取消没有未知写入。
            activeNeedsRefresh = true
            throw cancelled
        }
        saveFailure?.let { return recordInstallationFailure(it) }
        val result = commitInstallationSuccess(target)
        activeNeedsRefresh = false
        safelyFinishedCallback { onProgress(ManagedProgress.Complete) }
        return result
    }

    /** 目录存在检查之后才同步读取页面绑定事实并授予删除权限。 */
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

    /** 已保存并确认可用后，只发布一次完整成功快照。 */
    private fun commitInstallationSuccess(target: PackageRecord): CheckResult.Installed {
        active = target
        usable = true
        history = history.copy(latestFailure = null)
        activity = ManagedActivity.Idle
        publishState()
        return CheckResult.Installed(target)
    }

    private fun recordCheckFailure(failure: ManagedFailure): CheckResult.Failed {
        require(failure.targetVersion == null)
        return recordFailure(failure)
    }

    private fun recordInstallationFailure(failure: ManagedFailure): CheckResult.Failed {
        failedVersion = maxOf(failedVersion ?: Int.MIN_VALUE, checkNotNull(failure.targetVersion))
        return recordFailure(failure)
    }

    private fun recordFailure(failure: ManagedFailure): CheckResult.Failed {
        history = history.copy(latestFailure = failure)
        activity = ManagedActivity.Failed(failure)
        publishState()
        return CheckResult.Failed(failure)
    }

    /** 首次正常结束才提交完成事实；History 挂起取消不伪完成。 */
    suspend fun finishFirst() {
        currentCoroutineContext().ensureActive()
        val completed = history.copy(initialPreparationFinished = true)
        val saved = writeHistory(completed)
        history = completed
        publishState()
        if (!saved) onDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.HISTORY))
    }

    /** 常规检查只持久化已安装或真实失败，不覆盖跳过结论。 */
    suspend fun finishRegular(check: CheckResult) {
        if (check is CheckResult.Installed || check is CheckResult.Failed) writeHistoryBestEffort(history)
    }

    private suspend fun writeHistoryBestEffort(value: PreparationHistory) {
        if (!writeHistory(value)) onDiagnostic(localFailure(ManagedFailureReason.STORAGE_WRITE, ManagedStage.HISTORY))
    }

    private suspend fun writeHistory(value: PreparationHistory): Boolean =
        try { withContext(ioDispatcher) { storage.writeHistory(value) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }

    /** IO 观察后在 Main 复核最新包、配置与代际，清资源缓存并加载原 URL。 */
    suspend fun loadPage(
        url: String,
        baseUrl: String,
        callbacks: ManagedPageCallbacks,
        allowHttpAndHttps: Boolean,
        onResourceFailure: ((String, String) -> Unit)?,
        onObservationReady: () -> Unit,
    ): PageDecision {
        val local = prepareLocal()
        // 清理期间开始的观察始终无效，即使 IO 返回时清理刚结束也不能使用。
        val observable = local is LocalPreparation.Ready && local.cleanupComplete && !cleanupInProgress
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
        if (observable) onObservationReady()
        // 通知可同步启动检查并改变配置；最终页面资格必须在通知之后就地复核。
        currentCoroutineContext().ensureActive()
        var cacheFailure: ManagedFailure? = null
        val result = run {
            val eligible = unchanged && !conditions.value.closed && coldCleaned && enabled && !shaConflict && record != null &&
                active == record && (onlineVersion == null || onlineVersion == record.version) &&
                directory != null && OfflineUrlRules.matchesPage(url, baseUrl, allowHttpAndHttps)
            val interceptor = if (eligible) {
                OfflineInterceptor.fromPreparedDirectory(
                    checkNotNull(directory), baseUrl,
                    allowHttpAndHttps = allowHttpAndHttps,
                    onResourceFailure = onResourceFailure,
                    isEnabled = { enabled },
                )
            } else null
            if (!cache.clearBeforeLoad(initialized, callbacks::clearResourceCache)) {
                cacheFailure = localFailure(ManagedFailureReason.LOCAL_PREPARATION, ManagedStage.CACHE)
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
        // 清理失败仍先加载；诊断只在页面结果成立后发送。
        cacheFailure?.let(onDiagnostic)
        return result
    }

    /** 只复位活动显示；取消不改包、History 或失败版本门槛。 */
    fun operationCancelled() {
        activity = ManagedActivity.Idle
        publishState()
    }

    private fun setActivity(value: ManagedActivity) {
        activity = value
        publishState()
    }

    private fun publishState() {
        mutableState.value = ManagedSnapshot(active, usable, enabled, initialFinished, activity, history.latestFailure)
    }

    /** 不接受任意文本；未知内容只说明被省略，异常、URL 或 Token 不进入诊断。 */
    private fun providerFailureDetail(detail: String?): String? = when (detail) {
        null -> null
        "timeout" -> "provider_timeout"
        "network" -> "provider_network"
        "http" -> "provider_http"
        "empty_response" -> "provider_empty_response"
        "response_decode" -> "provider_response_decode"
        "exception" -> "provider_exception"
        else -> "provider_detail_withheld"
    }

    private fun localFailure(reason: ManagedFailureReason, stage: ManagedStage, detail: String? = null) =
        ManagedFailure(reason, stage, detail = detail)
}
