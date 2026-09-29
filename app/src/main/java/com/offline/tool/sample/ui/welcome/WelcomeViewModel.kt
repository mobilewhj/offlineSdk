package com.offline.tool.sample.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.tool.FirstPreparationResult
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedProgress
import com.offline.tool.StartupDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal sealed interface WelcomeUiState {
    data object Idle : WelcomeUiState
    data class Preparing(val progress: ManagedProgress? = null) : WelcomeUiState
    data object Interrupted : WelcomeUiState
    data object Ready : WelcomeUiState
    data class Failed(val reason: WelcomeFailure) : WelcomeUiState
}

internal enum class WelcomeFailure { PRIVACY_REQUIRED, LOCAL_PREPARATION }

/** 页面只拥有本次首次调用；SDK 的后续检查不依赖这个 ViewModel 的生命周期。 */
internal class WelcomeViewModel(private val manager: ManagedOfflineSdk) : ViewModel() {
    private val _uiState = MutableStateFlow<WelcomeUiState>(WelcomeUiState.Idle)
    val uiState = _uiState.asStateFlow()
    private var preparationJob: Job? = null

    fun prepare() {
        if (preparationJob?.isActive == true || _uiState.value != WelcomeUiState.Idle) return
        // 本地判定可能挂起，期间保持中性页面；只有确认为首次才展示首装进度。
        _uiState.value = WelcomeUiState.Idle
        preparationJob = viewModelScope.launch {
            try {
                when (manager.startupDecision()) {
                    StartupDecision.CONTINUE -> _uiState.value = WelcomeUiState.Ready
                    StartupDecision.NEEDS_FIRST_PREPARATION -> {
                        _uiState.value = WelcomeUiState.Preparing()
                        when (manager.prepareFirst(
                            onProgress = { progress ->
                                // 下载和解压进度可从 IO 回调；只更新线程安全的 UI 状态。
                                _uiState.update { state ->
                                    (state as? WelcomeUiState.Preparing)?.copy(progress = progress) ?: state
                                }
                            },
                        )) {
                            is FirstPreparationResult.Finished,
                            FirstPreparationResult.AlreadyFinished -> _uiState.value = WelcomeUiState.Ready
                            FirstPreparationResult.PrivacyRequired -> {
                                _uiState.value = WelcomeUiState.Failed(WelcomeFailure.PRIVACY_REQUIRED)
                            }
                            FirstPreparationResult.NotForeground -> _uiState.value = WelcomeUiState.Idle
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // 自己被销毁时不留 UI；仍存活的等待者退出加载，交由用户显式重试。
                _uiState.value = if (coroutineContext.isActive) {
                    WelcomeUiState.Interrupted
                } else {
                    WelcomeUiState.Idle
                }
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = WelcomeUiState.Failed(WelcomeFailure.LOCAL_PREPARATION)
            }
        }
    }

    /** 仅由页面操作重入正常启动入口，不接管已取消的首装尝试。 */
    fun retry() {
        if (_uiState.value != WelcomeUiState.Interrupted && _uiState.value !is WelcomeUiState.Failed) return
        viewModelScope.launch {
            // 等旧调用完成取消收尾；点击一次只开启下一次正常入口。
            preparationJob?.join()
            if (_uiState.value == WelcomeUiState.Interrupted || _uiState.value is WelcomeUiState.Failed) {
                _uiState.value = WelcomeUiState.Idle
                prepare()
            }
        }
    }
}
