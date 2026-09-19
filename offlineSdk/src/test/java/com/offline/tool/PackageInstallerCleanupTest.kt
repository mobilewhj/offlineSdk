package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageInstallerCleanupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun sourceFailureKeepsMetadataAndBothCleanupErrors() = runBlocking {
        withCleanupBlocked { root, blockCleanup ->
            val cause = IOException("原始读取异常")
            val result = PackageInstaller(root).installBuiltin(10000) {
                blockCleanup()
                throw InstallException(FailureReason.DOWNLOAD, "原始下载说明", cause, status = 503)
            }

            assertTrue(result is InstallResult.Failure)
            result as InstallResult.Failure
            assertEquals(FailureReason.DOWNLOAD, result.reason)
            assertEquals(InstallStage.DOWNLOAD, result.stage)
            assertEquals("原始下载说明", result.message)
            assertEquals(503, result.httpStatus)
            assertSame(cause, result.cause)
            assertBothCleanupErrors(cause)
        }
    }

    @Test
    fun cancellationDuringReadKeepsBothCleanupErrors() = runBlocking {
        withCleanupBlocked { root, blockCleanup ->
            val cancellation = CancellationException("取消安装")
            val observed = CompletableDeferred<CancellationException>()
            val task = launch {
                val ownJob = coroutineContext[Job]!!
                try {
                    PackageInstaller(root).installBuiltin(10000) {
                        blockCleanup()
                        ownJob.cancel(cancellation)
                        throw IOException("请求被取消后读取失败")
                    }
                    fail("Cancellation must propagate")
                } catch (error: CancellationException) {
                    observed.complete(error)
                    throw error
                }
            }
            task.join()

            assertTrue(task.isCancelled)
            assertPropagatedOriginal(observed.await(), cancellation)
        }
    }

    @Test
    fun unexpectedSourceExceptionAlsoKeepsBothCleanupErrors() = runBlocking {
        withCleanupBlocked { root, blockCleanup ->
            val original = IllegalStateException("调用方输入流工厂失败")
            try {
                PackageInstaller(root).installBuiltin(10000) {
                    blockCleanup()
                    throw original
                }
                fail("Caller exception must propagate")
            } catch (error: IllegalStateException) {
                assertPropagatedOriginal(error, original)
            }
        }
    }

    @Test
    fun publishedPackageStillReportsResidualCleanupFailure() = runBlocking {
        val root = temporary.newFolder().canonicalFile
        assumeTrue(
            "POSIX permissions are required", Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")
        )
        val residue = root.resolve("10000_temp/residue")
        val remainingFile = residue.resolve("keep.txt")
        var permissions: Set<PosixFilePermission>? = null
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { archive ->
                for ((path, content) in listOf("dist/index.html" to "published", "residue/keep.txt" to "keep")) {
                    archive.putNextEntry(ZipEntry(path))
                    archive.write(content.toByteArray())
                    archive.closeEntry()
                }
            }
        }.toByteArray()

        try {
            val result = PackageInstaller(root).installBuiltin(
                10000,
                onExtractProgress = {
                    // 写入残留内容后只限制其父目录，dist 仍可正常发布到版本目录。
                    if (permissions == null && remainingFile.length() > 0L) {
                        val original = Files.getPosixFilePermissions(residue.toPath())
                        permissions = original
                        Files.setPosixFilePermissions(
                            residue.toPath(),
                            original - setOf(
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_WRITE,
                                PosixFilePermission.OTHERS_WRITE,
                            )
                        )
                        assumeFalse(
                            "This environment bypasses directory write permissions", Files.isWritable(residue.toPath())
                        )
                    }
                }
            ) { bytes.inputStream() }

            assertNotNull("The extraction callback must restrict the residual directory", permissions)
            assertTrue(result is InstallResult.Failure)
            result as InstallResult.Failure
            assertEquals(FailureReason.CLEANUP, result.reason)
            assertEquals(InstallStage.CLEANUP, result.stage)
            assertTrue(result.cause is InstallException)
            assertTrue(result.message!!.contains("residue/keep.txt"))
            assertEquals("published", root.resolve("10000/index.html").readText())
            assertFalse(root.resolve("10000.zip.tmp").exists())
            assertEquals("keep", remainingFile.readText())
        } finally {
            permissions?.let { if (residue.exists()) Files.setPosixFilePermissions(residue.toPath(), it) }
        }
    }

    /** 临时目录只读会阻止删除两个直接子项；恢复权限后由 JUnit 清理。无生产测试注入点。 */
    private suspend fun withCleanupBlocked(block: suspend (File, () -> Unit) -> Unit) {
        val root = temporary.newFolder().canonicalFile
        val path = root.toPath()
        assumeTrue("POSIX permissions are required", Files.getFileStore(path).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(path)
        try {
            block(root) {
                assertTrue(root.resolve("10000_temp").mkdir())
                Files.setPosixFilePermissions(
                    path,
                    permissions - setOf(
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_WRITE,
                    )
                )
                assumeFalse("This environment bypasses directory write permissions", Files.isWritable(path))
            }
        } finally {
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private fun assertPropagatedOriginal(propagated: Throwable, original: Throwable) {
        // 协程恢复堆栈可能复制异常；实际传播的因果链必须仍保留原对象及其清理错误。
        val retained = generateSequence(propagated) { it.cause }.firstOrNull { it === original }
        assertSame("The propagated cause chain must retain the original exception", original, retained)
        assertEquals(original.message, propagated.message)
        assertBothCleanupErrors(checkNotNull(retained))
    }

    private fun assertBothCleanupErrors(error: Throwable) {
        assertEquals(2, error.suppressed.size)
        assertTrue(error.suppressed.all { it is InstallException && it.reason == FailureReason.CLEANUP })
        assertTrue(error.suppressed[0].message!!.contains("10000.zip.tmp"))
        assertTrue(error.suppressed[1].message!!.contains("10000_temp"))
    }
}
