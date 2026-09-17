package com.offline.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun resourceDiagnosticsExcludeNormalSkipsAndStripQuery() {
        val root = File(context.cacheDir, "offline_reasons_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        try {
            root.resolve("empty.js").writeText("")
            val reasons = mutableListOf<Pair<String, String>>()
            val interceptor = OfflineInterceptor(
                root, "https://example.test/app/",
                onResourceFailure = { reason, path -> reasons += reason to path }
            )
            assertNull(interceptor.resolve("https://example.test/app/chunk.js?token=private#route"))
            assertNull(interceptor.resolve("https://example.test/app/empty.js"))
            assertEquals(listOf("RESOURCE_MISSING" to "chunk.js", "RESOURCE_EMPTY" to "empty.js"), reasons)
            assertNull(interceptor.resolve("https://example.test/app/route"))
            assertNull(interceptor.resolve("https://example.test/api/user.json"))
            assertNull(interceptor.resolve("https://other.test/app/chunk.js"))
            assertNull(interceptor.resolve("https://example.test/app/chunk.js", "POST"))
            assertNull(interceptor.resolve("https://example.test/app/chunk.js", hasRange = true))
            assertNull(interceptor.resolve("https://example.test/app/../private.js"))
            assertEquals(2, reasons.size)
            val failedReporter = OfflineInterceptor(
                root, "https://example.test/app/",
                onResourceFailure = { _, _ -> error("Reporter unavailable") }
            )
            assertNull(failedReporter.resolve("https://example.test/app/chunk.js"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitMimeAndUtf8PropertiesHaveNoCorsHeaders() {
        val root = File(context.cacheDir, "offline_mime_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        try {
            val interceptor = OfflineInterceptor(root, "https://example.test/web/")
            val expected = mapOf(
                "css" to ("text/css" to "UTF-8"),
                "html" to ("text/html" to "UTF-8"),
                "htm" to ("text/html" to "UTF-8"),
                "svg" to ("image/svg+xml" to "UTF-8"),
                "properties" to ("text/plain" to "UTF-8"),
                "js" to ("text/javascript" to "UTF-8"),
                "mjs" to ("text/javascript" to "UTF-8"),
                "json" to ("application/json" to "UTF-8"),
                "map" to ("application/json" to "UTF-8"),
                "txt" to ("text/plain" to "UTF-8"),
                "ttf" to ("font/ttf" to null),
                "woff" to ("font/woff" to null),
                "woff2" to ("font/woff2" to null),
                "wasm" to ("application/wasm" to null),
            )
            for ((extension, responseType) in expected) {
                val (mime, encoding) = responseType
                val body = if (extension == "properties") "title=中文资源" else "resource"
                root.resolve("test.$extension").writeText(body, Charsets.UTF_8)
                val response = interceptor.resolve("https://example.test/web/test.$extension")!!
                assertEquals(mime, response.mimeType)
                assertEquals(encoding, response.encoding)
                assertFalse(response.responseHeaders.keys.any { it.startsWith("Access-Control-", ignoreCase = true) })
                assertEquals(body, response.data.bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interceptorReturnsFileStreamOrNullWithoutChangingItsVersionDirectory() {
        val root = File(context.cacheDir, "offline_test_${UUID.randomUUID()}").canonicalFile
        val first = File(root, "10000").apply { mkdirs(); resolve("index.html").writeText("v1") }
        val second = File(root, "10001").apply { mkdirs(); resolve("index.html").writeText("v2") }
        try {
            val old = OfflineInterceptor(first, "https://example.test/web/")
            val latest = OfflineInterceptor(second, "https://example.test/web/")
            val response = old.resolve("https://example.test/web/?a=1#page")!!
            assertTrue(response.data is FileInputStream)
            assertEquals("v1", response.data.bufferedReader().use { it.readText() })
            assertEquals(
                "v2", latest.resolve("https://example.test/web/")!!.data.bufferedReader().use { it.readText() }
            )
            first.resolve("empty.js").writeText("")
            assertNull(old.resolve("https://example.test/web/empty.js"))
            assertNull(old.resolve("https://example.test/web/missing.js"))
            assertNull(old.resolve("https://another.test/web/index.html"))
            assertNull(old.resolve("https://example.test/api/user"))
            assertNull(old.resolve("https://example.test/web/index.html", "POST"))
            assertNull(old.resolve("https://example.test/web/index.html", hasRange = true))
            assertNull(old.resolve("https://example.test/web/../10001/index.html"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun baseAndRequestSchemesAreCaseInsensitive() {
        val root = File(context.cacheDir, "offline_scheme_case_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        try {
            for (scheme in listOf("HTTP", "HtTp", "HTTPS", "HtTpS")) {
                val interceptor = OfflineInterceptor(root, "$scheme://example.test/app/")
                for (requestScheme in listOf(scheme, scheme.lowercase())) {
                    val response = interceptor.resolve("$requestScheme://example.test/app/")
                    assertNotNull("$scheme -> $requestScheme", response)
                    assertEquals("local", response!!.data.bufferedReader().use { it.readText() })
                }
                val alternate = if (scheme.equals("https", ignoreCase = true)) "http" else "https"
                assertNull(interceptor.resolve("$alternate://example.test/app/"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun optionalDualSchemeMapsBothDefaultPortsToTheSameFiles() {
        val root = File(context.cacheDir, "offline_schemes_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        try {
            val origins = listOf(
                "http://example.test", "http://example.test:80",
                "https://example.test", "https://example.test:443",
            )
            for (base in origins) {
                val interceptor = OfflineInterceptor(root, "$base/app/", allowHttpAndHttps = true)
                for (origin in origins) {
                    val response = interceptor.resolve("$origin/app/?query=1#route")
                    assertNotNull("$base -> $origin", response)
                    assertEquals("local", response!!.data.bufferedReader().use { it.readText() })
                }
                for (url in listOf(
                    "ftp://example.test/app/", "https://another.test/app/",
                    "https://example.test:8443/app/", "http://example.test:8080/app/",
                    "https://user@example.test/app/", "https://example.test/appp/",
                )) assertNull("$base -> $url", interceptor.resolve(url))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun strictDefaultAndExplicitNonstandardPortsKeepTheirBoundaries() {
        val root = File(context.cacheDir, "offline_ports_${UUID.randomUUID()}").canonicalFile
        root.mkdirs()
        root.resolve("index.html").writeText("local")
        try {
            for ((scheme, alternate) in listOf("http" to "https", "https" to "http")) {
                val strict = OfflineInterceptor(root, "$scheme://example.test/app/")
                assertNull(strict.resolve("$alternate://example.test/app/"))
                assertEquals(
                    "local",
                    strict.resolve("$scheme://example.test/app/")!!
                        .data.bufferedReader().use { it.readText() }
                )
            }
            val nonstandard = OfflineInterceptor(root, "https://example.test:8443/app/", allowHttpAndHttps = true)
            for (scheme in listOf("http", "https")) {
                assertEquals(
                    "local",
                    nonstandard.resolve("$scheme://example.test:8443/app/")!!
                        .data.bufferedReader().use { it.readText() }
                )
                assertNull(nonstandard.resolve("$scheme://example.test/app/"))
                assertNull(nonstandard.resolve("$scheme://example.test:8080/app/"))
            }
            val wrongDefault = OfflineInterceptor(root, "http://example.test:443/app/", allowHttpAndHttps = true)
            assertNull(wrongDefault.resolve("http://example.test/app/"))
            assertNull(wrongDefault.resolve("https://example.test:80/app/"))
        } finally {
            root.deleteRecursively()
        }
    }
}
