# Android 离线包 SDK

SDK 负责离线 ZIP 的下载、校验、安装和 WebView 资源映射。版本选择、元数据持久化及页面切换由调用方负责。

| 文件 | 职责 |
|---|---|
| [PackageRecord.kt](src/main/java/com/offline/tool/PackageRecord.kt) | 与存储方式无关的 `PackageRecord(version, sha256)` |
| [InstallResult.kt](src/main/java/com/offline/tool/InstallResult.kt) | 公开的安装成功／失败结果与错误详情 |
| [FailureReason.kt](src/main/java/com/offline/tool/FailureReason.kt) | 公开的安装失败分类 |
| [InstallStage.kt](src/main/java/com/offline/tool/InstallStage.kt) | 公开的安装失败阶段 |
| [InstallException.kt](src/main/java/com/offline/tool/InstallException.kt) | 内部步骤间传递失败原因、说明与 HTTP 状态的异常 |
| [PackageInstaller.kt](src/main/java/com/offline/tool/PackageInstaller.kt) | 公共 API、安装顺序与互斥、元数据校验、错误映射、目录发布和清理 |
| [PackageDownloader.kt](src/main/java/com/offline/tool/PackageDownloader.kt) | 内部 HTTP 下载、请求总时限、下载进度、取消及响应关闭 |
| [PackageArchive.kt](src/main/java/com/offline/tool/PackageArchive.kt) | 内部无状态 ZIP 工具：有界 Okio 复制与摘要、解压进度、路径及大小限制、内容目录校验 |
| [OfflineInterceptor.kt](src/main/java/com/offline/tool/OfflineInterceptor.kt) | 固定版本目录的资源映射及失败诊断 |
| [X5OfflineResponse.kt](src/main/java/com/offline/tool/x5/X5OfflineResponse.kt) | 可选 X5 响应适配 |

## 存储契约

`PackageRecord` 只包含整数 `version` 和 ZIP 整包 `sha256`，不包含下载地址，也不依赖具体存储库。SDK 接收候选记录，安装成功后返回 `InstallResult.Success.record`；SDK 不读取或保存当前版本记录，不提供存储接口或默认存储实现。

`Success` 表示完整版本目录已经发布。调用方必须自行保存返回的记录，确认保存成功后才能报告当前版本切换；保存失败时保留原当前记录，并自行处理未绑定页面的新目录。安装失败不会修改调用方的记录。

`0.2.1` 中，`Success.requestStarted` 和 `Failure.requestStarted` 都表示**本次调用已经进入 HTTP `Call.execute` 边界**。它在响应及首字节前置为 `true`；参数错误、非法 URL、目标冲突或请求前本地失败为 `false`。请求后的 HTTP 错误、超时、下载中断为 `true`。本地流和内置包安装始终为 `false`。状态只归属单次调用，不代表业务重试次数；计数、预算和冷却由宿主决定。

`Failure.publishedRecord` 仅在本次校验、解压并发布目录后，后续临时文件清理失败时有值。此时 `reason = CLEANUP`、`stage = CLEANUP`，原异常及附加的清理异常保留；结果**仍是失败**。SDK 不从已有完整目录推断本次发布，不自动保存或激活该记录。宿主默认保持原当前版本，按自身策略处理该已发布但清理未完成的目录。

`InstallResult`、`FailureReason`、`InstallStage` 为顶层类型。Failure 同时保留失败阶段、HTTP 状态、错误说明及原始异常；取消以异常传播。

上述两个数据类新增公开构造字段，默认参数只帮助源码重编译；旧二进制对构造函数、`copy` 等调用可能不兼容。从 `0.2.0` 升级到 `0.2.1` 或 `0.2.2` 时须重新编译宿主与封装库，不要把它们当成与 `0.2.0` 完全二进制兼容。`0.2.1` 到 `0.2.2` 未改变公开 API 签名。详见 [宿主迁移说明](../docs/MIGRATION-INSTALL-FACTS.md)。

## 安装与进度

同一根目录应复用同一个 `PackageInstaller`，其安装与清理方法通过实例内的互斥锁串行执行。调用方提供候选版本、可信摘要和下载 URL；版本须不小于 `10000`，摘要为 64 位小写十六进制 SHA-256。

下面是调用方协程中的最小调用示例；返回后按上述存储契约处理结果：

```kotlin
import com.offline.tool.InstallResult
import com.offline.tool.PackageInstaller
import com.offline.tool.PackageRecord

suspend fun installCandidate(
    installer: PackageInstaller,
    candidate: PackageRecord,
    url: String,
): InstallResult = installer.install(
    candidate,
    url,
    onDownloadProgress = { downloadedBytes, totalBytes -> /* totalBytes 为 null 表示长度未知 */ },
    onExtractProgress = { percent -> /* 0..99；保存记录成功后由调用方显示完成 */ },
)
```

通过 `PackageInstaller(root, httpClient, downloadTimeoutMillis = 120_000L)` 可传入共享 OkHttpClient。下载支持 HTTP(S)，不允许跨 HTTP/HTTPS 重定向。默认 **120 秒** 为单次网络请求总时限，覆盖连接、重定向和响应读取，不修改共享客户端的其他请求。取消会取消本次 Call、关闭响应、清理临时文件，并向调用方传播协程取消。

构造函数接受可选参数 `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`。安装、下载及清理共用该调度器；本地输入流工厂、流读写和进度回调也在该调度器的上下文中执行。默认仍为 `Dispatchers.IO`，测试或调用方可通过 `PackageInstaller(root, ioDispatcher = dispatcher)` 注入适合阻塞 IO 的调度器，调用方负责自行创建的调度器的生命周期。

