package com.offline.tool

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageInstallerFactsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun zip(content: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { archive ->
            archive.putNextEntry(ZipEntry("index.html"))
            archive.write(content.toByteArray())
            archive.closeEntry()
        }
    }.toByteArray()

    private fun record(bytes: ByteArray, version: Int = 10000) = PackageRecord(
        version,
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    private fun failure(result: InstallResult): InstallResult.Failure {
        assertTrue("Expected failure, got $result", result is InstallResult.Failure)
        return result as InstallResult.Failure
    }

    @Test
    fun requestStartedIsPerCallAndBeginsBeforeAnyResponseOrProgress() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = temporary.newFolder().canonicalFile
            val installer = PackageInstaller(root, downloadTimeoutMillis = 300L)
            val bytes = zip("remote")
            val expected = record(bytes)
            val url = server.url("/package.zip").toString()

            assertFalse(failure(installer.install(expected, "file:///package.zip")).requestStarted)
            assertFalse(failure(installer.install(expected.copy(version = 9999), url)).requestStarted)
            assertEquals(0, server.requestCount)

            root.resolve("10000").mkdir()
            root.resolve("10000/index.html").writeText("existing")
            val existing = failure(installer.install(expected, url))
            assertEquals(FailureReason.TARGET_EXISTS, existing.reason)
            assertFalse(existing.requestStarted)
            assertNull(existing.publishedRecord)
            assertEquals("existing", root.resolve("10000/index.html").readText())
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(503))
            val httpFailure = failure(installer.install(expected.copy(version = 10001), url))
            assertEquals(503, httpFailure.httpStatus)
            assertTrue(httpFailure.requestStarted)
            assertNull(httpFailure.publishedRecord)

            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val success = installer.install(expected.copy(version = 10001), url)
            assertTrue(success is InstallResult.Success)
            assertTrue((success as InstallResult.Success).requestStarted)

            // 同一安装器前次请求已开始，也不能污染后续请求前失败的事实。
            assertFalse(failure(installer.install(expected.copy(version = 10001), url)).requestStarted)
            assertEquals(2, server.requestCount)

            var progressCalled = false
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val timeout = failure(installer.install(
                expected.copy(version = 10002), url,
                onDownloadProgress = { _, _ -> progressCalled = true },
            ))
            assertEquals(FailureReason.DOWNLOAD_TIMEOUT, timeout.reason)
            assertTrue(timeout.requestStarted)
            assertFalse(progressCalled)
            assertNull(timeout.publishedRecord)

            val local = installer.install(expected.copy(version = 10003)) { bytes.inputStream() }
            assertTrue(local is InstallResult.Success)
            assertFalse((local as InstallResult.Success).requestStarted)
            val builtin = installer.installBuiltin(10004) { bytes.inputStream() }
            assertTrue(builtin is InstallResult.Success)
            assertFalse((builtin as InstallResult.Success).requestStarted)
            assertFalse(failure(installer.installBuiltin(10005) { "bad".byteInputStream() }).requestStarted)
        }
    }

    @Test
    fun failedVerificationExtractionAndPublicationNeverClaimPublication() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val installer = PackageInstaller(root)
        val bytes = zip("verified")
        val badHash = failure(installer.install(record(bytes).copy(sha256 = "0".repeat(64))) {
            bytes.inputStream()
        })
        assertEquals(InstallStage.VERIFY, badHash.stage)
        assertNull(badHash.publishedRecord)

        val invalid = "not a ZIP".toByteArray()
        val extract = failure(installer.install(record(invalid)) { invalid.inputStream() })
        assertEquals(InstallStage.EXTRACT, extract.stage)
        assertNull(extract.publishedRecord)

        val publish = failure(installer.installBuiltin(10001, onExtractProgress = { percent ->
            if (percent == 99) {
                root.resolve("10001").mkdir()
                root.resolve("10001/index.html").writeText("other")
            }
        }) { bytes.inputStream() })
        assertEquals(FailureReason.TARGET_EXISTS, publish.reason)
        assertEquals(InstallStage.PUBLISH, publish.stage)
        assertNull(publish.publishedRecord)
        assertEquals("other", root.resolve("10001/index.html").readText())
    }

    @Test
    fun entryCheckIsReadOnlyAndRejectsMissingEmptyAndLinkedPaths() = runBlocking {
        val root = temporary.root.resolve("offline")
        val installer = PackageInstaller(root)
        assertFalse(installer.isUsable(9999))
        assertFalse(installer.isUsable(10000))
        assertFalse(root.exists())

        val installed = root.resolve("10000").also { assertTrue(it.mkdirs()) }
        val keep = root.resolve("keep.txt").also { it.writeText("keep") }
        assertFalse(installer.isUsable(10000))
        val entry = installed.resolve("index.html")
        entry.writeText("")
        assertFalse(installer.isUsable(10000))
        entry.writeText("ready")
        assertTrue(installer.isUsable(10000))
        assertEquals("ready", entry.readText())

        val external = temporary.newFolder("external").canonicalFile
        external.resolve("index.html").writeText("outside")
        val linkedDirectory = root.resolve("10001")
        Files.createSymbolicLink(linkedDirectory.toPath(), external.toPath())
        assertFalse(installer.isUsable(10001))
        val linkedEntryDirectory = root.resolve("10002").also { it.mkdir() }
        Files.createSymbolicLink(
            linkedEntryDirectory.resolve("index.html").toPath(), external.resolve("index.html").toPath()
        )
        assertFalse(installer.isUsable(10002))
        assertEquals("outside", external.resolve("index.html").readText())
        assertEquals("keep", keep.readText())
        assertEquals(setOf("10000", "10001", "10002", "keep.txt"), root.list()!!.toSet())

        // 指定不安全的当前目录不能授权清理其他目录。
        assertFalse(installer.clearOldVersions(10002))
        assertEquals("ready", entry.readText())
        assertEquals("keep", keep.readText())

        val linkedRoot = temporary.root.resolve("linked-root")
        Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
        assertFalse(PackageInstaller(linkedRoot).isUsable(10000))
        assertTrue(entry.exists())
    }

    @Test
    fun unreadableEntryReturnsFalseWithoutChangingFiles() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        val entry = root.resolve("10000/index.html")
        assertTrue(entry.parentFile!!.mkdir())
        entry.writeText("ready")
        val path = entry.toPath()
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView("posix"))
        val original = Files.getPosixFilePermissions(path)
        try {
            Files.setPosixFilePermissions(path, original - setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ))
            assumeFalse("This environment bypasses file read permissions", Files.isReadable(path))
            assertFalse(PackageInstaller(root).isUsable(10000))
            assertEquals(setOf("10000"), root.list()!!.toSet())
            assertEquals(setOf("index.html"), entry.parentFile!!.list()!!.toSet())
        } finally {
            Files.setPosixFilePermissions(path, original)
        }
        assertEquals("ready", entry.readText())
    }
}
