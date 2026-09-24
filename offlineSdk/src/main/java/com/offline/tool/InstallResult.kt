package com.offline.tool

/** 安装结果；协程取消直接向上传播，不转换为 Failure。 */
sealed interface InstallResult {
    /** 版本目录已发布；调用方仍需自行保存记录，确认后才切换当前版本。 */
    data class Success(
        val record: PackageRecord,
        /** 本次安装是否已进入 HTTP Call.execute；本地安装始终为 false。 */
        val requestStarted: Boolean = false,
    ) : InstallResult

    /** 保留失败分类、实际阶段和原始异常，供调用方处理或上报。 */
    data class Failure(
        val reason: FailureReason,
        val cause: Throwable? = null,
        val httpStatus: Int? = null,
        val stage: InstallStage = InstallStage.INSTALL,
        /** 用于上报的错误说明；cause 保留原始异常供诊断。 */
        val message: String? = cause?.message,
        /** 仅本次安装完成校验、解压和目录发布后才有值；清理失败仍是失败，不表示可激活。 */
        val publishedRecord: PackageRecord? = null,
        /** 本次安装是否已进入 HTTP Call.execute；不表示响应或首字节已到达。 */
        val requestStarted: Boolean = false,
    ) : InstallResult
}
