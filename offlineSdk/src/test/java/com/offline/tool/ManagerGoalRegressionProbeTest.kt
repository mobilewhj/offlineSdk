package com.offline.tool

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 只放在回归研究目录的契约探针，不修改 SDK 生产代码或既有测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagerGoalRegressionProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun successfulActivationCanResumeMonitoringAfterCancelledFirstHistoryWrite() = runTest {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("index.html"))
                zip.write("first success".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val target = PackageRecord(10000, MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) })
        val historyEntered = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val store = object : ManagedOfflineStorage {
            var active: PackageRecord? = null
            var history: PreparationHistory? = null
            var cacheDirty: Boolean? = null
            override suspend fun readActive() = active
            override suspend fun writeActive(record: PackageRecord?): Boolean { active = record; return true }
            override suspend fun readEnabled(): Boolean? = true
            override suspend fun writeEnabled(enabled: Boolean) = true
            override suspend fun readHistory() = history
            override suspend fun writeHistory(history: PreparationHistory): Boolean {
                historyEntered.complete(Unit)
                historyRelease.await()
                this.history = history
                return true
            }
            override fun readCacheDirty() = cacheDirty
            override suspend fun writeCacheDirty(dirty: Boolean): Boolean { cacheDirty = dirty; return true }
        }
        var requests = 0
        val outcomes = mutableListOf<InstallationOutcome>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sdk = ManagedOfflineSdk.forTest(
            root = temporary.newFolder(), storage = store,
            configProvider = ManagedConfigProvider {
                requests++
                ConfigResponse.Success(OfflineConfiguration(true, target.version,
                    OfflineCandidate(target, PackageSource.Local { bytes.inputStream() })))
            },
            mainDispatcher = dispatcher, ioDispatcher = dispatcher,
            monotonicMillis = { testScheduler.currentTime },
            onInstallationOutcome = { outcomes += it },
        )
        try {
            sdk.setConditions(privacyAllowed = true, foreground = true)
            val first = async { sdk.prepareFirst() }
            runCurrent()
            assertTrue("探针必须停在安装已成功后的首次 History 保存", historyEntered.isCompleted)
            assertEquals(target, store.active)
            assertTrue(sdk.state.value.usablePackage)
            first.cancelAndJoin()
            // 保持原取消契约：取消不能伪造首次完成；既成成功只通知一次。
            assertFalse(sdk.state.value.initialPreparationFinished)
            assertEquals(listOf(InstallationOutcome.Installed(target)), outcomes)
            historyRelease.complete(Unit)

            // 同一实例的正常恢复入口应利用已有 active 事实恢复常规监控资格。
            assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
            sdk.setConditions(privacyAllowed = true, foreground = false)
            advanceTimeBy(300_001L)
            sdk.setConditions(privacyAllowed = true, foreground = true)
            runCurrent()
            println("ManagerGoalRegressionProbe requests=$requests active=${sdk.state.value.active?.version}" +
                " usable=${sdk.state.value.usablePackage} initialFinished=${sdk.state.value.initialPreparationFinished}")
            assertEquals("已有可用 active，合法入口及前台恢复后应继续五分钟检查", 2, requests)
            assertEquals("常规检查同版可用不应重复安装通知", 1, outcomes.size)
        } finally {
            historyRelease.complete(Unit)
            sdk.shutdown()
        }
    }
}
