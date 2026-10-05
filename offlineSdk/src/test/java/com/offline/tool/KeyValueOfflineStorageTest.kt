package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class KeyValueOfflineStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val keys = OfflineStorageKeys("old_active", "old_enabled", "env_history", "env_dirty")
    private class Values : OfflineKeyValueStore {
        val values = mutableMapOf<String, Any>()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var writesSucceed = true
        override fun readString(key: String): String? { readFailure?.let { throw it }; return values[key] as? String }
        override fun readBoolean(key: String): Boolean? { readFailure?.let { throw it }; return values[key] as? Boolean }
        override fun writeString(key: String, value: String?): Boolean {
            writeFailure?.let { throw it }
            if (!writesSucceed) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
        override fun writeBoolean(key: String, value: Boolean): Boolean {
            writeFailure?.let { throw it }
            if (writesSucceed) values[key] = value
            return writesSucceed
        }
    }

    @Test fun oldKeysAndBooleanTypesStayInPlace() = runBlocking {
        val values = Values()
        val storage = ManagedOfflineStorage.keyValue(values, keys)
        assertNull(storage.readActive()); assertNull(storage.readEnabled()); assertNull(storage.readHistory())
        assertNull(storage.readCacheDirty())
        val record = PackageRecord(100001, "a".repeat(64))
        assertTrue(storage.writeActive(record)); assertTrue(storage.writeEnabled(false))
        assertTrue(storage.writeHistory(PreparationHistory(true))); assertTrue(storage.writeCacheDirty(false))
        assertTrue(values.values[keys.active] is String)
        assertTrue(values.values[keys.enabled] is Boolean)
        assertTrue(values.values[keys.history] is String)
        assertTrue(values.values[keys.cacheDirty] is Boolean)
        assertEquals(record, storage.readActive()); assertEquals(false, storage.readEnabled())
        assertEquals(PreparationHistory(true), storage.readHistory()); assertEquals(false, storage.readCacheDirty())
        assertTrue(storage.writeActive(null)); assertNull(storage.readActive())
    }

    @Test fun existingEmptyAndNullJsonAreNotMissingRecords() = runBlocking {
        val values = Values()
        val storage = ManagedOfflineStorage.keyValue(values, keys)
        for (json in listOf("", "null")) {
            values.values[keys.active] = json
            try { storage.readActive(); throw AssertionError("存在但损坏不是缺记录") }
            catch (_: org.json.JSONException) { }
        }
    }

    @Test fun readsAndLegacyFailuresPropagate() = runBlocking {
        val values = Values().apply { readFailure = IOException("read failed") }
        val storage = ManagedOfflineStorage.keyValue(values, keys) { throw IOException("legacy failed") }
        try { storage.readActive(); throw AssertionError("读失败必须传播") } catch (_: IOException) { }
        try { storage.readLegacyEvidence(); throw AssertionError("旧事实读失败必须传播") } catch (_: IOException) { }
    }

    @Test fun falseOrThrownCommitDoesNotClaimSuccessAndCancellationPropagates() = runBlocking {
        val values = Values()
        val storage = ManagedOfflineStorage.keyValue(values, keys)
        values.writesSucceed = false
        assertFalse(storage.writeEnabled(true)); assertFalse(storage.writeActive(null))
        values.writeFailure = IOException("commit failed")
        assertFalse(storage.writeHistory(PreparationHistory(true))); assertFalse(storage.writeCacheDirty(false))
        values.writeFailure = CancellationException("write cancelled")
        try { storage.writeCacheDirty(false); throw AssertionError("写取消不能折叠") } catch (_: CancellationException) { }
        values.readFailure = CancellationException("read cancelled")
        try { storage.readEnabled(); throw AssertionError("读取消不能折叠") } catch (_: CancellationException) { }
    }

    @Test fun unreadableJsonThroughRealManagerPreservesDirectoriesAndCanRecover() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val digest = "a".repeat(64)
        val record = PackageRecord(100001, digest)
        val damaged = listOf(
            keys.active to """{"version":100001,"sha256":undefined}""",
            keys.active to """{"version":100001}""",
            keys.active to """{"version":100001,"sha256":false}""",
            keys.active to """{"version":100001,"sha256":null}""",
            keys.active to """{"version":100001,"sha256":"$digest",}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":NaN}""",
            keys.active to """{version:100001,"sha256":"$digest"}""",
            keys.active to """{'version':100001,"sha256":"$digest"}""",
            keys.active to """{"version":100001,/* comment */"sha256":"$digest"}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":TRUE}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":NULL}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra"=1}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":0x10}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":01}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":1.}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":1e}""",
            keys.active to """{"version":100001,"sha256":"$digest","extra":"\x41"}""",
            keys.active to "{\"version\":100001,\"sha256\":\"$digest\",\"extra\":\"raw\tcontrol\"}",
            keys.active to "{\"version\":100001,\"sha256\":\"$digest\",\"extra\":\"raw\nline\"}",
            keys.history to """{"initialPreparationFinished":undefined}""",
            keys.history to """{"initialPreparationFinished":null}""",
            keys.history to """{"initialPreparationFinished":"true"}""",
            keys.history to """{"initialPreparationFinished":true,"extra":undefined}""",
        )
        for ((key, json) in damaged) {
            val root = temporary.newFolder()
            val entry = root.resolve("100001/index.html").apply {
                parentFile!!.mkdirs()
                writeText("existing active page")
            }
            val other = root.resolve("100002/index.html").apply {
                parentFile!!.mkdirs()
                writeText("existing unselected page")
            }
            val values = Values().apply {
                this.values[keys.active] = OfflineStorageCodec.encodeActive(record)
                this.values[keys.history] = OfflineStorageCodec.encodeHistory(PreparationHistory(true))
                this.values[keys.enabled] = true
                this.values[keys.cacheDirty] = false
                this.values[key] = json
            }
            val diagnostics = mutableListOf<ManagedFailure>()
            val outcomes = mutableListOf<InstallationOutcome>()
            val sdk = ManagedOfflineSdk.forTest(
                root, ManagedOfflineStorage.keyValue(values, keys),
                ManagedConfigProvider { error("坏本地事实不能请求配置") },
                minimumVersion = 100000, mainDispatcher = dispatcher, ioDispatcher = dispatcher,
                onDiagnostic = { diagnostics += it }, onInstallationOutcome = { outcomes += it },
            )
            try {
                sdk.setConditions(privacyAllowed = true, foreground = false)
                assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
                val failure = (sdk.state.value.activity as ManagedActivity.Failed).failure
                assertEquals(ManagedFailureReason.STORAGE_READ, failure.reason)
                assertEquals(ManagedStage.LOCAL, failure.stage)
                assertEquals(listOf(failure), diagnostics)
                assertTrue(outcomes.isEmpty())
                assertNull(sdk.state.value.active)
                assertTrue("$key 读取故障必须保留既有 active 目录", entry.isFile)
                assertEquals("existing active page", entry.readText())
                assertTrue("$key 读取故障不能取得其他版本清理资格", other.isFile)
                assertEquals("existing unselected page", other.readText())

                // 同一实例下修正存储后重新读取，恢复真实包使用；不绕过管理器保护与页面入口。
                values.values[keys.active] = OfflineStorageCodec.encodeActive(record)
                values.values[keys.history] = OfflineStorageCodec.encodeHistory(PreparationHistory(true))
                assertEquals(StartupDecision.CONTINUE, sdk.startupDecision())
                assertEquals(record, sdk.state.value.active)
                assertTrue(sdk.state.value.usablePackage)
                assertEquals("existing active page", entry.readText())
                assertFalse(other.exists())
                var loaded: File? = null
                val callbacks = object : ManagedPageCallbacks {
                    override fun clearResourceCache() = true
                    override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
                        loaded = directory
                    }
                    override fun loadOnline(url: String) { error("正常包必须通过管理器加载离线页面") }
                }
                val url = "https://example.com/app/"
                assertEquals(PageDecision.Offline(record), sdk.loadPage(url, url, callbacks))
                assertEquals(entry.parentFile!!.canonicalFile, loaded)
            } finally { sdk.shutdown() }
        }
    }

    @Test fun legacyCallbackIsOnlyReadWhenCalledAndDoesNotWriteNewHistory() = runBlocking {
        val values = Values()
        var reads = 0
        val storage = ManagedOfflineStorage.keyValue(values, keys) { reads++; LegacyPreparationEvidence() }
        assertEquals(0, reads)
        assertEquals(LegacyPreparationEvidence(), storage.readLegacyEvidence())
        assertEquals(1, reads)
        assertTrue(values.values.isEmpty())
    }
}
