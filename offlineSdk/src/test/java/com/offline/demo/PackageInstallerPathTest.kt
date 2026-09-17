package com.offline.demo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageInstallerPathTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun installsAndKeepsActiveVersionThroughParentAlias() = runBlocking {
        val (alias, realParent) = parentAlias()
        val installer = PackageInstaller(alias.resolve("offline"))
        val realRoot = realParent.resolve("offline")

        assertTrue(installer.installBuiltin(10000) { zip("old").inputStream() } is InstallResult.Success)
        assertTrue(installer.installBuiltin(10001) { zip("active").inputStream() } is InstallResult.Success)
        assertEquals(realRoot.resolve("10001"), installer.directory(10001).canonicalFile)
        assertEquals("active", installer.directory(10001).resolve("index.html").readText())

        assertTrue(installer.clearOldVersions(10001))
        assertEquals(listOf("10001"), realRoot.list()!!.toList())
        assertEquals("active", realRoot.resolve("10001/index.html").readText())

        assertTrue(installer.clearOldVersions(null))
        assertTrue(realRoot.list()!!.isEmpty())
    }

    @Test
    fun cleanupThroughParentAliasDoesNotFollowLinkedDescendants() = runBlocking {
        val (alias, realParent) = parentAlias()
        val realRoot = realParent.resolve("offline").also { assertTrue(it.mkdir()) }
        val external = temporary.newFolder("external").canonicalFile
        external.resolve("keep.txt").writeText("keep")
        val installer = PackageInstaller(alias.resolve("offline"))

        val residue = realRoot.resolve("10000").also { assertTrue(it.mkdir()) }
        Files.createSymbolicLink(residue.resolve("linked").toPath(), external.toPath())
        assertTrue(installer.discardUnboundVersion(10000))
        assertFalse(residue.exists())
        assertEquals("keep", external.resolve("keep.txt").readText())

        Files.createSymbolicLink(realRoot.resolve("linked").toPath(), external.toPath())
        assertTrue(installer.clearOldVersions(null))
        assertTrue(realRoot.list()!!.isEmpty())
        assertEquals("keep", external.resolve("keep.txt").readText())
    }

    @Test
    fun linkedRootRejectsInstallationAndBothCleanupEntrypoints() = runBlocking {
        val (alias, realParent) = parentAlias()
        val external = temporary.newFolder("external").canonicalFile
        external.resolve("10000").mkdir()
        external.resolve("10000/index.html").writeText("keep")
        Files.createSymbolicLink(realParent.resolve("offline").toPath(), external.toPath())
        val installer = PackageInstaller(alias.resolve("offline"))
        var sourceOpened = false

        val result = installer.installBuiltin(10001) {
            sourceOpened = true
            zip("new").inputStream()
        }
        assertTrue(result is InstallResult.Failure)
        result as InstallResult.Failure
        assertEquals(FailureReason.FILE_IO, result.reason)
        assertEquals(InstallStage.PREPARE, result.stage)
        assertFalse(sourceOpened)
        assertFalse(installer.clearOldVersions(null))
        assertFalse(installer.discardUnboundVersion(10000))
        assertEquals(listOf("10000"), external.list()!!.toList())
        assertEquals("keep", external.resolve("10000/index.html").readText())
    }

    private fun parentAlias(): Pair<File, File> {
        val realParent = temporary.newFolder("real").canonicalFile
        val alias = temporary.root.canonicalFile.resolve("alias")
        Files.createSymbolicLink(alias.toPath(), realParent.toPath())
        return alias to realParent
    }

    private fun zip(html: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { archive ->
            archive.putNextEntry(ZipEntry("index.html"))
            archive.write(html.toByteArray())
            archive.closeEntry()
        }
    }.toByteArray()
}
