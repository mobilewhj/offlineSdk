package com.offline.tool

import java.io.File
import java.io.InputStream

/** 宿主将既有配置响应映射为这些事实，不承担安装资格判断。挂起实现须可从 Main 安全调用。 */
fun interface ManagedConfigProvider {
    /** 在 Main 开始调用；阻塞工作由适配器自行切 IO。[currentVersion] 为入口文件可用的 active 版本，否则为 0；请求须支持取消。 */
    suspend fun fetch(currentVersion: Int): ConfigResponse
}

sealed interface ConfigResponse {
    data class Success(val config: OfflineConfiguration) : ConfigResponse
    data class Failure(val reason: ConfigFailureReason, val detail: String? = null) : ConfigResponse
}

enum class ConfigFailureReason { REQUEST, PARSE, UNAVAILABLE }

/** 关闭响应不要求提供版本、URL 或摘要；SDK 优先应用关闭事实。 */
data class OfflineConfiguration(
    val enabled: Boolean,
    val onlineVersion: Int? = null,
    val candidate: OfflineCandidate? = null,
)

data class OfflineCandidate(val record: PackageRecord, val source: PackageSource)

sealed interface PackageSource {
    data class Remote(val url: String) : PackageSource

    /** 用于 APK 内置初始包；工厂在 IO 调用，返回的流由 SDK 关闭。 */
    class Local(val openZip: () -> InputStream) : PackageSource
}

/** 宿主负责此单值小记录的编码和环境隔离；最近失败仅作诊断，不恢复进程失败门槛。 */
data class PreparationHistory(
    val initialPreparationFinished: Boolean = false,
    val latestFailure: ManagedFailure? = null,
)

/** 只提供可靠识别的旧记录证据；旧拒绝策略不会迁移。 */
data class LegacyPreparationEvidence(val latestFailure: ManagedFailure? = null)

/** 挂起存储方法由 SDK 在 IO 调度器调用。缺记录返回 null，读取故障必须抛出异常；
 * 普通写入失败返回 false，取消必须传播。active 读取故障会保留目录并在下一合法入口重读。 */
interface ManagedOfflineStorage {
    suspend fun readActive(): PackageRecord?
    suspend fun writeActive(record: PackageRecord?): Boolean
    suspend fun readEnabled(): Boolean?
    suspend fun writeEnabled(enabled: Boolean): Boolean
    suspend fun readHistory(): PreparationHistory?
    suspend fun writeHistory(history: PreparationHistory): Boolean

    /** 缓存读取由 SDK 在 IO 调用；缺记录返回 null，读取故障抛出异常。 */
    fun readCacheDirty(): Boolean?
    /** 缓存写入由 SDK 在 IO 调用；仅持久提交确认后返回 true，失败返回 false，取消传播。 */
    suspend fun writeCacheDirty(dirty: Boolean): Boolean

    /** 只返回可靠识别的旧失败或拒绝事实；没有证据返回 null，读取故障抛出异常。 */
    suspend fun readLegacyEvidence(): LegacyPreparationEvidence? = null
}

enum class ManagedFailureReason {
    CONFIG_REQUEST,
    CONFIG_PARSE,
    CONFIG_UNAVAILABLE,
    INVALID_CONFIG,
    LOCAL_PREPARATION,
    TARGET_IN_USE,
    STORAGE_READ,
    STORAGE_WRITE,
    INSTALL,
    PACKAGE_UNUSABLE,
}

enum class ManagedStage {
    LOCAL,
    CONFIG,
    PREPARE,
    DOWNLOAD,
    VERIFY,
    EXTRACT,
    PUBLISH,
    CLEANUP,
    SAVE_ACTIVE,
    VERIFY_ACTIVE,
    SAVE_ENABLED,
    HISTORY,
    CACHE,
}

/** 稳定诊断数据；不持久化异常对象、签名 URL、重试次数或拒绝策略。 */
data class ManagedFailure(
    val reason: ManagedFailureReason,
    val stage: ManagedStage,
    val targetVersion: Int? = null,
    val targetSha256: String? = null,
    val installReason: FailureReason? = null,
    val httpStatus: Int? = null,
    val detail: String? = null,
    val occurredAtMillis: Long = System.currentTimeMillis(),
    /** 保存 active 后最终可用性失败，且恢复旧 active 也失败。 */
    val activeRollbackFailed: Boolean = false,
)

/** 每次合法候选正常到达终态只通知一次。跳过或取消不伪造终态，宿主回调异常不改写既成结果。 */
sealed interface InstallationOutcome {
    data class Installed(val record: PackageRecord) : InstallationOutcome
    data class Failed(val failure: ManagedFailure) : InstallationOutcome
}

sealed interface CheckResult {
    data class Installed(val record: PackageRecord) : CheckResult
    data class Failed(val failure: ManagedFailure) : CheckResult
    data object Disabled : CheckResult
    data object UpToDate : CheckResult
    data object BlockedVersion : CheckResult
    data object Backgrounded : CheckResult
}

enum class StartupDecision { NEEDS_FIRST_PREPARATION, CONTINUE }

sealed interface FirstPreparationResult {
    data class Finished(val check: CheckResult, val usablePackage: Boolean) : FirstPreparationResult
    data object AlreadyFinished : FirstPreparationResult
    data object PrivacyRequired : FirstPreparationResult
    data object NotForeground : FirstPreparationResult
}

sealed interface ManagedActivity {
    data object Idle : ManagedActivity
    data object Checking : ManagedActivity
    data class Installing(val version: Int) : ManagedActivity
    data class Failed(val failure: ManagedFailure) : ManagedActivity
}

sealed interface ManagedProgress {
    data object Checking : ManagedProgress
    data class Preparing(val version: Int) : ManagedProgress
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : ManagedProgress
    data class Extracting(val percent: Int) : ManagedProgress
    data object Saving : ManagedProgress
    data object Confirming : ManagedProgress
    data object Complete : ManagedProgress
}

data class ManagedSnapshot(
    val active: PackageRecord? = null,
    val usablePackage: Boolean = false,
    val enabled: Boolean = true,
    val initialPreparationFinished: Boolean = false,
    val activity: ManagedActivity = ManagedActivity.Idle,
    val latestFailure: ManagedFailure? = null,
)

sealed interface PageDecision {
    data class Offline(val record: PackageRecord) : PageDecision
    data object Online : PageDecision
}

/** 在 Main 同步调用；loadOffline 绑定拦截器并加载原 URL。clearResourceCache 必须实际清除
 * 内存和磁盘资源缓存后才返回 true，保留 Cookie、localStorage 与业务数据。 */
interface ManagedPageCallbacks {
    fun clearResourceCache(): Boolean
    fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String)
    fun loadOnline(url: String)
}
