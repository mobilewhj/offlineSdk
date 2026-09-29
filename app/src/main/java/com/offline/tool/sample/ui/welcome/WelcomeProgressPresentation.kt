package com.offline.tool.sample.ui.welcome

import androidx.annotation.StringRes
import com.offline.tool.ManagedProgress
import com.offline.tool.sample.R

internal data class WelcomeProgressPresentation(@StringRes val statusResource: Int, val percent: Int?)

/** 阶段百分比不等于安装完成；只有 SDK 确认激活后的 Complete 才表达整体 100%。 */
internal fun presentProgress(state: WelcomeUiState.Preparing): WelcomeProgressPresentation =
    when (val progress = state.progress) {
        // 重复调用只等待 owner 结果；没有收到自己的阶段回调时展示中性文案。
        null -> WelcomeProgressPresentation(R.string.preparing_offline, null)
        ManagedProgress.Checking -> WelcomeProgressPresentation(R.string.checking, null)
        is ManagedProgress.Preparing -> WelcomeProgressPresentation(R.string.installing, null)
        is ManagedProgress.Downloading -> WelcomeProgressPresentation(
            R.string.downloading,
            progress.totalBytes?.takeIf { it > 0L }?.let {
                (progress.downloadedBytes.toDouble() / it * 100).toInt().coerceIn(0, 99)
            },
        )
        is ManagedProgress.Extracting -> WelcomeProgressPresentation(R.string.extracting, progress.percent.coerceIn(0, 99))
        ManagedProgress.Saving -> WelcomeProgressPresentation(R.string.saving, null)
        ManagedProgress.Confirming -> WelcomeProgressPresentation(R.string.confirming, null)
        ManagedProgress.Complete -> WelcomeProgressPresentation(R.string.preparation_complete, 100)
    }
