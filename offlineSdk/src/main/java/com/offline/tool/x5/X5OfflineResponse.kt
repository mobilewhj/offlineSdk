package com.offline.tool.x5

import com.offline.tool.OfflineInterceptor
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse

/** 把 SDK 资源转换成 X5 响应；页面跳转、网络请求和 JSBridge 仍由宿主处理。 */
object X5OfflineResponse {
    fun resolve(interceptor: OfflineInterceptor?, request: WebResourceRequest): WebResourceResponse? =
        resolve(interceptor, request.url.toString(), request.method, request.requestHeaders.orEmpty())

    fun resolve(
        interceptor: OfflineInterceptor?,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap()
    ): WebResourceResponse? {
        val resource = interceptor?.resolve(url, method, headers.keys.any { it.equals("Range", true) }) ?: return null
        return try {
            WebResourceResponse(
                resource.mimeType,
                resource.encoding,
                resource.statusCode,
                resource.reasonPhrase,
                resource.responseHeaders,
                resource.data
            )
        } catch (error: Exception) {
            resource.data.close()
            throw error
        }
    }
}
