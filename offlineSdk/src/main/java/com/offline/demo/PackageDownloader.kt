package com.offline.demo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** 单次 HTTP 下载的资源边界：管理请求、真实字节进度、超时和关闭，不决定安装目录。 */
internal class PackageDownloader(
    httpClient: OkHttpClient,
    private val timeoutMillis: Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(timeoutMillis > 0L)
    }

    private val client = httpClient.newBuilder().followSslRedirects(false).build()

    /**
     * 在 ioDispatcher 上执行 [block]。传入的工厂延迟发送请求，安装器检查目标并取得锁后才打开源。
     * block 负责消费并关闭 Source；本方法兜底关闭响应，并在协程取消时只取消本次 Call。
     */
    suspend fun <T> withSource(
        url: String,
        onProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)?,
        block: suspend (openSource: () -> Source) -> T,
    ): T = withContext(ioDispatcher) {
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw InstallException(FailureReason.INVALID_URL, "离线包下载地址无效: $url")
        val call = client.newCall(Request.Builder().url(httpUrl).build())
        // 总时限覆盖连接、重定向和完整响应读取，不修改共享客户端的超时配置。
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        val operationContext = currentCoroutineContext()
        return@withContext withCallCancellation(call) {
            var response: Response? = null
            try {
                block {
                    operationContext.ensureActive()
                    val received = try {
                        call.execute().also { response = it }
                    } catch (error: IOException) {
                        operationContext.ensureActive()
                        throw downloadFailure(error)
                    }
                    received.openDownloadSource(operationContext, onProgress)
                }
            } finally {
                response?.close()
            }
        }
    }

    /** 校验完整包响应，并在读取期间报告真实字节进度、优先传播协程取消。 */
    private fun Response.openDownloadSource(
        operationContext: CoroutineContext,
        onProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)?,
    ): Source {
        if (code != 200) {
            throw InstallException(
                reason = FailureReason.DOWNLOAD,
                message = "离线包下载失败，HTTP 状态码：$code",
                status = code,
            )
        }
        val body = body ?: throw InstallException(
            reason = FailureReason.DOWNLOAD,
            message = "离线包下载响应内容为空",
            status = code,
        )
        val total = body.contentLength().takeIf { it > 0L }
        var downloaded = 0L
        onProgress?.invoke(downloaded, total)
        return object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                operationContext.ensureActive()
                val count = try {
                    super.read(sink, byteCount)
                } catch (error: IOException) {
                    operationContext.ensureActive()
                    throw downloadFailure(error, code)
                }
                if (count > 0L) {
                    downloaded += count
                    onProgress?.invoke(downloaded, total)
                }
                return count
            }
        }
    }

    /**
     * 在 [block] 内把外层任务的取消传给 OkHttp，覆盖 execute 和响应体读取中的阻塞。
     * 外层任务取消时：子协程随之取消，调用 call.cancel() 中断网络等待。
     * block 正常结束时：仅停止子协程，外层任务仍活跃，不取消已完成的请求。
     */
    private suspend fun <T> CoroutineScope.withCallCancellation(call: Call, block: suspend () -> T): T {
        val operationContext = coroutineContext
        // 先运行到 awaitCancellation 建立监听，再允许 block 开始网络操作。
        // 取消处理不排队到 IO 调度器，避免单线程被 execute/read 占用时无法关闭请求。
        val cancellationWatcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!operationContext.isActive) call.cancel()
            }
        }
        return try {
            block()
        } finally {
            // 请求关闭或 block 抛错时也收掉监听，避免外层任务一直等待这个子协程。
            cancellationWatcher.cancel()
        }
    }

    private fun downloadFailure(error: IOException, status: Int? = null) = InstallException(
        reason = if (error is InterruptedIOException) FailureReason.DOWNLOAD_TIMEOUT else FailureReason.DOWNLOAD,
        message = if (error is InterruptedIOException) "离线包下载超时" else "离线包下载失败",
        cause = error,
        status = status,
    )
}
