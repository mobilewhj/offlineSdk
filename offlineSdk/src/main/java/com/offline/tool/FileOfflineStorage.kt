package com.offline.tool

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private val defaultStorageKeys = OfflineStorageKeys("active.json", "enabled", "history.json", "cache-dirty")

/** 固定四个状态值，主进程单例使用；不提供多进程数据库或跨键事务保证。 */
internal class FileManagedOfflineStorage(private val values: FileOfflineValues) :
    ManagedOfflineStorage by KeyValueOfflineStorage(values, defaultStorageKeys, { null }),
    InstallationRootBoundStorage {
    override fun validateInstallationRoot(root: File) {
        val state = values.directory.canonicalFile
        val installation = root.canonicalFile
        require(!containsDirectory(state, installation) && !containsDirectory(installation, state)) {
            "Offline state directory and installation root must not overlap"
        }
    }

    private fun containsDirectory(parent: File, child: File): Boolean =
        parent == child || child.path.startsWith(parent.path.trimEnd(File.separatorChar) + File.separator)
}

/** 仅隔离 API24 的文件系统调用，JVM 验证可注入可观察的提交故障；不承接任何 SDK 策略。 */
internal interface OfflineFileCommit {
    fun exists(file: File): Boolean
    fun syncFile(output: FileOutputStream)
    fun replace(temporary: File, target: File)
    fun syncDirectory(directory: File)
}

internal object AndroidOfflineFileCommit : OfflineFileCommit {
    override fun exists(file: File): Boolean = try {
        Os.stat(file.path)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw IOException("Cannot inspect offline state", error)
    }

    override fun syncFile(output: FileOutputStream) = output.fd.sync()

    override fun replace(temporary: File, target: File) = Os.rename(temporary.path, target.path)

    override fun syncDirectory(directory: File) {
        // API24 使用公开的只读目录打开方式；不依赖隐藏 O_DIRECTORY 常量，fsync 失败仍向上报告。
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}

/** 目录解析推迟到首次 IO；读错不折叠为缺键，写入逐值同步提交并返回可观察失败。 */
internal class FileOfflineValues(
    directory: () -> File,
    private val commit: OfflineFileCommit = AndroidOfflineFileCommit,
) : OfflineKeyValueStore {
    val directory: File by lazy(directory)

    override fun readString(key: String): String? {
        val target = File(directory, key)
        if (!commit.exists(target)) return null
        return target.readText(Charsets.UTF_8)
    }

    override fun readBoolean(key: String): Boolean? = when (readString(key)) {
        null -> null
        "true" -> true
        "false" -> false
        else -> throw IOException("Invalid stored boolean")
    }

    override fun writeBoolean(key: String, value: Boolean): Boolean = writeString(key, value.toString())

    override fun writeString(key: String, value: String?): Boolean {
        var temporary: File? = null
        try {
            val target = File(directory, key)
            if (value == null) {
                if (!commit.exists(directory)) return true
                ensureDirectory()
                if (commit.exists(target) && !target.delete()) return false
                // 上次删除可能已完成但目录同步失败；目标缺失也必须重新确认提交。
                commit.syncDirectory(directory)
                return true
            }
            ensureDirectory()
            temporary = File(directory, "$key.tmp")
            FileOutputStream(temporary).use {
                it.write(value.toByteArray(Charsets.UTF_8))
                it.flush()
                commit.syncFile(it)
            }
            commit.replace(temporary, target)
            commit.syncDirectory(directory)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            // 仅清本次临时文件；提交或目录同步失败不会启动补写。
            temporary?.delete()
        }
    }

    private fun ensureDirectory() {
        val missing = mutableListOf<File>()
        var current: File? = directory
        while (current != null && !commit.exists(current)) {
            missing += current
            current = current.parentFile
        }
        if (current == null || !current.isDirectory) throw IOException("Invalid state directory parent")
        for (created in missing.asReversed()) {
            if (!created.mkdir()) throw IOException("Cannot create state directory")
        }
        if (!directory.isDirectory) throw IOException("Invalid state directory")
        val parent = checkNotNull(directory.parentFile)
        // mkdir 后同步失败仍会留下目录；每次自然写入都确认固定两级父路径。
        // File 工厂基目录缺失时，也保留本次新建祖先的父目录确认，不遍历已有祖先。
        val parents = missing.asReversed().map { checkNotNull(it.parentFile) } +
            listOfNotNull(parent.parentFile, parent)
        for (confirmation in parents.distinct()) commit.syncDirectory(confirmation)
    }
}
