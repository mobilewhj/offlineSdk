# 本轮外部七项探针映射

本目录从 `docs/verification/2026-09-28-sdk-v2-closeout/external-probes/ReviewRegressionTest.kt` 迁移原七项。文件名保持 `ReviewRegressionTest.kt`，供既有 `review-probes.init.gradle` 发现；测试类改为 `ComplexityReductionReviewRegressionTest`，避免与仓库测试或其他注入类同名。本目录只注入这一个 Kotlin 文件，不与旧探针目录同时注入。

| 用例 | 本轮入口迁移 | 保留的业务断言 |
| --- | --- | --- |
| `missingActiveFilesMustNotPermitDowngrade` | 继续由 `startupDecision()` 和前台 SDK 静默任务触发。 | provider 收到可用版本 `0`；缺包不抹掉持久 active `100002`，不激活低版。 |
| `privacyRevocationCancelsSdkSilentCheckBeforeActivation` | 继续在 active 保存边界撤回隐私。 | SDK 检查取消、不写 active、不伪造安装终态；无关宿主任务存活并完成。 |
| `observedSameVersionShaConflictMustBlockPageDuringEnabledStorageWait` | 删除 `preparePage()`/`commitPage()` 两段调用；开关写挂起后直接调用单一挂起 `loadPage()`。 | 已观察的同版不同 SHA 冲突立即走 Online，离线加载 `0` 次、在线加载 `1` 次，不等待保存完成。 |
| `activeReadFailureMustNotDeleteRecoverableInstalledDirectory` | 初始化读取失败后的下一个合法入口改为 `loadPage()`，由 SDK 内部完成 IO 观察与 Main 复核。 | 可恢复目录和持久 active 保留；后续恢复内存 active，并实际离线加载一次。 |
| `enabledWriteFailureMustNotHideObservedSameVersionShaConflict` | 开关保存失败后直接调用 `loadPage()`。 | `SAVE_ENABLED` 故障可见；已观察的 SHA 冲突仍强制走 Online，离线加载 `0` 次、在线加载 `1` 次。 |
| `outcomeCallbackCancellationMustNotReplaceCommittedInstallResult` | 首装只传 `prepareFirst(onProgress = …)`；结果观察回调仍故意抛取消异常。 | active 已提交、安装终态一次、首装调用正常返回 `Finished(Installed, true)`，最终进度为 `Complete`。 |
| `firstPreparationReentryDuringCancellationCleanupMustNotCreateAnotherOwner` | 三次首装调用均使用单一 `onProgress` 参数；Deferred 取消边界不变。 | 旧 owner 清理期间重入继承取消且不发第二次配置；显式重试后才安装，合计两次请求、一次成功终态。 |

旧页面令牌已经删除，第 3、5 项现在验证配置冲突与页面调用重叠时的实际在线选择；IO→Main 交接竞态由 SDK 仓库页面回归覆盖。F2-R 是另一项后加探针，不计入这七项。

可在 SDK 根目录重放。此命令强制重新编译注入探针；执行后需在 JUnit XML 中核对 `ComplexityReductionReviewRegressionTest` 的七项结果，不能用历史测试数量代替：

```sh
./gradlew :offlineSdk:testDebugUnitTest \
  --tests com.offline.tool.ComplexityReductionReviewRegressionTest \
  -I $TRACKING_REPO/.trellis/tasks/09-27-offline-sdk-architecture-simplification/research/goal-regression-2026-09-28/verification/review-probes.init.gradle \
  -PreviewProbesDir=$SDK_REPO/docs/verification/2026-09-29-complexity-reduction/external-probes \
  --rerun-tasks --offline --no-daemon --console=plain
```

当前仅适配与保存探针，尚未执行 Gradle；是否通过以后续新候选的实际 XML 为准。
