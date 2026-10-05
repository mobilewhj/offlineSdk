package com.offline.tool

import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OfflineAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class DeviceStorage(
        private val cachePreferences: SharedPreferences? = null,
        initialCacheDirty: Boolean? = null,
    ) : ManagedOfflineStorage {
        @Volatile var active: PackageRecord? = null
        @Volatile var enabled: Boolean? = null
        @Volatile var history: PreparationHistory? = null
        @Volatile var cacheDirty: Boolean? = initialCacheDirty
        @Volatile var failActiveRead = false
        @Volatile var cleanGate: CompletableDeferred<Unit>? = null
        val cleanEntered = CountDownLatch(1)

        override suspend fun readActive(): PackageRecord? {
            if (failActiveRead) throw IOException("测试主动触发 active 读取失败")
            return active
        }

        override suspend fun writeActive(record: PackageRecord?): Boolean {
            active = record
            return true
        }

        override suspend fun readEnabled() = enabled
        override suspend fun writeEnabled(enabled: Boolean): Boolean {
            this.enabled = enabled
            return true
        }

        override suspend fun readHistory() = history
        override suspend fun writeHistory(history: PreparationHistory): Boolean {
            this.history = history
            return true
        }

        override fun readCacheDirty(): Boolean? =
            if (cachePreferences?.contains("dirty") == true) cachePreferences.getBoolean("dirty", true)
            else cacheDirty

        override suspend fun writeCacheDirty(dirty: Boolean): Boolean {
            if (!dirty) {
                cleanGate?.let { gate ->
                    cleanEntered.countDown()
                    gate.await()
                }
            }
            val saved = cachePreferences?.edit()?.putBoolean("dirty", dirty)?.commit() ?: true
            if (saved) cacheDirty = dirty
            return saved
        }
    }

    private class ManagedPage(val webView: WebView) {
        val offlinePaths = CopyOnWriteArrayList<String>()
        val fallthroughPaths = CopyOnWriteArrayList<String>()
        val order = CopyOnWriteArrayList<String>()
        val clearCalls = AtomicInteger()
        val finished = AtomicReference(CompletableFuture<String>())
        var boundClient: WebViewClient? = null
            private set

        fun nextLoad(): CompletableFuture<String> = CompletableFuture<String>().also(finished::set)

        fun callbacks(): ManagedPageCallbacks = object : ManagedPageCallbacks {
            override fun clearResourceCache(): Boolean {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                webView.clearCache(true)
                clearCalls.incrementAndGet()
                order += "clear"
                return true
            }

            override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                order += "offline"
                val client = client(interceptor)
                boundClient = client
                webView.webViewClient = client
                webView.loadUrl(url)
            }

            override fun loadOnline(url: String) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                order += "online"
                webView.webViewClient = client(null)
                webView.loadUrl(url)
            }
        }

        private fun client(interceptor: OfflineInterceptor?): WebViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?,
            ): WebResourceResponse? {
                val response = interceptor?.shouldInterceptRequest(view, request)
                val path = request?.url?.path.orEmpty()
                if (response != null) offlinePaths += path
                else if (interceptor != null) fallthroughPaths += path
                return response
            }

            override fun onPageFinished(view: WebView, url: String) {
                finished.get().complete(url)
            }
        }
    }

    private fun newPage(): ManagedPage {
        val view = AtomicReference<WebView>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.set(WebView(context).apply { settings.javaScriptEnabled = true })
        }
        return ManagedPage(view.get())
    }

    private fun destroyPage(page: ManagedPage) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            page.webView.stopLoading()
            page.webView.destroy()
        }
    }

    private fun evaluate(page: ManagedPage, expression: String): String {
        val value = CompletableFuture<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            page.webView.evaluateJavascript(expression) { value.complete(it) }
        }
        return value.get(10, TimeUnit.SECONDS)
    }

    private fun awaitJavascript(page: ManagedPage, expression: String, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (evaluate(page, expression) == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, evaluate(page, expression))
    }

    private fun archive(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun candidate(version: Int, bytes: ByteArray): OfflineCandidate {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return OfflineCandidate(PackageRecord(version, digest), PackageSource.Local { bytes.inputStream() })
    }

    private fun setConditions(sdk: ManagedOfflineSdk, privacy: Boolean, foreground: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sdk.setConditions(privacyAllowed = privacy, foreground = foreground)
        }
    }

    /** 测试 APK 独占的环回服务，只为 WebView 的真实在线请求提供可控页面。 */
    private class LoopbackPages : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var bodyMarker = "online-A"
        val baseUrl = "http://127.0.0.1:${server.localPort}/app/"
        private val worker = Thread {
            while (running.get()) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val request = input.readLine() ?: return@use
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        val path = request.split(' ').getOrNull(1) ?: "/"
                        requests += path
                        val route = path.substringBefore('?')
                        val (contentType, content, cacheControl) = when (route) {
                            "/app/probe.js" -> Triple(
                                "text/javascript; charset=UTF-8",
                                "document.title='online-proof';window.__managedJs='online';",
                                "no-store",
                            )
                            "/app/dynamic.js" -> Triple(
                                "text/javascript; charset=UTF-8",
                                "window.__managedDynamic='online';",
                                "no-store",
                            )
                            else -> Triple(
                                "text/html; charset=UTF-8",
                                "<html><head><script src='probe.js'></script></head><body id='online-body'>$bodyMarker</body></html>",
                                "public, max-age=600",
                            )
                        }
                        val bytes = content.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                            "Content-Length: ${bytes.size}\r\nCache-Control: $cacheControl\r\n" +
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().use { output ->
                            output.write(headers.toByteArray(Charsets.ISO_8859_1))
                            output.write(bytes)
                            output.flush()
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // WebView 可取消预取连接；下一次请求仍由同一服务处理。
                } catch (_: SocketException) {
                    if (!running.get()) break
                } catch (_: IOException) {
                    if (!running.get()) break
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            running.set(false)
            server.close()
            worker.join(5_000)
        }
    }

    @Test
    fun managerConstructionOnMainDoesNotAccessDiskUnderStrictMode() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val root = File(context.cacheDir, "strict_mode_${UUID.randomUUID()}")
        val storage = object : ManagedOfflineStorage {
            override suspend fun readActive(): PackageRecord? = error("构造不应读取 active")
            override suspend fun writeActive(record: PackageRecord?) = error("构造不应写入 active")
            override suspend fun readEnabled(): Boolean? = error("构造不应读取开关")
            override suspend fun writeEnabled(enabled: Boolean) = error("构造不应写入开关")
            override suspend fun readHistory(): PreparationHistory? = error("构造不应读取历史")
            override suspend fun writeHistory(history: PreparationHistory) = error("构造不应写入历史")
            override fun readCacheDirty(): Boolean? = error("构造不应读取缓存标记")
            override suspend fun writeCacheDirty(dirty: Boolean) = error("构造不应写入缓存标记")
        }
        val provider = ManagedConfigProvider { error("构造不应请求配置") }
        val violations = CopyOnWriteArrayList<String>()
        val original = AtomicReference<StrictMode.ThreadPolicy>()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // 先加载类，避免首次类加载的框架 IO 被误计作管理器构造。
                ManagedOfflineSdk(root, storage, provider).shutdown()
                original.set(StrictMode.getThreadPolicy())
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(original.get())
                        .detectDiskReads().detectDiskWrites()
                        .penaltyListener({ command -> command.run() }, { violations += it.toString() })
                        .build()
                )
                ManagedOfflineSdk(root, storage, provider).apply {
                    setConditions(privacyAllowed = false, foreground = false)
                    shutdown()
                }
            }
            // 违规由 Main 队列稍后交付；策略保留到队列有机会处理后再断言。
            val drained = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertTrue("Main 构造和同步条件入口不得触发磁盘 IO: $violations", violations.isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                original.get()?.let(StrictMode::setThreadPolicy)
            }
        }
    }

    @Test
    fun strictModeDetectorCatchesKnownMainDiskRead() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val file = File(context.cacheDir, "strict_positive_${UUID.randomUUID()}")
        file.writeText("positive")
        val violations = CopyOnWriteArrayList<String>()
        val delivered = CountDownLatch(1)
        val original = AtomicReference<StrictMode.ThreadPolicy>()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                original.set(StrictMode.getThreadPolicy())
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(original.get())
                        .detectDiskReads().detectDiskWrites()
                        .penaltyListener({ command -> command.run() }) {
                            violations += it.toString()
                            delivered.countDown()
                        }.build()
                )
                FileInputStream(file).use { it.read() }
            }
            assertTrue("已知 FileInputStream 读取必须被检测", delivered.await(5, TimeUnit.SECONDS))
            assertTrue(violations.isNotEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                original.get()?.let(StrictMode::setThreadPolicy)
            }
            file.delete()
        }
    }

    @Test
    fun systemWebViewLoadsOfflineHtmlAndScriptAtOriginalUrl() {
        val root = File(context.cacheDir, "webview_${UUID.randomUUID()}").canonicalFile
        val base = "https://example.test/app/"
        val originalUrl = "${base}?ticket=original#screen"
        val bytes = archive(
            "index.html" to "<html><head><script src='probe.js'></script></head><body id='offline-body'>offline</body></html>",
            "probe.js" to "document.title='offline-proof';",
        )
        val target = candidate(10_000, bytes)
        val storage = DeviceStorage(initialCacheDirty = true)
        val sdk = ManagedOfflineSdk(root, storage, ManagedConfigProvider {
            ConfigResponse.Success(OfflineConfiguration(true, target.record.version, target))
        })
        val page = newPage()
        try {
            setConditions(sdk, privacy = true, foreground = true)
            assertEquals(StartupDecision.NEEDS_FIRST_PREPARATION, runBlocking { sdk.startupDecision() })
            val first = runBlocking { sdk.prepareFirst() }
            assertEquals(CheckResult.Installed(target.record), (first as FirstPreparationResult.Finished).check)
            val loaded = page.nextLoad()
            assertEquals(PageDecision.Offline(target.record), runBlocking {
                sdk.loadPage(originalUrl, base, page.callbacks())
            })
            assertEquals(originalUrl, loaded.get(20, TimeUnit.SECONDS))
            assertEquals("\"offline-proof\"", evaluate(page, "document.title"))
            assertEquals("\"offline\"", evaluate(page, "document.getElementById('offline-body').textContent"))
            assertEquals("\"$originalUrl\"", evaluate(page, "location.href"))
            assertTrue("HTML 与脚本均须由本地拦截", page.offlinePaths.size >= 2)
            assertTrue(page.offlinePaths.contains("/app/"))
            assertTrue(page.offlinePaths.contains("/app/probe.js"))
            assertEquals(listOf("clear", "offline"), page.order)
            val observedUrl = AtomicReference<String>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                observedUrl.set(page.webView.url)
            }
            assertEquals(originalUrl, observedUrl.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.shutdown() }
            destroyPage(page)
            root.deleteRecursively()
        }
    }

    @Test
    fun disabledConfigurationStopsLaterResourcesOnTheBoundWebView() {
        val root = File(context.cacheDir, "bound_switch_${UUID.randomUUID()}").canonicalFile
        val bytes = archive(
            "index.html" to "<html><head><script src='probe.js'></script></head><body>offline</body></html>",
            "probe.js" to "document.title='offline-proof';",
            "dynamic.js" to "window.__managedDynamic='local';",
        )
        val target = candidate(10_000, bytes)
        val storage = DeviceStorage(initialCacheDirty = false).apply {
            active = target.record
            enabled = true
            history = PreparationHistory(initialPreparationFinished = true)
        }
        val fetched = CountDownLatch(1)
        LoopbackPages().use { server ->
            assertTrue(runBlocking {
                PackageInstaller(root).install(target.record) { bytes.inputStream() }
            } is InstallResult.Success)
            val sdk = ManagedOfflineSdk(root, storage, ManagedConfigProvider {
                fetched.countDown()
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            })
            val page = newPage()
            try {
                assertEquals(StartupDecision.CONTINUE, runBlocking { sdk.startupDecision() })
                val loaded = page.nextLoad()
                assertEquals(PageDecision.Offline(target.record), runBlocking {
                    sdk.loadPage(server.baseUrl, server.baseUrl, page.callbacks())
                })
                assertEquals(server.baseUrl, loaded.get(20, TimeUnit.SECONDS))
                assertEquals("\"offline-proof\"", evaluate(page, "document.title"))
                assertFalse("首次页面必须由本地包提供", server.requests.any { it.startsWith("/app/") })
                val client = page.boundClient
                val originalOrder = page.order.toList()

                setConditions(sdk, privacy = true, foreground = true)
                assertTrue("必须真实取得关闭配置", fetched.await(10, TimeUnit.SECONDS))
                runBlocking {
                    withTimeout(10_000) {
                        while (sdk.state.value.enabled) delay(20)
                    }
                }
                val suffix = UUID.randomUUID().toString()
                evaluate(page, "(function(){var s=document.createElement('script');" +
                    "s.src='dynamic.js?probe=$suffix';document.body.appendChild(s);return true})()")
                awaitJavascript(page, "window.__managedDynamic", "\"online\"")
                assertSame("旧页面的客户端不能被替换", client, page.boundClient)
                assertEquals("资源开关不能主动刷新旧页面", originalOrder, page.order)
                assertTrue(page.fallthroughPaths.contains("/app/dynamic.js"))
                assertTrue(server.requests.any { it == "/app/dynamic.js?probe=$suffix" })
                assertFalse(page.offlinePaths.contains("/app/dynamic.js"))
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.shutdown() }
                destroyPage(page)
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun onlineFallbackLoadsRealHtmlAndJavascriptAtOriginalUrl() {
        LoopbackPages().use { server ->
            for (case in listOf("disabled", "missing", "read_failure")) {
                val root = File(context.cacheDir, "online_${case}_${UUID.randomUUID()}").canonicalFile
                val storage = DeviceStorage(initialCacheDirty = true).apply {
                    history = PreparationHistory(initialPreparationFinished = true)
                    enabled = case != "disabled"
                    failActiveRead = case == "read_failure"
                }
                val sdk = ManagedOfflineSdk(root, storage, ManagedConfigProvider {
                    error("本用例不应发起配置请求")
                })
                val page = newPage()
                val originalUrl = "${server.baseUrl}?case=$case#original"
                val scriptRequestsBefore = server.requests.count { it == "/app/probe.js" }
                try {
                    assertEquals(StartupDecision.CONTINUE, runBlocking { sdk.startupDecision() })
                    val loaded = page.nextLoad()
                    assertEquals(PageDecision.Online, runBlocking {
                        sdk.loadPage(originalUrl, server.baseUrl, page.callbacks())
                    })
                    assertEquals(originalUrl, loaded.get(20, TimeUnit.SECONDS))
                    assertEquals("\"online-proof\"", evaluate(page, "document.title"))
                    assertEquals("\"online-A\"", evaluate(page, "document.getElementById('online-body').textContent"))
                    assertEquals("\"$originalUrl\"", evaluate(page, "location.href"))
                    assertTrue(server.requests.any { it == "/app/?case=$case" })
                    assertTrue(server.requests.count { it == "/app/probe.js" } > scriptRequestsBefore)
                    assertEquals("待清资源缓存须在默认线上加载前处理", listOf("clear", "online"), page.order)
                    val observedUrl = AtomicReference<String>()
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        observedUrl.set(page.webView.url)
                    }
                    assertEquals(originalUrl, observedUrl.get())
                } finally {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.shutdown() }
                    destroyPage(page)
                    root.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun pageClearCacheReloadsTheSameUrlAndDoesNotWaitForPersistedConfirmation() {
        val root = File(context.cacheDir, "cache_page_${UUID.randomUUID()}").canonicalFile
        val preferencesName = "managed_cache_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, 0)
        assertTrue(preferences.edit().putBoolean("dirty", true).commit())
        val storage = DeviceStorage(preferences, initialCacheDirty = true).apply {
            history = PreparationHistory(initialPreparationFinished = true)
            enabled = true
        }
        val fetched = CountDownLatch(1)
        LoopbackPages().use { server ->
            val sdk = ManagedOfflineSdk(root, storage, ManagedConfigProvider {
                fetched.countDown()
                ConfigResponse.Success(OfflineConfiguration(enabled = false))
            })
            val page = newPage()
            try {
                assertEquals(StartupDecision.CONTINUE, runBlocking { sdk.startupDecision() })
                val first = page.nextLoad()
                assertEquals(PageDecision.Online, runBlocking {
                    sdk.loadPage(server.baseUrl, server.baseUrl, page.callbacks())
                })
                assertEquals(server.baseUrl, first.get(20, TimeUnit.SECONDS))
                assertEquals("\"online-A\"", evaluate(page, "document.getElementById('online-body').textContent"))
                runBlocking {
                    withTimeout(10_000) {
                        while (storage.readCacheDirty() != false) delay(20)
                    }
                }
                val firstRequests = server.requests.count { it == "/app/" }
                assertEquals(1, firstRequests)

                server.bodyMarker = "online-B"
                val cached = page.nextLoad()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    page.webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    page.webView.loadUrl(server.baseUrl)
                }
                assertEquals(server.baseUrl, cached.get(20, TimeUnit.SECONDS))
                assertEquals("\"online-A\"", evaluate(page, "document.getElementById('online-body').textContent"))
                assertEquals("缓存正控不得重新请求同一 URL", firstRequests, server.requests.count { it == "/app/" })

                setConditions(sdk, privacy = true, foreground = true)
                assertTrue("配置变更须重新置脏", fetched.await(10, TimeUnit.SECONDS))
                runBlocking {
                    withTimeout(10_000) {
                        while (storage.readCacheDirty() != true) delay(20)
                    }
                }
                val gate = CompletableDeferred<Unit>()
                storage.cleanGate = gate
                val refreshed = page.nextLoad()
                assertEquals(PageDecision.Online, runBlocking {
                    sdk.loadPage(server.baseUrl, server.baseUrl, page.callbacks())
                })
                assertEquals(server.baseUrl, refreshed.get(20, TimeUnit.SECONDS))
                assertEquals("\"online-B\"", evaluate(page, "document.getElementById('online-body').textContent"))
                assertTrue("清缓存后须重新请求相同 URL", server.requests.count { it == "/app/" } > firstRequests)
                assertTrue("持久确认必须已进入 IO 屏障", storage.cleanEntered.await(10, TimeUnit.SECONDS))
                assertTrue("写盘未确认时持久脏标记仍在", preferences.getBoolean("dirty", false))
                assertEquals(listOf("clear", "online", "clear", "online"), page.order)
                gate.complete(Unit)
                runBlocking {
                    withTimeout(10_000) {
                        while (storage.readCacheDirty() != false) delay(20)
                    }
                }
            } finally {
                storage.cleanGate?.complete(Unit)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.shutdown() }
                destroyPage(page)
                root.deleteRecursively()
                context.deleteSharedPreferences(preferencesName)
            }
        }
    }

    @Test
    fun boundOldPageKeepsItsDirectoryDuringLongUpdateAndNewPageUsesNewVersion() {
        val root = File(context.cacheDir, "version_page_${UUID.randomUUID()}").canonicalFile
        val oldBytes = archive(
            "index.html" to "<html><head><script src='probe.js'></script></head><body>old</body></html>",
            "probe.js" to "document.title='old-proof';",
            "late.js" to "window.__lateVersion='old';",
        )
        val newBytes = archive(
            "index.html" to "<html><head><script src='probe.js'></script></head><body>new</body></html>",
            "probe.js" to "document.title='new-proof';",
            "late.js" to "window.__lateVersion='new';",
        )
        val old = candidate(10_000, oldBytes)
        val next = candidate(10_001, newBytes)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = OfflineCandidate(next.record, PackageSource.Local {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "测试安装屏障超时" }
            newBytes.inputStream()
        })
        val storage = DeviceStorage(initialCacheDirty = false).apply {
            active = old.record
            enabled = true
            history = PreparationHistory(initialPreparationFinished = true)
        }
        LoopbackPages().use { server ->
            assertTrue(runBlocking {
                PackageInstaller(root).install(old.record) { oldBytes.inputStream() }
            } is InstallResult.Success)
            val sdk = ManagedOfflineSdk(root, storage, ManagedConfigProvider {
                ConfigResponse.Success(OfflineConfiguration(enabled = true, onlineVersion = next.record.version, candidate = target))
            })
            val oldPage = newPage()
            val duringPage = newPage()
            val newPage = newPage()
            try {
                assertEquals(StartupDecision.CONTINUE, runBlocking { sdk.startupDecision() })
                val oldLoaded = oldPage.nextLoad()
                assertEquals(PageDecision.Offline(old.record), runBlocking {
                    sdk.loadPage(server.baseUrl, server.baseUrl, oldPage.callbacks())
                })
                assertEquals(server.baseUrl, oldLoaded.get(20, TimeUnit.SECONDS))
                assertEquals("\"old-proof\"", evaluate(oldPage, "document.title"))

                setConditions(sdk, privacy = true, foreground = true)
                assertTrue("新版安装须停在可控来源屏障", entered.await(10, TimeUnit.SECONDS))
                assertTrue(File(root, old.record.version.toString()).resolve("index.html").isFile)
                val suffix = UUID.randomUUID().toString()
                evaluate(oldPage, "(function(){var s=document.createElement('script');" +
                    "s.src='late.js?during=$suffix';document.body.appendChild(s);return true})()")
                awaitJavascript(oldPage, "window.__lateVersion", "\"old\"")
                assertTrue(oldPage.offlinePaths.contains("/app/late.js"))

                val duringUrl = "${server.baseUrl}?during=${UUID.randomUUID()}"
                val duringLoaded = duringPage.nextLoad()
                assertEquals(PageDecision.Online, runBlocking {
                    withTimeout(3_000) {
                        sdk.loadPage(duringUrl, server.baseUrl, duringPage.callbacks())
                    }
                })
                assertEquals(duringUrl, duringLoaded.get(20, TimeUnit.SECONDS))
                assertEquals("\"online-proof\"", evaluate(duringPage, "document.title"))
                assertEquals("安装尚未放行时页面已加载", 1L, release.count)

                release.countDown()
                runBlocking {
                    withTimeout(20_000) {
                        while (sdk.state.value.active != next.record) delay(20)
                    }
                }
                val newUrl = "${server.baseUrl}?version=${next.record.version}"
                val newLoaded = newPage.nextLoad()
                assertEquals(PageDecision.Offline(next.record), runBlocking {
                    sdk.loadPage(newUrl, server.baseUrl, newPage.callbacks())
                })
                assertEquals(newUrl, newLoaded.get(20, TimeUnit.SECONDS))
                assertEquals("\"new-proof\"", evaluate(newPage, "document.title"))
                assertTrue(newPage.offlinePaths.contains("/app/probe.js"))
                assertTrue("已绑定旧目录不能在新版安装后删除", File(root, old.record.version.toString()).resolve("index.html").isFile)

                evaluate(oldPage, "window.__lateVersion='pending'")
                evaluate(oldPage, "(function(){var s=document.createElement('script');" +
                    "s.src='late.js?after=${UUID.randomUUID()}';document.body.appendChild(s);return true})()")
                awaitJavascript(oldPage, "window.__lateVersion", "\"old\"")
                assertTrue(oldPage.offlinePaths.count { it == "/app/late.js" } >= 2)
            } finally {
                release.countDown()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.shutdown() }
                destroyPage(oldPage)
                destroyPage(duringPage)
                destroyPage(newPage)
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun runtimeSwitchStopsInterceptionWithoutRecreatingClient() {
        val root = File(context.cacheDir, "offline_switch_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        var enabled = false
        val reasons = mutableListOf<Pair<String, String>>()
        try {
            val interceptor = OfflineInterceptor(
                root, "https://example.test/app/",
                onResourceFailure = { reason, path -> reasons += reason to path },
                isEnabled = { enabled },
            )
            assertNull(interceptor.resolve("https://example.test/app/"))
            assertNull(interceptor.resolve("https://example.test/app/missing.js"))
            assertTrue(reasons.isEmpty())

            enabled = true
            val response = interceptor.resolve("https://example.test/app/")
            assertNotNull(response)
            assertEquals("local", response!!.data.bufferedReader().use { it.readText() })
            assertNull(interceptor.resolve("https://example.test/app/missing.js"))
            assertEquals(listOf("RESOURCE_MISSING" to "missing.js"), reasons)

            enabled = false
            assertNull(interceptor.resolve("https://example.test/app/"))
            assertNull(interceptor.resolve("https://example.test/app/missing.js"))
            assertEquals(1, reasons.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resourceDiagnosticsExcludeNormalSkipsAndStripQuery() {
        val root = File(context.cacheDir, "offline_reasons_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        try {
            root.resolve("empty.js").writeText("")
            val reasons = mutableListOf<Pair<String, String>>()
            val interceptor = OfflineInterceptor(
                root, "https://example.test/app/",
                onResourceFailure = { reason, path -> reasons += reason to path }
            )
            assertNull(interceptor.resolve("https://example.test/app/chunk.js?token=private#route"))
            assertNull(interceptor.resolve("https://example.test/app/empty.js"))
            assertEquals(listOf("RESOURCE_MISSING" to "chunk.js", "RESOURCE_EMPTY" to "empty.js"), reasons)
            assertNull(interceptor.resolve("https://example.test/app/route"))
            assertNull(interceptor.resolve("https://example.test/api/user.json"))
            assertNull(interceptor.resolve("https://other.test/app/chunk.js"))
            assertNull(interceptor.resolve("https://example.test/app/chunk.js", "POST"))
            assertNull(interceptor.resolve("https://example.test/app/chunk.js", hasRange = true))
            assertNull(interceptor.resolve("https://example.test/app/../private.js"))
            assertEquals(2, reasons.size)
            val failedReporter = OfflineInterceptor(
                root, "https://example.test/app/",
                onResourceFailure = { _, _ -> error("Reporter unavailable") }
            )
            assertNull(failedReporter.resolve("https://example.test/app/chunk.js"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitMimeAndUtf8PropertiesHaveNoCorsHeaders() {
        val root = File(context.cacheDir, "offline_mime_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        try {
            val interceptor = OfflineInterceptor(root, "https://example.test/web/")
            val expected = mapOf(
                "css" to ("text/css" to "UTF-8"),
                "html" to ("text/html" to "UTF-8"),
                "htm" to ("text/html" to "UTF-8"),
                "svg" to ("image/svg+xml" to "UTF-8"),
                "properties" to ("text/plain" to "UTF-8"),
                "js" to ("text/javascript" to "UTF-8"),
                "mjs" to ("text/javascript" to "UTF-8"),
                "json" to ("application/json" to "UTF-8"),
                "map" to ("application/json" to "UTF-8"),
                "txt" to ("text/plain" to "UTF-8"),
                "ttf" to ("font/ttf" to null),
                "woff" to ("font/woff" to null),
                "woff2" to ("font/woff2" to null),
                "wasm" to ("application/wasm" to null),
            )
            for ((extension, responseType) in expected) {
                val (mime, encoding) = responseType
                val body = if (extension == "properties") "title=中文资源" else "resource"
                root.resolve("test.$extension").writeText(body, Charsets.UTF_8)
                val response = interceptor.resolve("https://example.test/web/test.$extension")!!
                assertEquals(mime, response.mimeType)
                assertEquals(encoding, response.encoding)
                assertFalse(response.responseHeaders.keys.any { it.startsWith("Access-Control-", ignoreCase = true) })
                assertEquals(body, response.data.bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interceptorReturnsFileStreamOrNullWithoutChangingItsVersionDirectory() {
        val root = File(context.cacheDir, "offline_test_${UUID.randomUUID()}").canonicalFile
        val first = File(root, "10000").apply { mkdirs(); resolve("index.html").writeText("v1") }
        val second = File(root, "10001").apply { mkdirs(); resolve("index.html").writeText("v2") }
        try {
            val old = OfflineInterceptor(first, "https://example.test/web/")
            val latest = OfflineInterceptor(second, "https://example.test/web/")
            val response = old.resolve("https://example.test/web/?a=1#page")!!
            assertTrue(response.data is FileInputStream)
            assertEquals("v1", response.data.bufferedReader().use { it.readText() })
            assertEquals(
                "v2", latest.resolve("https://example.test/web/")!!.data.bufferedReader().use { it.readText() }
            )
            first.resolve("empty.js").writeText("")
            assertNull(old.resolve("https://example.test/web/empty.js"))
            assertNull(old.resolve("https://example.test/web/missing.js"))
            assertNull(old.resolve("https://another.test/web/index.html"))
            assertNull(old.resolve("https://example.test/api/user"))
            assertNull(old.resolve("https://example.test/web/index.html", "POST"))
            assertNull(old.resolve("https://example.test/web/index.html", hasRange = true))
            assertNull(old.resolve("https://example.test/web/../10001/index.html"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun baseAndRequestSchemesAreCaseInsensitive() {
        val root = File(context.cacheDir, "offline_scheme_case_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        try {
            for (scheme in listOf("HTTP", "HtTp", "HTTPS", "HtTpS")) {
                val interceptor = OfflineInterceptor(root, "$scheme://example.test/app/")
                for (requestScheme in listOf(scheme, scheme.lowercase())) {
                    val response = interceptor.resolve("$requestScheme://example.test/app/")
                    assertNotNull("$scheme -> $requestScheme", response)
                    assertEquals("local", response!!.data.bufferedReader().use { it.readText() })
                }
                val alternate = if (scheme.equals("https", ignoreCase = true)) "http" else "https"
                assertNull(interceptor.resolve("$alternate://example.test/app/"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun optionalDualSchemeMapsBothDefaultPortsToTheSameFiles() {
        val root = File(context.cacheDir, "offline_schemes_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        try {
            val origins = listOf(
                "http://example.test", "http://example.test:80",
                "https://example.test", "https://example.test:443",
            )
            for (base in origins) {
                val interceptor = OfflineInterceptor(root, "$base/app/", allowHttpAndHttps = true)
                for (origin in origins) {
                    val response = interceptor.resolve("$origin/app/?query=1#route")
                    assertNotNull("$base -> $origin", response)
                    assertEquals("local", response!!.data.bufferedReader().use { it.readText() })
                }
                for (url in listOf(
                    "ftp://example.test/app/", "https://another.test/app/",
                    "https://example.test:8443/app/", "http://example.test:8080/app/",
                    "https://user@example.test/app/", "https://example.test/appp/",
                )) assertNull("$base -> $url", interceptor.resolve(url))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun strictDefaultAndExplicitNonstandardPortsKeepTheirBoundaries() {
        val root = File(context.cacheDir, "offline_ports_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        try {
            for ((scheme, alternate) in listOf("http" to "https", "https" to "http")) {
                val strict = OfflineInterceptor(root, "$scheme://example.test/app/")
                assertNull(strict.resolve("$alternate://example.test/app/"))
                assertEquals(
                    "local",
                    strict.resolve("$scheme://example.test/app/")!!
                        .data.bufferedReader().use { it.readText() }
                )
            }
            val nonstandard = OfflineInterceptor(root, "https://example.test:8443/app/", allowHttpAndHttps = true)
            for (scheme in listOf("http", "https")) {
                assertEquals(
                    "local",
                    nonstandard.resolve("$scheme://example.test:8443/app/")!!
                        .data.bufferedReader().use { it.readText() }
                )
                assertNull(nonstandard.resolve("$scheme://example.test/app/"))
                assertNull(nonstandard.resolve("$scheme://example.test:8080/app/"))
            }
            val wrongDefault = OfflineInterceptor(root, "http://example.test:443/app/", allowHttpAndHttps = true)
            assertNull(wrongDefault.resolve("http://example.test/app/"))
            assertNull(wrongDefault.resolve("https://example.test:80/app/"))
        } finally {
            root.deleteRecursively()
        }
    }
}
