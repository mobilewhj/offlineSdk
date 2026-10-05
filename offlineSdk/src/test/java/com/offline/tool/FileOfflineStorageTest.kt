package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

@OptIn(ExperimentalCoroutinesApi::class)
class FileOfflineStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    /** JVM 仅替换 Android 系统调用；数据仍通过真实文件、fd.sync 与目录 force 提交。 */
    private open class JvmCommit : OfflineFileCommit {
        val directorySyncAttempts = mutableListOf<File>()
        var failedDirectory: File? = null
        override fun exists(file: File): Boolean = file.exists()
        override fun syncFile(output: FileOutputStream) = output.fd.sync()
        override fun replace(temporary: File, target: File) {
            if (!temporary.renameTo(target)) throw IOException("rename failed")
        }
        override fun syncDirectory(directory: File) {
            directorySyncAttempts += directory
            if (directory == failedDirectory) throw IOException("directory sync failed")
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun storage(directory: File, commit: OfflineFileCommit = JvmCommit()) =
        FileManagedOfflineStorage(FileOfflineValues({ directory }, commit))

    @Test fun constructionDoesNotResolveDirectoryOrReadFiles() {
        var directoryReads = 0
        val state = File(temporary.root, "new/state")
        val values = FileOfflineValues({ directoryReads++; state }, JvmCommit())
        FileManagedOfflineStorage(values)
        assertEquals(0, directoryReads)
        assertFalse(state.exists())
        ManagedOfflineStorage.default(temporary.root, "stable-main")
        assertFalse(File(temporary.root, "offline-sdk-state").exists())
    }

    @Test fun committedValuesReadBackFromNewStorageInstance() = runBlocking {
        val state = File(temporary.root, "offline-sdk-state/main")
        val confirmations = listOf(temporary.root, state.parentFile, state)
        val commit = JvmCommit()
        val first = storage(state, commit)
        val record = PackageRecord(100001, "a".repeat(64))
        val history = PreparationHistory(true, ManagedFailure(ManagedFailureReason.CONFIG_REQUEST,
            ManagedStage.CONFIG, occurredAtMillis = 50))
        assertNull(first.readActive()); assertNull(first.readCacheDirty())
        assertTrue(first.writeActive(record)); assertDirectorySyncs(commit, confirmations)
        assertTrue(first.writeEnabled(false)); assertDirectorySyncs(commit, confirmations)
        assertTrue(first.writeHistory(history)); assertDirectorySyncs(commit, confirmations)
        assertTrue(first.writeCacheDirty(false)); assertDirectorySyncs(commit, confirmations)
        val cold = storage(state, commit)
        assertEquals(record, cold.readActive()); assertEquals(false, cold.readEnabled())
        assertEquals(history, cold.readHistory()); assertEquals(false, cold.readCacheDirty())
        assertDirectorySyncs(commit, emptyList())
        val replacement = PackageRecord(100002, "b".repeat(64))
        assertTrue(cold.writeActive(replacement)); assertDirectorySyncs(commit, confirmations)
        assertEquals(replacement, storage(state).readActive())
        assertTrue(cold.writeActive(null)); assertDirectorySyncs(commit, confirmations)
        assertNull(storage(state).readActive())
        assertTrue(storage(state, commit).writeActive(null)); assertDirectorySyncs(commit, confirmations)
        assertFalse(state.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun failedDeletionConfirmationRetriesAbsentTargetAcrossStorageInstances() = runBlocking {
        val noBackup = temporary.newFolder()
        val state = File(noBackup, "offline-sdk-state/main")
        val confirmations = listOf(noBackup, state.parentFile, state)
        assertTrue(storage(state).writeActive(PackageRecord(100001, "a".repeat(64))))
        val commit = JvmCommit().apply { failedDirectory = state }
        val first = storage(state, commit)
        // unlink 已发生，但最后目录确认仍失败；缺文件不能代替提交确认。
        assertFalse(first.writeActive(null)); assertDirectorySyncs(commit, confirmations)
        assertNull(storage(state).readActive())
        assertFalse(first.writeActive(null)); assertDirectorySyncs(commit, confirmations)
        assertFalse(storage(state, commit).writeActive(null)); assertDirectorySyncs(commit, confirmations)
        commit.failedDirectory = null
        assertTrue(first.writeActive(null)); assertDirectorySyncs(commit, confirmations)
        assertTrue(storage(state, commit).writeActive(null)); assertDirectorySyncs(commit, confirmations)
        assertNull(storage(state).readActive())
    }

    @Test fun failedNamespaceParentConfirmationRetriesExistingPathAndNewInstance() = runBlocking {
        assertParentConfirmationRetries(failStateRootParent = false)
    }

    @Test fun failedStateRootParentConfirmationRetriesExistingPathAndNewInstance() = runBlocking {
        assertParentConfirmationRetries(failStateRootParent = true)
    }

    private suspend fun assertParentConfirmationRetries(failStateRootParent: Boolean) {
        val noBackup = temporary.newFolder()
        val state = File(noBackup, "offline-sdk-state/main")
        val stateRoot = state.parentFile
        val fault = if (failStateRootParent) noBackup else stateRoot
        val failedConfirmations = if (failStateRootParent) listOf(noBackup) else listOf(noBackup, stateRoot)
        val confirmations = listOf(noBackup, stateRoot, state)
        val commit = JvmCommit().apply { failedDirectory = fault }
        val first = storage(state, commit)
        val history = PreparationHistory(true)
        assertFalse(first.writeHistory(history)); assertDirectorySyncs(commit, failedConfirmations)
        assertTrue(state.isDirectory)
        assertFalse(File(state, "history.json").exists())
        assertFalse(first.writeHistory(history)); assertDirectorySyncs(commit, failedConfirmations)
        assertFalse(storage(state, commit).writeHistory(history)); assertDirectorySyncs(commit, failedConfirmations)
        assertFalse(File(state, "history.json").exists())
        commit.failedDirectory = null
        assertTrue(first.writeHistory(history)); assertDirectorySyncs(commit, confirmations)
        assertEquals(history, storage(state).readHistory())
        val replacement = PreparationHistory(false)
        assertTrue(storage(state, commit).writeHistory(replacement)); assertDirectorySyncs(commit, confirmations)
        assertEquals(replacement, storage(state).readHistory())
    }

    @Test fun missingNoBackupBaseConfirmsOnlyNewAncestorsAndFixedParents() = runBlocking {
        val base = temporary.newFolder()
        val newParent = File(base, "missing")
        val noBackup = File(newParent, "no-backup")
        val state = File(noBackup, "offline-sdk-state/main")
        val commit = JvmCommit()
        assertFalse(noBackup.exists())
        assertTrue(storage(state, commit).writeEnabled(true))
        assertDirectorySyncs(commit, listOf(base, newParent, noBackup, state.parentFile, state))
        assertEquals(true, storage(state).readEnabled())
    }

    @Test fun cancellationDuringParentConfirmationPropagatesBeforeValueCommit() = runBlocking {
        val noBackup = temporary.newFolder()
        val state = File(noBackup, "offline-sdk-state/main")
        val commit = object : JvmCommit() {
            override fun syncDirectory(directory: File) {
                if (directory == state.parentFile) {
                    directorySyncAttempts += directory
                    throw CancellationException("cancel directory sync")
                }
                super.syncDirectory(directory)
            }
        }
        try {
            storage(state, commit).writeHistory(PreparationHistory(true))
            throw AssertionError("父目录确认取消必须传播")
        } catch (_: CancellationException) { }
        assertDirectorySyncs(commit, listOf(noBackup, state.parentFile))
        assertFalse(File(state, "history.json").exists())
        assertFalse(File(state, "history.json.tmp").exists())
    }

    private fun assertDirectorySyncs(commit: JvmCommit, expected: List<File>) {
        assertEquals(expected, commit.directorySyncAttempts)
        commit.directorySyncAttempts.clear()
    }

    @Test fun fileSyncAndRenameFailuresKeepPreviousRecordAndReturnFalse() = runBlocking {
        val state = temporary.newFolder()
        val old = PackageRecord(100001, "a".repeat(64))
        assertTrue(storage(state).writeActive(old))
        val fileFailure = object : JvmCommit() {
            override fun syncFile(output: FileOutputStream) { throw IOException("sync failed") }
        }
        assertFalse(storage(state, fileFailure).writeActive(PackageRecord(100002, "b".repeat(64))))
        assertEquals(old, storage(state).readActive())
        val renameFailure = object : JvmCommit() {
            override fun replace(temporary: File, target: File) { throw IOException("rename failed") }
        }
        assertFalse(storage(state, renameFailure).writeActive(PackageRecord(100002, "b".repeat(64))))
        assertEquals(old, storage(state).readActive())
        assertFalse(state.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun failedDirectoryConfirmationAndDeleteNeverReportSuccess() = runBlocking {
        val state = temporary.newFolder()
        val failed = object : JvmCommit() {
            override fun syncDirectory(directory: File) { throw IOException("directory sync failed") }
        }
        assertFalse(storage(state, failed).writeCacheDirty(false))
        val active = File(state, "active.json").apply { mkdir() }
        File(active, "child").writeText("preserved")
        assertFalse(storage(state).writeActive(null))
        assertTrue(File(active, "child").exists())
        active.deleteRecursively()
        assertTrue(storage(state).writeActive(PackageRecord(100001, "a".repeat(64))))
        assertFalse(storage(state, failed).writeActive(null))
    }

    @Test fun readFaultAndCorruptBooleanDoNotLookLikeMissingValues() = runBlocking {
        val state = temporary.newFolder()
        File(state, "enabled").writeText("TRUE")
        try { storage(state).readEnabled(); throw AssertionError("错误Boolean必须失败") } catch (_: IOException) { }
        File(state, "active.json").mkdir()
        try { storage(state).readActive(); throw AssertionError("读取目录不是缺记录") } catch (_: IOException) { }
        val statFailure = object : JvmCommit() {
            override fun exists(file: File): Boolean { throw IOException("permission/stat failed") }
        }
        try { storage(state, statFailure).readHistory(); throw AssertionError("查询失败不是缺记录") } catch (_: IOException) { }
    }

    @Test fun cancellationDuringSyncPropagatesAndRemovesOnlyTemporaryFile() = runBlocking {
        val state = temporary.newFolder()
        val commit = object : JvmCommit() {
            override fun syncFile(output: FileOutputStream) { throw CancellationException("cancel sync") }
        }
        try { storage(state, commit).writeHistory(PreparationHistory(true)); throw AssertionError("取消必须传播") }
        catch (_: CancellationException) { }
        assertFalse(File(state, "history.json").exists())
        assertFalse(File(state, "history.json.tmp").exists())
    }

    @Test fun overlappingDefaultDirectoriesRejectBeforeAnyCleanupOrFileWrite() = runBlocking {
        for (relation in listOf("same", "state-inside-root", "root-inside-state")) {
            val base = temporary.newFolder()
            val state = File(base, "state").apply { mkdir() }
            val root = when (relation) {
                "same" -> state
                "state-inside-root" -> base
                else -> File(state, "packages").apply { mkdir() }
            }
            val sentinel = File(root, "cleanup-sentinel").apply { writeText("must remain") }
            val manager = manager(root, storage(state))
            try {
                try { manager.startupDecision(); throw AssertionError("重叠必须明确拒绝") }
                catch (_: IllegalArgumentException) { }
                assertEquals("must remain", sentinel.readText())
                assertFalse(File(state, "cache-dirty").exists())
            } finally { manager.shutdown() }
        }
    }

    @Test fun canonicalIoFailureReleasesStartupWaitAndPreservesInstallationRoot() = runBlocking {
        val base = temporary.newFolder()
        val state = object : File(base, "offline-sdk-state/main") {
            override fun getCanonicalFile(): File = throw IOException("canonical lookup failed")
        }
        val root = File(base, "packages").apply { mkdir() }
        val sentinel = File(root, "unknown-state-must-preserve").apply { writeText("preserved") }
        val manager = manager(root, storage(state))
        try {
            assertEquals(StartupResult.Continue, manager.prepareStartup())
            val failure = (manager.state.value.activity as ManagedActivity.Failed).failure
            assertEquals(ManagedFailureReason.LOCAL_PREPARATION, failure.reason)
            assertEquals(ManagedStage.LOCAL, failure.stage)
            assertEquals("preserved", sentinel.readText())
            assertFalse(File(base, "offline-sdk-state").exists())
        } finally { manager.shutdown() }
    }

    @Test fun coldCleanupPreservesIndependentDefaultStateDirectory() = runBlocking {
        val base = temporary.newFolder()
        val state = File(base, "offline-sdk-state/main")
        val root = File(base, "packages").apply { mkdir() }
        val sentinel = File(root, "stale").apply { writeText("removed by genuine cleanup") }
        val stored = storage(state)
        assertTrue(stored.writeHistory(PreparationHistory(true)))
        assertTrue(stored.writeCacheDirty(false))
        val before = File(state, "history.json").readBytes()
        val manager = manager(root, stored)
        try {
            assertEquals(StartupDecision.CONTINUE, manager.startupDecision())
            assertFalse(sentinel.exists())
            assertTrue(before.contentEquals(File(state, "history.json").readBytes()))
            assertEquals(PreparationHistory(true), storage(state).readHistory())
        } finally { manager.shutdown() }
    }

    @Test fun corruptedReadKeepsInstallationRootSentinel() = runBlocking {
        val base = temporary.newFolder()
        val state = File(base, "state").apply { mkdir() }
        File(state, "active.json").writeText("null")
        val root = File(base, "packages").apply { mkdir() }
        val sentinel = File(root, "unread-state-must-preserve").apply { writeText("preserved") }
        val manager = manager(root, storage(state))
        try {
            assertEquals(StartupDecision.CONTINUE, manager.startupDecision())
            assertEquals(ManagedFailureReason.STORAGE_READ, (manager.state.value.activity as ManagedActivity.Failed).failure.reason)
            assertEquals("preserved", sentinel.readText())
        } finally { manager.shutdown() }
    }

    @Test fun namespaceRejectsPathSemanticsAtConstructionWithoutCreatingState() {
        for (namespace in listOf("", ".", "..", "../other", "a/b", "a\\b", " main")) {
            try { ManagedOfflineStorage.default(temporary.root, namespace); throw AssertionError("namespace不能是路径") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(File(temporary.root, "offline-sdk-state").exists())
    }

    private fun manager(root: File, storage: ManagedOfflineStorage) = ManagedOfflineSdk.forTest(
        root, storage, ManagedConfigProvider { throw AssertionError("这些测试不请求配置") },
        mainDispatcher = UnconfinedTestDispatcher(), ioDispatcher = Dispatchers.Unconfined,
    )
}
