# Welcome 接入示例

运行 Android Studio 的 `app` 配置，最低 Android API 24。默认安装 APK 内合成的 `assets/sample.zip`，不需要服务端、账户或网络；包内页面和样式不含业务数据。示例代码位于 `app/src/main/java/com/offline/tool/sample/`，不会被打入 SDK AAR。

## 页面流程

1. 首次启动进入 Welcome，检查本地记录及 `index.html`。清理尚未交付页面的安装残留。
2. ViewModel 请求 Repository 的候选版本，比较版本及摘要，然后调用安装 UseCase。
3. SDK 校验 ZIP 摘要并发布目录；示例保存版本记录成功且入口可用后，才进入 WebView。
4. 失败停留在 Welcome，显示原因及重试按钮。旋转屏幕沿用 ViewModel，不重复安装或自动重试失败操作。
5. 已有可用包时直接进入页面，在应用作用域检查候选更新。更新失败保留旧包；更新成功供后续页面使用，已打开的 WebView 继续使用原目录。

内置安装接口没有字节进度回调，页面显示不定进度及真实阶段；网络安装展示 SDK 的下载／解压百分比。下载达到 100% 只代表下载结束，仍须完成解压和版本保存。

## 对应代码

| 文件 | 职责 |
| --- | --- |
| `ui/welcome/WelcomeActivity.kt` | ViewBinding、按生命周期收集状态、失败重试和就绪跳转 |
| `ui/welcome/WelcomeViewModel.kt` | 请求时机、版本选择、首次安装和静默更新编排 |
| `ui/welcome/WelcomeRepository.kt` | 提供类型化候选；默认实现返回可信内置包和固定摘要 |
| `ui/welcome/OfflineUpdatePlan.kt` | 不降级、同版本内容冲突拒绝、缺失资源修复 |
| `offline/DemoOfflinePackages.kt` | Okio 记录读写、原子替换保存、本地可用性和清理边界 |
| `offline/InstallOfflinePackageUseCase.kt` | 单次准备目标 → SDK 安装 → 保存记录；回调报告阶段 |
| `offline/OfflinePageAdapter.kt` | 取得单个 WebView 的固定版本目录，不下载资源 |
| `offline/OfflineUrlRules.kt` | 匹配允许映射的主机、端口和资源路径 |
| `offline/OfflineLog.kt` | DEBUG 日志，不记录 URL 查询参数或业务响应 |
| `DemoApplication.kt` | 简单构造注入，同一根目录共用一个安装器和本地存储入口 |

采用 [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)中的职责分层、ViewModel、StateFlow 和构造注入；UI 使用 [`repeatOnLifecycle`](https://developer.android.com/topic/libraries/architecture/coroutines) 收集状态。保留 XML / Activity 形式，便于已有 Views 项目参考接入。

## 目录与生命周期

资源放在 `filesDir/offline/packages/<version>/`；版本记录为 `filesDir/offline/current.txt`，内容是版本号、摘要两行。记录通过同目录临时文件和 Okio `atomicMove` 替换。它是此示例的本地存储格式，不是远端 API 格式；实际宿主可以替换为已有 DataStore、数据库或其他存储。

SDK 安装成功不等于记录保存成功。保存失败不会进入页面，重试可清理未绑定的目标目录再安装。一旦目录交付页面，本进程不再清理旧目录；下一次冷启动、页面绑定前才清理。该示例采用单进程、单 Welcome 更新入口，不提供多进程并发安装协调。

首次准备使用 `viewModelScope`，旋转不会中断，退出并销毁 ViewModel 会取消。已有可用包后的更新使用 Application 持有的作用域，Welcome 跳转销毁后仍可继续；进程被系统结束后不保证完成。SDK 取消及临时文件清理契约保持不变。

## 接入真实服务端

默认实现不请求远端配置，也没有远端开关、广告、登录等业务规则。需要网络更新时，在宿主已有 Repository 中调用现有 Retrofit Service，通过 Moshi 生成适配器获得类型化配置，再构建：

```kotlin
OfflinePackageCandidate(
    record = PackageRecord(config.version, config.sha256),
    source = OfflinePackageSource.Remote(config.url),
)
```

这些类型属于示例 `internal` 代码，供复制接入思路；SDK 的公开 API 仍是 `PackageInstaller`、`PackageRecord` 等。示例网络安装 UseCase 已通过 MockWebServer 测试；真实服务端及其 JSON 转换尚未集成。请使用 HTTPS 及可信渠道给出的摘要，不要从下载内容自行计算摘要来替代来源校验。

修改内置 ZIP 时，同步修改 `WelcomeRepositoryImpl` 中的版本号和 SHA-256；已发布版本不能更换内容。`bundledCandidateDigestMatchesShippedAsset` 测试会发现 ZIP 与摘要不同步。

资源映射地址统一在 `DemoGraph.BASE_URL`。示例沿用同主机 HTTP/HTTPS 映射规则，并在 `OfflineInterceptor` 显式启用 `allowHttpAndHttps`；它不会改写页面 URL。真实项目应按自己的 origin 约束选择策略。

## 验证

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

示例测试覆盖版本决策、首次成功／失败重试、摘要错误、保存失败重试、缺包修复、静默更新与旧页面保护、取消行为、网络阶段进度及 URL 映射。设备验收还需检查首次启动、重新打开、旋转、WebView 页面和失败提示；单元测试和 APK 编译不能代替设备验证。
