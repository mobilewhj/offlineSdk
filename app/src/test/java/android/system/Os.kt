package android.system

import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** 公开默认工厂的局部 JVM 平台夹具：真实文件检查、原子替换和目录 force。
 * 仅 app/src/test 生效，不改 SDK 生产实现，不替代 Android Os/API24 或设备验收。 */
object Os {
    private val channels = ConcurrentHashMap<FileDescriptor, FileChannel>()

    @JvmStatic
    @Throws(ErrnoException::class)
    fun stat(path: String): StructStat = try {
        Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java)
        StructStat()
    } catch (missing: NoSuchFileException) {
        throw ErrnoException("stat", OsConstants.ENOENT, missing)
    } catch (failed: IOException) {
        throw ErrnoException("stat", OsConstants.EIO, failed)
    }

    @JvmStatic
    @Throws(ErrnoException::class)
    fun rename(source: String, target: String) {
        try {
            val from = Paths.get(source)
            val to = Paths.get(target)
            if (from.parent != to.parent) throw IOException("Fixture requires same-directory rename")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failed: IOException) {
            throw ErrnoException("rename", OsConstants.EIO, failed)
        }
    }

    @JvmStatic
    @Throws(ErrnoException::class)
    fun open(path: String, flags: Int, mode: Int): FileDescriptor {
        try {
            if (flags != OsConstants.O_RDONLY || mode != 0) {
                throw IOException("Fixture only opens directories read-only")
            }
            val directory = Paths.get(path)
            if (!Files.readAttributes(directory, BasicFileAttributes::class.java).isDirectory) {
                throw IOException("Fixture expected a directory")
            }
            val channel = FileChannel.open(directory, StandardOpenOption.READ)
            return FileDescriptor().also { channels[it] = channel }
        } catch (failed: IOException) {
            throw ErrnoException("open", OsConstants.EIO, failed)
        }
    }

    @JvmStatic
    @Throws(ErrnoException::class)
    fun fsync(descriptor: FileDescriptor) {
        try {
            val channel = channels[descriptor] ?: throw IOException("Unknown fixture descriptor")
            channel.force(true)
        } catch (failed: IOException) {
            throw ErrnoException("fsync", OsConstants.EIO, failed)
        }
    }

    @JvmStatic
    @Throws(ErrnoException::class)
    fun close(descriptor: FileDescriptor) {
        try {
            val channel = channels.remove(descriptor) ?: throw IOException("Unknown fixture descriptor")
            channel.close()
        } catch (failed: IOException) {
            throw ErrnoException("close", OsConstants.EIO, failed)
        }
    }
}
