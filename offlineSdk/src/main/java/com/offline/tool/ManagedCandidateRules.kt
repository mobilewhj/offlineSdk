package com.offline.tool

import java.net.URI

internal sealed interface CandidateDecision {
    data class Install(val candidate: OfflineCandidate) : CandidateDecision
    data class Skip(val result: CheckResult) : CandidateDecision
    data class InvalidConfiguration(val reason: InvalidConfigReason) : CandidateDecision
}

/** 内部有限原因只提供排障提示，不作为业务决定或持久化协议。 */
internal enum class InvalidConfigReason(val detail: String) {
    ONLINE_VERSION_MISSING("online_version_missing"),
    ONLINE_VERSION_BELOW_MINIMUM("online_version_below_minimum"),
    CANDIDATE_MISSING("candidate_missing"),
    CANDIDATE_VERSION_BELOW_MINIMUM("candidate_version_below_minimum"),
    SHA256_FORMAT("sha256_format"),
    CANDIDATE_VERSION_MISMATCH("candidate_version_mismatch"),
    SOURCE_URL_INVALID("source_url_invalid"),
    SAME_VERSION_SHA256_CONFLICT("same_version_sha256_conflict"),
}

/** 只解释本次传入的事实；关闭、格式、降级、同版修复与失败门槛依原顺序判断。 */
internal fun decideCandidate(
    config: OfflineConfiguration,
    current: PackageRecord?,
    currentUsable: Boolean,
    minimumVersion: Int,
    failedVersion: Int?,
): CandidateDecision {
    if (!config.enabled) return CandidateDecision.Skip(CheckResult.Disabled)
    val version = config.onlineVersion
    if (version == null) return invalidConfiguration(InvalidConfigReason.ONLINE_VERSION_MISSING)
    if (version < minimumVersion) return invalidConfiguration(InvalidConfigReason.ONLINE_VERSION_BELOW_MINIMUM)
    val candidate = config.candidate
    if (candidate == null) {
        return if (current != null && currentUsable && current.version >= version) {
            CandidateDecision.Skip(CheckResult.UpToDate)
        } else invalidConfiguration(InvalidConfigReason.CANDIDATE_MISSING)
    }
    val target = candidate.record
    if (target.version < minimumVersion) return invalidConfiguration(InvalidConfigReason.CANDIDATE_VERSION_BELOW_MINIMUM)
    if (!SHA256.matches(target.sha256)) return invalidConfiguration(InvalidConfigReason.SHA256_FORMAT)
    if (target.version != version) return invalidConfiguration(InvalidConfigReason.CANDIDATE_VERSION_MISMATCH)
    if (!validSource(candidate.source)) return invalidConfiguration(InvalidConfigReason.SOURCE_URL_INVALID)
    if (hasShaConflict(current, target)) return invalidConfiguration(InvalidConfigReason.SAME_VERSION_SHA256_CONFLICT)
    // active 记录承担防降级；文件可用性只决定同版是否需要修复。
    if (current != null && (current.version > target.version || (currentUsable && current.version == target.version))) {
        return CandidateDecision.Skip(CheckResult.UpToDate)
    }
    if (failedVersion != null && target.version <= failedVersion) {
        return CandidateDecision.Skip(CheckResult.BlockedVersion)
    }
    return CandidateDecision.Install(candidate)
}

private fun invalidConfiguration(reason: InvalidConfigReason) = CandidateDecision.InvalidConfiguration(reason)

internal fun hasShaConflict(current: PackageRecord?, candidate: PackageRecord?): Boolean =
    current != null && candidate != null && current.version == candidate.version && current.sha256 != candidate.sha256

internal fun validRecord(record: PackageRecord, minimumVersion: Int): Boolean =
    record.version >= minimumVersion && SHA256.matches(record.sha256)

private fun validSource(source: PackageSource): Boolean = when (source) {
    is PackageSource.Local -> true
    is PackageSource.Remote -> try {
        val uri = URI(source.url)
        (uri.scheme == "http" || uri.scheme == "https") && uri.host != null && uri.userInfo == null
    } catch (_: Exception) { false }
}

private val SHA256 = Regex("[0-9a-f]{64}")
