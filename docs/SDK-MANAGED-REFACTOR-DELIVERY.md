# SDK 初始化与公开 API 精简 v2 交付回执（2026-09-28）

> **历史回执，非当前候选**：本文记录 2026-09-28 v2 源码、旧双步页面 API、AAR 和当时的测试结果。后续 F1–F4 整改见[9 月 28 日回执](verification/2026-09-28-regression-fix/verification.md)；2026-09-29 减负与单入口页面 API 的当前候选请以[新回执](verification/2026-09-29-complexity-reduction/README.md)为准。本文的源码摘要、代码行数、9/9 设备结果及旧双步页面测试均不能证明新候选；F4 当前设备证据仍独立开放。

对应宿主侧架构精简 v2 交接。本轮从 b25b08 **未提交候选**继续修改 SDK、Demo、测试、迁移和消费样例；又按 v2 复审完成 E1 诊断分类与 E2 回归证据收尾。未修改业务 App。结构与自动化行为已通过前轮复审；设备和正式产物门仍开放，**不接入业务 App**。

## 身份、路径与发布状态

| 项目 | 2026-09-28 v2 当轮事实 |
| --- | --- |
| 仓库与分支 | `$SDK_REPO`；`main`。HEAD `ef55eb17e659d9f77ee714a9a37a9bb9d0259921` 仍是既有 0.2.2 历史提交。本轮 SDK、Demo、测试、文档和证据全是**未提交工作区改动**，没有本轮提交 SHA；见[状态快照](verification/2026-09-28-sdk-v2-closeout/git-status.txt)。 |
| 本轮真实基线 | 本地 AAR SHA-256 `b25b0874d7b46410495ab48cb23cc820be609d48d16b617094b5c84daa6e8c00`，管理器 SHA-256 `d162aa4db504bbed0a5f029c60c725766f59fabba0cc12138d65c19cb09e4555`。开始修改前保存的全部 SDK＋Demo 源码、测试和身份在 `build/reports/sdk-init-api-v2/baseline/`；不以 Git HEAD 或更旧候选作本轮差异基线。 |
| 当轮 API | [ManagedOfflineSdk.kt](../offlineSdk/src/main/java/com/offline/tool/ManagedOfflineSdk.kt) 的当轮 SHA-256 为 `c273a7577067967c11931e155d90568891ec438fad560350fe2a9150dfdabae9`；[ManagedOfflineTypes.kt](../offlineSdk/src/main/java/com/offline/tool/ManagedOfflineTypes.kt)。其余 SDK＋Demo 当轮生产文件摘要见[逐文件对比](verification/2026-09-28-sdk-v2-closeout/production-compare.json)；当前调用形态见[迁移说明](MIGRATION-MANAGED-0.3.0.md#最小接入链)和[AAR 消费源码](../scripts/aar-consumer-smoke/src/com/offline/tool/sample/OfflineSdkAarSmoke.kt)。 |
| Demo | [DemoApplication.kt](../app/src/main/java/com/offline/tool/sample/DemoApplication.kt) 直接持有 manager；[存储适配](../app/src/main/java/com/offline/tool/sample/offline/DemoManagedStorage.kt)、[报告适配](../app/src/main/java/com/offline/tool/sample/offline/DemoOutcomeReporter.kt)、[Welcome](../app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeViewModel.kt)、[页面](../app/src/main/java/com/offline/tool/sample/MainActivity.kt)；见[Demo 说明](DEMO.md)。 |
| 当轮本地候选 | 工程 `build/repo/com/github/mobilewhj/offlineSdk/offlineSdk/0.3.0/` 当时的 AAR SHA-256 为 **`79616940830c3a39d188556169046d831c32a72113582b881ea276b73961bcb6`**，sources JAR SHA-256 为 `0158e130669972a54a8023a42242748fb58507db8b2d2b33f8408eb72a37812d`。当轮候选 15/15 SDK 生产源码与 sources JAR 逐字节一致，AAR 与 Release 输出一致，四种产物 SHA-256 sidecar 均匹配；见[校验 JSON](verification/2026-09-28-sdk-v2-closeout/artifact-verification.json)。 |
| 坐标与正式发布 | `com.github.mobilewhj.offlineSdk:offlineSdk:0.3.0` **仅在工程本地 Maven 仓库消费验证**。未提交、未推送、未打 tag、未正式发布，也没有远端不可变坐标或远端解析证据。已发布 0.2.2 低层源码兼容保留；既有旧二进制需重编限制不变。 |

## 最小接入和实际删除

当轮 Application 在 Main 直接创建 `ManagedOfflineSdk`，Welcome 按需调用 `prepareFirst()`，页面仍使用 `preparePage()` 与 `commitPage()` 两步；当轮 Demo 与本地 AAR 消费编译了这条历史调用链。当前未发布高层 API 已删除这两步，宿主页面只挂起调用一次 `loadPage(url, baseUrl, callbacks)`；首装仅用 `prepareFirst(onProgress)` 接收进度。当前调用样例见[迁移说明](MIGRATION-MANAGED-0.3.0.md#最小接入链)，新构建与消费结果见[新回执](verification/2026-09-29-complexity-reduction/README.md)。

本轮从宿主删除 `DemoGraph`、可空 graph、`graphReady`、`awaitGraph()`、ViewModel `awaitManager`、进程协程中异步创建 graph、发布前条件重放及页面等待 graph 的两处步骤。存储适配器仅延迟到既有 IO 读写入口打开文件/偏好对象，没有另造 Host/Builder 或 SDK 就绪 Future。高层公开构造从 **11 项**缩为三项必需（`root`、`storage`、`configProvider`）和四项可选（`minimumVersion`、`onInstallationOutcome`、`onDiagnostic`、`downloadClient`）。`ioDispatcher`、`monotonicMillis`、`checkIntervalMillis`、`downloadTimeoutMillis` 只留 SDK 内部测试/实现；生产五分钟与 120 秒总时限不变。主构造为私有，内部测试使用 `@JvmSynthetic` 工厂；[字节码构造标志](verification/2026-09-28-sdk-init-api-v2/api-constructor-flags.txt)显示七参数构造为唯一非 synthetic 的公开构造，其他是 Kotlin 默认参数/私有访问桥；本地 AAR 跨模块源码编译证明公开调用可用。

SDK 内部删去 `bootstrapJob` 与 `pollingJob` 两套前台生命周期，`monitorJob` 先初始化再检查首次/TTL；`silentJob` 仍是独立的 SDK 任务。首装清理在整个 `coroutineScope` 返回后释放 handle，删除显式 `NonCancellable`/`join`。上轮已删除的管理器 `gate`、公开 `requestCheck()`、通用 Job 登记和管理器重复根登记没有恢复。剩余 `initialization` 串行化跨挂起首次读取与安全清理；`operation` 防止首装事实已发布但 owner 尚未收尾时与静默更新交叉；`firstJob` 保留并发 Welcome/Preview 共享结果与取消隔离；页面代际与绑定标记保护目录。详见[结构说明](SDK-CODE-QUALITY-REFACTOR.md)。

## B1–B6 / D1–D4 整改证据

| 要求 | 实施与行为证据 |
| --- | --- |
| B1 / D1 初始化归 SDK | Main 构造不触发 canonical/记录/网络；首次合法入口在 IO 创建安装器，回 Main 检查取消和关闭后 claim 并立即保存所有者，再读事实。`mainConstructionDefersCanonicalIoAndSameManagerRecoversAfterPreparationFault`、Demo 公开构造测试；取消/关闭晚到结果和同 canonical 根并发拒绝测试。已 claim 后读失败由同实例复用，不重复登记。 |
| B2 / D2 故障降级及恢复 | 当轮读取或路径准备运行故障返回不可离线页面观察；旧 `commitPage()` 实际调用 `loadOnline()`，初始化未知时不在 Main 打开尚未就绪存储。故障移除后同一管理器下一合法旧 `preparePage()` 恢复离线；`pageEntryCanRecoverAfterAnEarlierLocalReadFailure`、`mainConstructionDefersCanonicalIoAndSameManagerRecoversAfterPreparationFault` 是当轮证据。当前两步已合为 `loadPage()`，真实取消仍不得导航或伪造完成。 |
| B3 / D4 API 收窄 | 唯一非 synthetic 公开构造 3＋4 项；最小三项和四项可选在本地 AAR 消费源码中分别编译，已发布低层 `PackageInstaller`/`OfflineInterceptor` 源码调用同时编译。测试调度和时间通过 SDK 内部工厂，不重新暴露生产参数。 |
| B4 宿主直接持有 | Demo Application、Welcome、Main 的真实调用链无等待/发布协议；13 项 Demo 单测覆盖直接注入、前台隐私、首装等待者 UI、进度及独立报告。配置与存储适配器仍只做各自编码/请求。 |
| B5 内部协调 | 单 `monitorJob` 与独立 `silentJob`；结构化首装收尾。持续前台、退后台/回前台、撤回隐私、首装/静默并发与五分钟测试通过；保留 `operation` 的完整更新互斥，未新增事件队列或恢复状态机。 |
| B6 / D3 保留业务、可审差异 | 并发首装一次结果、等待者取消隔离、owner 取消后显式恢复仍通过；下表给出 C/R/Q 映射。全量逐文件源码差异和产物摘要来自 b25b08 基线与最终源码，不把行数当作精简证明。 |

## SDK＋Demo 全部生产代码对比

统计两个模块所有 `src/main/**/*.kt|java`，含全部助手与新增文件；逐文件行数和 SHA-256 见[生产代码对比](verification/2026-09-28-sdk-v2-closeout/production-compare.json)，实际源码差异见[统一 diff](verification/2026-09-28-sdk-v2-closeout/structure.diff)。

| 范围 | b25b08：文件 / 物理行 / 非空行 | v2：文件 / 物理行 / 非空行 | 物理行变化 |
| --- | ---: | ---: | ---: |
| SDK | 15 / 1983 / 1828 | 15 / 2032 / 1876 | +49 |
| Demo | 9 / 638 / 582 | 9 / 598 / 546 | −40 |
| 合计 | 24 / 2621 / 2410 | 24 / 2630 / 2422 | **+9** |
| 其中 `ManagedOfflineSdk.kt` | 1 / 781 / 730 | 1 / 830 / 778 | +49 |

管理器增加的是内部初始化交接、关闭/取消边界和真正私有构造的 SDK 内部测试工厂；Demo 减少的是宿主跨线程 graph 协议。总代码未减少，不能据此宣称体积精简；本轮收益是少了一整段宿主创建/等待/重放责任、一套前台任务生命周期和四个公开可调选择。没有通过搬文件、删注释或压排版换取数字。

## v2 复审后 E1/E2 收尾

- **E1 诊断分类**：[初始化实现](../offlineSdk/src/main/java/com/offline/tool/ManagedOfflineSdk.kt) 在安装器/路径准备异常时提交 `LOCAL_PREPARATION / LOCAL`，在 active 等本地事实读取失败时提交 `STORAGE_READ / LOCAL`。本次失败事实同时供状态、授权后的诊断和首装失败结果使用；失败仍可走线上，同一管理器后续合法入口仍可恢复。`ManagedInitializationTest` 在既有 canonical 和读取故障场景补了类型与一致性断言。公开 API 无变化，也没有新增错误框架。
- **E2 外部证据**：旧六项的入口和部分观察方式确实发生迁移，并非“断言不变、仅改构造”。[新映射及七项探针](verification/2026-09-28-sdk-v2-closeout/external-probes/MAPPING.md)补强缺包防降级用例，要求 provider 实际收到 `[0]`；回调取消用例改为验证 `prepareFirst()` 正常返回 `Finished(Installed(...), true)`；另加旧首装取消清理窗口重入测试。旧探针、旧日志和勘误说明保留在历史验证目录。
- 当轮仅改管理器诊断来源、既有初始化测试、外部探针及文档。Demo 产品代码和公开构造没有再变化；相对上次固定 v2 候选，管理器从 827 到 830 行，SDK＋Demo 合计从 2627 到 2630 行。行数增加不作为结构收益，当轮产物与回归均按当轮源码重新核对。

## C1–C9、R1–R9、Q1–Q3 回归映射

| 契约 | v2 当轮实现与测试证据 |
| --- | --- |
| C1 入口与根 | 唯一管理器复用安装器；初始化在 IO 规范路径，Main 接管；同根并发仅一方成功，取消/关闭无晚到 claim，低层新写被拒、只读保留。初始化与 `managedRootRejectsOtherLowLevelMutationsButKeepsReadOnlyCompatibility` 测试。 |
| C2 首次与静默 | `startupDecision()` 判断，首装共享 owner 结果，History 正常完成；后次无包由 SDK 自有监控检查。并发首装、取消和 Demo Welcome 测试。 |
| C3 五分钟与失败门槛 | `lastCheck` 取真实 provider 入口；前台五分钟且无积压。`failedVersion` 仅进程内、同低版拦截/高版可试；配置故障不抬门槛。`silentChecksUseActualProviderEntryForFiveMinuteInterval`、`failedVersionBlocksSameAndLowerVersionsButHigherVersionCanRun`。 |
| C4 取消与线程 | Main 管理事实、IO 做阻塞准备；后台只停监控等待，隐私撤回取消 SDK 在途更新而不伤宿主兄弟任务。首装 owner/等待者、后台/隐私与 IO 线程测试。 |
| C5 存储与错误 | active/enabled/单值 History/缓存 Boolean；读错不等于无记录、不授予清理。配置等无目标故障只诊断，不伪造安装终态；初始化与 History 测试。 |
| C6 页面与缓存 | 当轮页面使用 IO `preparePage()`、Main 同步 `commitPage()`；代际复核、目录保护及在线缓存测试属于旧候选。当前改为单次挂起 `loadPage()`，SDK 内部保留 IO 观察与 Main 复核；本行的系统 WebView 本地 HTML/JS 设备结果只属于当轮，当前设备证据见[新回执](verification/2026-09-29-complexity-reduction/README.md)。 |
| C7 薄宿主 | Demo 直接持有七参数管理器，无轮询、失败版本判断、目录算法或 graph 等待；AAR 消费和 Demo 13 项通过。 |
| C8 迁移消费 | 旧 active/enabled/目录解释保留；sources JAR 15/15 字节一致，实际消费者解析新 AAR 摘要；正式不可变产物尚无。 |
| C9 类型化一次终态 | 目录发布、active 保存和入口可用后才通知成功；真实目标失败通知一次，跳过/取消不通知，History 或接收回调取消不改既成终态。安装、回滚、回调测试；真实上报仍归 App。 |

R1 读错保目录和后续恢复：`incompleteLocalReadsPreserveDirectoriesAndRetryBeforeCommitting` 与外部探针；R2 撤回隐私取消 SDK 静默激活：外部探针；R3 缺包仍防降级及同版修复：补强探针证明 provider 收到 `[0]`，并有 `missingActiveFilesStillAllowSameDigestRepairOrHigherVersion`；R4 同版 SHA 冲突在 enabled 保存挂起/失败前阻止旧页：外部两项；R5 结果接收器取消不改安装成功：补强探针验证 `prepareFirst()` 返回 `Installed`；R6 页面 Main 无包文件读取：`pageCommitUsesPreparedFactsWithoutReadingPackageFiles`；R7 下载客户端注入：安装测试与 AAR 消费，**总下载时限现为内部固定 120 秒**；R8 Demo `WebView.clearCache(true)` 与缓存标记测试；R9 首次进度、存活等待者显式恢复与独立报告：Demo 13 项。完整[七项映射](verification/2026-09-28-sdk-v2-closeout/external-probes/MAPPING.md)逐项列明入口和断言迁移；首装取消清理窗口另有新增探针。

Q1 URL 规则仍集中 `OfflineUrlRules`；Q2 `InitialLocalFacts` 只读解释，管理器提交、迁移和授权清理；Q3 无单次 History 保存全局开关，首装与静默各自保存。已有对应单测在 117 项 SDK 回归中重跑。

## 实际命令与结果

以下均在 SDK 仓库执行。当轮[测试汇总与独立保存的 JUnit XML](verification/2026-09-28-sdk-v2-closeout/test-summary.json)：仓库 SDK **117/117**、外部补强 **7/7**，合并 **124/124**；Demo **13/13**；PHP110 / Android 16 设备 SDK **9/9**，均零失败、零错误、零跳过。完整构建强制重跑全部 192 个 Gradle 任务。SDK lint 为 2 条 Warning、零 Error，Demo lint 为 17 条 Warning、零 Error。

```sh
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintRelease :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -I $TRACKING_REPO/.trellis/tasks/09-27-offline-sdk-architecture-simplification/research/sdk-v2-acceptance-2026-09-28/rerun-tests.init.gradle --rerun-tasks --no-daemon --console=plain
```

[全量日志](verification/2026-09-28-sdk-v2-closeout/full-build.log)：`BUILD SUCCESSFUL`，包括 SDK lintRelease、Release、Android 测试源码编译、Demo lintDebug、Debug/Release/R8；仓库 SDK XML 单独保存为 117 项。

```sh
./gradlew :offlineSdk:testDebugUnitTest -I $TRACKING_REPO/.trellis/tasks/09-27-offline-sdk-architecture-simplification/research/sdk-v2-acceptance-2026-09-28/review-probes.init.gradle -PreviewProbesDir=$SDK_REPO/docs/verification/2026-09-28-sdk-v2-closeout/external-probes -I $TRACKING_REPO/.trellis/tasks/09-27-offline-sdk-architecture-simplification/research/sdk-v2-acceptance-2026-09-28/rerun-tests.init.gradle --rerun-tasks --no-daemon --console=plain
```

[外部七项日志](verification/2026-09-28-sdk-v2-closeout/external-regressions.log)：`BUILD SUCCESSFUL`，`ReviewRegressionTest` 的七项 JUnit XML 实际出现，与仓库测试合计 124/124。初次增量注入时只运行了仓库 117 项；已强制重新编译探针并用 XML 核对，不把那次日志计作外部通过。

```sh
./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository -PsdkVersion=0.3.0 --no-daemon --console=plain
./gradlew :app:verifyOfflineSdkAar :app:dependencyInsight --configuration debugRuntimeClasspath --dependency offlineSdk :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug -PsdkVersion=0.3.0 -PsmokeSdkVersion=0.3.0 -I scripts/aar-consumer-smoke.init.gradle -I $TRACKING_REPO/.trellis/tasks/09-24-offline-sdk-managed-refactor/research/sdk-quality-acceptance-2026-09-27/rerun-tests.init.gradle --no-daemon --console=plain
python3 scripts/generate-sample.py --check
git diff --check
```

[本地候选日志](verification/2026-09-28-sdk-v2-closeout/local-candidate.log)与[AAR 消费日志](verification/2026-09-28-sdk-v2-closeout/aar-consumer.log)均 `BUILD SUCCESSFUL`；`dependencyInsight` 当时指向本地 0.3.0 AAR，消费者打印的 SHA-256 `79616940…61bcb6` 与上表当轮候选完全相同。消费侧 Demo 13 项、Debug/Release R8/lint 当轮通过。样例 ZIP 检查和 diff 空白检查退出码 0，见[样例日志](verification/2026-09-28-sdk-v2-closeout/sample-check.log)及[差异日志](verification/2026-09-28-sdk-v2-closeout/diff-check.log)。本地仓库写入仅供消费验证，不是正式发布。

```sh
./gradlew :offlineSdk:connectedDebugAndroidTest --no-daemon --console=plain
```

[设备最终日志](verification/2026-09-28-sdk-v2-closeout/connected-android-final.log)与[设备 XML](verification/2026-09-28-sdk-v2-closeout/device/TEST-PHP110-16-offlineSdk.xml)：9/9。既有七项验证拦截器资源规则；新增两项在 Main 严格磁盘策略下构造管理器并同步设置条件，以及让[系统 WebView](verification/2026-09-28-sdk-v2-closeout/device/webview-provider.txt)（`com.google.android.webview` 138.0.7204.179）从 SDK 本地拦截加载 HTML/JS，验证原 URL。新增设备测试源码 SHA-256 `9af416a6e86f846aabbe2c584030b3adb4c11625d9661c4ecdc1b12472170100`。首次扩展设备运行的 WebView 测试在 instrumentation 线程读取 `WebView.url`，触发线程错误；已将该测试读取改到 Main，最终九项重跑通过。设备侧测试覆盖不代替 Demo 完整生命周期或 X5 内核运行。

## 契约差异、风险与后续验收

- 与旧规划建议相比，高层从 WorkerThread 构造和宿主等待 graph 改为 Main 轻量构造、SDK 内部挂起准备；同 canonical 根重复实例从构造时改为首次初始化接管时拒绝。运行故障降级线上，下一合法入口同实例恢复；既有失败版本门槛与 TTL 不重置。高层四个技术选项内部化；低层 0.2.2 源码调用和旧二进制需重编限制保留。
- Demo 仅写本地安装结果日志，没有业务成功编码、真实 Repository→Retrofit/Moshi 上报或后端成功响应证据。类型化结果与 App 独立上传的分工保持。
- 设备上已验证 SDK 九项、系统 WebView 本地 HTML/JS 加载、管理器 Main 构造与条件同步入口的 StrictMode 磁盘边界。Demo 完整隐私/前后台生命周期、页面绑定后的目录行为、系统 WebView Cookie/请求头/Range/缓存效果、Main 页面提交耗时与 X5 实际内核仍**未运行验收**；不能把上述局部设备测试写成全部运行门通过。
- 真实业务 Repository→Retrofit/Moshi 上传和后端成功编码仍归后续 App 阶段。没有实现提交、不可变 tag、远端 POM/AAR 或正式消费证据；本地 `0.3.0` 不是正式发布。原规划任务继续复审交付门，业务 App 等待。
