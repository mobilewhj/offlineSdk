# 0.3.0-rc.1 测试候选发布回执

本回执记录固定测试候选的远端发布与消费结果。`0.3.0-rc.1` 是 GitHub **Pre-release**，不设为 Latest；正式 `0.3.0` 未发布。本轮没有修改业务 App 或 SDK 产品行为。

## 固定身份与依赖

| 项目 | 已核对结果 |
| --- | --- |
| 测试版本 | `0.3.0-rc.1` |
| 候选提交 | [`ea4f042e98533c94694001503c2522ba0d1e7446`](https://github.com/mobilewhj/offlineSdk/commit/ea4f042e98533c94694001503c2522ba0d1e7446) |
| 固定标签 | [`0.3.0-rc.1`](https://github.com/mobilewhj/offlineSdk/tree/0.3.0-rc.1)，注解标签对象 `d6ef3e2271fb8f14a8a23cae57cff106e669db66`，解引用后为上述提交 |
| GitHub Release | [0.3.0-rc.1 Pre-release](https://github.com/mobilewhj/offlineSdk/releases/tag/0.3.0-rc.1) |
| 真实 JitPack 坐标 | `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`，`aar` 打包；以[远端 POM](https://jitpack.io/com/github/mobilewhj/offlineSdk/0.3.0-rc.1/offlineSdk-0.3.0-rc.1.pom)为准 |

仓库与依赖配置：

```kotlin
// settings.gradle.kts 的 dependencyResolutionManagement.repositories
maven("https://jitpack.io") {
    content { includeGroup("com.github.mobilewhj") }
}

// App 模块 build.gradle.kts
implementation("com.github.mobilewhj:offlineSdk:0.3.0-rc.1")
```

发布前文档曾把本地多模块 group 当作 JitPack 预期值。JitPack 实际聚合为仓库级 group `com.github.mobilewhj`；本回执、当前主分支 README 和迁移说明均按真实 POM 更正，固定标签不移动。

## 构建与远端产物

- [主分支 CI](https://github.com/mobilewhj/offlineSdk/actions/runs/36592291493)和[标签 CI](https://github.com/mobilewhj/offlineSdk/actions/runs/36592826643)均为 `success`，运行的是 SDK 单测、Release lint、AndroidTest Kotlin 编译、本地发布及 Demo 单测、lint、Debug/Release/R8。
- [JitPack 构建日志](https://jitpack.io/com/github/mobilewhj/offlineSdk/0.3.0-rc.1/build.log)为 `BUILD SUCCESSFUL`、退出码 0，列出 AAR、sources JAR、POM、module；[保存日志](jitpack-build.log)与[保存 POM](jitpack-pom.xml)供复核。
- [远端 AAR](https://jitpack.io/com/github/mobilewhj/offlineSdk/0.3.0-rc.1/offlineSdk-0.3.0-rc.1.aar) SHA-256：`cbfb3d35b8da06b6727643399daca82562bdadf60bcb2e25add916d1685333d3`；[远端 sources JAR](https://jitpack.io/com/github/mobilewhj/offlineSdk/0.3.0-rc.1/offlineSdk-0.3.0-rc.1-sources.jar) SHA-256：`8e4ae3dc4c2dfbf69ea68c7e615ad797619058fbd9946360df8ed4bc52783cc4`。这两个摘要来自独立下载的远端文件，虽然恰好与已验收本地候选相同，并非沿用本地记录。
- sources JAR 的 **15/15** 个 Kotlin/Java 生产源码条目与标签提交路径集合及内容逐字节一致；远端 AAR `classes.jar` 中 `ManagedOfflineSdk` 的 `javap -public` 输出与[已接受公开入口清单](../2026-09-29-complexity-reduction/api-surface-p2.txt)逐字一致。[身份校验 JSON](remote-identity.json)和[远端公开入口](remote-api.txt)留存结果；这份入口清单只覆盖管理器 JVM 声明，不代表整库完整 ABI 校验。标签中管理器 SHA-256 仍为 `2930808d110dabe57d719d632e41a160ba12795ab93cdacdd1d2a46efd91f53f`。

## 真实远端薄宿主消费

从固定标签 `git archive` 建立仅含 `:app` 的独立工程；其[仓库配置](remote-consumer-settings.gradle.kts)只有 Google、Maven Central 与 JitPack，应用直接声明上述远端坐标。新 Gradle 用户目录加 `--refresh-dependencies`；没有 SDK project 依赖、`mavenLocal`、本地 `build/repo` 或依赖替换。解析打印的 AAR 来自独立 Gradle 缓存，其 SHA-256 与另行下载的远端 AAR 一致。[解析结果](remote-consumer-resolution.txt)、[汇总 JSON](remote-consumer-summary.json)、[完整成功日志](remote-consumer-build.log)及[JUnit XML](junit/remote-app/)均已保存。

实际通过命令在隔离工程 `$REMOTE_SMOKE/project` 执行（`$REMOTE_SMOKE` 为测试时新建目录，`$SDK_REPO` 为 SDK 源码检出目录）：

```bash
./gradlew --gradle-user-home "$REMOTE_SMOKE/gradle-home" \
  --refresh-dependencies --no-daemon --console=plain \
  -I "$SDK_REPO/scripts/remote-consumer-smoke/verify-remote.init.gradle" \
  -PremoteSmokeGroup=com.github.mobilewhj \
  -PremoteSmokeArtifact=offlineSdk \
  -PremoteSmokeVersion=0.3.0-rc.1 \
  :app:verifyRemoteSdkResolution \
  :app:dependencyInsight --configuration debugRuntimeClasspath --dependency offlineSdk \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
  :app:lintDebug :app:compileDebugAndroidTestKotlin
```

结果为 `BUILD SUCCESSFUL`，**110 个 actionable Gradle 任务全部执行**，包含 Debug、Release/R8、lint 与 AndroidTest Kotlin 源码编译；Demo JVM **14/14**，零失败、错误和跳过。第一次运行只因验证辅助脚本对 Groovy `Set` 调用不存在的 `single()` 而停在记录解析结果时；改为迭代器取唯一值后，在同一隔离工程重跑全部门禁成功。该修正仅涉及验证脚本，标签中的 SDK/Demo 产品代码未改变。后续可直接使用已修正的 `scripts/remote-consumer-smoke/run.sh` 重建同一验证。

## 迁移与待验范围

[托管接入迁移说明](../../MIGRATION-MANAGED-0.3.0.md#最小接入链)给出 Application 持有管理器、Welcome 的 `prepareFirst(onProgress)`、页面 `loadPage(url, baseUrl, callbacks)` 和存储契约；[薄宿主 Demo](../../../app/)和[AAR 消费源码](../../../scripts/aar-consumer-smoke/src/com/offline/tool/sample/OfflineSdkAarSmoke.kt)提供最小调用样例。升级宿主需重新编译二进制。

发布前本地源码同一候选通过 SDK **129/129**、Demo **14/14**；独立聚焦 **6/6** 属于 SDK 129 项，不重复计数。[发布前证据](../2026-09-29-complexity-reduction/README.md)保留历史外部七项及代码验收边界。**F4 当前候选设备通过 XML 尚无结果**；完整 Demo 生命周期、系统 WebView 缓存/Cookie/请求头/Range、X5 实际内核及业务 App 接入仍待后续测试。本 Pre-release 不宣称正式 `0.3.0` 已完成这些验收。
