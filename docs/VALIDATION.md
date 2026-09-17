# 发布准备验证

验证日期：2026-09-17。版本：`0.1.0-beta`，尚未对外发布。

## 已验证

- 维护者已选择 Apache-2.0，LICENSE、双语 README 和 Maven POM 许可元数据已补齐。

- Kotlin、Gradle Kotlin DSL 与 XML 已使用 Android Studio 格式化，根目录 `.editorconfig` 固定格式规则。

- SDK 16 个 Kotlin 源码／测试文件完成命名空间迁移；除包名和导入替换外，逻辑保持一致；后续统一代码格式，不改变实现语义。
- SDK 41 项、Welcome 示例 26 项 JVM 单元测试通过，0 失败、0 错误、0 跳过。
- SDK Release lint 通过，无错误和警告。
- SDK Android 测试源码与 APK 编译通过。
- SDK Release AAR、源码 JAR、POM 和 Gradle Module Metadata 已生成。
- POM 正确声明 Kotlin、协程、OkHttp 和 Okio；可选 TBS 不作为强制传递依赖。
- Welcome 示例 App 的 Debug 和开启 R8 的 Release 构建通过；首次准备、失败重试、版本持久化、后台更新和固定页面目录已接入。
- 使用本地 Maven 发布物替代项目依赖，示例 Debug／Release 构建及 lint 再次通过，验证实际 AAR 接入。
- 示例 lint 无错误；仍有依赖更新、示例应用图标和备份声明提示。
- 可发布源码、AAR 和源码 JAR 未检出迁移前的标识或开发机绝对路径。
- 本地 Git 已初始化；身份及 origin 对应 GitHub 账号 mobilewhj。缓存、local.properties 和构建产物均未进入暂存区。

## 验证命令

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintRelease \
  :offlineSdk:compileDebugAndroidTestKotlin \
  :offlineSdk:publishReleasePublicationToLocalReleaseRepository \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

实际 AAR 接入通过临时 Gradle init script 将 `project(":offlineSdk")` 替换为本地 `build/repo/` 中的 `com.github.mobilewhj.offlineSdk:offlineSdk:0.1.0-beta` 后验证。

## 尚未完成

- 设备测试尝试停在测试 APK 安装阶段，已中止等待，测试未执行，不计为通过。可在设备安装条件就绪后运行 `:offlineSdk:connectedDebugAndroidTest`。
- 尚未完成新版 Welcome／系统 WebView 示例和 X5 的设备运行验收。
- 示例默认使用内置配置；网络 ZIP 路径有 MockWebServer 测试，真实服务端配置接口尚未集成。
- GitHub 公开仓库已创建；Actions 和 JitPack 的远端执行与 tag 发布待完成。

