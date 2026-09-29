# 托管 SDK 结构说明（0.3.0 v2 候选）

本说明以未提交的 b25b08 候选为本轮起点。Q1–Q3、R1–R9 和先前主流程修复继续保留；本轮针对初始化、公开构造和前台任务所有权，并按 v2 复审收尾初始化诊断分类与外部回归证据。完整基线与当前逐文件对比见[生产代码对比](verification/2026-09-28-sdk-v2-closeout/production-compare.json)，交付事实见[回执](SDK-MANAGED-REFACTOR-DELIVERY.md)。行数仅描述变化，职责和调用链才是复审依据。

## 接入链和职责

`DemoApplication.onCreate()` 在 Main 直接创建一个 `ManagedOfflineSdk` 并持有它。生命周期或隐私变化同步调用 `setConditions()`。Welcome 直接注入同一对象，先 `startupDecision()`，必要时 `prepareFirst(onProgress)`。页面直接取同一对象，挂起调用一次 `loadPage()`；SDK 内部完成 IO 观察和 Main 最终复核，再调用线上或离线加载。安装终态进 App 独立报告任务，配置等无目标诊断只进诊断入口。完整代码示例见[迁移说明](MIGRATION-MANAGED-0.3.0.md#最小接入链)。

Demo 不再拥有可空 graph、`graphReady`、`awaitGraph()`、`awaitManager`、异步发布和创建期条件重放。SDK 构造不规范化根路径、不创建安装器或读取记录；Demo 存储适配器的文件和偏好对象也延迟到现有 IO 读写入口。宿主只负责配置/存储编码、条件事实、UI/WebView 与上报，不需要知道 SDK 何时“创建完成”。

## 初始化与状态所有者

| 事实或许可 | 唯一职责与保留理由 |
| --- | --- |
| `preparedInstaller` | 首次合法入口在 IO 构造，回 Main 复核关闭及取消后用安装器自身的一套根登记接管；接管成功立即保存。记录读取失败仍复用它，不重复 claim 或重建管理器。 |
| `initialization: Mutex` | 串行化启动、页面和监控可能同时触发的本地读取、事实提交、迁移保存和冷清理；Main 在 IO 挂起期间可被其他入口重入。 |
| `operation: Mutex` | 首装完成标记可能先于 owner 子任务全部收尾，监控可据此尝试静默工作；更新许可覆盖配置、安装、active 和 History 收尾，避免两条路径交叉保存。删除需要覆盖此窗口及报告回调重入，本轮不以 Main 串行代替完整互斥证明。 |
| `firstJob` | 首个调用者的子任务拥有一次首装；其他调用者只等待结果。等待者取消不取消 owner；owner 取消后，整个 `coroutineScope` 已收尾才清 handle。 |
| `monitorJob` / `silentJob` | 前者只拥有初始化和五分钟等待，后者独立拥有已经开始的静默更新。退后台取消等待，撤回隐私与关闭再取消在途更新。 |
| `pagesBound`、`directoryGeneration`、`cleanupInProgress` | 单次页面操作内的 IO 观察可能与清理交错，回 Main 后只复核代际；绑定后不删除或覆盖已有版本目录，新版本目录仍可安装。 |

初始化的主顺序是“IO 准备安装器 → Main 检查关闭/取消并 claim → IO 读取本地事实 → Main 提交并安全冷清理”。IO 返回取消时不留下无主根登记，已接管后读失败不清理未知目录；运行故障返回在线资格，下一合法入口在同一实例重新读取。初始化未知的页面提交只清 WebView 资源缓存并实际调用 `loadOnline()`，不会在 Main 打开未就绪存储。纯参数错误和同根重复实例仍明确拒绝。

初始化故障由发生边界一次定类：安装器/路径准备为 `LOCAL_PREPARATION / LOCAL`，本地事实读取为 `STORAGE_READ / LOCAL`。Main 保存本次类型化失败，状态、授权后的诊断及首装失败返回共同引用这次事实；下一合法入口成功后恢复正常状态。没有增加异常分类框架或宿主恢复协议。

## 主流程和规则维护位置

`executeCheck()` 读取当前可用性、请求配置、先提交页面可见的配置事实，再调用无 IO、无隐藏状态读写的 `decideCandidate()`。候选决策只返回安装、跳过或无效配置；共同 SHA 冲突规则由 `hasShaConflict()` 维护。活动状态、失败门槛和终态由主流程提交。`installCandidate()` 的顺序仍是目标目录准备、现有安装器安装、active 保存与可用性复核、一次完整成功状态提交。底层下载、ZIP、发布和根独占仍归 `PackageInstaller`，管理器不复制它们。

前台监控由一个 `monitorJob` 先初始化、再根据首次完成和真实 provider 起点的 TTL 等待；它不拥有 `silentJob`。首次完成、前台条件改变及本地入口恢复均调用同一个调度查询。到期时若另一个入口占用更新许可，现有让出周期仍保留，避免空转；没有引入唤醒队列或新的运行状态。取消在等待、请求前和目标安装边界重新检查，普通后台与隐私撤回保持不同语义。

Q1 的 URL 规则仍集中在 `OfflineUrlRules`。Q2 的 `InitialLocalFacts` 只做只读解释，管理器负责提交与清理授权。Q3 的缓存待清状态由 SDK 调用存储端口控制，没有全局“单次保存策略”开关。修改候选版本/SHA 规则主要落在 `decideCandidate()`/`hasShaConflict()`；修改页面资格在 `loadPage()` 的 Main 最终复核；修改磁盘安全在安装器和页面代际边界，不能把这些独立职责简单压成一个布尔判断。

## 实际删除与未删除

- 本轮删除宿主 graph 创建完成协议和条件重放；公开高层构造从 11 项收为三项必需、四项可选，测试时钟/调度/周期和 120 秒总时限留 SDK 内部。
- 本轮删除 `bootstrapJob` 与 `pollingJob` 两套前台任务生命周期，改由单个 `monitorJob`；删除首装的显式 `NonCancellable`/`join`，由结构化并发完成收尾。
- 上轮已删除的管理器 `gate`、公开 `requestCheck()`、通用更新 Job 登记及重复 `claimedRoots` 没有恢复。App 的 `DemoOutcomeReporter` 仍有自己的短锁，保护独立上报任务登记，与管理器状态锁无关。
- 保留 `operation`、`initialization`、首装共享 handle、独立静默任务与目录代际，因为它们分别解决跨挂起互斥、并发调用、后台取消所有权和页面安全。没有新增 Builder、Options、Host、恢复账本或事件队列。

## 验证边界

以下 [9 月 28 日全量构建日志](verification/2026-09-28-sdk-v2-closeout/full-build.log)、[外部七项](verification/2026-09-28-sdk-v2-closeout/external-regressions.log)、[本地 AAR 消费](verification/2026-09-28-sdk-v2-closeout/aar-consumer.log)与[产物校验](verification/2026-09-28-sdk-v2-closeout/artifact-verification.json)只对应当轮 v2 源码。[当轮设备九项](verification/2026-09-28-sdk-v2-closeout/connected-android-final.log)记录了拦截器规则和系统 WebView 本地 HTML/JS 加载等历史运行结果；Main StrictMode 检测器之后已修正，旧结果不能证明当前边界。2026-09-29 当前源码、AAR 消费与 F4 设备边界见[本轮回执](verification/2026-09-29-complexity-reduction/README.md)。Demo 完整生命周期、X5 实际内核、WebView 缓存/Cookie/请求边界、页面 `loadPage()` StrictMode、真实上传及正式发布仍分别验收。
