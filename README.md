# Offline SDK

`0.3.0-rc.1` 是供业务 App 接入测试的托管 SDK 候选，**正式 `0.3.0` 未发布**。代码与结构范围已复审接受；固定 tag 的 JitPack 构建、远端 POM 和隔离薄宿主依赖消费均已核验。它统一首次准备、前台五分钟检查、失败版本门槛、安装结果通知和页面目录保护；可编译薄宿主见 [`app/`](app/)。实际 API 与迁移方式见 [托管接入说明](docs/MIGRATION-MANAGED-0.3.0.md)，发布前代码证据见 [验收快照](docs/verification/2026-09-29-complexity-reduction/README.md)，远端消费证据见 [发布回执](docs/verification/2026-09-29-test-release/README.md)。下文 `0.2.2` 坐标与低层用法是已发布版本的历史接入说明。

[![CI](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml/badge.svg)](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml) [![JitPack](https://jitpack.io/v/mobilewhj/offlineSdk.svg)](https://jitpack.io/#mobilewhj/offlineSdk)

**中文** | [English](README.en.md)

面向**小型 Android 项目的单包离线方案**。将一套 H5 静态资源打成 ZIP，按版本安装到本地，再通过 WebView 映射原始 URL 加载资源。

适合一个应用维护一套 H5 资源、以整包方式更新的场景。目标是让单包接入简单、职责清晰；多业务包管理、差分更新和插件化不在当前范围内。

## 功能与边界

- HTTP(S) ZIP 下载、可信 SHA-256 校验、有界解压和版本目录发布。
- Kotlin 挂起 API、取消传播、下载／解压进度；文件操作使用 Okio。
- 系统 WebView 资源映射，可选 X5 响应适配。
- Welcome 薄宿主：首次资格与调用级进度、存储适配、隐私条件和独立报告任务；更新与目录保护由 SDK 管理。

托管入口由 SDK 决定更新和目录保护；宿主提供配置接口、存储编码及页面操作。以下低层接入仍由调用方承担管理。**单包不代表只保留一个目录**：更新后旧页面仍可使用旧版本，直到安全的清理时机。

测试候选的托管接入使用 `prepareFirst(onProgress)` 接收一条完整首装进度，并通过单次挂起 `loadPage(url, baseUrl, callbacks)` 让 SDK 完成页面选择与加载；示例与约束见[迁移说明](docs/MIGRATION-MANAGED-0.3.0.md#最小接入链)。

## 环境

- Android API 24+。
- 源码构建：JDK 17、Gradle 8.11.1、AGP 8.10.1、Kotlin 2.0.21、Android SDK 35。
- SDK 字节码目标为 JVM 17；接入工具链需兼容 Kotlin 2.0 元数据。
- 系统 WebView 无需 TBS。仅接入 X5 时另行提供 `com.tencent.tbs:tbssdk:44286`。

## 引入

### `0.3.0-rc.1` 接入测试候选

固定 tag 的 JitPack 构建、远端 POM 和隔离薄宿主依赖消费确认以下 SDK 坐标；详见 [发布回执](docs/verification/2026-09-29-test-release/README.md)。

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
implementation("com.github.mobilewhj:offlineSdk:0.3.0-rc.1")
```

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

公开包名为 `com.offline.tool`。克隆源码后，示例使用 `implementation(project(":offlineSdk"))`。

`0.2.2` 修复入口检查等待后台下载的问题，公开 API 签名不变。`0.2.1` 增加的安装结果字段改变了部分二进制签名；从 `0.2.0` 升级时请重新编译宿主。详见 [迁移说明](docs/MIGRATION-INSTALL-FACTS.md)和[并发修复记录](docs/EXECUTION-ISUSABLE-CONCURRENCY.md)。

## 快速接入

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
- `clearOldVersions(...)` 仅在确认没有页面使用待清理目录时调用。
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

已发布 `0.2.2` 的历史源码通过 72 项 JVM 测试、Debug / R8 Release 构建和本地 Maven AAR 接入验证。`0.3.0-rc.1` 发布前的代码验收为 SDK 129 项、Demo 14 项；本地 AAR 消费与构建证据见[验收快照](docs/verification/2026-09-29-complexity-reduction/README.md)。隔离 tag Demo 从 JitPack 消费远端 AAR，Debug、Release/R8、14/14 测试、lint 和 AndroidTest Kotlin 源码编译通过，详见[发布回执](docs/verification/2026-09-29-test-release/README.md)。**F4 设备运行验收仍开放**；当前候选的完整 Demo 生命周期、系统 WebView 缓存／Cookie／请求头／Range 和 X5 内核均没有通过结果。历史详情见 [0.2.2 执行记录](docs/EXECUTION-ISUSABLE-CONCURRENCY.md)和 [0.2.1 执行记录](docs/EXECUTION-INSTALL-FACTS.md)；已发布 `0.2.0` 的结果见 [验证记录](docs/VALIDATION-0.2.0.md)。

连接设备后可运行 `./gradlew :offlineSdk:connectedDebugAndroidTest`。编译 Android 测试源码不代表设备测试通过。

[更新记录](CHANGELOG.md) · [发布步骤](docs/RELEASING.md) · [GitHub Issues](https://github.com/mobilewhj/offlineSdk/issues)

## 开发与许可

`.editorconfig` 固定 Kotlin official 风格、4 空格缩进和 XML 属性换行，可直接使用 Android Studio Reformat Code。

本项目采用 [Apache License 2.0](LICENSE)。

推荐使用 Maven 坐标获得传递依赖。直接使用 AAR 时，调用方需自行提供 Kotlin 标准库、OkHttp 4.12.0、Okio 3.7.0 和 kotlinx-coroutines-android 1.7.3；AAR 本身不包含这些依赖。

## 本地生成示例包

Demo ZIP 仅由仓库 `sample-web/` 中可审阅的示例文件生成，脚本不接收下载地址或外部 ZIP。使用 Python 3，固定文件顺序、时间戳和权限，生成 ZIP 时同步更新 Demo 的 SHA-256。

```sh
python3 scripts/generate-sample.py
python3 scripts/generate-sample.py --check
```

CI 会检查已提交 ZIP、示例源码和配置摘要是否一致。修改网页后，应提高 Demo 的离线包版本号再测试已有安装，或清除 Demo 应用数据。测试 ZIP 由测试夹具在本地构造；Demo 和测试均无需业务网页或真实账号。

[0.2.0 migration / 改名接入说明](docs/MIGRATION-0.2.0.md)
