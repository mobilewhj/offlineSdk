# 更新记录

## 0.2.1（Android SDK）

- 安装结果增加本次 HTTP 请求是否进入执行边界的 `requestStarted`；本地安装为 `false`，协程取消仍抛出。
- 发布成功后清理失败仍返回 `Failure`，并以 `publishedRecord` 报告本次已发布的记录；其他失败不据已有目录推断发布。
- 增加只读挂起 API `PackageInstaller.isUsable(version)`，统一入口文件和安全路径检查；入口可用不构成可信安装证明。
- 结果数据类增加公开字段会改变 JVM 构造函数、`copy` 等二进制签名；宿主需重新编译。见 [迁移说明](docs/MIGRATION-INSTALL-FACTS.md) 和 [执行记录](docs/EXECUTION-INSTALL-FACTS.md)。
- 正式依赖坐标：`com.github.mobilewhj:offlineSdk:0.2.1`；`0.2.0` 标签和产物保持不变。

## 0.2.0

统一 SDK 标识为 `com.offline.tool`，更新 Demo 和测试标识。iOS 模块改为 OfflineTool，类名前缀改为 OFT。见 [迁移说明](docs/MIGRATION-0.2.0.md)。

## 0.1.0

首个正式版本，面向小型 Android 项目的单包 H5 离线资源方案。

- 沿用 `0.1.0-beta` 的 SDK API 与实现，无功能变更。
- 正式依赖坐标：`com.github.mobilewhj:offlineSdk:0.1.0`。
- GitHub Release 使用正式发布标记；设备验收限制继续保留。

### English

First regular release for small Android projects using one offline H5 resource package.

- Same SDK APIs and implementation as `0.1.0-beta`; no functional changes.
- Release coordinates: `com.github.mobilewhj:offlineSdk:0.1.0`.
- Published as a regular GitHub Release. Device acceptance remains pending.

## 0.1.0-beta

首个 beta：面向小型 Android 项目的单包离线资源方案。

- ZIP 下载、SHA-256 校验、有界解压和版本目录发布。
- 支持协程取消、下载/解压进度和类型化安装结果。
- 系统 WebView 离线映射及可选 X5 响应适配。
- 独立命名空间 `com.offline.demo`，附 SDK 测试与 Welcome 接入示例。
- 示例包含本地版本保存、安装编排、固定页面目录、URL 匹配和调试诊断。
- Welcome 使用 ViewModel / StateFlow，支持首次准备、失败重试、已有包静默更新与生命周期取消。
- 中文与英文 README；Apache-2.0 许可证。

设备验收尚未完成，详见 [验证记录](docs/VALIDATION.md)。

### English

First beta for small Android projects using one offline H5 resource package.

- ZIP download, SHA-256 verification, bounded extraction, and version directory publication.
- Coroutine cancellation, progress callbacks, system WebView mapping, and an optional X5 adapter.
- Welcome sample with persistence, retry, background updates, and fixed resource directories per page.
- Chinese and English READMEs, Apache-2.0 licensing, and 67 passing local JVM tests.
- Device acceptance is pending; no device validation is claimed for Welcome, system WebView, or X5.
