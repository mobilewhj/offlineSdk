# 原外部六项回归的入口映射

此目录从原规划任务只读探针改编。测试仍为六项，生产源码和仓库内测试均未改动。每项先用 `startupDecision()` 初始化，再在同一串行 `StandardTestDispatcher(testScheduler)` 上调用 `setConditions(true, true)` 和 `runCurrent()`，由 SDK 自有静默任务触发常规检查。`mainDispatcher` 与 `ioDispatcher` 共用同一个测试调度器；同步页面和条件入口由测试 Main owner 调用。

| 原测试 | 本轮测试及保留断言 |
| --- | --- |
| `missingActiveFilesMustNotPermitDowngrade` | 同名；缺失 active 文件后静默检查低版本，持久 active 仍为 100002，未写入 100001。 |
| `privacyRevocationCancelsExternallyRequestedCheckBeforeActivation` | `privacyRevocationCancelsSdkSilentCheckBeforeActivation`；静默任务到达 active 写入挂起点，撤回隐私后既不写入也不激活；宿主无关兄弟任务保持活动并正常结束。 |
| `observedSameVersionShaConflictMustBlockPageDuringEnabledStorageWait` | 同名；配置已经进入 enabled 保存挂起点时，旧 `PagePreparation` 立即转在线且未加载离线页面。 |
| `activeReadFailureMustNotDeleteRecoverableInstalledDirectory` | 同名；active 读取故障不删目录，持久记录与文件内容保留；下一合法 `preparePage()` 恢复 active 并可绑定页面。 |
| `enabledWriteFailureMustNotHideObservedSameVersionShaConflict` | 同名；SDK 状态确认 `SAVE_ENABLED` 失败，已观察到的 SHA 冲突仍阻止旧页面离线绑定。 |
| `outcomeCallbackCancellationMustNotReplaceCommittedInstallResult` | 同名；安装 active 成功且回调仅一次，接收器抛取消异常后状态仍成功、终态仍为 `InstallationOutcome.Installed`，宿主测试 Job 未被取消。 |

## 注入与执行

保留原任务的 `review-probes.init.gradle` 原件。该脚本通过 `-PreviewProbesDir` 向 `:offlineSdk` 测试 source set 加入本目录，并加上 `kotlinx-coroutines-test:1.7.3`。主代理在本轮统一运行：

```sh
./gradlew :offlineSdk:testDebugUnitTest \
  -I $TRACKING_REPO/.trellis/tasks/09-24-offline-sdk-managed-refactor/research/sdk-acceptance-2026-09-27/review-probes.init.gradle \
  -PreviewProbesDir=$SDK_REPO/build/reports/architecture-simplification/external-probes \
  --no-daemon --console=plain
```

此文件只描述探针映射；通过与否以本轮实际 Gradle 命令、JUnit XML 和日志为准。
