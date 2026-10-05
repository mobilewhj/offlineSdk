package com.offline.tool.sample.offline

import android.content.Context
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.OfflineStorageCodec
import com.offline.tool.PackageRecord
import com.offline.tool.PreparationHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import java.io.File

/** 保留 Demo 已有文件和偏好 key；History 编码复用 SDK，更新决策归 SDK。 */
internal class DemoManagedStorage(context: Context) : ManagedOfflineStorage {
    private val application = context.applicationContext
    // 首次读取由 SDK 的初始化 IO 边界触发，Main 构造适配器时不打开偏好文件。
    private val preferences by lazy { application.getSharedPreferences("offline_demo", Context.MODE_PRIVATE) }
    // 保留示例已有的 active 记录格式，使已安装资源可以直接迁移。
    private val activeFile by lazy { File(application.filesDir, "offline/current.txt") }

    override suspend fun readActive(): PackageRecord? = withContext(Dispatchers.IO) {
        if (!activeFile.exists()) return@withContext null
        val lines = activeFile.readLines() // 读取异常必须到达 SDK，不能误判为记录不存在。
        val version = requireNotNull(lines.getOrNull(0)?.toIntOrNull()) { "Demo active 版本损坏" }
        val sha = requireNotNull(lines.getOrNull(1)) { "Demo active 摘要缺失" }
        require(version > 0 && SHA256.matches(sha)) { "Demo active 记录损坏" }
        PackageRecord(version, sha)
    }

    override suspend fun writeActive(record: PackageRecord?): Boolean = withContext(Dispatchers.IO) {
        try {
            if (record == null) return@withContext !activeFile.exists() || activeFile.delete()
            val parent = checkNotNull(activeFile.parentFile)
            if (!parent.isDirectory && !parent.mkdirs()) return@withContext false
            val temporary = File(parent, "${activeFile.name}.tmp")
            temporary.sink().buffer().use { it.writeUtf8("${record.version}\n${record.sha256}\n") }
            FileSystem.SYSTEM.atomicMove(temporary.toOkioPath(), activeFile.toOkioPath())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun readEnabled(): Boolean? = withContext(Dispatchers.IO) {
        if (preferences.contains("enabled")) preferences.getBoolean("enabled", true) else null
    }

    override suspend fun writeEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean("enabled", enabled).commit()
    }

    override suspend fun readHistory(): PreparationHistory? = withContext(Dispatchers.IO) {
        if (!preferences.contains("history")) return@withContext null
        val json = checkNotNull(preferences.getString("history", null)) { "Demo History 读取失败" }
        OfflineStorageCodec.decodeHistory(json)
    }

    override suspend fun writeHistory(history: PreparationHistory): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString("history", OfflineStorageCodec.encodeHistory(history)).commit()
    }

    override fun readCacheDirty(): Boolean? =
        if (preferences.contains("cache_dirty")) preferences.getBoolean("cache_dirty", false) else null

    override suspend fun writeCacheDirty(dirty: Boolean): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean("cache_dirty", dirty).commit()
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
