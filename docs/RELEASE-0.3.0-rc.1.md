# Offline SDK 0.3.0-rc.1（接入测试候选）

本版本供业务 App 进行接入测试，是 **Pre-release**，不设为 Latest，也不是正式 `0.3.0`。已接受的托管入口与旧低层 API 一同包含在此候选中。

## 主要变化

- `ManagedOfflineSdk` 负责首装、前台静默检查、版本选择、active 保存、缓存标记和页面目录保护。宿主保留配置、存储、隐私条件、WebView 操作和独立业务上报。
- 首装仅用 `prepareFirst(onProgress)` 接收完整进度；页面只调用一次挂起的 `loadPage(url, baseUrl, callbacks)`。旧的公开页面准备/提交握手已经删除。
- 保留缓存标记的真实持久提交、旧 active 保护与必要取消语义。冷清理失败、History 挂起和后续页面恢复的诊断不重复上报。
- 薄宿主 Demo 和迁移说明已更新。旧 `0.2.2` 的低层入口继续保留；二进制消费者应重新编译。

## 接入方式

JitPack 多模块候选坐标预计为 `com.github.mobilewhj.offlineSdk:offlineSdk:0.3.0-rc.1`。使用前应核对本次 GitHub Release 回执中的远端 POM、构建状态和实际消费验证。`settings.gradle.kts` 中将 `https://jitpack.io` 加入依赖仓库，并允许 group `com.github.mobilewhj.offlineSdk`。最小调用链见[迁移说明](MIGRATION-MANAGED-0.3.0.md#最小接入链)和[薄宿主](../app/)。

## 候选验证与开放项

发布前相同生产源码通过 SDK JVM **129/129**、Demo JVM **14/14**；SDK Release、Demo Debug/Release/R8、lint 和两模块 AndroidTest Kotlin 源码编译通过。本地 `0.3.0-rc.1` AAR 与已接受的候选 AAR 字节一致，源码摘要及证据见[回执](verification/2026-09-29-complexity-reduction/README.md)。远端产物身份与消费结果以 GitHub Release 回执为准，不沿用本地摘要。

F4 当前候选真机验证尚无通过 XML；完整系统 WebView/X5、缓存与 Cookie 生命周期、业务 App 接入和正式 `0.3.0` 验收仍需后续进行。本候选不表示这些项目已经通过。
