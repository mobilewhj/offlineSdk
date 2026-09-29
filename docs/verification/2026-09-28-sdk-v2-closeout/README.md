# v2 复审 E1/E2 收尾证据

日期 2026-09-28。执行仓库 `$SDK_REPO`，分支 `main`，HEAD `ef55eb17e659d9f77ee714a9a37a9bb9d0259921`。全部修改仍未提交；本地 Maven `0.3.0` 仅用于消费验证。

## 测试与构建

- `full-build.log`：不注入外部探针，`--rerun-tasks` 完整执行 SDK/Demo JVM 测试、SDK Android 测试源码编译、lint、Debug/Release/R8；`junit/sdk-repository/` 为 SDK 117 项，`junit/demo-repository/` 为 Demo 13 项。
- `external-regressions.log`：以 `review-probes.init.gradle` 注入本目录 `external-probes/ReviewRegressionTest.kt`，`--rerun-tasks` 强制重编；`junit/sdk/` 保存 SDK 124 项，其中外部类七项。初次增量注入未实际编入探针，所以不计作外部通过；以保存的 XML 为准。
- `local-candidate.log`、`aar-consumer.log`：重建工程本地 Maven AAR，消费侧解析 `0.3.0` 并验证 SHA-256 `79616940830c3a39d188556169046d831c32a72113582b881ea276b73961bcb6`，再执行 Demo Debug/Release/R8/lint/13 项单测。
- `connected-android-final.log`、`device/TEST-PHP110-16-offlineSdk.xml`：PHP110 / Android 16 最终九项通过。既有七项拦截器规则，加 Main 构造和同步条件入口的 StrictMode 磁盘检查、系统 WebView 本地 HTML/JS 及原 URL 检查。`connected-android-expanded.log` 保存新增 WebView 测试最初在 instrumentation 线程读取 `WebView.url` 的失败；测试改为 Main 读取后最终重跑通过。`device/webview-provider.txt` 记录实际系统 WebView 版本。
- `test-summary.json`：分别按保存的 JUnit XML 汇总；`offlineSdk-lint-results-release.txt` 与 `app-lint-results-debug.txt` 保留建议数。

## 源码与产物

- `production-compare.json` 和 `structure.diff`：以 b25b08 未提交基线为起点，包含全部 24 个 SDK＋Demo 生产文件，未以 Git HEAD 代替基线。当前 2630 行，基线 2621 行；其中 Demo 638→598，管理器 781→830。
- `artifact-verification.json`：四种本地产物 SHA-256 sidecar、Release AAR、15/15 SDK sources JAR 源码逐字节核对。
- `external-probes/MAPPING.md`：逐项说明原六项入口/断言迁移和新增第七项；历史旧探针与旧日志未覆盖。
- `sample-check.log`、`diff-check.log`：样例 ZIP 摘要和 Git 空白检查；`git-status.txt` 保存未提交状态。

本目录不包含正式 tag、远端 POM/AAR 解析、X5 实际内核、Demo 完整设备生命周期、系统 WebView Cookie/缓存全场景或真实业务上传的通过证据。交付状态见 [SDK 回执](../../SDK-MANAGED-REFACTOR-DELIVERY.md)。
