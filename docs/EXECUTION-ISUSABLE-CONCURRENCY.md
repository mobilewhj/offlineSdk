# Android SDK 入口检查并发修复执行记录

日期：2026-09-24。基线为已发布的 `0.2.1`，提交 `f4dfa4f2662cac74502541cb6f37d54391b215f8`；开始时工作区干净。本补丁版本为 `0.2.2`，不移动或覆盖 `0.2.1` 标签及产物。

## 问题与范围

`installZip()` 持有安装器互斥锁，网络源在该锁内打开和读取；`isUsable()` 原来也获取同一把锁。使用两个 IO 工作线程的临时验证表明：新版本 HTTP 请求挂起时，已存在且内容未变的旧版本入口检查等待了至少 517ms，取消下载后才返回 `true`。这会延后宿主等待检查结果后的页面打开，但不是主线程同步阻塞，也不是 Android 官方规则要求的特定锁设计。

本次仅移除 `isUsable()` 的取锁，保留 `withContext(ioDispatcher)`、版本与根目录定位、路径和符号链接检查、可读非空普通文件规则、异常返回约定与取消传播。`installZip()`、`clearOldVersions()`、`discardUnboundVersion()` 继续使用原互斥锁；`clearOldVersions()` 内对当前入口的安全检查也保持不变。不增加读写锁、目录租约、调度器或公开 API。

`isUsable()` 的结果只反映本次观察到的入口状态。并发清理时，文件在检查过程中或返回后均可能变化；即使返回 `true`，也不保证返回瞬间目录仍存在。原有互斥锁同样无法在返回后保持目录。已绑定页面的目录保护仍由宿主控制。入口可用不构成可信发布证明，也不认领未知孤立目录。

## 回归与验证

正式回归使用双线程 IO 调度器和 `MockWebServer` 挂起新包下载，等待服务端实际收到请求，然后检查旧包入口。该测试在修改前因 3 秒超时而失败；移除 `isUsable()` 的取锁后通过，并确认下载仍挂起、旧入口内容未变、新版本未发布。测试结束取消下载并清理资源。

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintDebug :offlineSdk:lintRelease \
  :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleDebug :offlineSdk:assembleRelease \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease --offline --console=plain
```

结果：SDK JVM 单测 46 项、示例 JVM 单测 26 项，均无失败、错误或跳过；SDK Debug/Release lint 均无问题；示例 Debug/Release lint 分别有 12/9 条警告、无错误。SDK Debug/Release AAR、示例 Debug/Release APK 与 AndroidTest Kotlin 源码编译成功。

验收阶段先用本地 `0.2.2-SNAPSHOT` AAR 完成消费验证。确定正式版本后，又运行：

```bash
./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository \
  :offlineSdk:publishToMavenLocal -PsdkVersion=0.2.2 --offline --console=plain
./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency offlineSdk \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug \
  --offline --console=plain -I scripts/aar-consumer-smoke.init.gradle
```

结果：本地 `build/repo/` 生成 `0.2.2` AAR、POM、源码 JAR 和 Gradle Module Metadata；与 `jitpack.yml` 一致的 `publishToMavenLocal` 通过。`dependencyInsight` 确认示例实际消费该 AAR，示例 Debug/Release 打包、单测与 Debug lint 通过。新 AAR 与已发布 `0.2.1` AAR 的 `PackageInstaller` 公开 JVM 声明集合相同；本地新 AAR 的 SHA-256 为 `8cd2bee65accf0ebeb5294d086ef1ad0e21afd3a7ac5f5c694e5ef086c92874b`。正式 JitPack 坐标为 `com.github.mobilewhj:offlineSdk:0.2.2`，远端可用性须以实际构建与消费验证为准。

**设备测试未运行。** AndroidTest 源码编译不等于设备执行；WebView/X5 与真实宿主页面打开速度仍需宿主接入后验证。本轮只同步示例 App 的版本号，未修改其安装编排，也未修改 `slcsp_android2`。已发布的 `0.2.1` 仍保留旧锁等待行为；宿主应在 `0.2.2` 实际发布并验证后更新依赖。
