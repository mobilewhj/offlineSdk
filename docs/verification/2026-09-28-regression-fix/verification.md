# 2026-09-28 目标回归整改回执

工程：`$SDK_REPO`。本轮基于未提交的 0.3.0 候选整改；HEAD 仍为 `ef55eb17e659d9f77ee714a9a37a9bb9d0259921`，没有提交、推送、tag 或远端发布，未修改业务 App。原计划与失败探针保存在宿主侧架构精简任务中。本轮开始时管理器 SHA-256 `c273a7577067967c11931e155d90568891ec438fad560350fe2a9150dfdabae9`，旧 AAR `79616940830c3a39d188556169046d831c32a72113582b881ea276b73961bcb6`；先按原注入命令重放，127 项中 F1/F2 两项仍失败，第三项通过只是在观察 F2 坏状态。所有原有未提交修改均保留。

## F1–F4 与 O1

| 项 | 实施和可观察结果 | 当前判定 |
| --- | --- | --- |
| F1 | `ManagedOfflineSdk.kt:741` 用首次 History 已完成**或**已确认可用的 active 授予监控资格；`startupDecision()` 返回 CONTINUE 后无需补调 `prepareFirst()`。取消当刻 History 仍未完成，已成立 Installed 仍仅通知一次。正常宿主入口加前后台恢复、推进 300001ms 后配置请求 1→2，未重装或重发终态。 | 产品缺陷关闭；JVM 回归通过 |
| F2 | `ManagedOfflineSdk.kt:550` 在 active 保存/确认跨取消或保存失败时标记需重读；`:322,379` 在下一合法决策前从 IO 重读持久 active 并复核目录。读取故障只诊断和降级，不以旧内存授权清理或降级；文件无效仍保留记录并在请求中报 0。高版 100002 写后取消后，低版 100001 不再覆盖；同目标下一检查不重装、不报 TARGET_IN_USE、不补发取消尝试的终态；新页可选择 100002，已绑定旧目录仍保留。写前取消后下一正常检查可真正安装并通知一次；写后缺文件的下一请求为 0，但防降级仍依据 100002。 | 产品缺陷关闭；JVM 回归通过 |
| F3 | `ManagedOfflineTypes.kt:54` 仅把未发布的缓存写端口改为挂起 Boolean；`ManagedOfflineSdk.kt:281,408` 在 Main 页面最终片段清资源缓存和绑定，之后由 SDK 在 IO 持久提交；`DemoManagedStorage.kt:78` 的 `SharedPreferences.commit()` 运行于 IO。内存待清标记在真实提交确认前保持脏，失败有诊断、下一页面重试；代际和互斥写入使旧清除不能盖过后到置脏。初始化、配置变化、页面与写失败的 JVM 测试通过。 | 产品/示例缺陷关闭；设备 Main 运行证据未完成，归 F4 |
| F4 | `OfflineAndroidTest.kt:32,78` 将 StrictMode 策略保留到 Main 队列处理后恢复，并加入 FileInputStream 正控；`DemoManagedStorageAndroidTest.kt:41` 编译了真实 Demo 存储适配器与管理器初始化→配置→页面链的设备测试。SDK/Demo Android 测试源码均编译。PHP110 在线，但 `connectedDebugAndroidTest` 停在 SDK 设备任务无 XML；单独 `adb install -r -t` 与 `--no-streaming` 都在安装阶段无完成输出，已停止等待。因此**本轮没有设备通过项**，检测器正控和真实适配器链仍未获得运行证据。 | 开放 |
| O1 | 本轮未改 `ManagedFailure.detail` 映射；避免扩大取消/存储边界的改动。 | 后置 |

## 四个存储/状态边界

已有可用 active 是“可以继续正常前台监控”的安装事实；首次 History 是交互准备曾正常收尾的独立记录。F1 取消时不伪造 History，但随后正常入口可由已确认可用 active 获得监控资格。无包或记录未知时不能因此获得资格。五分钟 TTL 仍从真实 provider 调用开始，恢复不重置 `lastCheck`、`failedVersion`，不补积压请求。

F2 的四个时点：`writeActive` 可能已产生**存储副作用**；挂起返回 Main 后仍可能被取消，故**内存提交**未发生；只有再次确认目标文件可用并经过管理器**最终确认**，才产生 Installed；**History** 属之后的独立收尾。取消尝试不补发 Installed。下一合法入口重读存储和文件，再统一给 provider、候选判断与新页面使用；已绑定页面继续固定旧目录。仅增加 `activeNeedsRefresh` 一个跨取消提示，不增加恢复队列或公开 API，也不把整段安装置于 NonCancellable。

缓存端口原同步 Boolean 若由 Demo 的 `SharedPreferences.commit()` 实现会在 Main 等磁盘；若改成 `apply(); return true` 又会谎称持久成功。现改为挂起写入；Main 清资源缓存成功后只产生“需保存清除”任务，真正 IO 写入返回 `true` 才将内存标记置净。配置后到置脏递增 `cacheGeneration`，写入以 `cacheWrite` 互斥，旧清除结果不会覆盖新事实。写失败保留脏标记与诊断，下一页面可再次清资源缓存。清理范围仍只有 WebView 资源缓存，不触及 Cookie、localStorage 和业务数据。

## 失败探针与当前回归

