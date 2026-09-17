package com.offline.demo

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageInstallerTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private fun zip(vararg paths: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { archive ->
            paths.forEach { (path, content) ->
                archive.putNextEntry(ZipEntry(path)); archive.write(content.toByteArray()); archive.closeEntry()
            }
        }
    }.toByteArray()

    private fun record(bytes: ByteArray, version: Int = 10000) = PackageRecord(
        version,
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    )

    private fun assertSuccess(result: InstallResult) =
        assertTrue("Expected success, got $result", result is InstallResult.Success)

    private fun assertFailure(result: InstallResult, reason: FailureReason? = null): InstallResult.Failure {
        assertTrue("Expected failure, got $result", result is InstallResult.Failure)
        return (result as InstallResult.Failure).also { if (reason != null) assertEquals(reason, it.reason) }
    }

    @Test
    fun publishesCompleteFilesAndRemovesBothTemps() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val bytes = zip("index.html" to "v1", "static/app.js" to "v1 script")
        var sourceClosed = false
        assertSuccess(
            installer.install(record(bytes)) {
                object : ByteArrayInputStream(bytes) {
                    override fun close() {
                        sourceClosed = true; super.close()
                    }
                }
            }
        )
        assertTrue(sourceClosed)
        assertEquals("v1 script", installer.directory(10000).resolve("static/app.js").readText())
        assertEquals(listOf("10000"), root.list()!!.toList())
    }

    @Test
    fun injectedDispatcherRunsLocalSourcesDownloadSetupAndProgress() = runBlocking {
        val threadName = "offline-sdk-test-io"
        Executors.newSingleThreadExecutor { Thread(it, threadName) }.asCoroutineDispatcher().use { dispatcher ->
            val ioThread = withContext(dispatcher) { Thread.currentThread() }
            MockWebServer().use { server ->
                server.start()
                var callsCreated = 0
                val client = OkHttpClient.Builder().eventListenerFactory {
                    // newCall happens in the downloader before entering the installer's context.
                    assertSame(ioThread, Thread.currentThread())
                    callsCreated++
                    EventListener.NONE
                }.build()
                val root = temporary.newFolder().canonicalFile
                val installer = PackageInstaller(root, client, ioDispatcher = dispatcher)
                val bytes = zip("dist/index.html" to "custom dispatcher")
                var sourceClosed = false
                assertSuccess(
                    installer.install(record(bytes)) {
                        assertSame(ioThread, Thread.currentThread())
                        object : ByteArrayInputStream(bytes) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                assertSame(ioThread, Thread.currentThread())
                                return super.read(buffer, offset, length)
                            }

                            override fun close() {
                                assertSame(ioThread, Thread.currentThread())
                                sourceClosed = true
                                super.close()
                            }
                        }
                    }
                )
                assertTrue(sourceClosed)

                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val downloads = mutableListOf<Long>()
                val extracts = mutableListOf<Int>()
                assertSuccess(
                    installer.install(
                        record(bytes, 10001), server.url("/package.zip").toString(),
                        onDownloadProgress = { downloaded, _ ->
                            assertSame(ioThread, Thread.currentThread())
                            downloads += downloaded
                        },
                        onExtractProgress = { percent ->
                            assertSame(ioThread, Thread.currentThread())
                            extracts += percent
                        }
                    )
                )
                assertEquals(1, callsCreated)
                assertEquals(bytes.size.toLong(), downloads.last())
                assertEquals(99, extracts.last())
                assertEquals("custom dispatcher", installer.directory(10001).resolve("index.html").readText())
                assertEquals(setOf("10000", "10001"), root.list()!!.toSet())
            }
        }
    }

    @Test
    fun hashesChunkedArchiveAcrossBuffersBeforePublishingExactBytes() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val random = java.util.Random(20260913)
        val content = buildString {
            repeat(96 * 1024 + 137) { append((32 + random.nextInt(95)).toChar()) }
        }
        val bytes = zip("index.html" to "chunked", "static/app.js" to content)
        assertTrue(bytes.size > 32 * 1024)
        assertTrue(bytes.size % 8192 != 0)
        val chunkSizes = intArrayOf(1, 7, 8191, 37, 16_387, 3, 32_768)
        assertSuccess(
            installer.install(record(bytes)) {
                object : ByteArrayInputStream(bytes) {
                    private var reads = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, minOf(length, chunkSizes[reads++ % chunkSizes.size]))
                }
            }
        )
        assertArrayEquals(content.toByteArray(), installer.directory(10000).resolve("static/app.js").readBytes())
        assertEquals("chunked", installer.directory(10000).resolve("index.html").readText())
        assertEquals(listOf("10000"), root.list()!!.toList())
    }

    @Test
    fun hashMismatchAndSourceFailureLeaveNoPublishedDirectory() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val bytes = zip("index.html" to "v1")
        val hashMismatch =
            assertFailure(
                installer.install(record(bytes).copy(sha256 = "0".repeat(64))) { bytes.inputStream() },
                FailureReason.HASH_MISMATCH
            )
        assertEquals(InstallStage.VERIFY, hashMismatch.stage)
        assertEquals("离线包 SHA-256 校验不一致", hashMismatch.message)
        val cause = IOException("source failed")
        assertSame(cause, assertFailure(installer.install(record(bytes)) { throw cause }, FailureReason.FILE_IO).cause)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun readFailureClosesSourceAndRemovesTemps() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val bytes = zip("index.html" to "v1")
        val cause = IOException("read failed")
        var sourceClosed = false
        val failure = assertFailure(
            PackageInstaller(root).install(record(bytes)) {
                object : ByteArrayInputStream(bytes) {
                    private var reads = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (reads++ > 0) throw cause
                        return super.read(buffer, offset, minOf(length, 17))
                    }

                    override fun close() {
                        sourceClosed = true; super.close()
                    }
                }
            },
            FailureReason.FILE_IO
        )
        assertSame(cause, failure.cause)
        assertEquals(InstallStage.DOWNLOAD, failure.stage)
        assertTrue(sourceClosed)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun zipSlipAndMissingEntryAreRejected() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        for (bytes in listOf(
            zip("../escaped.txt" to "escape", "index.html" to "v1"), zip("static/app.js" to "no entry")
        )) {
            assertFailure(installer.install(record(bytes)) { bytes.inputStream() }, FailureReason.INVALID_ARCHIVE)
        }
        assertTrue(root.list()!!.isEmpty())
        assertFalse(root.parentFile!!.resolve("escaped.txt").exists())
    }

    @Test
    fun installingNewVersionDoesNotOverwriteOrDeleteOldVersion() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val first = zip("index.html" to "v1")
        val second = zip("index.html" to "v2")
        assertSuccess(installer.install(record(first)) { first.inputStream() })
        assertFailure(installer.install(record(second)) { second.inputStream() }, FailureReason.TARGET_EXISTS)
        assertSuccess(installer.install(record(second, 10001)) { second.inputStream() })
        assertEquals("v1", installer.directory(10000).resolve("index.html").readText())
        assertTrue(installer.clearOldVersions(10001))
        assertFalse(installer.directory(10000).exists())
        assertEquals("v2", installer.directory(10001).resolve("index.html").readText())
    }

    @Test
    fun invalidActiveDirectoryDoesNotAuthorizeCleanup() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        root.resolve("10000").mkdir()
        root.resolve("10000/keep").writeText("keep")
        assertFalse(PackageInstaller(root).clearOldVersions(10001))
        assertEquals("keep", root.resolve("10000/keep").readText())
    }

    @Test
    fun cancellationDuringCopyCleansTempsAndAllowsRetry() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val bytes = zip("index.html" to "v1")
        var sourceClosed = false
        val task = launch {
            val ownJob = coroutineContext[kotlinx.coroutines.Job]!!
            installer.install(record(bytes)) {
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        ownJob.cancel()
                        return super.read(buffer, offset, length)
                    }

                    override fun close() {
                        sourceClosed = true; super.close()
                    }
                }
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(sourceClosed)
        assertTrue(root.list()!!.isEmpty())
        assertSuccess(installer.install(record(bytes)) { bytes.inputStream() })
    }

    @Test
    fun builtinComputesDigestAndPublishesDistWithAndWithoutProgress() = runBlocking {
        val bytes = zip("dist/" to "", "dist/index.html" to "builtin", "dist/static/app.js" to "script")
        for (withProgress in listOf(false, true)) {
            val root = temporary.newFolder().canonicalFile
            val installer = PackageInstaller(root)
            val result = if (withProgress) {
                installer.installBuiltin(10000, onExtractProgress = {}) { bytes.inputStream() }
            } else {
                installer.installBuiltin(10000) { bytes.inputStream() }
            }
            assertSuccess(result)
            assertEquals(record(bytes), (result as InstallResult.Success).record)
            assertEquals("builtin", installer.directory(10000).resolve("index.html").readText())
            assertEquals("script", installer.directory(10000).resolve("static/app.js").readText())
            assertFalse(installer.directory(10000).resolve("dist").exists())
            assertEquals(listOf("10000"), root.list()!!.toList())
        }
    }

    @Test
    fun archiveWithoutCentralDirectoryFailsWithAndWithoutProgress() = runBlocking {
        val bytes = zip("dist/index.html" to "builtin", "dist/static/app.js" to "script")
        // The helper writes no ZIP comment: the central-directory offset is six bytes before EOF.
        val centralDirectoryOffset = ByteBuffer.wrap(bytes, bytes.size - 6, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val truncated = bytes.copyOf(centralDirectoryOffset)
        for (withProgress in listOf(false, true)) {
            val root = temporary.newFolder().canonicalFile
            val installer = PackageInstaller(root)
            val result = if (withProgress) {
                installer.installBuiltin(10000, onExtractProgress = {}) { truncated.inputStream() }
            } else {
                installer.installBuiltin(10000) { truncated.inputStream() }
            }
            assertEquals(InstallStage.EXTRACT, assertFailure(result, FailureReason.INVALID_ARCHIVE).stage)
            assertFalse(installer.directory(10000).exists())
            assertTrue(root.list()!!.isEmpty())
        }
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val failure = assertFailure(
            installer.install(record(truncated)) { truncated.inputStream() },
            FailureReason.INVALID_ARCHIVE
        )
        assertEquals(InstallStage.EXTRACT, failure.stage)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun builtinProgressTracksWrittenBytesAndReservesCompletionForHost() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val bytes = zip("dist/index.html" to "x".repeat(32 * 1024), "dist/app.js" to "y".repeat(96 * 1024))
        val progress = mutableListOf<Int>()
        val result = installer.installBuiltin(
            10000,
            onExtractProgress = { percent ->
                progress += percent
                val written = root.resolve("10000_temp/dist/index.html").length() +
                    root.resolve("10000_temp/dist/app.js").length()
                assertEquals(minOf(99, (written * 100 / (128 * 1024)).toInt()), percent)
                assertFalse(installer.directory(10000).exists())
            }
        ) { bytes.inputStream() }
        assertSuccess(result)
        assertEquals(0, progress.first())
        assertTrue(progress.contains(25))
        assertEquals(99, progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> after > before })
        assertEquals(96 * 1024L, installer.directory(10000).resolve("app.js").length())
    }

    @Test
    fun invalidBuiltinNeverReportsCompletedProgress() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val progress = mutableListOf<Int>()
        val bytes = zip("app.js" to "x".repeat(64 * 1024))
        val installer = PackageInstaller(root)
        val result = installer.installBuiltin(10000, onExtractProgress = { progress += it }) { bytes.inputStream() }
        assertFailure(result, FailureReason.INVALID_ARCHIVE)
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it in 0..99 })
        assertFalse(installer.directory(10000).exists())
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun coldCleanupRemovesOrphansWithoutFollowingLinks() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val outside = temporary.newFolder().canonicalFile
        outside.resolve("metadata").writeText("keep")
        root.resolve("10000").mkdir()
        root.resolve("10000/index.html").writeText("orphan")
        root.resolve("10000.zip.tmp").writeText("temp")
        java.nio.file.Files.createSymbolicLink(root.resolve("linked").toPath(), outside.toPath())
        assertTrue(PackageInstaller(root).clearOldVersions(null))
        assertTrue(root.list()!!.isEmpty())
        assertEquals("keep", outside.resolve("metadata").readText())
        java.nio.file.Files.createSymbolicLink(root.resolve("linked-root").toPath(), outside.toPath())
        assertFalse(PackageInstaller(root.resolve("linked-root")).clearOldVersions(null))
        assertEquals("keep", outside.resolve("metadata").readText())
    }

    @Test
    fun invalidInputsAndExpandedSizeLimitAreObservable() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val bytes = zip("index.html" to "valid")
        assertFailure(
            installer.install(record(bytes).copy(version = 9999)) { fail("must not open source"); bytes.inputStream() },
            FailureReason.INVALID_RECORD
        )
        val unsupportedUrl = assertFailure(
            installer.install(record(bytes), "file:///tmp/file.zip"),
            FailureReason.INVALID_URL,
        )
        assertEquals(InstallStage.DOWNLOAD, unsupportedUrl.stage)
        assertEquals("离线包下载地址无效: file:///tmp/file.zip", unsupportedUrl.message)
        assertNull(unsupportedUrl.cause)
        val malformedUrl = assertFailure(
            installer.install(record(bytes), "https://"),
            FailureReason.INVALID_URL,
        )
        assertEquals(InstallStage.DOWNLOAD, malformedUrl.stage)
        assertEquals("离线包下载地址无效: https://", malformedUrl.message)
        assertNull(malformedUrl.cause)
        assertTrue(root.list()!!.isEmpty())
        val oversized = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { archive ->
                archive.putNextEntry(ZipEntry("index.html"))
                val block = ByteArray(1024 * 1024)
                repeat(65) { archive.write(block) }
                archive.closeEntry()
            }
        }.toByteArray()
        assertFailure(installer.install(record(oversized)) { oversized.inputStream() }, FailureReason.SIZE_LIMIT)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun httpDownloadStillChecksDigestBeforePublishing() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = temporary.newFolder().canonicalFile
            val installer = PackageInstaller(root)
            val bytes = zip("dist/index.html" to "http package")
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            assertFailure(
                installer.install(
                    record(bytes, 10002).copy(sha256 = "0".repeat(64)), server.url("/offline.zip").toString()
                ),
                FailureReason.HASH_MISMATCH,
            )
            assertFalse(installer.directory(10002).exists())
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            assertSuccess(installer.install(record(bytes, 10002), server.url("/offline.zip").toString()))
            assertEquals("http package", installer.directory(10002).resolve("index.html").readText())
            assertEquals(listOf("10002"), root.list()!!.toList())
        }
    }

    @Test
    fun cancelledUpgradePreservesOldDirectory() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val old = zip("index.html" to "old")
        val next = zip("index.html" to "new")
        assertSuccess(installer.install(record(old)) { old.inputStream() })
        val task = launch {
            val ownJob = coroutineContext[kotlinx.coroutines.Job]!!
            installer.install(record(next, 10001)) {
                object : ByteArrayInputStream(next) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        ownJob.cancel()
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals("old", root.resolve("10000/index.html").readText())
        assertEquals(listOf("10000"), root.list()!!.toList())
    }

    private fun https(block: (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            block(server, client)
        }
    }

    @Test
    fun httpsDownloadsAndInstallsExpectedZip() = https { server, client ->
        runBlocking {
            val bytes = zip("index.html" to "https")
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val installer = PackageInstaller(temporary.newFolder().canonicalFile, client)
            assertSuccess(installer.install(record(bytes), server.url("/v1.zip").toString()))
            assertEquals("https", installer.directory(10000).resolve("index.html").readText())
        }
    }

    @Test
    fun downloadHttpStatusAndInterruptedResponseAreObservable() = https { server, client ->
        runBlocking {
            val bytes = zip("index.html" to "https")
            val root = temporary.newFolder().canonicalFile
            val installer = PackageInstaller(root, client)
            server.enqueue(MockResponse().setResponseCode(503))
            val failed = assertFailure(
                installer.install(record(bytes), server.url("/v1.zip").toString()), FailureReason.DOWNLOAD
            )
            assertEquals(503, failed.httpStatus)
            assertEquals(InstallStage.DOWNLOAD, failed.stage)
            assertEquals("离线包下载失败，HTTP 状态码：503", failed.message)
            assertNotNull(failed.cause)
            server.enqueue(
                MockResponse().setBody(Buffer().write(bytes))
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
            val interrupted = assertFailure(
                installer.install(record(bytes), server.url("/v1.zip").toString()), FailureReason.DOWNLOAD
            )
            assertTrue(interrupted.cause is IOException)
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test
    fun cancelledHttpsRequestClosesItsCallAndCleansTemps() = https { server, client ->
        runBlocking {
            val bytes = zip("index.html" to "https")
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val root = temporary.newFolder().canonicalFile
            val task = launch(kotlinx.coroutines.Dispatchers.IO) {
                PackageInstaller(root, client).install(record(bytes), server.url("/v1.zip").toString())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test
    fun remoteProgressUsesActualBytesAndSeparatesExtraction() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = zip("dist/index.html" to "remote", "dist/app.js" to "x".repeat(96 * 1024))
            for (chunked in listOf(false, true)) {
                server.enqueue(
                    if (chunked) MockResponse().setChunkedBody(Buffer().write(bytes), 17)
                    else MockResponse().setBody(Buffer().write(bytes))
                )
                val root = temporary.newFolder().canonicalFile
                val installer = PackageInstaller(root)
                val downloads = mutableListOf<Pair<Long, Long?>>()
                val extracts = mutableListOf<Int>()
                assertSuccess(
                    installer.install(
                        record(bytes, 100000), server.url("/package.zip").toString(),
                        onDownloadProgress = { downloaded, total ->
                            downloads += downloaded to total
                            assertTrue(extracts.isEmpty())
                            assertFalse(installer.directory(100000).exists())
                        },
                        onExtractProgress = {
                            extracts += it
                            assertEquals(bytes.size.toLong(), downloads.last().first)
                            assertFalse(installer.directory(100000).exists())
                        }
                    )
                )
                assertEquals(0L, downloads.first().first)
                assertEquals(bytes.size.toLong(), downloads.last().first)
                assertTrue(downloads.zipWithNext().all { (a, b) -> a.first < b.first })
                assertTrue(downloads.all { it.second == if (chunked) null else bytes.size.toLong() })
                assertEquals(0, extracts.first())
                assertEquals(99, extracts.last())
                assertEquals("remote", installer.directory(100000).resolve("index.html").readText())
            }
        }
    }

    @Test
    fun wholeDownloadTimeoutStopsTrickleAndKeepsCurrentAndOtherClientCalls() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = temporary.newFolder().canonicalFile
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            val installer = PackageInstaller(root, client, downloadTimeoutMillis = 250L)
            val old = zip("index.html" to "old")
            val bytes = zip("index.html" to "new")
            assertSuccess(installer.install(record(old, 100000)) { old.inputStream() })
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(1, 50, TimeUnit.MILLISECONDS))
            val start = System.nanoTime()
            val failure = assertFailure(
                installer.install(record(bytes, 100001), server.url("/slow.zip").toString()),
                FailureReason.DOWNLOAD_TIMEOUT
            )
            assertEquals("离线包下载超时", failure.message)
            assertTrue(failure.cause is java.io.InterruptedIOException)
            assertEquals(InstallStage.DOWNLOAD, failure.stage)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
            assertEquals(listOf("100000"), root.list()!!.toList())
            assertEquals("old", installer.directory(100000).resolve("index.html").readText())
            assertEquals(0, client.callTimeoutMillis)
            server.enqueue(MockResponse().setBody("still available"))
            client.newCall(okhttp3.Request.Builder().url(server.url("/other")).build()).execute().use {
                assertEquals("still available", it.body!!.string())
            }
        }
    }

    @Test
    fun safeRepairRemovesOnlySpecifiedResidueAndReinstallsSameRecord() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val old = zip("index.html" to "old")
        val bytes = zip("index.html" to "restored")
        assertSuccess(installer.install(record(old, 10000)) { old.inputStream() })
        root.resolve("100000").mkdir()
        root.resolve("100000/partial.js").writeText("partial")
        assertTrue(installer.discardUnboundVersion(100000))
        assertEquals("old", installer.directory(10000).resolve("index.html").readText())
        assertSuccess(installer.install(record(bytes, 100000)) { bytes.inputStream() })
        assertEquals("restored", installer.directory(100000).resolve("index.html").readText())
        assertEquals(setOf("10000", "100000"), root.list()!!.toSet())
    }

    @Test
    fun emptyCurrentEntryDoesNotAuthorizeDeletingOtherVersions() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        root.resolve("100000").mkdir()
        root.resolve("100000/index.html").writeText("")
        root.resolve("10000").mkdir()
        root.resolve("10000/index.html").writeText("keep")
        assertFalse(PackageInstaller(root).clearOldVersions(100000))
        assertEquals("keep", root.resolve("10000/index.html").readText())
    }
}
