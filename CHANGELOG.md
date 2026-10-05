# 更新记录

## 0.3.0（2026-10-05）

- B2 内部职责分开：Manager 拥有任务、调度和条件；Session 拥有包、配置、目录、失败门槛和 History；Cache 拥有缓存失效与持久确认。公开 RC 入口继续保留。
- 已有 `ManagedOfflineStorage.default`、`keyValue`、`OfflineStorageCodec` 和 `prepareStartup` 简化新 App 接入。四原语只适配介质，需要 SDK codec 格式；legacyEvidence 不转换旧字符串。默认 File 的 F1/F2 回归及原证据保留。
- 本轮修复非法 JSON 被解释成正常 active 记录的问题，并通过现有诊断 detail 区分安全、有限的配置失败原因；版本/SHA/目录策略仍由管理器处理，不增加业务上传或改变安装终态通道。
- 文档区分固定 RC 与正式 0.3.0，RC 样例链接固定 tag；本地发布使用显式新唯一版本，拒绝覆盖已发布 RC 或既有候选身份。历史验证记录不改写，当前源码不继承历史测试数量或设备成绩。
- 正式坐标 `com.github.mobilewhj:offlineSdk:0.3.0`；新增 Gson 2.11.0 严格语法运行依赖由 POM/module 传递，AAR 不内嵌该库。
- CI 使用明确唯一的隔离验证版本，正式 JitPack 通过受控入口生成 Maven 本地制品；普通候选显式版本、RC 和既有身份不可覆盖保护保留。发布成立以实际远端构建/获取/普通消费为准，见[发布说明](docs/RELEASE-0.3.0.md)。
- 最终产物的 API24、真实 X5、真实冷进程、默认五分钟以及完整业务启动/两小时路径和后端协议/回执仍单独开放；业务 App 的固定 RC 接入未在本次升级。

## 0.3.0-rc.1（接入测试候选）

RC 发布时正式 `0.3.0` 尚未发布。本条历史记录对应已接受代码与结构范围的测试候选，按 GitHub Pre-release 发布，不设为 Latest。固定 tag 的 JitPack 构建、远端 POM 和隔离薄宿主依赖消费已核验；证据见[发布回执](docs/verification/2026-09-29-test-release/README.md)。

- 新增 `ManagedOfflineSdk`，统一首次准备、前台五分钟配置检查、安装、active 保存、页面目录保护及资源缓存失效。
- 本进程仅记录最高失败版本；同版及更低版本跳过，更高版本仍可安装，真实新进程重置。首次完成与最新诊断写入独立小记录，不引入旧退避、保存恢复或持久封禁。
- 类型化 `InstallationOutcome` 对首次和静默安装各终态只通知一次；SDK 不发送业务上报 HTTP。
- Demo 改为配置、存储、条件、结果回调和页面 UI 的薄宿主。现有低层 `PackageInstaller` API 保留，并增加本地 ZIP 解压进度重载。
- `OfflineInterceptor` 新增运行期开关参数；使用旧构造函数的源码可重新编译，已编译二进制须重编。
- 修复候选验收 R1–R9：存储读取故障不清目录、公开检查统一取消、防降级独立于文件、配置冲突及时生效、终态回调隔离、页面 Main 无包文件读取，并支持注入既有下载客户端。Demo 补全磁盘资源缓存、首次进度和隐私报告取消示例。
- 代码质量整改集中 URL 共同规则与本地初始事实解释，将 History 保存收敛到入口收尾，删除全局保存策略开关；公开管理 API 不变。
- F2-R 和后续 P2/P3 已关闭；删除重复终态载体与无效调度分支，首装收为单条进度回调，页面收为挂起 `loadPage()` 入口。R0–R5 代码与结构范围已由原规划会话复审接受。
- 远端 POM 和隔离 tag Demo 确认 JitPack 坐标为 `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`；远端 AAR 消费后的 Debug、Release/R8、14/14 测试、lint 和 AndroidTest Kotlin 源码编译通过。旧 `0.2.2` 坐标属于历史低层版本。
- F4 设备结果及完整 Demo 生命周期、系统 WebView 缓存／Cookie／请求头／Range、X5 实际内核继续开放。当前 `0.2.2` 正式发布物不受影响。

