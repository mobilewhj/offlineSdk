package com.offline.demo

/** 安装失败的稳定分类；实际阶段和详细说明见 InstallResult.Failure。 */
enum class FailureReason {
    INVALID_RECORD,
    INVALID_URL,
    DOWNLOAD,
    DOWNLOAD_TIMEOUT,
    HASH_MISMATCH,
    INVALID_ARCHIVE,
    SIZE_LIMIT,
    TARGET_EXISTS,
    FILE_IO,
    CLEANUP,
    PUBLISH,
}
