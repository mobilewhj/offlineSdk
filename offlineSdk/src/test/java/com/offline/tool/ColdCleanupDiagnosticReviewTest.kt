package com.offline.tool

import java.io.File
import java.security.Permission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 独立复审探针：仅通过存储挂起及实际删除故障控制时序，不读取管理器私有状态。 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class ColdCleanupDiagnosticReviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun pageRecoveryDuringSilentHistorySaveMustNotDuplicateCleanupDiagnostic() = runTest {
        val root = temporary.newFolder().canonicalFile
        val old = PackageRecord(100001, "a".repeat(64))
        root.resolve("${old.version}/index.html").apply {
            assertTrue(parentFile!!.mkdirs())
            writeText("confirmed old package")
        }
        val residue = root.resolve("old-version").apply { assertTrue(mkdir()) }
        val historyEntered = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val store = object : ManagedOfflineStorage {
            var active: PackageRecord? = old
            var history = PreparationHistory(initialPreparationFinished = true)
            override suspend fun readActive() = active
            override suspend fun writeActive(record: PackageRecord?): Boolean { active = record; return true }
            override suspend fun readEnabled() = true
            override suspend fun writeEnabled(enabled: Boolean) = true
            override suspend fun readHistory() = history
            override suspend fun writeHistory(history: PreparationHistory): Boolean {
                historyEntered.complete(Unit)
                historyRelease.await()
                this.history = history
                return true
            }
            override fun readCacheDirty() = false
            override suspend fun writeCacheDirty(dirty: Boolean) = true
        }
        val diagnostics = mutableListOf<ManagedFailure>()
        val outcomes = mutableListOf<InstallationOutcome>()
        var requests = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sdk = ManagedOfflineSdk.forTest(
            root = root, storage = store,
            configProvider = ManagedConfigProvider {
                requests++
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            },
            minimumVersion = 100000,
            onDiagnostic = { diagnostics += it },
            onInstallationOutcome = { outcomes += it },
            mainDispatcher = dispatcher, ioDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
        val previous = System.getSecurityManager()
        var deletionAttempts = 0
        val deletionFailure = object : SecurityManager() {
            override fun checkPermission(permission: Permission) { previous?.checkPermission(permission) }
            override fun checkDelete(file: String) {
                if (file == residue.path) {
                    deletionAttempts++
                    throw SecurityException("模拟本次冷清理失败")
                }
                previous?.checkDelete(file)
            }
        }
        try {
            System.setSecurityManager(deletionFailure)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            assertTrue("静默检查已在清理失败后进入 History 保存", historyEntered.isCompleted)
            assertEquals(1, deletionAttempts)
            assertEquals(listOf(ManagedStage.CLEANUP), diagnostics.map { it.stage })
            assertEquals("清理失败不能请求配置", 0, requests)

            // History 仍挂起时，故障解除后的正常页面入口完成下一次冷清理。
            System.setSecurityManager(previous)
            var offlineLoads = 0
            val callbacks = object : ManagedPageCallbacks {
                override fun clearResourceCache() = true
                override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
                    offlineLoads++
                }
                override fun loadOnline(url: String) = Unit
            }
            val url = "https://offline.example/app/"
            assertEquals(PageDecision.Offline(old), sdk.loadPage(url, url, callbacks))
            assertEquals(1, offlineLoads)
            assertFalse("页面正常入口已恢复清理", residue.exists())
            assertEquals("页面恢复不应新增诊断", listOf(ManagedStage.CLEANUP), diagnostics.map { it.stage })

            historyRelease.complete(Unit)
            runCurrent()
            assertEquals("旧检查恢复后不能因共享清理状态变化而再报 LOCAL",
                listOf(ManagedStage.CLEANUP), diagnostics.map { it.stage })
            assertEquals(ManagedFailureReason.LOCAL_PREPARATION, diagnostics.single().reason)
            assertTrue("清理诊断不属于安装终态", outcomes.isEmpty())
            assertEquals("无需补发本次配置请求", 0, requests)
        } finally {
            System.setSecurityManager(previous)
            historyRelease.complete(Unit)
            sdk.shutdown()
        }
    }
}
