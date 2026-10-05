# Offline SDK

本页对应 **`0.3.0` 正式版本源码**，正式坐标为 `com.github.mobilewhj:offlineSdk:0.3.0`，样例见[固定 `0.3.0` tag](https://github.com/mobilewhj/offlineSdk/tree/0.3.0/app)，版本范围及发布核验要求见[发布说明](docs/RELEASE-0.3.0.md)。`0.3.0-rc.1` 是此前发布的接入测试候选，固定依赖仍为 `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`。RC 的可编译样例见[固定 tag 的 Demo](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app)，其 API 说明见[固定 tag 的迁移文档](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/docs/MIGRATION-MANAGED-0.3.0.md)，远端消费身份见[发布回执](docs/verification/2026-09-29-test-release/README.md)。

**`0.3.0` 新增能力**：B2 内部职责拆分、默认文件存储、四原语 keyValue 适配、固定 codec 和 `prepareStartup` 已实现，不能据此认为 RC 包含这些接口。当前源码用法见下文与[Demo 说明](docs/DEMO.md)；已有业务 App 的固定 RC 接入不需要切换到本地候选。

[![CI](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml/badge.svg)](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml) [![JitPack](https://jitpack.io/v/mobilewhj/offlineSdk.svg)](https://jitpack.io/#mobilewhj/offlineSdk)

**中文** | [English](README.en.md)

面向**小型 Android 项目的单包离线方案**。将一套 H5 静态资源打成 ZIP，按版本安装到本地，再通过 WebView 映射原始 URL 加载资源。

适合一个应用维护一套 H5 资源、以整包方式更新的场景。目标是让单包接入简单、职责清晰；多业务包管理、差分更新和插件化不在当前范围内。

## 功能与边界

- HTTP(S) ZIP 下载、可信 SHA-256 校验、有界解压和版本目录发布。
- Kotlin 挂起 API、取消传播、下载／解压进度；文件操作使用 Okio。
- 系统 WebView 资源映射，可选 X5 响应适配。
- Welcome 薄宿主：首次资格与调用级进度、存储适配、隐私条件和独立报告任务；更新与目录保护由 SDK 管理。

托管入口由 SDK 决定更新和目录保护；宿主提供配置接口、存储、隐私/前后台事实和页面操作。`0.3.0` 提供默认存储与固定编码。**单包不代表只保留一个目录**：更新后旧页面仍可使用旧版本，直到安全的清理时机。

## 0.3.0：最短完整接入链

新 App 不需要实现存储方法。Application 在 Main 持有一个稳定管理器，用现有 Repository 映射配置，直接传隐私和合法前台事实。`root` 是 SDK 独占安装目录；默认状态目录位于 `noBackupFilesDir/offline-sdk-state/<namespace>`，不能与安装目录重叠。

```kotlin
import android.webkit.WebViewClient
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.OfflineInterceptor
import com.offline.tool.StartupResult
import java.io.File

// Application / Main：existingConfigProvider 从现有配置链映射 ConfigResponse。
val manager = ManagedOfflineSdk(
    root = File(context.filesDir, "offline-packages"),
    storage = ManagedOfflineStorage.default(context, namespace = "main"),
    configProvider = existingConfigProvider,
    minimumVersion = 100_000,
    onInstallationOutcome = receiveInstallationOutcome,
    onDiagnostic = receiveDiagnostic,
)
manager.setConditions(privacyAllowed, foreground) // Main，同步传最新事实。

// Welcome 的生命周期协程：一次调用；进度回调应线程安全或切 Main。
when (manager.prepareStartup(onProgress = showProgress)) {
    StartupResult.Continue -> finishOfflineWait()
    StartupResult.Deferred -> showExplicitRetry()
}

// 业务配置、广告和导航门禁完成后，由真实页面调用一次；callbacks 在 Main 执行。
manager.loadPage(originalUrl, baseUrl, object : ManagedPageCallbacks {
    override fun clearResourceCache(): Boolean {
        webView.clearCache(true)
        return true
    }
    override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
        webView.webViewClient = interceptor
        webView.loadUrl(url)
    }
    override fun loadOnline(url: String) {
        webView.webViewClient = WebViewClient()
        webView.loadUrl(url)
    }
})
```

宿主已有的配置提供器、回调和 UI 函数代表集成边界；完整可编译调用见 [`DefaultStorageSample.kt`](app/src/main/java/com/offline/tool/sample/offline/DefaultStorageSample.kt) 与 [`MainActivity.kt`](app/src/main/java/com/offline/tool/sample/MainActivity.kt)。页面使用 SDK 给定的 interceptor 和原 URL，保留原 WebView/X5 的 JSBridge、Cookie、localStorage 和销毁保护；已有 WebViewClient 的宿主把 `interceptor.resolve(...)` 接入原资源回调。

`Continue` 表示本次离线等待结束，包含正常失败或关闭；业务导航仍由宿主门禁决定。`Deferred` 表示临时条件不足，无自动排队；取消继续抛出，不能伪装完成。默认 namespace 使用稳定值、主进程单例；缺失返回 null，读取故障抛出。写入 true 表示同步提交确认；false 不承诺已发生的文件操作被回滚，也不提供跨 key 事务。

已有介质可通过 `ManagedOfflineStorage.keyValue(values, keys, legacyEvidence)` 只实现 `OfflineKeyValueStore` 的 String/Boolean 四原语，null 字符串写入表示删除。**active/history 字符串必须已经是 SDK codec 格式**；四原语只适配介质，不自动转换任意旧字符串。`legacyEvidence` 仅提供可靠历史事实，不是格式转换器；旧格式须保留必要的 `ManagedOfflineStorage` 适配，而不是直接交给 keyValue。

配置和无目标故障走 `onDiagnostic`，真实安装终态走 `onInstallationOutcome`，各收一次。回调可以把类型映射给宿主现有报告链；SDK 不调用后端。Demo 的日志不算成功上传，业务成功协议与回执需要单独联调。

### 当前配置诊断的安全 detail

配置错误仍使用稳定 reason/stage，现有 `ManagedFailure.detail` 区分缺版本、版本门槛、缺候选、版本不一致、摘要格式、URL 或同版摘要冲突，不把候选版本伪装为 targetVersion。`ConfigResponse.Failure.detail` 只精确接受 `timeout`、`network`、`http`、`empty_response`、`response_decode`、`exception` 六个安全标记，并映射为固定 `provider_*` 说明；null 无细节，其他非 null 值显示 `provider_detail_withheld`。任意异常消息、完整响应、签名 URL 和 Token 不透传。detail 只供诊断，不能用作业务分支；SDK codec 不持久化它。配置诊断不触发安装终态、不抬高失败版本门槛；有效关闭仍优先。

## 本地候选与历史身份

正式版本使用不可变 `0.3.0` tag；另做本地验证时，为每份最终源码显式分配全新的本地版本，并生成 AAR、sources、POM 与 module；实际消费应核对解析路径和摘要。普通 Demo 的 project 编译仅验证源码，不能代替 AAR 消费验证。

```sh
# 在 SDK 根目录；将占位值替换为此次源码的全新唯一版本，不能使用 RC 或旧候选。
./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository \
  -PsdkVersion=0.3.0-local-UNIQUE-SOURCE-ID --console=plain
```

本地候选发布仅允许 file 仓库（默认 `build/repo`），要求显式非空、安全版本名，并拒绝 `0.3.0-rc.1` 或已存在的版本目录。默认版本标识为 `0.3.0`，发布仍不能省略 sdkVersion。普通 `publishToMavenLocal` 继续拒绝；仅显式 `-PjitpackRelease=true -PsdkVersion=0.3.0` 的正式入口可输出至新的 Maven 本地目录，正式入口须显式指定绝对路径 `-Dmaven.repo.local`，隔离检查使用全新目录，见[发布说明](docs/RELEASE-0.3.0.md#发布入口与身份保护)。工作站路径、旧 App candidate init 脚本与历史候选命令仅是对应冻结输入的证据，不能用当前源码重放旧身份。[历史来源说明](docs/RELEASE-0.3.0.md#历史来源与验证边界)保留 B2/候选证据边界；固定 RC 的结果仍见其[发布回执](docs/verification/2026-09-29-test-release/README.md)。

## 环境

- Android API 24+。
- 源码构建：JDK 17、Gradle 8.11.1、AGP 8.10.1、Kotlin 2.0.21、Android SDK 35。
- SDK 字节码目标为 JVM 17；接入工具链需兼容 Kotlin 2.0 元数据。
- 系统 WebView 无需 TBS。仅接入 X5 时另行提供 `com.tencent.tbs:tbssdk:44286`。

## 引入

### 正式 `0.3.0`

下方依赖使用不可变 `0.3.0` tag。远端发布成立须同时核对 JitPack 实际构建、远端 AAR/sources/POM/module 和普通 Gradle 消费，不能由本地构建推断；见[发布说明](docs/RELEASE-0.3.0.md)。

在 `settings.gradle.kts` 中：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mobilewhj") }
        }
    }
}
```

在应用模块 `build.gradle.kts` 中：

```kotlin
implementation("com.github.mobilewhj:offlineSdk:0.3.0")
```

### 已发布的固定 `0.3.0-rc.1`

继续使用 RC 的宿主保持 `implementation("com.github.mobilewhj:offlineSdk:0.3.0-rc.1")`，并使用页首固定 tag 的样例。其 JitPack 构建、远端 POM 与隔离消费见[历史发布回执](docs/verification/2026-09-29-test-release/README.md)；RC 不包含 default/keyValue/codec/prepareStartup 新入口。

### `0.2.2` 历史低层版本

`0.2.2` 已发布。以下仅供继续使用低层 API 的宿主参考。

在 `settings.gradle.kts` 中：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mobilewhj") }
        }
    }
}
```

在应用模块 `build.gradle.kts` 中：

```kotlin
implementation("com.github.mobilewhj:offlineSdk:0.2.2")
```

公开包名为 `com.offline.tool`。当前源码 Demo 默认使用 `implementation(project(":offlineSdk"))`；RC 样例必须使用上方固定 tag。

`0.2.2` 修复入口检查等待后台下载的问题，公开 API 签名不变。`0.2.1` 增加的安装结果字段改变了部分二进制签名；从 `0.2.0` 升级时请重新编译宿主。详见 [迁移说明](docs/MIGRATION-INSTALL-FACTS.md)和[并发修复记录](docs/EXECUTION-ISUSABLE-CONCURRENCY.md)。

## 历史低层接入

同一资源根目录复用一个安装器：

```kotlin
import com.offline.tool.InstallResult
import com.offline.tool.PackageInstaller
import com.offline.tool.PackageRecord
import java.io.File

val installer = PackageInstaller(File(context.filesDir, "offline-packages"))

suspend fun installCandidate(record: PackageRecord, url: String): InstallResult =
    installer.install(record, url)
```

`version` 从 `10000` 起，`sha256` 是可信配置提供的 64 位小写十六进制摘要。协程取消继续抛出；失败返回 `InstallResult.Failure`，包含 `reason`、`stage`、`httpStatus` 等诊断信息。

**收到 `Success` 后先持久化 `result.record`，保存成功再让新页面使用该版本。** 低层 `PackageInstaller` 不保存记录；托管入口会调用宿主存储完成保存。完整托管调用链见 [Welcome 示例](docs/DEMO.md)。

在主线程为 WebView 绑定已经安装并保存的版本：

```kotlin
import com.offline.tool.OfflineInterceptor

webView.webViewClient = OfflineInterceptor(
    directory = installer.directory(installedVersion),
    baseUrl = "https://your-site.example/app/",
)
webView.loadUrl("https://your-site.example/app/")
```

`baseUrl` 以 `/` 结尾。资源映射保留原 URL；只处理匹配范围内的 GET、无 Range 请求，未命中时交给 WebView 默认加载。已有自定义 WebViewClient 的项目，可在 `shouldInterceptRequest` 中调用 `interceptor.resolve(...)`。

## ZIP 与更新约定

ZIP 根目录或 `dist/` 内必须有非空 `index.html`，例如：

```text
site.zip
├── index.html
├── assets/
│   ├── app.js
│   └── app.css
└── images/
```

- 压缩包和单个文件上限均为 64 MiB，总解压上限 256 MiB，最多 10000 个条目。
- 已存在版本目录不覆盖；同一版本号不更换内容。
- WebView 绑定固定版本目录，后台更新不会自动刷新当前页面。
- 低层 `clearOldVersions(...)` 仅在确认没有页面使用待清理目录时调用；托管根目录的清理交给管理器。
- 当前托管示例采用单进程管理器，首次操作由 Welcome 调用，后续检查由 SDK 根据 Application 前台事实调度；不提供跨进程协调。

进度、流关闭、取消、清理、映射规则及 X5 用法详见 [SDK API 文档（中文）](offlineSdk/README.md)。

## 运行示例

用 Android Studio 打开项目并运行 `app`。示例包名为 `com.offline.tool.sample`，默认安装随 APK 提供的合成 ZIP，无需服务端或账号。

首次启动进入 Welcome，SDK 完成资源安装、active 保存及可用性确认后打开 WebView；首次离线准备失败也结束离线等待，并以原 URL 默认加载。以后冷启动即使无包也不重复首装 UI，隐私允许且前台时由 SDK 静默检查和五分钟轮询。Demo 的配置适配返回内置包；接真实配置接口时复用宿主的 Retrofit / Moshi 链路。详见 [Demo 说明](docs/DEMO.md)。

| 目录 | 内容 |
| --- | --- |
| `offlineSdk/` | 可发布的 SDK 与测试 |
| `app/` | ViewModel / StateFlow / ViewBinding 示例和宿主适配 |
| `docs/` | 示例说明、验证结果和发布步骤 |

## 验证与版本状态

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintRelease \
  :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleRelease \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

已发布 `0.2.2` 的历史源码通过 72 项 JVM 测试、Debug / R8 Release 构建和本地 Maven AAR 接入验证。`0.3.0-rc.1` 发布前的代码验收为 SDK 129 项、Demo 14 项；本地 AAR 消费与构建证据见[验收快照](docs/verification/2026-09-29-complexity-reduction/README.md)。隔离 tag Demo 从 JitPack 消费远端 AAR，Debug、Release/R8、14/14 测试、lint 和 AndroidTest Kotlin 源码编译通过，详见[发布回执](docs/verification/2026-09-29-test-release/README.md)。**F4 设备运行验收仍开放**；`0.3.0` 最终产物的完整 Demo 生命周期、系统 WebView 缓存／Cookie／请求头／Range 和 X5 内核均没有通过结果。历史详情见 [0.2.2 执行记录](docs/EXECUTION-ISUSABLE-CONCURRENCY.md)和 [0.2.1 执行记录](docs/EXECUTION-INSTALL-FACTS.md)；已发布 `0.2.0` 的结果见 [验证记录](docs/VALIDATION-0.2.0.md)。

`0.3.0` 功能基线已在独立本地候选中验收；正式发布须另绑定最终 tag、远端制品及普通 Gradle 消费，上述 RC 历史计数不能作为正式产物的新结果。见[验证边界](docs/RELEASE-0.3.0.md#历史来源与验证边界)。API24、真实 X5、真实冷进程、默认五分钟、完整业务启动/两小时路径和后端回执仍单独开放。SDK 发布不代表业务 App 已升级或完成验收。连接设备后可运行 `./gradlew :offlineSdk:connectedDebugAndroidTest`；编译 Android 测试源码不代表设备测试通过。

[更新记录](CHANGELOG.md) · [0.3.0 发布说明](docs/RELEASE-0.3.0.md) · [GitHub Issues](https://github.com/mobilewhj/offlineSdk/issues)

## 开发与许可

`.editorconfig` 固定 Kotlin official 风格、4 空格缩进和 XML 属性换行，可直接使用 Android Studio Reformat Code。

本项目采用 [Apache License 2.0](LICENSE)。

推荐使用 Maven 坐标获得传递依赖，AAR 本身不包含它们。固定 RC 的依赖包含 Kotlin 标准库、OkHttp 4.12.0、Okio 3.7.0 和 kotlinx-coroutines-android 1.7.3；`0.3.0` 另外使用 Gson 2.11.0 作严格 JSON 语法验证，具体传递依赖以正式远端 POM/module 为准。直接复制 AAR 不能省略这些依赖。

## 本地生成示例包

Demo ZIP 仅由仓库 `sample-web/` 中可审阅的示例文件生成，脚本不接收下载地址或外部 ZIP。使用 Python 3，固定文件顺序、时间戳和权限，生成 ZIP 时同步更新 Demo 的 SHA-256。

```sh
python3 scripts/generate-sample.py
python3 scripts/generate-sample.py --check
```

CI 会检查已提交 ZIP、示例源码和配置摘要是否一致。修改网页后，应提高 Demo 的离线包版本号再测试已有安装；不要把清业务数据作为升级或兼容手段。测试 ZIP 由测试夹具在本地构造；Demo 和测试均无需业务网页或真实账号。

[0.2.0 migration / 改名接入说明](docs/MIGRATION-0.2.0.md)
