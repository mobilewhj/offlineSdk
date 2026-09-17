package com.offline.demo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PackageArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun tooManyEntriesAreRejectedBeforeExtractingWithAndWithoutProgress() = runBlocking {
        val zip = temporary.newFile("package.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { archive ->
            archive.putNextEntry(ZipEntry("index.html"))
            archive.write("local".toByteArray())
            archive.closeEntry()
            repeat(10_000) { index ->
                archive.putNextEntry(ZipEntry("static/$index.js"))
                archive.closeEntry()
            }
        }
        for (withProgress in listOf(false, true)) {
            val temp = temporary.newFolder().resolve("extract")
            val progress = mutableListOf<Int>()
            val callback: ((Int) -> Unit)? = if (withProgress) ({ progress += it }) else null
            val failure = try {
                PackageArchive.extract(zip, temp, callback)
                error("应拒绝条目数量超限的 ZIP")
            } catch (error: InstallException) {
                error
            }
            assertEquals(FailureReason.SIZE_LIMIT, failure.reason)
            assertTrue("超限 ZIP 不应写入任何条目", temp.list()!!.isEmpty())
            assertTrue("超限 ZIP 不应开始报告解压进度", progress.isEmpty())
        }
    }

    @Test
    fun corruptLocalHeaderIsRejectedWithoutPublishingWithAndWithoutProgress() = runBlocking {
        val zip = temporary.newFile("corrupt_local.zip")
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { archive ->
                archive.putNextEntry(ZipEntry("index.html"))
                archive.write("hello".toByteArray())
                archive.closeEntry()
                archive.putNextEntry(ZipEntry("app.js"))
                archive.write("world".toByteArray())
                archive.closeEntry()
            }
        }.toByteArray()

        // 定位第二个本地文件头，仅破坏它，保留中央目录和第一个入口文件。
        var secondHeaderIndex = -1
        var count = 0
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte()
            ) {
                count++
                if (count == 2) {
                    secondHeaderIndex = i
                    break
                }
            }
        }
        assertTrue(secondHeaderIndex != -1)
        bytes[secondHeaderIndex] = 0x00

        zip.writeBytes(bytes)
        ZipFile(zip).use { assertEquals(2, it.size()) }

        for (withProgress in listOf(false, true)) {
            val root = temporary.newFolder().canonicalFile
            val installer = PackageInstaller(root)
            val result = if (withProgress) {
                installer.installBuiltin(10000, onExtractProgress = {}) { zip.inputStream() }
            } else {
                installer.installBuiltin(10000) { zip.inputStream() }
            }
            assertTrue("损坏 ZIP 应安装失败，实际结果：$result", result is InstallResult.Failure)
            val failure = result as InstallResult.Failure
            assertEquals(FailureReason.INVALID_ARCHIVE, failure.reason)
            assertEquals(InstallStage.EXTRACT, failure.stage)
            assertFalse("不得发布缺少资源的版本", installer.directory(10000).exists())
            assertTrue("失败后应清理 ZIP 和解压临时目录", root.list()!!.isEmpty())
        }
    }
}
