package com.offline.tool

/** 本次离线等待的结论；不表达安装成功，也不替代宿主业务配置与导航门禁。 */
sealed interface StartupResult {
    data object Continue : StartupResult
    /** 本次临时未结束；没有排队或自动重试，下一次合法显式启动动作可重入。 */
    data object Deferred : StartupResult
}
