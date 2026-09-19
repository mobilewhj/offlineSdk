package com.offline.tool.sample.offline

import java.net.URI

/**
 * 判断页面是否落在配置的 H5 资源目录内，匹配时返回供离线拦截器使用的目录地址。
 *
 * 配置可以是以 `/` 结尾的目录、该目录的 `index.html` 入口，或未写路径的站点地址。
 * 协议名和主机名忽略大小写，路径保持大小写敏感；配置不接受用户信息、查询参数或 fragment。
 * 页面自身的查询参数和 fragment 不参与目录匹配，原始页面 URL 始终由调用方保留。
 *
 * @param url 待加载的页面地址；为空或格式不合法时不建立映射。
 * @param configuredBase 允许映射的 HTTP/HTTPS 入口；空字符串用于关闭该映射。
 * @return 匹配成功时返回以 `/` 结尾、无查询参数和 fragment 的配置目录地址，否则返回 null。
 */
internal fun matchingOfflineBase(url: String?, configuredBase: String): String? = runCatching {
    if (url.isNullOrBlank() || configuredBase.isBlank()) return null
    val base = URI(configuredBase)
    val request = URI(url)
    if ((!base.scheme.equals("https", ignoreCase = true) && !base.scheme.equals("http", ignoreCase = true)) ||
        base.host.isNullOrBlank() || base.userInfo != null ||
        base.rawQuery != null || base.rawFragment != null
    ) return null
    // 省略路径按站点根目录处理；入口文件转换为所在目录，统一后续前缀匹配的边界。
    val path = base.path.orEmpty().ifEmpty { "/" }
    val prefix = if (path.endsWith("index.html")) path.removeSuffix("index.html") else path
    if (!prefix.endsWith("/") || prefix.split('/').any { it == "." || it == ".." } || '\\' in prefix) return null
    fun defaultPort(uri: URI): Int = if (uri.scheme.equals("https", true)) 443 else 80
    fun port(uri: URI): Int = if (uri.port >= 0) uri.port else defaultPort(uri)
    fun usesDefaultPort(uri: URI): Boolean = port(uri) == defaultPort(uri)
    // 同主机下端口相同，或两者均为各自协议的默认端口时允许映射。
    // 这仅决定本地资源映射资格，不改写原请求，也不合并 HTTP/HTTPS 的浏览器 origin。
    if ((!request.scheme.equals("https", ignoreCase = true) && !request.scheme.equals("http", ignoreCase = true)) ||
        !base.host.equals(request.host, true) ||
        request.userInfo != null ||
        (port(base) != port(request) && !(usesDefaultPort(base) && usesDefaultPort(request)))
    ) return null
    val requestPath = request.path.orEmpty().ifEmpty { "/" }
    if (!requestPath.startsWith(prefix)) return null
    if (requestPath.split('/').any { it == ".." || it == "." } || '\\' in requestPath) return null
    // prefix 来自 URI.path 的解码结果，由组件构造器重新编码，避免将已有的 % 转义重复编码。
    URI(base.scheme, null, base.host, base.port, prefix, null, null).toASCIIString()
}.getOrNull()
