# Offline SDK 0.3.0 正式版本说明

正式坐标为 `com.github.mobilewhj:offlineSdk:0.3.0`，源码使用不可变 [`0.3.0` tag](https://github.com/mobilewhj/offlineSdk/tree/0.3.0)。最低 Android API 24，源码构建使用 JDK 17 / Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.0.21 / Android SDK 35；消费者需兼容 JVM 17 字节码和 Kotlin 2.0 元数据。

本页说明版本范围与发布核验要求。正式远端发布成立须以 [JitPack 实际构建日志](https://jitpack.io/com/github/mobilewhj/offlineSdk/0.3.0/build.log)、远端制品及普通 Gradle 消费结果为准，本地构建或创建 tag 不代表远端制品可用。

## 功能与 RC 区别

- B2 内部所有权明确：Manager 拥有任务、调度时钟和运行条件；Session 拥有包、配置、目录、失败版本门槛和 History；Cache 拥有缓存失效与持久确认状态。保持首次正常失败放行、五分钟检查、隐私/前后台、完整 SHA-256 及已绑定页面的目录保护。
- 新 App 可使用 `ManagedOfflineStorage.default(context, namespace)` 默认文件存储，无需自行实现存储方法。状态目录与 SDK 安装 root 隔离；同步成功必须完成持久确认，false 不承诺可见文件操作已回滚。默认目录创建与删除的同步故障恢复已补回归。
- 已有介质可通过 `OfflineKeyValueStore` 四个 String/Boolean 读写原语接入 `ManagedOfflineStorage.keyValue`。active/history 字符串必须是 `OfflineStorageCodec` 格式；四原语只适配介质，不转换任意旧格式，`legacyEvidence` 也不是格式转换器。
- `prepareStartup` 收敛 `startupDecision` → 必要时 `prepareFirst`；`Continue` 结束本次离线等待，`Deferred` 表示临时条件不足，取消仍传播。既有公开管理入口继续保留。
- codec 严格拒绝非法 JSON；读取故障产生 `STORAGE_READ` 诊断并保护已有版本目录。合法类型但业务无效的版本、SHA 以及包可用性仍由管理器判断，codec 不复制策略。
- 配置失败沿既有 `ManagedFailure.detail` 输出有限安全原因。provider detail 仅精确接受 timeout/network/http/empty_response/response_decode/exception 六标记；任意响应、异常消息、URL 或 Token 不透传、不持久化。配置诊断不伪装安装终态，不提高失败版本门槛，关闭仍优先。

这些新增入口不在固定 `0.3.0-rc.1` 中。RC 的可编译 Demo 与原始 API 说明仍使用[固定提交](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app)及其[迁移文档](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/docs/MIGRATION-MANAGED-0.3.0.md)，历史远端身份见[RC 发布回执](verification/2026-09-29-test-release/README.md)。既有低层 API 及历史升级限制见[迁移说明](MIGRATION-MANAGED-0.3.0.md)，不能把 `0.2.0` 旧二进制升级限制省略为无条件兼容。

## 接入与依赖

在普通 Gradle 仓库配置中加入 JitPack：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mobilewhj") }
        }
    }
}
```

应用模块固定使用：

```kotlin
implementation("com.github.mobilewhj:offlineSdk:0.3.0")
```

最短完整调用链为 Application 主进程单例/config/storage/conditions → `prepareStartup` → 宿主业务门禁 → 一次 `loadPage` → SDK 给定 interceptor 与原 URL。完整代码见[主 README](../README.md#030最短完整接入链)、[Demo 说明](DEMO.md)及[固定版本 Demo](https://github.com/mobilewhj/offlineSdk/tree/0.3.0/app)。Demo 普通开发使用 project 依赖；发布核验必须另用隔离 Demo 和普通 Maven 坐标消费远端 AAR。

POM/module 必须带入 Kotlin 标准库、OkHttp 4.12.0、Okio 3.7.0、kotlinx-coroutines-android 1.7.3 和新增 **Gson 2.11.0**；Gson 用于严格 JSON 语法，AAR 不内嵌该库。Gson 的传递依赖以实际远端元数据为准。TBS 是 compileOnly，仅使用 X5 的宿主另行提供 `com.tencent.tbs:tbssdk:44286`。

## 发布入口与身份保护

沿用仓库的 GitHub + JitPack 流程：检查逐项选择的 SDK 发布文件 → 提交并推送 → 等待正常 CI → 创建并推送不可变 `0.3.0` tag → 核对 JitPack 构建和远端制品 → 普通 Gradle 隔离消费。已存在的远端 tag 必须先核对提交与制品，不删除、移动或用另一份源码重新发布同一身份。

CI 的本地仓库验证使用包含 GitHub run/attempt 身份的显式唯一版本，继续运行原 SDK/Demo 测试、lint、AndroidTest 编译及 Release/R8，不删除检查。普通本地候选仍使用全新的显式 sdkVersion，输出至隔离 `build/repo`；缺版本、固定 RC 或已存在的版本目录会拒绝。

JitPack 只通过显式 `-PjitpackRelease=true -PsdkVersion="$VERSION"` 发布正式 `0.3.0`，为其必要的 Maven 本地制品输出提供入口。普通 `publishToMavenLocal` 继续拒绝，正式入口须显式指定绝对路径 `-Dmaven.repo.local`，同样拒绝 RC、版本不符或既有身份。正向本地检查将 Maven 输出定向到新的隔离目录，不能让普通消费者使用 mavenLocal 冒充远端消费：

```sh
# 必须将 RELEASE_CHECK_REPO 设为本轮全新隔离目录的绝对路径。
./gradlew :offlineSdk:publishToMavenLocal \
  -PjitpackRelease=true -PsdkVersion=0.3.0 \
  -Dmaven.repo.local="$RELEASE_CHECK_REPO" --no-daemon --console=plain
```

完整历史 `0.2.0` 发布清单保留在 [RELEASING.md](RELEASING.md)，其中的旧版本号、命令和身份属于原阶段，不能作为从当前源码重发旧版本的指令。

## 历史来源与验证边界

当前功能基线已在 2026-10-04 的独立本地候选 `0.3.0-sdk-opt-local-20261004-6e9416de43-9567ee` 中验收。其 AAR、源码清单、真实 codec/Manager 探针、公开 API/旧预编译消费者和 SDK179/Demo16 等结果，保存在本地 Trellis 原任务 `09-29-offline-regression-audit` 的冻结优化与独立评审记录。该身份属于本地候选，**不是正式远端 0.3.0 制品身份**；发布须重新记录正式输入、AAR/sources/POM/module、对应 commit 和普通远端消费结果，不能直接沿用候选 SHA。

B2、默认 File F1/F2 及旧工作站候选命令仍保留在该任务的本地冻结历史记录，未随本次发布上传设备序列号、工作站路径或原始设备日志；历史记录不修改。旧 B2/F1F2 设备成绩只归属于其原始源码与产物，不是正式 `0.3.0` 的新成绩。

本次发布阶段保留以下未完成项：

- 最终产物的 API24 默认存储、真实冷进程、默认五分钟自然周期。
- 真实 X5 内核、完整 `loadPage` → 真实 WebView 的缓存/Cookie/localStorage/请求头/Range、隐私/前后台及 Activity 销毁矩阵。
- 业务 App 完整启动、广告导航、两小时后台返回、成功上报协议及后端实际接收回执。

SDK 发布不操作设备、不安装 App、不清业务数据，也不升级业务 App 当前固定的 RC 依赖。正式 SDK 可解析和构建不代表业务 App 已完成新版本接入或完整业务验收；日志和 SDK 类型化结果不算后端上传成功。
