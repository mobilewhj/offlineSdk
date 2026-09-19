package com.offline.tool

import java.io.IOException

/** 内部步骤携带中文说明、失败原因和 HTTP 状态，由安装入口保留说明并转换为 InstallResult。 */
internal class InstallException(
    val reason: FailureReason,
    message: String,
    cause: Throwable? = null,
    val status: Int? = null,
) : IOException(message, cause)
