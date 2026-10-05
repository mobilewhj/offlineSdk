package com.offline.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 仅拥有资源缓存的内存脏标记、写入顺序和持久确认；所有状态操作由 Main 调用。 */
internal class ResourceCacheState(
    private val ioDispatcher: CoroutineDispatcher,
    private val writeDirty: suspend (Boolean) -> Boolean,
    private val processScope: CoroutineScope,
    private val onWriteFailure: () -> Unit,
) {
    private var dirty = true
    private var generation = 0L
    private val writeMutex = Mutex()

    /** 完整本地事实提交时同步恢复；缺记录或读取失败都保守地保持待清理。 */
    fun restore(storedDirty: Boolean?) {
        dirty = storedDirty != false
    }

    /** 初始缺记录只补持久标记，不产生新的失效代际。 */
    suspend fun persistInitialDirty() {
        val saved = writeMutex.withLock { persist(true) }
        if (!saved) onWriteFailure()
    }

    /** 配置事实已改变时先在 Main 置脏，再等待旧清理写入并保存 true。 */
    suspend fun invalidate() {
        generation++
        dirty = true
        val saved = writeMutex.withLock { persist(true) }
        if (!saved) onWriteFailure()
    }

    /** 同步清理实际 WebView 缓存；磁盘确认只借用既有 SDK scope，不延迟页面加载。 */
    fun clearBeforeLoad(canConfirm: Boolean, clear: () -> Boolean): Boolean {
        if (!dirty) return true
        val cleared = try { clear() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        if (cleared && canConfirm) {
            val observedGeneration = generation
            processScope.launch { persistClean(observedGeneration) }
        }
        return cleared
    }

    private suspend fun persistClean(observedGeneration: Long) {
        val saved = try {
            writeMutex.withLock {
                if (generation != observedGeneration || !dirty) return@withLock null
                persist(false)
            }
        } catch (_: CancellationException) {
            return
        }
        // 过期清理的成功与失败都不得改变后到置脏的事实或诊断。
        if (generation != observedGeneration) return
        if (saved == true) dirty = false
        else if (saved == false) onWriteFailure()
    }

    /** 仅转换普通 IO 写故障；取消仍交由发起入口分别处理。 */
    private suspend fun persist(value: Boolean): Boolean = try {
        withContext(ioDispatcher) { writeDirty(value) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
