# 架构精简回归映射

本轮删除公开 `requestCheck()` 后，旧测试通过 `startupDecision()`、`setConditions()` 和 SDK 自有静默任务触发常规检查。保留原行为断言；不为测试恢复旧入口。原外部六项的逐项改编及原件差异见 `external-probes/MAPPING.md` 和 `external-probes/entry-migration.diff`。

| 既有修复 | 本轮可观察验证 |
| --- | --- |
| R1：active 读取失败保目录并可恢复 | `ManagedInitializationTest.incompleteLocalReadsPreserveDirectoriesAndRetryBeforeCommitting`、`pageEntryCanRecoverAfterAnEarlierLocalReadFailure`；`ManagedOfflineRegressionTest.activeReadFailureMustNotDeleteRecoverableInstalledDirectory`、`activeReadFailureCanRecoverOnTheNextLocalPreparation`；外部同名保目录探针。 |
| R2：隐私撤回取消在途更新且隔离宿主兄弟任务 | `ManagedOfflineRegressionTest.privacyRevocationCancelsSdkSilentCheckBeforeActivation`、`shutdownCancelsSdkSilentCheckBeforeActivation`、`privacyWithdrawalCancelsOnlyOperationAndLeavesHostSiblingActive`；首装 owner/等待者取消测试；外部 R2 改为 SDK 静默入口。 |
| R3：记录存在但文件缺失时仍防降级 | `ManagedOfflineRegressionTest.missingActiveFilesMustNotPermitDowngrade`、`missingActiveFilesStillAllowSameDigestRepairOrHigherVersion`；外部缺文件防降级探针。 |
| R4：同版 SHA 冲突立即影响页面资格 | `ManagedOfflineRegressionTest.observedSameVersionShaConflictMustBlockPageDuringEnabledStorageWait`、`enabledWriteFailureMustNotHideObservedSameVersionShaConflict`；对应两个外部探针。 |
| R5：结果接收器抛取消异常不改写已提交终态 | `ManagedOfflineRegressionTest.outcomeCallbackCancellationMustNotReplaceCommittedInstallResult`；对应外部探针。 |
| R6：页面 Main 提交不读包文件，绑定目录受保护 | `ManagedOfflineRegressionTest.pageCommitUsesPreparedFactsWithoutReadingPackageFiles`、`completedActivationInvalidatesEarlierPageObservation`、`sameVersionRepairInvalidatesObservationFromBeforeDirectoryLoss`；`ManagedOfflineSdkTest.boundPageProtectsAnExistingTargetDirectoryFromReplacement`。设备 StrictMode 仍未执行。 |
| R7：注入既有下载客户端 | `ManagedOfflineRegressionTest.customDownloadClientIsUsedForTheManagedInstallation`；AAR smoke 编译 `downloadClient` 与超时构造参数。 |
| R8：Demo 清磁盘资源缓存 | `MainActivity` 调用 `clearCache(true)`；设备系统 WebView/X5 的实际缓存效果仍未验证。 |
| R9：薄宿主首次进度及独立报告任务 | `WelcomeViewModelTest`、`WelcomeProgressPresentationTest`、`DemoOutcomeReporterTest`；本轮追加存活等待者取消后的显式恢复验证，以及异步 SDK 构造期间条件更新和构造失败收尾验证。 |
| Q1：URL 共同规则唯一维护 | `OfflineUrlRulesTest` 七项，以及页面和资源拦截相关回归。 |
| Q2：本地事实读取与管理状态提交分责 | `ManagedInitializationTest` 六项；读取失败保目录、后续合法入口重读及页面 IO 验证。 |
| Q3：History 保存属于本次入口收尾 | `ManagedOfflineSdkTest.historyWritesFollowFirstCompletionAndLaterTerminalResultsOnly`、`regularHistoryCancellationKeepsCommittedInstallationOutcomeOnce`、`regularDiagnosticHistoryCancellationDoesNotEmitInstallationOutcome`。 |

## 本轮新增边界

| 边界 | 验证 |
| --- | --- |
| Main 状态归属及 IO 边界 | `ManagedOfflineRegressionTest.suspendEntriesKeepManagedFactsOnMainAndBlockingWorkOnIo`；构造入口 `@WorkerThread`，Demo `DemoGraph` 在 IO 构造后回 Main 应用最新条件、页面等待图发布；`WelcomeViewModelTest.delayedManagerPublicationUsesCurrentPrivacyAndForegroundConditions`、`delayedManagerPublicationRespectsBackgroundAndCreationFailureExitsNeutralLoading`。 |
| 首装单 owner、多等待者 | `ManagedOfflineSdkTest.concurrentFirstCallersShareOneConfigurationAndInstallation`、`cancellingFirstWaiterDoesNotCancelOwnerOrHostSibling`、`cancellingFirstOwnerEndsWaitingAttemptWithoutAutomaticRetry`、`cancelledBeforeFirstOwnerRunsAllowsLaterExplicitRetry`；Demo `WelcomeViewModelTest.survivingWaiterDoesNotRestartAfterOwnerCancellationUntilExplicitRetry`。 |
| 静默任务和 TTL | `ManagedOfflineRegressionTest.silentChecksUseActualProviderEntryForFiveMinuteInterval`、`providerSelfCancellationDoesNotStopLaterSdkSilentChecks`；`ManagedOfflineSdkTest.fiveMinuteLimitUsesActualRequestStartAndBackgroundDoesNotStartChecks`、`resumedPollingWaitsForCancelledSilentCheckToFinishCleanup`、`sustainedForegroundPollsAndBackgroundStopsFutureRequests`。 |
| 根目录一套独占保护 | `ManagedOfflineSdkTest.managedRootRejectsOtherLowLevelMutationsButKeepsReadOnlyCompatibility`；本地 AAR smoke 在独立旧根目录编译低层 `PackageInstaller`、`OfflineInterceptor` 调用。 |

本轮 XML 汇总在 `test-summary.json`：SDK 仓库 114 项、Demo 13 项、加入原外部六项后 SDK 120 项，均无失败、错误或跳过。统计不能代替设备运行或完整代码复审。
