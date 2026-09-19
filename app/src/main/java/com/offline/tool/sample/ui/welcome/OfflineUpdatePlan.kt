package com.offline.tool.sample.ui.welcome

import com.offline.tool.PackageRecord

internal sealed interface OfflineUpdatePlan {
    data object Keep : OfflineUpdatePlan
    data object Install : OfflineUpdatePlan
    data class Reject(val reason: String) : OfflineUpdatePlan
}

/** 版本决策属于 Welcome；相同版本不得替换不同内容，缺失文件可以修复。 */
internal fun planUpdate(local: PackageRecord?, candidate: PackageRecord, localUsable: Boolean): OfflineUpdatePlan =
    when {
        candidate.version < 10_000 -> OfflineUpdatePlan.Reject("INVALID_VERSION")
        !Regex("[0-9a-f]{64}").matches(candidate.sha256) -> OfflineUpdatePlan.Reject("INVALID_SHA256")
        local != null && candidate.version < local.version ->
            if (localUsable) OfflineUpdatePlan.Keep else OfflineUpdatePlan.Reject("LOCAL_VERSION_UNAVAILABLE")

        local != null && candidate.version == local.version && candidate.sha256 != local.sha256 ->
            OfflineUpdatePlan.Reject("VERSION_CONTENT_CONFLICT")

        local != null && candidate.version == local.version && localUsable -> OfflineUpdatePlan.Keep
        else -> OfflineUpdatePlan.Install
    }
