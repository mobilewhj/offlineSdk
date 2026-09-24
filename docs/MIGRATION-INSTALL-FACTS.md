# Android 宿主接入：安装事实与入口检查（0.2.1）

本文面向从 `offlineSdk:0.2.0` 升级到 `0.2.1` 的 Android 宿主。本轮只修改 SDK 仓库；示例 App 的安装编排保持现状，迁移由宿主后续实施。

## API 与兼容性

| API | 契约 |
| --- | --- |
| `InstallResult.Success.record` | 本次已完成校验、解压和正式目录发布的记录；仍需宿主保存后才能切换当前版本。 |
| `InstallResult.Success.requestStarted` | 本次 HTTP 请求已进入 `Call.execute` 边界；本地安装为 `false`。 |
| `InstallResult.Failure.reason/stage/cause/httpStatus/message` | 保持原有分类、阶段、异常及说明。取消仍抛异常，不产生 `Failure`。 |
| `InstallResult.Failure.publishedRecord` | 只在本次发布成功、随后清理失败时携带本次记录；结果仍为失败。已有孤立目录不会产生该证明。 |
| `InstallResult.Failure.requestStarted` | 请求执行前失败为 `false`；执行后的超时、HTTP 错误或中断为 `true`。 |
| `PackageInstaller.isUsable(version)` | 挂起的只读入口检查。非法版本、不安全路径、入口缺失/空/不可读和读取失败返回 `false`；取消传播。 |

`requestStarted` 不代表已收到 HTTP 响应、首字节或业务重试次数。进度回调保持原状。`isUsable` 不证明 ZIP 摘要、来源或安装历史，也不写入或删除文件；不要用它收编未知孤立目录。

`Success` 和 `Failure` 是公开 `data class`。新增带默认值的构造字段保持常见 Kotlin 源码调用可重编译，但会改变 JVM 构造函数、`copy`、合成默认参数等二进制签名，`equals`/`hashCode` 也纳入新字段。**不能宣称与 0.2.0 完全二进制兼容**；升级时应重新编译宿主及持有这些类型调用点的封装库，避免混用旧编译产物。

## 宿主处理建议

```kotlin
when (val result = installer.install(candidate, url)) {
    is InstallResult.Success -> {
        // 宿主按自己的规则记录 result.requestStarted；保存成功后才切换页面版本。
        saveRecord(result.record)
    }
    is InstallResult.Failure -> {
        // result.requestStarted 仅供宿主决定是否计入自己的重试预算。
        reportTechnicalFailure(result.reason, result.stage, result.cause)
        if (result.publishedRecord != null) {
            // 保留发布事实供诊断/后续处理；本轮默认维持原当前版本，不激活该包。
            notePublishedButCleanupFailed(result.publishedRecord)
        }
    }
}
```

上述 `saveRecord`、`reportTechnicalFailure` 和 `notePublishedButCleanupFailed` 是宿主行为示意，并非 SDK 新接口。宿主负责 MMKV/环境隔离、配置请求、异常上报、版本选择、检查间隔、重试预算、候选拒绝和冷却策略。SDK 的 `FailureReason` 与 `InstallStage` 可供后续 App 接入减少字符串判断；本轮不改示例 App 的业务响应模型。

有已保存且来源可信的当前记录时，可用 `installer.isUsable(record.version)` 检查入口，再由宿主决定是否进入页面。`true` 只说明当前文件入口可读；若记录不存在或不可信，即使孤立目录有首页也不能自动认领。对发布后清理失败的包，宿主应继续保持现有当前记录，待自己的策略明确后再处理目录或记录。

## 保持不变

SDK 继续负责下载、摘要校验、有界解压、目录发布、文件检查、WebView 资源映射和技术诊断。取消继续传播，清理异常不会覆盖原失败或取消；系统 WebView/X5 适配和资源未命中返回 `null` 的行为不变。SDK 不引入页面引用计数、全局调度器、调度回调或新网络框架。页面绑定、浏览器缓存策略及 OnlineFallback 仍由宿主决定。
