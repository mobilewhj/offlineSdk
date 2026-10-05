# 托管 SDK 薄宿主 Demo（0.3.0）

Android Studio 运行 `app`，最低 Android API 24。当前 Demo 默认以 project 依赖运行本工作区源码，从 APK `assets/sample.zip` 安装合成页面，无需服务端、账号或真实业务资源。示例源码不进入 SDK AAR；project 编译通过不能代替实际 AAR 消费验证。

固定 RC 已发布为 `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`；其可编译样例在[固定 tag](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app)，其历史验证见[发布回执](verification/2026-09-29-test-release/README.md)。当前 Demo 与默认存储样例对应 `0.3.0` 新能力，不能作为 RC API 样例。正式坐标为 `com.github.mobilewhj:offlineSdk:0.3.0`，可编译样例见[固定 `0.3.0` tag](https://github.com/mobilewhj/offlineSdk/tree/0.3.0/app)，范围及发布核验要求见[发布说明](RELEASE-0.3.0.md)。

当前 SDK 已完成 B2 所有权拆分，提供 default/keyValue/codec 和 `prepareStartup`。完整新 App 链为 Application 单例/config/storage/conditions → `prepareStartup` → 业务门禁 → 一次 `loadPage` → 给定 interceptor/原 URL；见[主 README](../README.md#030最短完整接入链)。既有 Welcome 保留已兼容的 `startupDecision` → 必要时 `prepareFirst`，独立 [`DefaultStorageSample.kt`](../app/src/main/java/com/offline/tool/sample/offline/DefaultStorageSample.kt) 展示 `prepareStartup`，不重复建立宿主框架或迁移既有 Demo 数据。

| 文件 | 宿主仍负责的事情 |
| --- | --- |
| [`DemoApplication.kt`](../app/src/main/java/com/offline/tool/sample/DemoApplication.kt) | Main 直接构造并持有唯一管理器；生命周期和隐私变化同步传给 SDK，不保存管理器就绪状态或重放条件。配置端口 Main-safe；C9 终态独立分发。 |
| [`DemoOutcomeReporter.kt`](../app/src/main/java/com/offline/tool/sample/offline/DemoOutcomeReporter.kt) | 用独立进程报告作用域接收终态，普通异常本地记录、取消继续传播，撤回隐私取消已登记任务，不重试或补发。真实 App 在其挂起 `report` 回调中调用 Repository。 |
| [`DemoManagedStorage.kt`](../app/src/main/java/com/offline/tool/sample/offline/DemoManagedStorage.kt) | 保留 `current.txt` 两行 active 格式和 SharedPreferences 的 enabled/cache_dirty 介质；History 使用 SDK codec，旧大写诊断码只作读取兼容。读取故障抛出，未知失败码不丢失独立首次完成事实，不持久化 detail。 |
| [`WelcomeViewModel.kt`](../app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeViewModel.kt) | 直接持有管理器并作 `startupDecision()`；初始化挂起时保持中性 Idle，运行故障放行线上导航。只有 `NEEDS_FIRST_PREPARATION` 才显示首次准备，单个页面 Job 去重；存活等待者收到 owner 取消后进入显式恢复状态。 |
| [`WelcomeActivity.kt`](../app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeActivity.kt)、[`WelcomeProgressPresentation.kt`](../app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeProgressPresentation.kt) | 等待 owner 且没有自己的回调时显示中性准备文案；中断时停止加载并显示“重试/返回”。单条进度覆盖检查、目标准备、下载/解压、保存及确认；下载/解压最高显示 99%，只有 SDK 的 `Complete` 显示总体完成及 100%；首次正常结束后进入页面。 |
| [`MainActivity.kt`](../app/src/main/java/com/offline/tool/sample/MainActivity.kt) | 直接取管理器并挂起调用一次 `loadPage()`；回 Main 后执行 `WebView.clearCache(true)` 清内存及磁盘资源缓存，绑定拦截器并加载原 URL，不清 Cookie、localStorage 或业务数据。 |

首次无包且未正常准备时，Welcome 在完成资格判定后显示下载/解压与保存阶段。首次实际检查的成功、失败或有效关闭均结束离线等待；失败后的页面使用原 URL 默认加载。初始化存储读取失败保留目录并放行线上流程，不将未知 active 当作没有记录，也不据此保存首次完成。后次冷启动即使无包，也不展示首装进度，Application 在允许且前台时由 SDK 静默检查，持续前台每五分钟由 SDK 自行调度。

SDK 首装只由第一个调用者的子任务执行，同时到来的其他页面只等待结果，不接管进度或触发第二次请求。普通退后台停止新请求；合法目标进入准备阶段即视为安装开始，已开始操作可继续收尾。撤回隐私取消首装实际任务与 SDK 静默任务，宿主独立取消报告任务；SDK 不取消宿主父 Job 或无关任务。常规检查由 SDK 自有任务管理，Demo 没有手动检查入口。合法 active 即使丢失文件仍用于禁止降级；同版不同 SHA 在开关保存前即影响页面选择，宿主不重复实现这些规则。

页面文件检查、路径规范化都在 SDK 的 IO 阶段完成。单次 `loadPage()` 回 Main 后复核内存事实与目录观察代际，不再次读取入口文件；清理与同版修复使旧观察失效，绑定后由 SDK 保护目录。缓存标记由 Demo 在 IO 用 `SharedPreferences.commit()` 持久确认，Main 页面加载不等待磁盘；写失败保留待清内存事实，下一页面可重试。宿主及其他进程不得直接改动托管根目录；外部破坏只能在后续 IO 观察或资源请求发现，不能靠 Main 的内存复核检测。

Demo 回调同时处理 `InstallationOutcome.Installed` 与 `InstallationOutcome.Failed`，仅写调试日志；它没有后端协议，不宣称真正上报成功。SDK 隔离同步结果接收器自身的异常，包括接收器抛出的 `CancellationException`，不会改写既成安装结果；真实更新取消仍正常传播。报告任务的普通异常由宿主本地记录，真实取消不作为安装失败。报告先登记到独立父 Job 再启动，隐私撤回关闭入口并取消在途或待执行报告；重新授权只接收新终态，无历史补发。配置/无目标诊断单走 `onDiagnostic`，成功上报编码由未来业务 App 与后端确认。

Demo 使用本地 ZIP，不需要网络下载客户端。真实宿主可在创建管理器时传 `downloadClient = existingDownloadClient` 复用已有 OkHttp 超时和拦截器，高层固定 120 秒单次下载总时限。配置适配从 Main 调用；真实宿主的 Retrofit 挂起链路可直接使用，同步磁盘或网络操作必须由适配器切到 IO。SDK 仍独占安装器与目录；宿主接管前先停止旧低层在途写，不需要 App 另写下载编排。

Demo 不包含 App 业务配置、广告、导航、隐私页、两小时后台后重启或上传接口；这些仍由真实宿主保持。完整迁移约定见 [托管接入说明](MIGRATION-MANAGED-0.3.0.md)。

## 验证

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

旧 Demo 策略单测随旧编排一起移除；`WelcomeViewModelTest` 覆盖直接注入后隐私/前台变化、后次启动中性状态、首装资格、存活等待者取消和显式恢复。`WelcomeProgressPresentationTest` 覆盖阶段进度、等待中性文案及完成含义；`DemoOutcomeReporterTest` 覆盖普通异常隔离、隐私取消与重新授权不补发。SDK 托管行为另由其单测验证，Demo 继续执行 Debug/Release 编译、lint 和本地真实 AAR 消费验证；AAR 薄宿主同时编译新高层和已发布低层的源码调用。固定 RC 的历史本地与远端结果见[验收快照](verification/2026-09-29-complexity-reduction/README.md)和[发布回执](verification/2026-09-29-test-release/README.md)。当前默认存储样例单测复用实际公开工厂，局部 JVM 文件同步夹具不计 Android Os/fsync 或设备通过；正式发布另外绑定最终 tag、远端 AAR 和普通 Gradle 消费证据，本地源码或 project 编译不能替代远端消费。

本轮源码不继承旧产物的设备成绩。完整 Demo 生命周期、系统 WebView 内存／磁盘缓存、Cookie／请求头／Range、X5 实际内核、Main StrictMode 和真实 HTTP 上报仍需设备或业务接入验证；不能用 JVM 测试或源码编译替代这些验收。

## 四原语的格式前提

`ManagedOfflineStorage.keyValue` 读取的是 SDK codec 格式的 active/history JSON，四原语只适配介质。Demo 的两行 `current.txt` 不是该格式，因此仍保留最小自定义 active 适配；`legacyEvidence` 不转换格式。正常格式、未知额外字段与历史诊断码读取按 codec 契约处理，非法 JSON 作为读取故障；版本下限、SHA 资格及可用性仍由 Manager/Session 判断。

默认存储的同步成功表示持久确认，false 不能解释为文件操作无副作用或自动回滚。隐私、合法前台事实与报告生命周期都由现有宿主直接提供；SDK 不加入业务 UI 控制或后端客户端。

当前配置诊断通过既有 ManagedFailure.detail 输出有限分类。provider Failure.detail 仅接受 timeout/network/http/empty_response/response_decode/exception 六安全标记，其他非 null 说明被标记为 withheld；不输出原异常、完整响应或带凭据 URL，不将 detail 用于业务判断，codec 不保存 detail。无效配置不产生安装终态或失败版本门槛，关闭配置仍优先。
