# 外部回归迁移与补强映射

本目录保留原六项行为目标，并增加一项首装取消清理窗口回归。探针来自原规划任务的独立补强版本；复制到 SDK 交付目录后，在当前源码上重新执行。旧六项源码与旧日志保留在 `docs/verification/2026-09-28-sdk-init-api-v2/external-probes/`，其“断言不变、仅改构造”描述不准确。

| 用例 | 入口与观察迁移 | 当前关键断言 |
| --- | --- | --- |
| `missingActiveFilesMustNotPermitDowngrade` | 原公开 `requestCheck()` 改为 `startupDecision()` 后由 SDK 前台静默任务触发；增加 provider 调用记录。 | provider 实际收到 `[0]`，缺包时仍保留 active 100002，不写入低版 100001。旧迁移仅看 active，存在未执行检查的假通过。 |
| `privacyRevocationCancelsSdkSilentCheckBeforeActivation` | 原外部检查改为 SDK 自有静默任务。 | 到达 active 保存挂起点后撤回隐私，未写入/激活、无安装终态；宿主无关任务正常结束。 |
| `observedSameVersionShaConflictMustBlockPageDuringEnabledStorageWait` | 通过 SDK 静默任务观察配置，页面用 `preparePage()`/`commitPage()`。 | 开关保存挂起时，已经取得的同版不同 SHA 立即阻止旧页面离线提交。 |
| `activeReadFailureMustNotDeleteRecoverableInstalledDirectory` | 读取失败发生在 `startupDecision()` 初始化；同一实例下次 `preparePage()` 恢复。 | 目录、持久 active、文件内容保留，后续实际离线绑定。恢复入口是迁移后的新增观察。 |
| `enabledWriteFailureMustNotHideObservedSameVersionShaConflict` | SDK 静默任务触发配置和开关保存。 | 确认 `SAVE_ENABLED` 失败，已观察的 SHA 冲突仍阻止旧页面离线绑定。 |
| `outcomeCallbackCancellationMustNotReplaceCommittedInstallResult` | 由静默状态/宿主 Job 观察改回公开 `prepareFirst()` 返回结果。 | active 成功持久化、终态一次；回调抛取消异常后调用正常返回 `Finished(Installed(target), true)`。旧迁移只看宿主测试 Job，不能证明 SDK 调用结果。 |
| `firstPreparationReentryDuringCancellationCleanupMustNotCreateAnotherOwner` | 新增，使用首次准备共享入口和确定的取消清理挂起点。 | 旧 owner 清理未完时重入不产生新配置请求，重入继承旧取消；清理后新的显式调用才成功，累计两次请求、一次成功终态。 |

测试通过临时源集注入 `:offlineSdk`，统一使用 `StandardTestDispatcher(testScheduler)` 和内部 `forTest()` 控制 Main、IO、时钟；第七项通过 Deferred 边界控制清理，不靠真实时间、反射或私有字段。此映射描述的是当前探针，实际通过情况以本目录上层本轮日志与 JUnit 汇总为准。
