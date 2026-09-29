package com.offline.tool

import java.net.URI

/**
 * 页面选择与资源查找共用的 URL 范围规则；只解释 URL，不读取文件或修改运行状态。
 * 页面额外拒绝含点段、反斜杠的路径；资源文件的 canonical 安全校验仍由拦截器负责。
 */
internal class OfflineUrlRules(baseUrl: String, private val allowHttpAndHttps: Boolean) {
    private val base = URI(baseUrl)

    init {
        require(isHttp(base) && base.host != null && base.userInfo == null)
        require(base.path.endsWith('/') && base.query == null && base.fragment == null)
    }

    fun matchesPage(url: String): Boolean {
        val path = matchingRequest(url)?.path?.ifEmpty { "/" } ?: return false
        return safePagePath(base.path) && safePagePath(path) && relativePath(path) != null
    }

    fun resourcePath(url: String): String? {
        val path = matchingRequest(url)?.path ?: return null
        return relativePath(path)?.ifEmpty { "index.html" }
    }

    private fun matchingRequest(url: String): URI? {
        val request = try { URI(url) } catch (_: Exception) { return null }
        val schemeMatches = if (allowHttpAndHttps) isHttp(request) else request.scheme.equals(base.scheme, true)
        val portsMatch = effectivePort(request) == effectivePort(base) ||
            (allowHttpAndHttps && effectivePort(request) == defaultPort(request) &&
                effectivePort(base) == defaultPort(base))
        return request.takeIf {
            schemeMatches && portsMatch && it.host.equals(base.host, true) && it.userInfo == null
        }
    }

    private fun relativePath(path: String): String? =
        if (path.startsWith(base.path)) path.removePrefix(base.path) else null

    private fun safePagePath(path: String): Boolean =
        !path.contains('\\') && path.split('/').none { it == "." || it == ".." }

    private fun isHttp(uri: URI): Boolean =
        uri.scheme.equals("http", true) || uri.scheme.equals("https", true)

    private fun defaultPort(uri: URI): Int = if (uri.scheme.equals("https", true)) 443 else 80
    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else defaultPort(uri)

    companion object {
        /** 页面入口收到无效 base 时正常回源；拦截器构造仍保留参数校验异常。 */
        fun matchesPage(url: String, baseUrl: String, allowHttpAndHttps: Boolean): Boolean = try {
            OfflineUrlRules(baseUrl, allowHttpAndHttps).matchesPage(url)
        } catch (_: Exception) { false }
    }
}