修复前按报告原注入命令重放：127 项，125 通过、2 失败。F1 输出为 `requests=1 active=10000 usable=true initialFinished=false`；F2 低版路径输出为 `requested=[100000, 100000] stored=100001 memory=100001`；同目标通过项只断言 `TARGET_IN_USE` 和错误失败门槛。本轮将 F1 及 F2 探针收入 `offlineSdk/src/test`：F1 删除多余 `prepareFirst()`，只靠正常 `startupDecision()`；F2 同目标用例改断言新页 100002、旧目录保留、零补发终态和零 TARGET_IN_USE，并增加写前取消、重读故障与写后缺文件各自的下一正常操作。新版本输出/断言见 [F1 XML](junit/sdk/TEST-com.offline.tool.ManagerGoalRegressionProbeTest.xml) 和 [F2 XML](junit/sdk/TEST-com.offline.tool.ActiveSaveCancellationProbe.xml)。缓存旧测试从“commitPage 返回即落盘”改为等待真实 IO 结果，增加后到配置置脏竞态回归。

最终独立注入原七项回归：SDK **131/131**（原 117＋本轮 7＋原七项），Demo **13/13**，均无失败/错误/跳过；完整 XML 位于 [SDK](junit/sdk/) 与 [Demo](junit/demo/)。原七项探针只更新缓存端口的 `suspend` 签名。SDK lintRelease [0 error/2 warning](offlineSdk-lint-release.txt)，Demo lintDebug [0 error/17 warning](app-lint-debug.txt)。SDK Release、Demo Debug/Release/R8、两模块 Android 测试源码编译成功；编译不算设备运行。

## 构建、AAR 消费与身份

全部 Gradle 命令均以 JBR 17、`--offline --no-daemon --console=plain` 执行。先运行原报告的 `review-probes.init.gradle` 与 `rerun-tests.init.gradle` 注入失败探针；整改后运行：

```sh
./gradlew :offlineSdk:testDebugUnitTest :app:testDebugUnitTest :offlineSdk:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :offlineSdk:lintRelease :app:lintDebug :offlineSdk:assembleRelease :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain
./gradlew :offlineSdk:testDebugUnitTest -I $TRACKING_REPO/.trellis/tasks/09-27-offline-sdk-architecture-simplification/research/goal-regression-2026-09-28/verification/review-probes.init.gradle -PreviewProbesDir=$SDK_REPO/docs/verification/2026-09-28-sdk-v2-closeout/external-probes -I $TRACKING_REPO/.trellis/tasks/09-27-offline-sdk-architecture-simplification/research/goal-regression-2026-09-28/verification/rerun-tests.init.gradle --offline --no-daemon --console=plain
./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository -PsdkVersion=0.3.0 --offline --no-daemon --console=plain
./gradlew :app:verifyOfflineSdkAar :app:dependencyInsight --configuration debugRuntimeClasspath --dependency offlineSdk :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug -PsdkVersion=0.3.0 -PsmokeSdkVersion=0.3.0 -I scripts/aar-consumer-smoke.init.gradle --offline --no-daemon --console=plain
./gradlew :offlineSdk:connectedDebugAndroidTest :app:connectedDebugAndroidTest --offline --no-daemon --console=plain
python3 scripts/generate-sample.py --check
git diff --check
```

前四条成功；第五条设备任务未完成而中断，无本轮设备 XML。AAR 消费侧从工程本地 Maven 实际解析 `com.github.mobilewhj.offlineSdk:offlineSdk:0.3.0`，编译七参数/三必需参数、低层兼容调用、Demo 实际存储和新的挂起缓存端口；消费者打印 SHA-256 与本地候选一致，摘要及依赖解析另存[消费日志](aar-consumer-resolution.log)。第六、七条退出码 0。

| 身份 | SHA-256 |
| --- | --- |
| 新 `ManagedOfflineSdk.kt` | `15624eabdf3368859a141834a4e180cf68865242c6b031bb311b926189c66c61` |
| 新 `ManagedOfflineTypes.kt` | `51acc9049c0250738e0400b35406fa34dedf8cbd7755dd8f1f06726bed3b9f52` |
| 新 Demo 存储源码 | `c9df9205b5c8c3e245440fc99cc408490ac27ca960a3df80a895ce6326713ee0` |
| Release AAR＝本地 Maven 消费 AAR | `36f00b9bbfe5ec43cefbf5a33d4ac22080030404d3351203b30c92fe4ebbf169` |
| sources JAR | `703a56e6879819439ca80b4552e0882ca49d04e7e2c4313004e0c6730bfca345` |

sources JAR 的 15/15 SDK Kotlin 生产源码与工作区逐字节匹配；本地 Maven AAR、sources、POM 和 module 的 SHA-256 sidecar 均与产物实算一致。SDK＋Demo 全部生产源码保持 24 文件，由基线 2630 行变为 2691 行（SDK 2032→2092，Demo 598→599）；新增管理器逻辑为取消后重读事实、缓存异步持久确认及对应注释，未增通用状态机/恢复队列；宿主仅迁移缓存写入签名，接入步骤见 [迁移说明](../../MIGRATION-MANAGED-0.3.0.md)。

## 仍待原规划任务复审

设备 StrictMode 正控、真实 Demo 适配器与管理器页面链、系统 WebView/X5 的受影响运行场景尚无本轮通过证据。完整 Demo 生命周期、Cookie/请求头/Range/缓存效果、正式远端不可变产物与业务 App 接入/成功上报仍分别验收。设备命令卡在 APK 安装阶段，不能把历史 9/9 或本轮 Android 测试源码编译转述为设备通过。
