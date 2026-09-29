# P2 修复前本仓库复现

- 命令：`./gradlew :offlineSdk:testDebugUnitTest --tests com.offline.tool.ColdCleanupDiagnosticReviewTest --rerun-tasks --offline --no-daemon --console=plain`
- 结果：`1 test completed, 1 failed`，Gradle `BUILD FAILED`。
- 失败断言：`pageRecoveryDuringSilentHistorySaveMustNotDuplicateCleanupDiagnostic`，预期诊断阶段 `[CLEANUP]`，实际 `[CLEANUP, LOCAL]`，测试第 106 行。
- 本地失败 XML 曾位于 `offlineSdk/build/test-results/testDebugUnitTest/TEST-com.offline.tool.ColdCleanupDiagnosticReviewTest.xml`，随后被修复后的强制重跑覆盖。旁边 `p2-red-independent-review/` 保存独立复审的同一探针原始失败 XML 和运行日志，来源为跟踪任务的 `research/independent-review-2026-09-29/`；不将其冒充本地重跑文件。
