package com.offline.tool

/** 安装失败发生的实际阶段；INSTALL 表示进入具体步骤前的输入检查。 */
enum class InstallStage {
    INSTALL,
    PREPARE,
    DOWNLOAD,
    VERIFY,
    EXTRACT,
    PUBLISH,
    CLEANUP,
}
