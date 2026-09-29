# SDK＋Demo 全部生产源码前后对比

基线是本轮实施前冻结的未提交 0.3.0 候选，不是 Git HEAD 的 0.2.2。口径为两个模块 `src/main` 下所有 Kotlin/Java 文件；新增助手自动计入。逐文件 SHA-256 见 `production-comparison.json` 与 `production-sha256.txt`。行数包含注释，机制是否删除须结合 `structure.diff` 审查。

| 范围 | 基线文件 | 当前文件 | 基线物理/非空行 | 当前物理/非空行 | 变化物理/非空行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| SDK | 15 | 15 | 1972/1818 | 1983/1828 | +11/+10 |
| Demo | 9 | 9 | 579/523 | 638/582 | +59/+59 |
| 合计 | 24 | 24 | 2551/2341 | 2621/2410 | +70/+69 |

| 生产文件 | 状态 | 基线物理/非空行 | 当前物理/非空行 | 变化物理/非空行 |
| --- | --- | ---: | ---: | ---: |
| `app/src/main/java/com/offline/tool/sample/DemoApplication.kt` | modified | 113/102 | 140/130 | +27/+28 |
| `app/src/main/java/com/offline/tool/sample/MainActivity.kt` | modified | 97/88 | 97/88 | +0/+0 |
| `app/src/main/java/com/offline/tool/sample/SystemBarInsets.kt` | unchanged | 18/16 | 18/16 | +0/+0 |
| `app/src/main/java/com/offline/tool/sample/offline/DemoManagedStorage.kt` | unchanged | 123/109 | 123/109 | +0/+0 |
| `app/src/main/java/com/offline/tool/sample/offline/DemoOutcomeReporter.kt` | unchanged | 49/45 | 49/45 | +0/+0 |
| `app/src/main/java/com/offline/tool/sample/offline/OfflineLog.kt` | unchanged | 8/6 | 8/6 | +0/+0 |
| `app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeActivity.kt` | modified | 73/67 | 78/72 | +5/+5 |
| `app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeProgressPresentation.kt` | modified | 26/23 | 28/25 | +2/+2 |
| `app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeViewModel.kt` | modified | 72/67 | 97/91 | +25/+24 |
| `offlineSdk/src/main/java/com/offline/tool/FailureReason.kt` | unchanged | 17/16 | 17/16 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/InitialLocalFacts.kt` | unchanged | 92/84 | 92/84 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/InstallException.kt` | unchanged | 11/9 | 11/9 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/InstallResult.kt` | unchanged | 25/23 | 25/23 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/InstallStage.kt` | unchanged | 12/11 | 12/11 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/ManagedOfflineSdk.kt` | modified | 767/717 | 781/730 | +14/+13 |
| `offlineSdk/src/main/java/com/offline/tool/ManagedOfflineTypes.kt` | modified | 176/150 | 173/147 | -3/-3 |
| `offlineSdk/src/main/java/com/offline/tool/OfflineInterceptor.kt` | unchanged | 129/119 | 129/119 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/OfflineUrlRules.kt` | unchanged | 56/45 | 56/45 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/PackageArchive.kt` | unchanged | 126/121 | 126/121 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/PackageDownloader.kt` | unchanged | 152/143 | 152/143 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/PackageEntry.kt` | unchanged | 23/21 | 23/21 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/PackageInstaller.kt` | unchanged | 349/326 | 349/326 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/PackageRecord.kt` | unchanged | 4/3 | 4/3 | +0/+0 |
| `offlineSdk/src/main/java/com/offline/tool/x5/X5OfflineResponse.kt` | unchanged | 33/30 | 33/30 | +0/+0 |

## 管理器机制定位计数

下表是源码定位指标，不能单凭文本次数证明状态归属。`firstJob` 与 `silentJob` 是真实任务的单一 handle，需结合取消测试核对所有权。

| 项目 | 基线 | 当前 |
| --- | ---: | ---: |
| `private var` | 21 | 23 |
| `synchronized(gate)` | 46 | 0 |
| `requestCheck` 文本引用 | 2 | 0 |
| `updateJobs` 文本引用 | 5 | 0 |
| `updateOperation` 文本引用 | 12 | 0 |
| `claimedRoots` 文本引用 | 3 | 0 |
| `firstJob` 文本引用 | 0 | 7 |
| `silentJob` 文本引用 | 0 | 7 |
| Mutex 声明 | operation, initialization | operation, initialization |

本轮总代码净增，应结合首装 owner/等待者与静默任务生命周期的新增显式处理审查；不能据此声称总代码减少。
