package com.offline.tool

import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * 创建 WebView 时传入一个版本目录；本地存在就读取，否则返回 null 正常联网。
 * allowHttpAndHttps 允许同一主机的两种协议共用资源，默认端口视为等价，其他端口仍须相同。
 */
class OfflineInterceptor(
    directory: File,
    baseUrl: String,
    private val allowHttpAndHttps: Boolean = false,
    private val debugLogging: Boolean = false,
    private val onResourceFailure: ((reason: String, path: String) -> Unit)? = null,
) : WebViewClient() {
    private val root = directory.canonicalFile
    private val rootPathPrefix = root.path + File.separator
    private val base = URI(baseUrl)

    init {
        require(
            (base.scheme.equals("http", ignoreCase = true) || base.scheme.equals("https", ignoreCase = true)) &&
                base.host != null && base.userInfo == null
        )
        require(base.path.endsWith('/') && base.query == null && base.fragment == null)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
        request?.let {
            resolve(
                it.url.toString(), it.method, it.requestHeaders.keys.any { key -> key.equals("Range", true) }
            )
        }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = url?.let { resolve(it) }

    /** X5 适配器也复用这个查找入口，不改原始 URL、Cookie 或 JSBridge。 */
    fun resolve(url: String, method: String = "GET", hasRange: Boolean = false): WebResourceResponse? {
        if (method != "GET" || hasRange) return null
        val request = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val schemeMatches = if (allowHttpAndHttps) {
            request.scheme.equals("http", true) || request.scheme.equals("https", true)
        } else request.scheme.equals(base.scheme, true)
        val portsMatch = port(request) == port(base) ||
            (allowHttpAndHttps && port(request) == defaultPort(request) && port(base) == defaultPort(base))
        if (!schemeMatches || !request.host.equals(
                base.host, true
            ) || !portsMatch || request.userInfo != null
        ) return null
        val path = request.path ?: return null
        if (!path.startsWith(base.path)) return null
        val relative = path.removePrefix(base.path).ifEmpty { "index.html" }
        return try {
            val file = File(root, relative)
            val canonical = file.canonicalFile
            if (!canonical.path.startsWith(rootPathPrefix) || canonical != file.absoluteFile) return null
            if (!file.isFile) {
                resourceFailure("RESOURCE_MISSING", relative)
                return null
            }
            if (file.length() == 0L) {
                resourceFailure("RESOURCE_EMPTY", relative)
                return null
            }
            val extension = file.extension.lowercase(Locale.ROOT)
            val mime = when (extension) {
                "css" -> "text/css"
                "html", "htm" -> "text/html"
                "svg" -> "image/svg+xml"
                "ttf" -> "font/ttf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "properties" -> "text/plain"
                "js", "mjs" -> "text/javascript"
                "json", "map" -> "application/json"
                "wasm" -> "application/wasm"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            }
            val encoding =
                if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml") "UTF-8" else null
            WebResourceResponse(
                mime, encoding, 200, "OK", mapOf("Cache-Control" to "no-store"), FileInputStream(file)
            ).also {
                if (debugLogging) {
                    val resourceUrl = url.substringBefore('#').substringBefore('?')
                    Log.d("OfflineInterceptor", "Offline LOCAL url=$resourceUrl file=${file.absolutePath}")
                }
            }
        } catch (_: IOException) {
            resourceFailure("RESOURCE_IO", relative)
            null
        } catch (_: SecurityException) {
            resourceFailure("RESOURCE_IO", relative)
            null
        }
    }

    private fun resourceFailure(reason: String, relative: String) {
        val callback = onResourceFailure ?: return
        // 无扩展名业务路由等仍正常回源；只诊断由包提供的静态资源。
        if (relative.substringAfterLast('.', "").lowercase(Locale.ROOT) !in staticExtensions ||
            relative.split('/').any { it == "." || it == ".." } || relative.contains('\\')
        ) return
        try {
            callback(reason, relative)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* 诊断失败不改变 WebView 的加载结果。 */
        }
    }

    private companion object {
        val staticExtensions = setOf(
            "html", "htm", "js", "mjs", "css", "json", "map", "wasm", "properties",
            "svg", "png", "jpg", "jpeg", "gif", "webp", "ico", "ttf", "otf", "woff", "woff2",
        )
    }

    private fun defaultPort(uri: URI): Int = if (uri.scheme.equals("https", true)) 443 else 80

    private fun port(uri: URI): Int = if (uri.port >= 0) uri.port else defaultPort(uri)
}
