package com.offline.tool.sample.offline

import com.offline.tool.InstallationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * 宿主的进程报告入口，与页面和 SDK 安装任务分离；每个终态只启动一个任务，不重试。
 * 隐私撤回关闭入口并取消在途报告，之后重新授权只接收新的终态，不补发历史结果。
 */
internal class DemoOutcomeReporter(
    private val report: suspend (InstallationOutcome) -> Unit,
    private val onFailure: (Exception) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val gate = Any()
    private var privacyAllowed = false

    fun setPrivacyAllowed(allowed: Boolean) = synchronized(gate) {
        privacyAllowed = allowed
        if (!allowed) supervisor.cancelChildren()
    }

    fun submit(outcome: InstallationOutcome) {
        val task = synchronized(gate) {
            if (!privacyAllowed) return
            // 延迟启动先把任务挂到报告父任务，避免撤回和任务登记之间遗漏取消。
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    report(outcome)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // SupervisorJob 只隔离子任务；普通网络异常仍需在宿主显式处理。
                    onFailure(error)
                }
            }
        }
        task.start()
    }
}
