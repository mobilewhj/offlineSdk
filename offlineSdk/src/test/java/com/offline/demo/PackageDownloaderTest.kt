package com.offline.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PackageDownloaderTest {
    @Test
    fun completedDownloadsReuseConnectionWithoutCanceledEvents() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val canceledCalls = AtomicInteger()
            val client = OkHttpClient.Builder().eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceledCalls.incrementAndGet()
                    }
                }
            ).build()
            val downloader = PackageDownloader(client, 10_000L)

            repeat(2) { index ->
                server.enqueue(MockResponse().setBody("package-$index"))
                val url = server.url("/package.zip").toString().replaceFirst("http:", "HTTP:")
                val body = downloader.withSource(url, null) { openSource ->
                    openSource().buffer().use { it.readUtf8() }
                }
                assertEquals("package-$index", body)
                assertEquals(index, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
                assertEquals(1, client.connectionPool.idleConnectionCount())
                assertEquals(0, canceledCalls.get())
            }
        }
    }

    @Test
    fun invalidUrlsFailBeforeInvokingConsumer() = runBlocking {
        val downloader = PackageDownloader(OkHttpClient(), 10_000L)
        for (url in listOf("file:///tmp/package.zip", "https://", "", "ftp://localhost/package.zip")) {
            val failure = try {
                downloader.withSource(url, null) {
                    fail("无效地址不能进入下载消费者")
                }
                error("应拒绝无效下载地址")
            } catch (error: InstallException) {
                error
            }
            assertEquals(FailureReason.INVALID_URL, failure.reason)
            assertEquals("离线包下载地址无效: $url", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun consumerMayFinishWithoutOpeningHttpRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val downloader = PackageDownloader(OkHttpClient(), 10_000L)
            val result = downloader.withSource(server.url("/package.zip").toString(), null) { "skipped" }
            assertEquals("skipped", result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun progressIoExceptionsRemainUnchangedBeforeAndAfterReadingBytes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val downloader = PackageDownloader(OkHttpClient(), 10_000L)
            for (failAtStart in listOf(true, false)) {
                for (expected in listOf(IOException("回调读写错误"), InterruptedIOException("回调中断"))) {
                    server.enqueue(MockResponse().setBody("package"))
                    val observed = downloader.withSource(
                        server.url("/package.zip").toString(),
                        onProgress = { downloaded, _ ->
                            if (if (failAtStart) downloaded == 0L else downloaded > 0L) throw expected
                        }
                    ) { openSource ->
                        try {
                            openSource().buffer().use { it.readUtf8() }
                            error("Callback exception must propagate to the source consumer")
                        } catch (error: IOException) {
                            error
                        }
                    }
                    assertSame(expected, observed)
                }
            }
        }
    }

    @Test
    fun partialOrEmptySuccessStatusDoesNotCountAsCompleteDownload() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val downloader = PackageDownloader(OkHttpClient(), 10_000L)
            for (status in listOf(206, 204)) {
                server.enqueue(MockResponse().setResponseCode(status))
                val failure = try {
                    downloader.withSource(server.url("/package.zip").toString(), null) { openSource ->
                        openSource().use { fail("只有 HTTP 200 才能向消费者提供包内容") }
                    }
                    error("应拒绝非 200 响应")
                } catch (error: InstallException) {
                    error
                }
                assertEquals(FailureReason.DOWNLOAD, failure.reason)
                assertEquals(status, failure.status)
                assertEquals("离线包下载失败，HTTP 状态码：$status", failure.message)
            }
        }
    }

    @Test
    fun cancellationInterruptsWaitingForHeadersAndPropagatesFromSourceFactory() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            val downloader = PackageDownloader(client, 10_000L)
            val sourceFailure = CompletableDeferred<Throwable>()
            val task = launch(Dispatchers.IO) {
                downloader.withSource(server.url("/package.zip").toString(), null) { openSource ->
                    try {
                        openSource().buffer().use { it.readUtf8() }
                    } catch (error: Throwable) {
                        sourceFailure.complete(error)
                        throw error
                    }
                }
            }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(2_000L) { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
                assertTrue(sourceFailure.await() is CancellationException)
            } finally {
                task.cancelAndJoin()
            }
        }
    }

    @Test
    fun injectedSingleThreadDispatcherDoesNotDelayCallCancellation() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
                val downloader = PackageDownloader(client, 5_000L, ioDispatcher = dispatcher)
                val sourceFailure = CompletableDeferred<Throwable>()
                val task = launch(Dispatchers.IO) {
                    downloader.withSource(server.url("/package.zip").toString(), null) { openSource ->
                        try {
                            openSource().buffer().use { it.readUtf8() }
                        } catch (error: Throwable) {
                            sourceFailure.complete(error)
                            throw error
                        }
                    }
                }
                try {
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    withTimeout(2_000L) { task.cancelAndJoin() }
                    assertTrue(task.isCancelled)
                    assertTrue(sourceFailure.await() is CancellationException)
                } finally {
                    // Network timeouts bound cleanup even when the cancellation regression is present.
                    task.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun cancellationInterruptsBlockedBodyReadAndPropagatesFromSource() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("package").throttleBody(1, 1, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            val downloader = PackageDownloader(client, 10_000L)
            val firstByte = CompletableDeferred<Unit>()
            val sourceFailure = CompletableDeferred<Throwable>()
            val task = launch(Dispatchers.IO) {
                downloader.withSource(
                    server.url("/package.zip").toString(),
                    onProgress = { downloaded, _ ->
                        if (downloaded > 0L) firstByte.complete(Unit)
                    }
                ) { openSource ->
                    try {
                        openSource().buffer().use { it.readUtf8() }
                    } catch (error: Throwable) {
                        sourceFailure.complete(error)
                        throw error
                    }
                }
            }
            try {
                withTimeout(5_000L) { firstByte.await() }
                // 首字节进度返回后，让消费者进入等待下一字节的阻塞读取。
                delay(100L)
                withTimeout(2_000L) { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
                assertTrue(sourceFailure.await() is CancellationException)
            } finally {
                task.cancelAndJoin()
            }
        }
    }
}
