# SDK 生产代码减负与接入收敛回执

> **发布前代码验收快照。** 下文的 HEAD、`0.3.0` 本地 Maven 路径、文件摘要及“尚无提交/远端发布”只描述 2026-09-29 验收当时的状态，不是 `0.3.0-rc.1` 发布结果。测试候选固定 tag、GitHub Pre-release、JitPack 产物及真实远端消费须由本轮发布回执另行记录；正式 `0.3.0` 未发布，F4 等未验项保持开放。

2026-09-29，执行仓库 `$SDK_REPO`，关联宿主侧 SDK 复杂度整改任务。基于 `main` 的未提交 0.3.0 候选继续修改，HEAD 仍为 `ef55eb17e659d9f77ee714a9a37a9bb9d0259921`。本记录包含独立复审后的 P2/P3 局部收口。保留原有未提交修改；未修改业务 App，未提交、推送、打 tag 或远端发布。

## 结构结果与 R0–R5

| 要求 | 实际改动与行为证据 |
| --- | --- |
| R0 / F2-R | 仅 active 写入/确认**取消**时置 `activeNeedsRefresh`；普通保存或最终复核失败不再置位。最终复核失败且回滚 false 后，本进程保留旧 100001 active。`finalUsabilityFailureRollsBackActiveAndReportsTheRollbackResultOnce` 延伸到下一页面、TTL 请求 `[100001, 100001]`、配置回旧版后再次离线加载及 Failed 仅一次。写前/写后取消恢复由 `ActiveSaveCancellationProbe` 保留，F1 History 取消与 F3 缓存 IO 确认回归通过。 |
| R1 | 删除 `Terminal` 类及 `executeCheck`、`installCandidate`、安装提交/失败函数之间的 carrier 传参。首装与静默入口在 `executeCheck` 返回后、History 挂起前保存本次 `CheckResult`；外层 `finally` 在更新锁释放后仅把 Installed 或带目标版本的 Failed 映射为一次 `InstallationOutcome`。History 取消不丢已成立终态；跳过、配置故障和真实取消不补安装终态。 |
| R2 | 删除 `waitingForFirst` 与 finally 重启分支、调度按失败 `ManagedStage.LOCAL` 判断的分支、无人使用的公开 `CheckResult.TooSoon`。`silentJob` 只返回 `Unit`；调度仅依据资格、任务状态及真实 provider 请求时间安排下一周期。初始化入口只调用一次 `attemptColdCleanup`，首装/静默消费 `coldCleaned`，失败保留首次正常收尾且不进入 provider。复审后静默检查用本次局部 `cleanedForCheck` 同时选择检查和诊断，History 挂起期间页面恢复清理不会给旧失败再报 LOCAL。页面在清理失败时实际走线上，下一合法入口可再清理。 |
| R3 | 删除 `prepareFirst(onActivity, onProgress)` 中的 `onActivity` 端口和全链传参，保留全局 `Snapshot.activity`。唯一的 `ManagedProgress` 回调包含 Checking、Preparing、Downloading、Extracting、Saving、Confirming、Complete。Demo Welcome 只存本次 progress，不再组合 activity 与 progress；owner 收进度，等待者共享结果且不接管进度。 |
| R4 | 删除公开 `PagePreparation`、`preparePage()`、`commitPage()` 及 owner/token 握手；公开页面操作只剩 `suspend loadPage(url, baseUrl, callbacks, ...)`。SDK 在 IO 观察文件与 canonical 目录，回 Main 复核 active、开关、在线版本、摘要冲突、目录代际和清理状态；其后无挂起地清资源缓存、保护目录、绑定拦截器并加载原 URL。取消后不迟到加载；缓存回调取消时不会留下未加载页面的绑定标记。 |
| R5 | SDK、Demo、测试、接入文档和本地 AAR 消费样例同步。实际生产文件总数不增加，管理器及总行数都净减少；注释/KDoc 新改内容使用中文。未添加 Coordinator、恢复账本或宿主重试策略。 |

三条主流程的所有权：首装由第一个调用者的子任务拥有，等待者只共享结果；静默检查由 SDK 进程任务拥有，调度器不消费业务结果；页面由宿主调用一次挂起入口，SDK 内部完成 IO→Main 交接与最终加载。更新锁仍覆盖安装及 History 收尾，页面不等待整段安装。缓存标记持久化、旧 active 保护、必要取消与目录代际继续保留。P3 迁移说明已同步真实页面顺序：按需清缓存、检查取消、保护离线目录、交付加载。

## 完整生产代码统计与身份

统计 `offlineSdk/src/main` 和 `app/src/main` 的全部 Kotlin/Java 文件，含 helper、空行和必要注释。基线来自任务激活前的工作区行数与 SHA，以及当时尚未覆盖的本地 sources JAR、前轮逐文件清单和回执；[基线文件表](baseline-production.json)逐项说明来源。[完整逐文件前后行数与 SHA](production-compare.json)保存当前候选。

| 范围 | 基线文件 / 行 | 本轮文件 / 行 | 变化 |
| --- | ---: | ---: | ---: |
| SDK | 15 / 2092 | 15 / 2060 | −32 |
| Demo | 9 / 599 | 9 / 586 | −13 |
| 合计 | 24 / 2691 | 24 / 2646 | **−45** |
| `ManagedOfflineSdk.kt` | 1 / 889 | 1 / 864 | **−25** |

