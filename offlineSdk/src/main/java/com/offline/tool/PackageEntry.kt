package com.offline.tool

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** 安装和清理共用的入口文件规则；调用方先规范化并检查 SDK 根目录。 */
internal object PackageEntry {
    fun isUsable(directory: File): Boolean = try {
        val entry = File(directory, "index.html")
        directory.canonicalFile == directory.absoluteFile &&
            directory.isDirectory &&
            entry.canonicalFile == entry.absoluteFile &&
            entry.isFile &&
            entry.canRead() &&
            entry.length() > 0L &&
            FileInputStream(entry).use { it.read() != -1 }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