## 0.2.2（Android SDK）

- `PackageInstaller.isUsable(version)` 不再等待安装互斥锁；后台下载新包时可检查已存在的旧包入口。结果只表示本次观察到的文件状态，页面使用期间的目录保护仍由宿主负责。公开 API 签名不变，见 [并发修复执行记录](docs/EXECUTION-ISUSABLE-CONCURRENCY.md)。
- 正式依赖坐标：`com.github.mobilewhj:offlineSdk:0.2.2`；`0.2.1` 标签和产物保持不变。

## 0.2.1（Android SDK）

- 安装结果增加本次 HTTP 请求是否进入执行边界的 `requestStarted`；本地安装为 `false`，协程取消仍抛出。
- 发布成功后清理失败仍返回 `Failure`，并以 `publishedRecord` 报告本次已发布的记录；其他失败不据已有目录推断发布。
- 增加只读挂起 API `PackageInstaller.isUsable(version)`，统一入口文件和安全路径检查；入口可用不构成可信安装证明。
- 结果数据类增加公开字段会改变 JVM 构造函数、`copy` 等二进制签名；宿主需重新编译。见 [迁移说明](docs/MIGRATION-INSTALL-FACTS.md) 和 [执行记录](docs/EXECUTION-INSTALL-FACTS.md)。
- 正式依赖坐标：`com.github.mobilewhj:offlineSdk:0.2.1`；`0.2.0` 标签和产物保持不变。

## 0.2.0

统一 SDK 标识为 `com.offline.tool`，更新 Demo 和测试标识。iOS 模块改为 OfflineTool，类名前缀改为 OFT。见 [迁移说明](docs/MIGRATION-0.2.0.md)。

## 0.1.0

首个正式版本，面向小型 Android 项目的单包 H5 离线资源方案。

- 沿用 `0.1.0-beta` 的 SDK API 与实现，无功能变更。
- 正式依赖坐标：`com.github.mobilewhj:offlineSdk:0.1.0`。
- GitHub Release 使用正式发布标记；设备验收限制继续保留。

### English

First regular release for small Android projects using one offline H5 resource package.

- Same SDK APIs and implementation as `0.1.0-beta`; no functional changes.
- Release coordinates: `com.github.mobilewhj:offlineSdk:0.1.0`.
- Published as a regular GitHub Release. Device acceptance remains pending.

## 0.1.0-beta

首个 beta：面向小型 Android 项目的单包离线资源方案。

- ZIP 下载、SHA-256 校验、有界解压和版本目录发布。
- 支持协程取消、下载/解压进度和类型化安装结果。
- 系统 WebView 离线映射及可选 X5 响应适配。
- 独立命名空间 `com.offline.demo`，附 SDK 测试与 Welcome 接入示例。
- 示例包含本地版本保存、安装编排、固定页面目录、URL 匹配和调试诊断。
- Welcome 使用 ViewModel / StateFlow，支持首次准备、失败重试、已有包静默更新与生命周期取消。
- 中文与英文 README；Apache-2.0 许可证。

设备验收尚未完成，详见 [验证记录](docs/VALIDATION.md)。

### English

First beta for small Android projects using one offline H5 resource package.

- ZIP download, SHA-256 verification, bounded extraction, and version directory publication.
- Coroutine cancellation, progress callbacks, system WebView mapping, and an optional X5 adapter.
- Welcome sample with persistence, retry, background updates, and fixed resource directories per page.
- Chinese and English READMEs, Apache-2.0 licensing, and 67 passing local JVM tests.
- Device acceptance is pending; no device validation is claimed for Welcome, system WebView, or X5.
