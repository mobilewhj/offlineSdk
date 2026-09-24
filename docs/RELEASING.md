# 0.2.0 历史发布清单

当前源码的默认版本已是 `0.2.1`；本页保留已发布 `0.2.0` 的历史步骤，不应用来再次发布或移动该标签。`0.2.1` 的改动见 [执行记录](EXECUTION-INSTALL-FACTS.md)。

目标仓库：`mobilewhj/offlineSdk`。SDK 包名为 `com.offline.tool`，示例包名为 `com.offline.tool.sample`。当前走 GitHub + JitPack 发布，首版使用 `0.2.0`。

## 当前准备情况

- 已有独立工程、SDK 和 Welcome 示例，以及使用说明、更新记录、GitHub Actions、Maven 发布与 JitPack 配置。
- Git 作者为 `mobilewhj`，邮箱使用 GitHub noreply 地址；origin 为 `https://github.com/mobilewhj/offlineSdk.git`。
- GitHub 公开仓库：[mobilewhj/offlineSdk](https://github.com/mobilewhj/offlineSdk)。版本与远端构建状态请查看仓库 Releases、Actions 和 JitPack。
- 已采用 Apache-2.0，LICENSE、双语 README 和 POM license 元数据已补齐；首个正式版本为 `0.2.0`，设备验收尚未完成，验证记录保留该限制。详细结果见 [验证记录](VALIDATION-0.2.0.md)。

## 1. 确定许可证

本项目已由维护者选择 Apache-2.0。根目录 `LICENSE` 保存官方原文，README 声明许可，`offlineSdk/build.gradle.kts` 生成对应的 POM license 元数据。后续发布保持三者一致。

## 2. 完成最后验收

在 Android Studio 中打开独立工程，选中源码执行 Reformat Code，使用仓库的 `.editorconfig`。Kotlin 采用 Kotlin official 风格、4 空格缩进；Android XML 属性分行。不要对生成目录或第三方二进制执行格式化。

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintRelease \
  :offlineSdk:compileDebugAndroidTestKotlin \
  :offlineSdk:publishReleasePublicationToLocalReleaseRepository \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

连接可安装 APK 的设备或模拟器后执行：

```bash
./gradlew :offlineSdk:connectedDebugAndroidTest
```

运行示例检查首次安装、后续启动、旋转、离线页面，以及失败后停留和重试。默认内置 ZIP 不依赖网络；真实服务端配置未接入。若本次对外宣称支持 X5 的运行效果，应补充相应设备验证，否则明确保留未验证说明。

检查待提交内容和发布物：

- `git diff --cached --check` 无格式错误；检查所有暂存文件。
- 不包含公司标识、凭据、本机路径、`local.properties`、签名文件或构建缓存。
- `build/repo/` 包含 AAR、源码 JAR、POM、Gradle Module Metadata，POM 作者和许可证正确。
- 当时发布版本在 `sdkVersion`、README、CHANGELOG 和 tag 中一致，使用 `0.2.0`，不加 `v` 前缀。

## 3. 提交源码到 GitHub

在 GitHub 账号 `mobilewhj` 下创建 `offlineSdk` 仓库。首次创建建议使用空仓库，不自动生成 README、LICENSE 或 .gitignore，因为本地已准备相关文件。若仓库已存在，先核对内容和 origin，避免覆盖远端历史。

许可证、验收和暂存内容确认后，在独立工程根目录依次执行：

```bash
git remote -v
git config --get user.name
git config --get user.email
git add <reviewed-files>
git diff --cached --check
git commit -m "Prepare offline SDK 0.2.0"
git push -u origin main
```

仓库的公开性由发布者选择。面向公开依赖使用时采用 public。以上命令只发布源码；等待 GitHub Actions 成功后再进入下一步。

## 4. 发布固定版本并验证 JitPack

确认 `main` 对应提交的 Actions 通过后：

```bash
git tag -a 0.2.0 -m "Offline SDK 0.2.0"
git push origin 0.2.0
```

不要覆盖或移动已发布的 tag。打开 [JitPack](https://jitpack.io)，查询 `mobilewhj/offlineSdk` 的 `0.2.0`，查看构建日志直到成功。

`jitpack.yml` 选择 JDK 17，通过 `VERSION` 设置实际 tag 版本，执行 SDK 的 `publishToMavenLocal`。发布规则参见 [JitPack 构建文档](https://docs.jitpack.io/building/)。本地 `build/repo/` 构建成功不代表远端已上线。

用独立接入工程实际解析并运行：

```kotlin
// settings.gradle.kts 的 dependencyResolutionManagement.repositories 内
maven("https://jitpack.io") {
    content { includeGroup("com.github.mobilewhj") }
}
```

```kotlin
// 接入方模块 build.gradle.kts
implementation("com.github.mobilewhj:offlineSdk:0.2.0")
```

本项目仅发布一个 SDK，JitPack 实际产物使用上述仓库级坐标。它与本地 `build/repo/` 中的 group 不同；以远端 POM 和实际解析结果为准。

## 5. 整理 GitHub Release

从已经验证的 `0.2.0` tag 创建 Release，不勾选 pre-release，设为 Latest，说明功能、最低 Android API 24、工具链要求、已知限制和安装坐标。内容可从 CHANGELOG 整理。

可附 `offlineSdk/build/outputs/aar/offlineSdk-release.aar`；手动 AAR 接入需要调用方补齐传递依赖，优先推荐 Maven 坐标。示例 APK 是可选附件；当前 `app-release-unsigned.apk` 未签名，不能直接作为可安装演示包提供。

此路径不要求 Google Play 账号、Maven Central 账号或 Android 应用签名证书；若另行分发可安装的 Release 示例 APK，再准备其签名。不要将私钥、密码或 token 提交到仓库。

## 示例资源检查

发布前运行 `python3 scripts/generate-sample.py --check`，确认示例网页、ZIP 与配置摘要一致。需要更新示例时，先修改 `sample-web/`，再运行生成脚本；不要复制其他工程的资源包。测试中的 ZIP 由本地测试夹具生成。

只暂存逐项检查过的文件。自动签名可能修改已跟踪的构建配置；不要暂存本地签名配置、账号、证书路径或凭据。提交前检查暂存区，发布前检查归档内的实际文件。
