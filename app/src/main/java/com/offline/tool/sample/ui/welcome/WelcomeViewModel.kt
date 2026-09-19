package com.offline.tool.sample.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.tool.sample.offline.DemoOfflinePackages
import com.offline.tool.sample.offline.InstallOfflinePackageUseCase
import com.offline.tool.sample.offline.LocalPreparationProgress
import com.offline.tool.sample.offline.OfflineInstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface WelcomeUiState {
    data object Idle : WelcomeUiState
    data class Preparing(val progress: LocalPreparationProgress) : WelcomeUiState
    data object Ready : WelcomeUiState
    data class Failed(val reason: String) : WelcomeUiState
}

internal class WelcomeViewModel(
    private val repository: WelcomeRepository,
    private val packages: DemoOfflinePackages,
    private val installPackage: InstallOfflinePackageUseCase,
    private val processScope: CoroutineScope,
    private val reportFailure: (OfflineInstallResult.Failure) -> Unit,
) : ViewModel() {
    private val _uiState = MutableStateFlow<WelcomeUiState>(WelcomeUiState.Idle)
    val uiState = _uiState.asStateFlow()

    init {
        prepare()
    }

    fun prepare() {
        if (_uiState.value != WelcomeUiState.Idle && _uiState.value !is WelcomeUiState.Failed) return
        _uiState.value = WelcomeUiState.Preparing(LocalPreparationProgress(LocalPreparationProgress.Stage.CHECKING))
        viewModelScope.launch {
            try {
                // 先准备目录，再允许页面绑定；静默更新不清理已交付页面的目录。
                when (val prepared = packages.prepareLocal()) {
                    is OfflineInstallResult.Failure -> {
                        if (packages.hasUsablePackage()) {
                            reportFailure(prepared)
                            _uiState.value = WelcomeUiState.Ready
                        } else fail(prepared)
                        return@launch
                    }

                    is OfflineInstallResult.Success -> Unit
                }
                if (packages.hasUsablePackage()) {
                    _uiState.value = WelcomeUiState.Ready
                    // 独立于 Welcome 的销毁，已有页面继续使用之前取得的版本。
                    processScope.launch {
                        val result = updatePackage()
                        if (result is OfflineInstallResult.Failure) reportFailure(result)
                    }
                } else {
                    when (val result = updatePackage { _uiState.value = WelcomeUiState.Preparing(it) }) {
                        is OfflineInstallResult.Failure -> fail(result)
                        is OfflineInstallResult.Success -> {
                            if (packages.hasUsablePackage()) _uiState.value = WelcomeUiState.Ready
                            else fail(OfflineInstallResult.Failure("LOCAL_PACKAGE_UNAVAILABLE"))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(OfflineInstallResult.Failure("LOCAL_PREPARATION_FAILED", cause = error))
            }
        }
    }

    private suspend fun updatePackage(
        onProgress: (LocalPreparationProgress) -> Unit = {},
    ): OfflineInstallResult {
        val candidate = try {
            repository.getOfflinePackage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return OfflineInstallResult.Failure("CONFIG_REQUEST_FAILED", "config", error)
        }
        return try {
            val local = packages.current()
            when (val plan = planUpdate(local, candidate.record, local != null && packages.isUsable(local))) {
                OfflineUpdatePlan.Keep -> OfflineInstallResult.Success(local)
                OfflineUpdatePlan.Install -> installPackage(candidate, onProgress)
                is OfflineUpdatePlan.Reject -> OfflineInstallResult.Failure(plan.reason, "config")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OfflineInstallResult.Failure("INSTALLATION_FAILED", cause = error)
        }
    }

    private fun fail(failure: OfflineInstallResult.Failure) {
        reportFailure(failure)
        _uiState.value = WelcomeUiState.Failed(failure.reason)
    }
}
