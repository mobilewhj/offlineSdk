# 更新记录

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