下载地址通过 OkHttp `HttpUrl` 解析，仅接受 HTTP(S)；只有 HTTP 200 响应才作为完整 ZIP 下载。成功消费并关闭响应后停止取消监听，不额外发出网络取消事件。

进度回调在配置的 `ioDispatcher` 上执行，应保持轻量。下载和解压回调默认均为 `null`，不传回调不影响安装。下载进度来自实际读取字节，未知总长度仍以 `null` 表示；解压进度来自实际写入字节与 ZIP 中央目录大小，各阶段独立计量。失败返回 `Failure`，保留 `reason`、`cause`、`httpStatus` 和枚举 `stage`；下载超时原因为 `DOWNLOAD_TIMEOUT`，取消继续抛出，不转换为失败结果。

上报错误说明读取 `Failure.message`。SDK 自定义的安装异常和文件操作说明使用中文，`reason` 错误码保持不变；原始 `cause` 保留用于诊断，不用其消息替代 SDK 的中文说明。系统、第三方库或调用方抛出的其他异常仍保留原文，不保证全部错误都是中文。

`install(record, openZip)` 接收本地输入流并校验给定摘要。`installBuiltin(version, openZip)` 及其进度重载计算并返回流内容的摘要；此摘要仅标识实际内容，不是独立可信校验。SDK 负责关闭传入工厂打开的流。

ZIP 须在根目录或 `dist/` 下提供非空 `index.html`。压缩包和单文件上限均为 64 MiB，总解压上限为 256 MiB，条目上限为 10000；路径穿越、重复路径及不完整归档会被拒绝。校验通过后才发布版本目录，已有版本目录不会被覆盖。

## 恢复与页面保护

- 新版本安装到新目录，已有 WebView 继续使用原目录；SDK 不自动切换页面。
- `clearOldVersions(activeVersion)` 仅在调用方确认没有页面绑定资源的安全冷启动阶段调用。指定当前目录缺失或入口为空时拒绝清理；传入 `null` 会清理根目录中的遗留安装。
- `discardUnboundVersion(version)` 仅删除指定版本残留。调用方必须在页面绑定流程中确认该版本尚未交付页面；SDK 不追踪页面。
- 同版同摘要缺包恢复须由调用方检查资源可用性并串行编排。同版不同摘要不得通过删除当前目录来覆盖。
- `suspend fun isUsable(version: Int): Boolean` 可从主线程调用，在安装器的 IO 调度器上只读检查版本目录下 `index.html` 是否为可读、非空的普通文件。非法版本、不安全的根目录／版本目录／入口链接、缺失或读取失败均返回 `false`；取消传播。它不下载、不保存记录、不切换版本、不清理目录，也不重新计算整包摘要。已有孤立目录即使返回 `true`，也不能当作可信发布或自动成为当前版本；宿主须先有自己的可信记录或本次 `Success.record`／`Failure.publishedRecord` 事实，再决定后续策略。
- `0.2.2` 的入口检查不等待安装互斥锁；文件在检查过程中或返回后均可能变化，页面使用期间的目录保护仍由宿主负责。已发布的 `0.2.1` 仍会等待安装，详见 [并发修复执行记录](../docs/EXECUTION-ISUSABLE-CONCURRENCY.md)。
- 安装和清理在配置的 `ioDispatcher` 上规范化根目录的父路径，允许 Android 系统目录别名；根目录自身为符号链接时拒绝操作。删除子项时不跟随符号链接。
- 取消和原始安装异常优先传播；`finally` 中的临时文件清理保持同步执行，清理异常附加到原异常，仍尝试清理两个临时路径。安装成功后发生的清理失败会通过 `Failure` 返回。

## 资源映射与诊断

`OfflineInterceptor(directory, baseUrl)` 在创建页面时绑定固定版本目录。`baseUrl` 须为 HTTP(S) URL，路径以 `/` 结尾，且不含用户名、query 或 fragment。资源映射保留原 URL、Cookie 和 JSBridge，仅处理配置范围内的 GET、无 Range 请求；返回 `null` 时由 WebView 自行使用缓存或网络。

JS/MJS 使用 `text/javascript`，WOFF/WOFF2 使用 `font/woff`、`font/woff2`；字体响应不指定文本编码。每次查找仍检查文件规范路径是否处于绑定目录内。

可选 `onResourceFailure(reason, path)` 区分 `RESOURCE_MISSING`、`RESOURCE_EMPTY`、`RESOURCE_IO`，仅诊断映射范围内的静态资源类型。路径不含 query/fragment；外域、Range、非 GET、无扩展名路由等正常跳过不记故障。回调应轻量，调用方负责去重及后续统计；SDK 不调用业务接口，诊断回调的一般异常不阻断默认加载，协程取消仍会传播。

`allowHttpAndHttps = true` 允许同主机默认 80/443 共用目录，其他端口仍须有效值相等。默认 `false`；此选项不改变浏览器 origin，也不添加 CORS 通配头。

## 依赖与验证

TBS 为 `compileOnly`，使用 X5 适配器的调用方须提供运行时依赖；其余核心代码不依赖 X5。SDK 不依赖 MMKV 或 JSON 存储格式。

SDK 验证命令：

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintDebug :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleDebug --offline --console=plain
```

这些命令仅验证 SDK；编译 Android 测试源码不等于设备测试通过。
