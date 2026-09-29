package com.offline.tool.sample.ui.welcome

import androidx.lifecycle.viewModelScope
import com.offline.tool.ConfigFailureReason
import com.offline.tool.ConfigResponse
import com.offline.tool.ManagedConfigProvider
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.PackageRecord
import com.offline.tool.PreparationHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun directlyInjectedManagerUsesCurrentPrivacyConditions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var requests = 0
        val storage = MemoryStorage(PreparationHistory(), CompletableDeferred(Unit))
        var canonicalCalls = 0
        val root = object : File(temporary.newFolder().path) {
            override fun getCanonicalPath(): String {
                canonicalCalls++
                return super.getCanonicalPath()
            }
        }
        val manager = ManagedOfflineSdk(
            root,
            storage,
            ManagedConfigProvider {
                requests++
                ConfigResponse.Failure(ConfigFailureReason.UNAVAILABLE)
            },
        )
        assertEquals(0, canonicalCalls)
        val viewModel = WelcomeViewModel(manager)
        try {
            manager.setConditions(privacyAllowed = false, foreground = true)
            viewModel.prepare()
            awaitState(viewModel) { it == WelcomeUiState.Failed(WelcomeFailure.PRIVACY_REQUIRED) }
            assertEquals(0, requests)
        } finally {
            viewModel.viewModelScope.cancel()
            manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun directlyInjectedManagerRespectsBackgroundWithoutHostPublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var requests = 0
        val storage = MemoryStorage(PreparationHistory(), CompletableDeferred(Unit))
        val manager = ManagedOfflineSdk(
            temporary.newFolder(),
            storage,
            ManagedConfigProvider {
                requests++
                ConfigResponse.Failure(ConfigFailureReason.UNAVAILABLE)
            },
        )
        val viewModel = WelcomeViewModel(manager)
        try {
            manager.setConditions(privacyAllowed = true, foreground = false)
            viewModel.prepare()
            awaitState(viewModel) { storage.historyReads > 0 && it == WelcomeUiState.Idle }
            assertEquals(0, requests)
        } finally {
            viewModel.viewModelScope.cancel()
            manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun suspendedLaterStartupStaysNeutralAndRepeatedEntryDoesNotStartAnotherCall() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val historyRead = CompletableDeferred<Unit>()
        val storage = MemoryStorage(PreparationHistory(initialPreparationFinished = true), historyRead)
        val manager = ManagedOfflineSdk(
            temporary.newFolder(), storage,
            ManagedConfigProvider { error("后次启动判定不应自行请求配置") },
        )
        val viewModel = WelcomeViewModel(manager)
        try {
            viewModel.prepare()
            runCurrent()
            viewModel.prepare()
            awaitCondition { storage.historyReads == 1 }
            assertEquals(WelcomeUiState.Idle, viewModel.uiState.value)

            historyRead.complete(Unit)
            awaitState(viewModel) { it == WelcomeUiState.Ready }
        } finally {
            viewModel.viewModelScope.cancel()
            manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun firstStartupShowsProgressOnlyAfterDecisionAndPrivacyCancellationOffersRecovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val historyRead = CompletableDeferred<Unit>()
        val configuration = CompletableDeferred<ConfigResponse>()
        val storage = MemoryStorage(PreparationHistory(), historyRead)
        val manager = ManagedOfflineSdk(
            temporary.newFolder(), storage,
            ManagedConfigProvider { configuration.await() },
        )
        val viewModel = WelcomeViewModel(manager)
        try {
            manager.setConditions(privacyAllowed = true, foreground = true)
            viewModel.prepare()
            awaitCondition { storage.historyReads == 1 }
            assertEquals(WelcomeUiState.Idle, viewModel.uiState.value)

            historyRead.complete(Unit)
            awaitState(viewModel) { it is WelcomeUiState.Preparing }

            manager.setConditions(privacyAllowed = false, foreground = true)
            awaitState(viewModel) { it == WelcomeUiState.Interrupted }
        } finally {
            viewModel.viewModelScope.cancel()
            manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun survivingWaiterDoesNotRestartAfterOwnerCancellationUntilExplicitRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val configuration = CompletableDeferred<ConfigResponse>()
        var requests = 0
        val manager = ManagedOfflineSdk(
            temporary.newFolder(),
            MemoryStorage(PreparationHistory(), CompletableDeferred(Unit)),
            ManagedConfigProvider {
                requests++
                configuration.await()
            },
        )
        val owner = WelcomeViewModel(manager)
        val waiter = WelcomeViewModel(manager)
        try {
            manager.setConditions(privacyAllowed = true, foreground = true)
            owner.prepare()
            awaitState(owner) { it is WelcomeUiState.Preparing && requests == 1 }

            waiter.prepare()
            awaitState(waiter) { it is WelcomeUiState.Preparing }
            assertEquals(1, requests)

            owner.viewModelScope.cancel()
            awaitState(waiter) { it == WelcomeUiState.Interrupted }
            waiter.prepare()
            runCurrent()
            assertEquals(1, requests)
            assertEquals(WelcomeUiState.Interrupted, waiter.uiState.value)

            configuration.complete(ConfigResponse.Failure(ConfigFailureReason.UNAVAILABLE))
            waiter.retry()
            awaitState(waiter) { it == WelcomeUiState.Ready && requests == 2 }
        } finally {
            owner.viewModelScope.cancel()
            waiter.viewModelScope.cancel()
            manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun missingPrivacyUsesTypedReasonInsteadOfExceptionMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val storage = MemoryStorage(PreparationHistory(), CompletableDeferred(Unit))
        val manager = ManagedOfflineSdk(
            temporary.newFolder(), storage,
            ManagedConfigProvider { ConfigResponse.Failure(ConfigFailureReason.UNAVAILABLE) },
        )
        val viewModel = WelcomeViewModel(manager)
        try {
            viewModel.prepare()
            awaitState(viewModel) { it == WelcomeUiState.Failed(WelcomeFailure.PRIVACY_REQUIRED) }
        } finally {
            viewModel.viewModelScope.cancel()
            manager.shutdown()
            repeat(20) { runCurrent(); Thread.sleep(5) }
            Dispatchers.resetMain()
        }
    }

    private class MemoryStorage(
        private var history: PreparationHistory,
        private val beforeHistoryRead: CompletableDeferred<Unit>,
    ) : ManagedOfflineStorage {
        var historyReads = 0
        override suspend fun readActive(): PackageRecord? = null
        override suspend fun writeActive(record: PackageRecord?) = true
        override suspend fun readEnabled() = true
        override suspend fun writeEnabled(enabled: Boolean) = true
        override suspend fun readHistory(): PreparationHistory {
            historyReads++
            beforeHistoryRead.await()
            return history
        }
        override suspend fun writeHistory(history: PreparationHistory): Boolean {
            this.history = history
            return true
        }
        override fun readCacheDirty() = false
        override suspend fun writeCacheDirty(dirty: Boolean) = true
    }

    private fun TestScope.awaitCondition(matches: () -> Boolean) {
        repeat(300) {
            runCurrent()
            if (matches()) return
            Thread.sleep(10)
        }
        assertTrue("未到达预期条件", matches())
    }

    /** 公共 API 的初始化在真实 IO 上运行，推动测试 Main 直到目标状态出现。 */
    private fun TestScope.awaitState(viewModel: WelcomeViewModel, matches: (WelcomeUiState) -> Boolean) {
        repeat(300) {
            runCurrent()
            if (matches(viewModel.uiState.value)) return
            Thread.sleep(10)
        }
        assertTrue("未到达预期状态，实际=${viewModel.uiState.value}", matches(viewModel.uiState.value))
    }
}
