# 0.3.0 与固定 RC 托管接入边界

正式 `0.3.0` 坐标为 `com.github.mobilewhj:offlineSdk:0.3.0`，可编译样例见[固定 `0.3.0` tag](https://github.com/mobilewhj/offlineSdk/tree/0.3.0/app)，版本范围及发布核验要求见[发布说明](RELEASE-0.3.0.md)。此前已发布接入测试候选是 `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`。本文“RC 接入”章节描述该固定版本；可编译样例与原始接口说明使用[固定 tag](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app)和[固定迁移文档](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/docs/MIGRATION-MANAGED-0.3.0.md)，不能用当前 Demo 推断 RC 能力。发布前历史、本地及远端消费分别见[验收快照](verification/2026-09-29-complexity-reduction/README.md)和[发布回执](verification/2026-09-29-test-release/README.md)。

## 0.3.0 的新增入口

`0.3.0` 已实现 B2 内部所有权、默认文件存储、四原语 keyValue、codec 和 `prepareStartup`；无需再次开发，**固定 RC 没有这些新增入口**。当前新 App 的完整链见[主 README](../README.md#030最短完整接入链)，可编译新入口见 [`DefaultStorageSample.kt`](../app/src/main/java/com/offline/tool/sample/offline/DefaultStorageSample.kt)。`prepareStartup` 的 Continue 只结束离线等待；Deferred 为临时条件不足，取消仍抛出。页面继续由一次 loadPage 加载给定 interceptor 与原 URL。

新 App 可用 `ManagedOfflineStorage.default(context, namespace)`，无需实现存储。保留介质的 App 可用四原语 keyValue，但 active/history 字符串须是 SDK codec 格式；它不自动转换旧格式，legacyEvidence 也不是格式转换器。当前 Demo 保留必要的两行 active 适配。正式消费使用固定 `0.3.0` 远端 AAR；另做本地验证须显式分配新的唯一候选身份。旧工作站候选命令只作为[历史来源](RELEASE-0.3.0.md#历史来源与验证边界)，不能复用其版本发布当前源码。

下面保留 RC 的八方法存储和 startupDecision→prepareFirst 接入契约。所有托管版本中，同一 root 只能有一个管理器；接管前先停止旧低层在途写，接管后不能同时另开目录写入口。设备/API24/X5、完整业务启动/两小时路径和后端回执仍需其对应最终产物的独立验收。

## RC 接入

### 固定依赖

远端 POM 和隔离 tag Demo 的依赖解析确认 SDK 的 JitPack 坐标为 `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`；远端 AAR 的实际消费与摘要见[发布回执](verification/2026-09-29-test-release/README.md)。在 `settings.gradle.kts` 中加入：

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

应用模块使用 `implementation("com.github.mobilewhj:offlineSdk:0.3.0-rc.1")`。发布前代码与行为证据见 [验收快照](verification/2026-09-29-complexity-reduction/README.md)；下方 API、存储和页面约定是业务 App 接入测试的迁移边界。

### API 与所有权

SDK 的公开构造仅需 `root`、`storage`、`configProvider`；可选 `minimumVersion`、`onInstallationOutcome`、`onDiagnostic`、`downloadClient`。宿主在 Application 的 Main 上为固定环境直接创建并持有一个管理器，立即传入最新隐私/前台事实，再把同一对象交给 Welcome 和页面。构造只保存依赖与内存状态；首次合法挂起入口由 SDK 在 IO 准备安装器、规范化路径并读取记录。运行期初始化故障不会留下失败的等待对象：本次页面实际调用 `loadOnline()`，下一合法入口在同一管理器上重试。生产业务传 `minimumVersion = 100_000`，并提供自己的 Repository、按环境存储、隐私及前后台事实。宿主适配器也应轻量构造，阻塞工作放在既有读写或请求入口。

`downloadClient: OkHttpClient` 可传入既有下载客户端，保留其拦截器及连接/读写超时。高层总下载时限固定为 120 秒，覆盖连接、重定向和响应读取；`downloadTimeoutMillis`、`ioDispatcher`、`checkIntervalMillis`、`monotonicMillis` 已从托管高层公开构造移除，测试注入仅在 SDK 模块内部。低层 `PackageInstaller` 的公开参数保持不变。根目录独占从首次初始化成功 claim 时生效；同 canonical 根的第二个管理器到初始化时明确拒绝，不能用重建实例绕过本进程失败门槛。接管前先停止旧低层在途写，接管后不再另开目录写入入口。

`startupDecision()`、`prepareFirst()`、`loadPage()` 是挂起入口，内部统一切到 Main 管理运行状态，可从其他协程上下文调用。`setConditions()`、`shutdown()` 是 Main 同步入口，宿主从其他线程调用前必须先切到 Main；隐私撤回和页面最终判定同步生效。生产管理状态固定使用 `Dispatchers.Main.immediate`；`mainDispatcher` 仅在 SDK 模块内部测试可注入，宿主单测可用 `Dispatchers.setMain` 提供同一串行测试上下文。阻塞文件、ZIP、下载和主要存储工作由 SDK 或宿主适配器放在 IO，资源拦截仍在 WebView 的资源线程执行。

`prepareFirst()` 的首个调用者拥有一次真实尝试，SDK 只保留一个首装 handle；同时到来的调用只等待同一结果，不重复请求、安装、History 保存或终态通知。等待者取消只结束自己的等待；owner 取消、撤回隐私或 `shutdown()` 结束实际尝试并让等待者收到取消。重复调用的进度回调不接管 owner，也不回放历史进度。调用方取消不会连带取消其父作用域或无关兄弟任务。SDK 的常规检查由自身单个静默任务拥有，不提供公开 `requestCheck()`；`shutdown()` 停止 SDK 调度器，同一进程仍保留根目录独占，不通过重建实例清空失败门槛。

配置入口是 Main-safe 的挂起 `ManagedConfigProvider.fetch(currentVersion: Int): ConfigResponse`。SDK 在同一次本地可用性检查后传入实际可用的 Int 版本，否则为 `0`；在 Main 确认资格、紧邻调用 provider 时记录本次单调时间，成功或失败均占五分钟间隔。宿主把现有接口响应映射为 `ConfigResponse.Success(OfflineConfiguration(enabled, onlineVersion, candidate))` 或类型化 `ConfigResponse.Failure`；既有 Retrofit 挂起调用可直接接入，适配器若做阻塞本地读写或同步请求，应自行 `withContext(Dispatchers.IO)`，并支持协程取消。有效关闭只需 `enabled = false`，不要求版本、URL 或 SHA。候选用 `OfflineCandidate(PackageRecord(version, sha256), PackageSource.Remote(url))`；Demo 用 `PackageSource.Local` 演示 APK 内置 ZIP。SDK 不知道业务 Entity、JSON 或 URL 生成规则。

`ManagedOfflineStorage` 仅保存 `active`、`enabled`、每环境一个 `PreparationHistory(initialPreparationFinished, latestFailure)`、以及缓存待清 Boolean。挂起读写由 SDK 在 IO 调度器调用，写入返回 `Boolean`，取消传播。`readCacheDirty()` 仍由 SDK 在 IO 读取；本候选的 `writeCacheDirty(dirty)` 为 `suspend`，宿主实现须在 IO 完成真实持久提交后才返回 `true`，失败返回 `false`，取消传播。Demo 用 IO 上的 `SharedPreferences.commit()`；不能以 `apply()` 后立即返回 `true` 冒充写盘成功。页面操作回到 Main 后只同步复核、清资源缓存和绑定，成功清理后的标记由 SDK 异步写盘，内存待清事实在持久确认前保持为脏；写失败下一页面可重试，旧清除写入不能覆盖后到配置置脏。`latestFailure` 只保存枚举原因/阶段、可选版本及摘要、时间和短诊断，不保存 Throwable、签名 URL 或失败次数。RC 的 `readLegacyEvidence()` 可选：仅在旧记录确实可识别时提供最近诊断；管理器同时以格式有效的旧 active 或可用旧包迁移首次完成事实。未知失败码由宿主解码为可空诊断，不能丢弃独立的首次完成标记。Demo 使用英文枚举名作稳定持久码，不使用 ordinal；业务上报仍需明确的协议映射。旧 remote/rejected/cache identity/ack 键可以留存供回滚，但不得继续作为运行策略读取或写入。

存储缺记录才返回 `null`，读取故障必须抛出异常。读取 active 等初始化事实失败时，SDK 保留全部目录，不提交空初始化事实，也不写迁移完成标记；`startupDecision()` 放行继续页面流程，`loadPage()` 实际回源。下一次合法入口仍能重新读取，不增加恢复账本或补写循环。

初始化诊断按故障来源区分：安装器或根路径准备失败报告 `LOCAL_PREPARATION / LOCAL`，active 等本地事实读取失败报告 `STORAGE_READ / LOCAL`。同一次故障在 `state.activity`、`onDiagnostic`（已授权时）及 `prepareFirst()` 的失败结果中使用相同类型；这些本地故障不产生安装终态。宿主按稳定枚举上报诊断，不解析错误文案。

### 最小接入链

```kotlin
// Application.onCreate，Main：直接保存一个稳定管理器。
val manager = ManagedOfflineSdk(root, storage, configProvider,
    minimumVersion = 100_000,
    onInstallationOutcome = reporter::submit,
    onDiagnostic = ::recordDiagnostic,
    downloadClient = existingDownloadClient)
manager.setConditions(privacyAllowed, foreground)

// Welcome/Preview 的调用协程：只负责这次首装的 UI 与导航。
if (manager.startupDecision() == StartupDecision.NEEDS_FIRST_PREPARATION) {
    manager.prepareFirst(onProgress = ::showProgress)
}

// 页面：单次挂起调用；SDK 在 IO 观察，回 Main 完成最终复核及 WebView.loadUrl。
manager.loadPage(url, baseUrl, callbacks)
```

`reporter`、`recordDiagnostic`、`storage`、`configProvider` 是宿主已有的轻量适配器或函数；示例回调名只表示边界，不要求新增万能 Host。初始化前后条件变化直接传给同一管理器，没有 graphReady、awaitGraph、awaitManager 或条件重放。

## 启动、调度和结果

Welcome/Preview 直接持有 Application 创建的管理器并调用 `startupDecision()`；判定挂起期间保持中性页面，不先展示首次进度。初始化运行故障放行导航，随后页面通过 `loadPage()` 实际加载线上地址；取消仍传播且不伪造导航。仅返回 `NEEDS_FIRST_PREPARATION` 时显示首次离线准备并在前台挂起调用 `prepareFirst(onProgress)`；返回 `CONTINUE` 时直接走业务配置、广告及导航，不等待静默更新。宿主用一个调用 Job 避免同一页面重复进入；SDK 合并跨页面并发首装。未进入前台时返回 `NotForeground`，隐私未允许时返回 `PrivacyRequired`。首次实际检查的失败或关闭也是一次正常结束，宿主可继续 OnlineFallback；初始化事实读取失败不据此写入首次完成。取消会抛出，既不完成首次事实也不制造失败终态。存活等待者遇 owner 取消后，应退出加载，提供显式重试/返回；重试只重新进入正常的启动判定和首装入口，不自动接管或清空门槛。单条进度依次表达 Checking、Preparing、Downloading/Extracting、Saving、Confirming 和 Complete；`Complete` 只在发布、active 保存及最终入口可用后发出。Demo 将下载/解压阶段限制为最高 99%，仅 `Complete` 显示总体 100% 和完成文案。检查、目标准备、保存、确认及安装终态来自 Main，下载/解压进度可来自安装 IO dispatcher；UI 必须使用线程安全状态或切 Main。重复调用者无细分进度回放，页面显示中性准备文案。

Application 在 Main 传 `setConditions(privacyAllowed, foreground)`，无需写五分钟循环或手动补查。首次已结束且允许前台时，SDK 根据实际 provider 调用起点的单调时间调度检查；冷启动、回前台和持续前台均由自身静默任务处理，不排积压请求。普通退后台停止后续调度，已开始安装独立继续；进入合法目标的准备阶段即算安装开始，包括必要的目标残留清理。撤回隐私取消首装实际任务和 SDK 静默任务，也由宿主取消其独立上报。不要把业务两小时后台后重启规则搬入 SDK。

每进程只维护最高失败版本：合法候选的目标准备、安装或 active 保存失败会抬高门槛；同版及更低版本跳过，更高版本可试。配置或无目标诊断不改变门槛。真实新进程从空门槛开始；`PreparationHistory.latestFailure` 仅供诊断，绝不恢复封禁。不存在退避 timer、候选 URL 账本、save-only 恢复、永久拒绝或失败补存循环。安装成功清最新失败诊断，但不会降低本进程失败门槛。

禁止降级独立于文件可用性：合法 active 为 100002，即使目录丢失且配置请求传 `currentVersion = 0`，也不会安装 100001；同版同 SHA 可以评估修复，更高版本可以评估更新。同版不同 SHA 的冲突、远端开关和在线版本在一次内存更新中生效，早于可挂起的 enabled 保存；保存挂起或失败不会让新页面继续绑定已知冲突的旧包。有效关闭优先生效。

`onInstallationOutcome` 只收到 `InstallationOutcome.Installed(record)` 或 `InstallationOutcome.Failed(failure)`。失败包含目标版本、`ManagedStage`、`ManagedFailureReason`，底层安装失败另含 `FailureReason`/HTTP 状态；普通跳过、关闭及取消不发安装终态。SDK 在统一收口处通知一次，同步接收器自身抛出的异常（包括 `CancellationException`）不会更改已完成安装的返回值或终态；实际更新任务的取消仍从协程边界传播。App 仅在此回调将类型映射后调度自己的进程级异步上报；配置/无目标故障走单独 `onDiagnostic`，不能从 `state`、进度或 Welcome 返回值二次上传。SDK 不调用报告 HTTP。成功上报的服务端编码尚待 App 阶段确认，不影响 SDK 的类型化成功结果。

报告任务归宿主：普通上传异常只作本地记录，真正的取消继续传播，不算安装失败；撤回隐私时关闭报告入口并取消已登记的报告子任务。Demo 的 `DemoOutcomeReporter` 演示这一边界，不重试、不排队补发，重新授权只接收新的终态。真实 Repository 的挂起接口必须支持取消。

`state: StateFlow<ManagedSnapshot>` 只读且订阅无副作用。它把资源可用性与检查/安装活动分开；Welcome 导航只消费本次 `FirstPreparationResult`，不从可回放状态重建一次启动的结论。

## 页面与缓存

页面只挂起调用一次 `loadPage(url, baseUrl, callbacks, ...)`。SDK 内部在 IO 完成入口可用性观察及路径规范化，回 Main 后复核资格并准备拦截器，再按需清理资源缓存、检查取消；离线页随后设置目录保护并交付原 URL 加载，宿主不持有中间观察值。`baseUrl` 是以 `/` 结尾的目录 URL，宿主仅把后端站点根地址或 `index.html` 入口规范成这一格式。最终提交只复核内存中的开关、active、在线版本、同版摘要冲突与观察代际，不再读文件或规范化路径；SDK 清理或同版修复会使旧观察失效，清理期间取得的观察也不能提交为离线页。实际顺序是按需调用 `ManagedPageCallbacks.clearResourceCache()` → 检查调用协程是否取消 → 离线页设置目录保护 → `loadOffline(directory, interceptor, url)`；线上页在同一取消检查后调用 `loadOnline(url)`。返回的页面决定不代表缓存标记已写盘成功。

宿主只执行 WebView 资源缓存清理与绑定/加载，不拿 SDK 锁或重做版本选择。`clearResourceCache()` 须包含内存及磁盘资源缓存，Demo 调用 `WebView.clearCache(true)` 后确认；不清 Cookie、localStorage、登录态或业务数据。返回 false 或缓存标记写 false 失败时，SDK 保留待清状态，下一页面重试。默认在线加载也消费待清标记。宿主以挂起存储适配器返回真实持久结果，SDK 在 IO 调度并确认；Main 同步清资源缓存及绑定，实际线程耗时仍需设备检查。

离线页面固定在绑定时的目录；任一页面绑定后，进程内不删除或覆盖任何版本目录，新版本仍可安装到全新目录。上述保证依赖 SDK 独占根目录，宿主及其他进程不得直接改动版本文件；任意外部删除不是内存代际可观察的操作，只能在下一次 IO 观察或资源请求发现，资源缺失按拦截器规则回源。`OfflineInterceptor` 在每次资源请求读取管理器的实时开关，远端关闭后旧页的后续拦截立即返回默认加载。已打开页面不主动刷新。历史 Android 16 设备验证了系统 WebView 在原 URL 加载本地 HTML/JS；Main StrictMode 检测器已修正且测试源码已编译，当前源码的设备运行证据仍待取得。系统 WebView 的 Cookie/请求头/Range/缓存行为、X5 实际内核、页面操作耗时及完整 Demo 生命周期仍需设备验收。

## 迁移核对

- 复用旧 active/enabled/版本目录格式；按环境隔离新的 History，先检查旧 active 的格式或可靠旧失败/拒绝证据。若存储不可写，首次本进程仍放行，但下一进程可能重新显示首次 UI。
- active 保存后最终入口复核若普通失败，SDK 会尝试恢复旧 active；失败终态的 `activeRollbackFailed` 指出回滚也失败。本进程继续保留已确认可用的旧 active，下一次页面或 TTL 检查不能用失败目标覆盖它；持久 active 仍可能与内存不一致，下一进程依实际存储和目录解释。若 active 写入期间发生取消、持久副作用尚不确定，下一合法入口才重读存储与目录，确认后用于防降级和页面选择。存储无法提供跨 key 事务时不承诺完全原子。
- 删除 App 中的轮询、失败版本判断、目录清理/保护和错误文案分支；保留 Repository/Retrofit/Moshi、MMKV 编码、隐私/前后台事实、业务配置与导航、WebView 操作、独立报告任务。
- `PackageInstaller` 与低层安装结果继续可用，但不得与同根托管管理器同时修改目录。`OfflineInterceptor` 增加默认的运行期开关参数；源码调用可重编译，依赖旧构造函数的二进制须重新编译。
- 本地 AAR 消费样例同时编译新的管理器入口与旧低层 `PackageInstaller` 构造/只读检查、`OfflineInterceptor` 构造/资源解析调用；旧二进制消费者仍按上条限制重编。低层样例使用独立根目录，不能拿它绕过托管目录保护。
- RC 可编译薄宿主：[DemoApplication](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app/src/main/java/com/offline/tool/sample/DemoApplication.kt)、[DemoManagedStorage](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app/src/main/java/com/offline/tool/sample/offline/DemoManagedStorage.kt)、[DemoOutcomeReporter](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app/src/main/java/com/offline/tool/sample/offline/DemoOutcomeReporter.kt)、[WelcomeViewModel](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeViewModel.kt)、[MainActivity](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app/src/main/java/com/offline/tool/sample/MainActivity.kt)。

内部职责及规则维护位置见 [代码质量整改说明](SDK-CODE-QUALITY-REFACTOR.md)；本轮不向 App 增加管理策略。
