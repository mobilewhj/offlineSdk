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
import java.util.Locale

/**
 * 创建 WebView 时传入一个版本目录；本地存在就读取，否则返回 null 正常联网。
 * allowHttpAndHttps 允许同一主机的两种协议共用资源，默认端口视为等价，其他端口仍须相同。
 */
class OfflineInterceptor private constructor(
    private val root: File,
    baseUrl: String,
    allowHttpAndHttps: Boolean = false,
    private val debugLogging: Boolean = false,
    private val onResourceFailure: ((reason: String, path: String) -> Unit)? = null,
    private val isEnabled: () -> Boolean = { true },
    @Suppress("UNUSED_PARAMETER") prepared: Unit,
) : WebViewClient() {
    constructor(
        directory: File,
        baseUrl: String,
        allowHttpAndHttps: Boolean = false,
        debugLogging: Boolean = false,
        onResourceFailure: ((String, String) -> Unit)? = null,
        isEnabled: () -> Boolean = { true },
    ) : this(directory.canonicalFile, baseUrl, allowHttpAndHttps, debugLogging, onResourceFailure, isEnabled, Unit)

    private val rootPathPrefix = root.path + File.separator
    private val urlRules = OfflineUrlRules(baseUrl, allowHttpAndHttps)

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
        if (!isEnabled()) return null
        if (method != "GET" || hasRange) return null
        val relative = urlRules.resourcePath(url) ?: return null
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

    internal companion object {
        /** 仅供管理器使用：目录已在 IO 规范化，Main 构造拦截器时不再访问文件系统。 */
        internal fun fromPreparedDirectory(
            directory: File,
            baseUrl: String,
            allowHttpAndHttps: Boolean,
            onResourceFailure: ((String, String) -> Unit)?,
            isEnabled: () -> Boolean,
        ) = OfflineInterceptor(directory, baseUrl, allowHttpAndHttps, false, onResourceFailure, isEnabled, Unit)

        val staticExtensions = setOf(
            "html", "htm", "js", "mjs", "css", "json", "map", "wasm", "properties",
            "svg", "png", "jpg", "jpeg", "gif", "webp", "ico", "ttf", "otf", "woff", "woff2",
        )
    }

}
