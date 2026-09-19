# 0.2.0 命名迁移

SDK 标识统一为 `com.offline.tool`，Demo 标识为 `com.offline.tool.sample`。业务应用本身的标识无需修改；离线包格式和安装逻辑不变。

- Android：依赖 `com.github.mobilewhj:offlineSdk:0.2.0`；将 `com.offline.demo` import 改为 `com.offline.tool`，包括 X5 适配。
- 鸿蒙：依赖 `com.offline.tool@0.2.0`；移除旧 `harmony-offline-sdk` 或 `com.offline.demo` 依赖，SDK import 改为 `com.offline.tool`。
- iOS：替换旧 Framework 为 `OfflineTool.xcframework`，使用 `#import <OfflineTool/OfflineTool.h>`；原 `SLC` 类名/常量前缀改为 `OFT`。Framework Bundle ID 和错误域改为 `com.offline.tool`，内部队列使用该前缀。

Demo 应用标识改变，旧 Demo 数据不会自动迁移。旧发布标签保留，新版本不覆盖历史产物。
