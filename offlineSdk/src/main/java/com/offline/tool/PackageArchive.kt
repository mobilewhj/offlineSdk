package com.offline.tool

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.HashingSink
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** ZIP 的读写与校验工具；由安装器在其 IO 调度器上持锁调用，不发布目录或保存版本。 */
internal object PackageArchive {
    /** 关闭输入和输出后返回整包 SHA-256，确保摘要包含缓冲区末尾的数据。 */
    suspend fun write(zip: File, openSource: () -> Source): String {
        val hashingSink = HashingSink.sha256(zip.sink())
        hashingSink.buffer().use { output ->
            openSource().use { input -> copy(input, output, MAX_ARCHIVE_BYTES) }
        }
        return hashingSink.hash.hex()
    }

    /**
     * 解压到尚未发布的 [temp]，返回含有效 index.html 的目录（根目录或 dist）。
     * 进度来自实际写入字节，最多 99；发布和版本保存完成由调用方确认。
     */
    suspend fun extract(zip: File, temp: File, onProgress: ((Int) -> Unit)?): File {
        if (!temp.mkdirs()) throw IOException("无法创建离线包临时解压目录")
        val destinationPrefix = temp.canonicalPath + File.separator
        // 即使不需要进度也检查中央目录，避免同一损坏 ZIP 因回调不同而有不同结果。
        var expectedEntries = 0
        val totalBytes = ZipFile(zip).use { archive ->
            expectedEntries = archive.size()
            if (expectedEntries > MAX_ENTRY_COUNT) {
                throw InstallException(FailureReason.SIZE_LIMIT, "离线包 ZIP 条目数量超出限制")
            }
            if (onProgress == null) 0L else {
                archive.entries().asSequence()
                    .sumOf { it.size.coerceAtLeast(0L) }
            }
        }
        var expanded = 0L
        var entries = 0
        var reported = 0
        val seen = mutableSetOf<String>()
        onProgress?.invoke(0)
        val reportBytes: (Long) -> Unit = { copied ->
            if (onProgress != null && totalBytes > 0) {
                val percent = ((expanded + copied) * 100 / totalBytes).coerceIn(0, 99).toInt()
                if (percent > reported) {
                    reported = percent
                    onProgress(percent)
                }
            }
        }
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            // Source 不预读下一个条目，也不逐条关闭；整个 ZIP 流由外层 use 统一释放。
            val source = input.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = input.nextEntry ?: break
                if (++entries > MAX_ENTRY_COUNT) {
                    throw InstallException(FailureReason.SIZE_LIMIT, "离线包 ZIP 条目数量超出限制")
                }
                val path = entry.name.removeSuffix("/")
                if (path.isEmpty() || path.startsWith('/') || path.contains('\\') ||
                    path.split('/').any { it == "." || it == ".." || it.isEmpty() } || !seen.add(path)
                ) {
                    throw InstallException(FailureReason.INVALID_ARCHIVE, "离线包 ZIP 路径不合法或重复")
                }
                val file = File(temp, path)
                if (!file.canonicalPath.startsWith(destinationPrefix)) {
                    throw InstallException(FailureReason.INVALID_ARCHIVE, "离线包 ZIP 路径超出解压目录范围")
                }
                if (entry.isDirectory) {
                    if (input.read() != -1) throw InstallException(
                        FailureReason.INVALID_ARCHIVE, "离线包 ZIP 目录条目包含非法数据"
                    )
                    if (!file.isDirectory && !file.mkdirs()) throw IOException("无法创建离线包解压目录")
                } else {
                    if (file.exists()) throw InstallException(FailureReason.INVALID_ARCHIVE, "离线包 ZIP 路径重复")
                    val parent = checkNotNull(file.parentFile)
                    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建离线包文件的父目录")
                    // 使用未缓冲的文件 Sink，使进度回调时统计的字节已写入文件。
                    expanded += file.sink().use { sink ->
                        copy(source, sink, minOf(MAX_ENTRY_BYTES, MAX_EXPANDED_BYTES - expanded), reportBytes)
                    }
                }
            }
        }
        if (entries != expectedEntries) {
            throw InstallException(FailureReason.INVALID_ARCHIVE, "离线包 ZIP 条目数量与中央目录不符或文件已损坏")
        }
        val content = if (File(temp, "index.html").isFile) temp else File(temp, "dist")
        val entry = File(content, "index.html")
        if (!entry.isFile || entry.length() == 0L) {
            throw InstallException(FailureReason.INVALID_ARCHIVE, "离线包入口文件 index.html 缺失或为空")
        }
        return content
    }

    /** 每次写入前检查大小上限，每次读取前响应取消；流的关闭由调用方的 use 负责。 */
    private suspend fun copy(input: Source, output: Sink, limit: Long, onCopied: (Long) -> Unit = {}): Long {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, COPY_BUFFER_BYTES)
            if (count == -1L) return total
            total += count
            if (total > limit) throw InstallException(FailureReason.SIZE_LIMIT, "离线包 ZIP 数据大小超出限制")
            output.write(buffer, count)
            onCopied(total)
        }
    }

    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 10_000
    private const val COPY_BUFFER_BYTES = 32L * 1024
}
