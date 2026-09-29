package com.offline.tool.sample.offline

import android.content.Context
import com.offline.tool.FailureReason
import com.offline.tool.ManagedFailure
import com.offline.tool.ManagedFailureReason
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.ManagedStage
import com.offline.tool.PackageRecord
import com.offline.tool.PreparationHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** 宿主只实现存储编码；更新决策归 SDK。阻塞写入均切到 IO，成功值只表示持久提交。 */
internal class DemoManagedStorage(context: Context) : ManagedOfflineStorage {
    private val application = context.applicationContext
    // 首次读取由 SDK 的初始化 IO 边界触发，Main 构造适配器时不打开偏好文件。
    private val preferences by lazy { application.getSharedPreferences("offline_demo", Context.MODE_PRIVATE) }
    // 保留示例已有的 active 记录格式，使已安装资源可以直接迁移。
    private val activeFile by lazy { File(application.filesDir, "offline/current.txt") }

    override suspend fun readActive(): PackageRecord? = withContext(Dispatchers.IO) {
        if (!activeFile.exists()) return@withContext null
        val lines = activeFile.readLines() // 读取异常必须到达 SDK，不能误判为记录不存在。
        val version = lines.getOrNull(0)?.toIntOrNull() ?: return@withContext null
        val sha = lines.getOrNull(1) ?: return@withContext null
        if (version <= 0 || !SHA256.matches(sha)) null else PackageRecord(version, sha)
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
        val json = preferences.getString("history", null) ?: return@withContext null
        try {
            decodeHistory(JSONObject(json))
        } catch (_: JSONException) {
            null // 未识别的编码按缺失处理；存储访问异常仍向上传播。
        }
    }

    override suspend fun writeHistory(history: PreparationHistory): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString("history", encodeHistory(history).toString()).commit()
    }

    override fun readCacheDirty(): Boolean? =
        if (preferences.contains("cache_dirty")) preferences.getBoolean("cache_dirty", false) else null

    override suspend fun writeCacheDirty(dirty: Boolean): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean("cache_dirty", dirty).commit()
    }

    private fun encodeHistory(history: PreparationHistory): JSONObject = JSONObject().apply {
        // 示例以英文枚举名作稳定持久码，不使用 ordinal；升级不得随意改名，业务协议另做映射。
        put("initialPreparationFinished", history.initialPreparationFinished)
        history.latestFailure?.let { failure ->
            put("latestFailure", JSONObject().apply {
                put("reason", failure.reason.name)
                put("stage", failure.stage.name)
                failure.targetVersion?.let { put("targetVersion", it) }
                failure.targetSha256?.let { put("targetSha256", it) }
                failure.installReason?.let { put("installReason", it.name) }
                failure.httpStatus?.let { put("httpStatus", it) }
                failure.detail?.let { put("detail", it) }
                put("occurredAtMillis", failure.occurredAtMillis)
                put("activeRollbackFailed", failure.activeRollbackFailed)
            })
        }
    }

    private fun decodeHistory(json: JSONObject): PreparationHistory {
        val failure = json.optJSONObject("latestFailure")?.let { encoded ->
            val reason = enumValueOrNull<ManagedFailureReason>(encoded.optString("reason"))
            val stage = enumValueOrNull<ManagedStage>(encoded.optString("stage"))
            if (reason == null || stage == null) null else ManagedFailure(
                reason = reason,
                stage = stage,
                targetVersion = encoded.optInt("targetVersion").takeIf { encoded.has("targetVersion") },
                targetSha256 = encoded.optString("targetSha256").takeIf { it.isNotEmpty() },
                installReason = enumValueOrNull<FailureReason>(encoded.optString("installReason")),
                httpStatus = encoded.optInt("httpStatus").takeIf { encoded.has("httpStatus") },
                detail = encoded.optString("detail").takeIf { it.isNotEmpty() },
                occurredAtMillis = encoded.optLong("occurredAtMillis"),
                activeRollbackFailed = encoded.optBoolean("activeRollbackFailed"),
            )
        }
        // 未知旧失败码只丢弃失败明细，不丢弃独立保存的首次完成事实。
        return PreparationHistory(json.optBoolean("initialPreparationFinished"), failure)
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
