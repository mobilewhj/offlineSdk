# Android SDK 安装事实与入口检查优化执行记录

日期：2026-09-24。审查基线：`offlineSdk:0.2.0`，提交 `9f23191e34357166ed0b3f25bf3e9d5c005caf26`。开始时 `git status --porcelain=v1 -uall` 为空；先在 SDK 仓库完成未提交验证，随后按用户的新指令准备 `0.2.1` 发布。

## 基线核对

- `PackageInstaller.installZip` 在解压校验后用 `renameTo` 发布目录；`finally` 清理失败会将 `Success` 改为 `Failure`，但原 `PackageRecord` 随之丢失。已有测试验证原始失败或取消与两个清理异常共存。
- `PackageDownloader.withSource` 在安装器完成目标检查后才打开 HTTP 源。现有下载进度首回调发生在收到 HTTP 200 响应后，不能表示请求执行边界。
- `PackageArchive` 检查 `index.html`，`clearOldVersions` 另行检查当前目录入口；示例 App 也有自己的入口文件判断。SDK 现有根目录规范化允许父路径别名，拒绝根目录自身链接；清理子项不跟随链接。
- SDK 不保存宿主版本记录。示例 App 承担存储、版本选择和页面绑定；本轮不修改示例 App 实现，只运行其现有验证并提供迁移说明。

## 实施与 API 契约

- `InstallResult.Success` 增加 `requestStarted: Boolean`；`Failure` 增加 `publishedRecord: PackageRecord?` 与 `requestStarted: Boolean`。保留原 `FailureReason`、`InstallStage`、异常、HTTP 状态及错误说明。公开数据类的构造、`copy` 和相等性随字段变化，默认参数不保证与 `0.2.0` 的二进制兼容；宿主需重新编译。
- 下载器在执行 `Call.execute()` 前标记仅属于本次调用的 `DownloadAttempt`；HTTP 响应或首字节尚未到达也为 `true`。参数/URL/目标冲突及其他请求前本地失败为 `false`；请求执行后的 HTTP 错误、超时、中断及后续安装失败保留 `true`。本地流与内置包始终为 `false`。进度回调接口未变，此字段不负责业务重试计数。
- 发布后清理失败仍返回 `Failure(CLEANUP, stage=CLEANUP)`，并将原 `Success.record` 放入 `publishedRecord`；没有本次成功发布就保持 `null`。原失败或取消异常优先，两个临时路径均尝试清理，新增清理异常附加到原异常，不由目录扫描推断发布。
- `PackageInstaller.isUsable(version)` 在安装器的 IO 调度器与互斥锁内复用版本定位和根目录检查。入口规则检查版本目录及 `index.html` 均无符号链接、入口为可读非空普通文件且实际能读出至少一个字节。非法版本、不安全路径、缺失或读取失败为 `false`；取消传播。该方法不写入、删除、下载、保存记录或计算整包摘要。
- ZIP 解压后的入口和 `clearOldVersions` 的当前目录入口复用 `PackageEntry`。清理保留原有根路径规范化与子项删除时不跟随链接的约束；当前目录入口还须可读、非空且无链接，才允许清理其他目录。
- SDK 默认版本改为 `0.2.1`，示例 App 显示版本同步为 `0.2.1`；已发布的 `0.2.0` 标签不变。新增可复现的本地 AAR 消费脚本和只在该脚本启用时注入的 API 编译夹具；示例 App 安装编排未修改。

## 验证

以下命令均在本仓库运行，使用缓存离线构建。首次普通沙箱运行 Gradle 时，因 `~/.gradle` 锁文件不可写，未进入编译；允许使用现有 Gradle 缓存后重跑成功。

`python3 scripts/generate-sample.py --check` 通过，示例 ZIP 与配置 SHA-256 一致。

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintDebug :offlineSdk:lintRelease \
  :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleDebug :offlineSdk:assembleRelease \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease --offline --console=plain
```

结果：构建成功；SDK JVM 单测 45 项、示例 JVM 单测 26 项，合计 71 项，0 失败、0 错误、0 跳过。SDK Debug/Release lint 均 0 问题；示例 Debug lint 10 条警告、Release lint 9 条警告，均无错误。SDK Debug/Release AAR、示例 Debug APK 与开启 R8 的 Release APK 均构建成功。`compileDebugAndroidTestKotlin` 只验证设备测试源码编译。

```bash
./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository \
  --offline --console=plain
./gradlew :offlineSdk:publishToMavenLocal -PsdkVersion=0.2.1 \
  --offline --console=plain
./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency offlineSdk \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug \
  --offline --console=plain -I scripts/aar-consumer-smoke.init.gradle
```

结果：本地 `build/repo/` 生成 `com.github.mobilewhj.offlineSdk:offlineSdk:0.2.1` 的 POM、AAR、源码 JAR 和 Gradle Module Metadata；与 `jitpack.yml` 一致的 `publishToMavenLocal` 命令也通过。AAR 含公开结果类型与安装器类。`dependencyInsight` 确认示例的 `project :offlineSdk` 被替换为本地 Maven 的 `release` AAR，注入的夹具编译了 `requestStarted`、`publishedRecord`、`isUsable` 调用；示例单测、lint 与 Debug/Release 打包再次成功。旧 `0.2.0` 目录未作为本轮发布目标；本地消费验证不调用远端发布任务。

新测试覆盖：发布后清理失败的准确记录；校验、解压、发布失败和已有完整目录均无发布证明；请求前失败、HTTP 错误、响应前超时、成功请求与本地安装的请求事实；取消叠加两个清理错误；入口正常、缺失、空、不可读、链接目录/入口/根目录及父路径别名。`git diff --check` 通过。

**未运行设备测试。** `:offlineSdk:connectedDebugAndroidTest` 未执行，AndroidTest 源码编译不能替代设备运行。未在真实设备上验收 Welcome、系统 WebView、X5 或真实服务端配置；本轮也未执行宿主业务层接入测试。

## 宿主接入与保持不变的行为

宿主后续应在配置来源可信的前提下处理 `Success.record`；保存成功后才切换当前版本。若 `Failure.publishedRecord` 非空，记录“本次已发布但清理失败”的事实供诊断与后续策略使用，**默认仍保持原当前版本，不激活该包**。是否保存该记录、何时清理或重试由宿主决定，不因目录存在而推断可信发布。宿主按自己的检查间隔、最大次数和冷却规则使用 `requestStarted`，不再依赖下载进度或错误字符串推断请求是否开始。对已有可信持久化记录，可调用 `isUsable(record.version)` 替代自行拼接入口路径；孤立目录的 `true` 不构成发布证明。

SDK 保持下载、摘要校验、解压、发布、文件检查、资源映射和技术诊断职责。配置请求、异常上报、MMKV/环境隔离、隐私、ViewModel/进程生命周期、重试与候选拒绝、版本选择、OnlineFallback、页面绑定和浏览器缓存仍在 App。后续 App 接入可直接使用 `FailureReason`/`InstallStage` 减少字符串判断。没有引入页面引用计数、全局调度器、调度控制回调或新网络框架；下载/解压进度、取消传播、系统 WebView/X5 适配及资源未命中返回 `null` 的行为保持不变。

本轮只修改此 SDK 仓库；未修改 `slcsp_android2`。`0.2.1` 发布以新提交和新标签进行，不移动 `0.2.0` 标签；远端构建状态须以发布后的实际检查为准。