基线管理器 SHA-256 为 `15624eabdf3368859a141834a4e180cf68865242c6b031bb311b926189c66c61`；当前为 `2930808d110dabe57d719d632e41a160ba12795ab93cdacdd1d2a46efd91f53f`。基线 AAR 为 `36f00b9bbfe5ec43cefbf5a33d4ac22080030404d3351203b30c92fe4ebbf169`；当前 Release、本地 Maven 与实际消费 AAR 同为 **`cbfb3d35b8da06b6727643399daca82562bdadf60bcb2e25add916d1685333d3`**。当前 sources JAR 为 `8e4ae3dc4c2dfbf69ea68c7e615ad797619058fbd9946360df8ed4bc52783cc4`，其中 SDK 生产源码 **15/15 逐字节匹配**工作区；AAR、sources、POM、module 的 SHA-256 sidecar 均匹配。[当前产物校验](artifact-verification-p2.json)、[当前 AAR 字节码公开入口](api-surface-p2.txt)记录了产物与 API 边界；公开入口与[初次候选](api-surface.txt)逐字一致，初次[产物校验](artifact-verification.json)仅用于历史对照。

## 验证

- 初次候选的[完整门禁](full-gate.log)成功：当时 SDK JVM 127/127、Demo 14/14；两模块 AndroidTest Kotlin 源码编译、SDK Release、Demo Debug/Release/R8 与两模块 lint 通过。这是 **P2 收口前**的门禁，不能代替当前源码验证；随后增加一项受控页面交错测试，独立复审时原套件为 128 项。
- 独立复审探针复现 `[CLEANUP, LOCAL]`，本仓库在修复前也强制跑出 1/1 red；[来源与本地复现记录](p2-red-local-reproduction.md)区分保存的独立复审失败 XML 和被后续 Gradle 重跑覆盖的本地 XML。修复后[聚焦回归](p2-focused.log)及其 [XML](junit/p2-focused/) **6/6**、[SDK 全套](p2-sdk-full.log)及其 [XML](junit/p2-sdk-final/) **129/129**，零失败、错误或跳过。新增探针已收入仓库；聚焦 6 项属于 129 项，不重复相加，覆盖清理恢复、History 取消与 F1 监控恢复。
- 当前候选从[本地 0.3.0 重建](local-aar-p2.log)到[真实 AAR 消费](aar-consumer-p2.log)均成功。Demo 依赖解析与消费打印同一新 AAR SHA，Debug/Release/R8、lint 及[单测 14/14](junit/p2-app-final/)通过；[SDK Release lint 与两模块 AndroidTest Kotlin 编译](android-lint-p2.log)通过。命令和退出码见[补验记录](p2-command-record.txt)。
- [原外部七项](external-probes/MAPPING.md)在 P2 收口前按新 API 强制重放 7/7，通过日志与[XML](junit/external/TEST-com.offline.tool.ComplexityReductionReviewRegressionTest.xml)留存；本次只改静默诊断判定和迁移文字，未将旧七项冒充为新 AAR 下的独立重跑。F2-R 已收入仓库回归。`python3 scripts/generate-sample.py --check` 与 `git diff --check` 再次退出 0。

页面受控交错覆盖目录代际变化与配置关闭；旧双步 token 测试中的 active 变化、在线版本失配未逐项单独复刻，激活后 active 选择和摘要冲突仍由现有顺序回归覆盖。

F4 单独记录：PHP110 在检查时已连接，P2 收口前尝试 `:offlineSdk:connectedDebugAndroidTest :app:connectedDebugAndroidTest`；最后可见 SDK connected task，期间有一次 Kotlin daemon 启动异常提示，命令未完成且没有新 XML，随后人工中断。见[设备过程记录](device-status.md)及[原始日志](device-attempt.log)。P2 收口后未重跑设备；**当前候选没有设备通过结果，F4 仍开放**。历史 Android 16 WebView XML、这次 AndroidTest 编译与 JVM 结果均不计为新候选设备通过。真实 Demo 生命周期、系统 WebView 缓存/Cookie/请求头/Range、X5 内核及正式远端不可变产物继续独立验收。

## 接入迁移与交回

宿主在 Application 持有同一管理器并同步传入隐私/前台条件；Welcome 先 `startupDecision()`，需要首装时调用 `prepareFirst(onProgress = ...)`；页面只调用 `loadPage(url, baseUrl, callbacks)`。安装终态由 App 独立报告；首次正常失败仍可按本次结果放行线上。完整样例与存储/线程边界见[迁移说明](../../MIGRATION-MANAGED-0.3.0.md)及[Demo](../../../app/src/main/java/com/offline/tool/sample/)。M1 已把缓存写入改述为挂起 IO 真实持久确认，校正 Main/StrictMode 设备证据范围，并明确普通回滚失败本进程保留旧 active 与取消未知写入的下次重读是两种边界。

原规划会话已独立接受 P2/P3 收口及 R0–R5 代码与结构范围；验收记录保存在宿主侧 SDK 复杂度整改任务中，无剩余代码阻断项。F4、完整运行与正式产物仍独立开放，Trellis 任务保持 `in_progress`，业务 App 接入任务保持原状态。本地 0.3.0 只是候选，尚无提交、tag、远端发布或远端消费证明。
