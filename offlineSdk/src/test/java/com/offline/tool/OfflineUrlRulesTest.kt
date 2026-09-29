package com.offline.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineUrlRulesTest {
    @Test
    fun strictSchemeAndHostMatchingIsCaseInsensitiveAndKeepsDefaultPortEquivalence() {
        for ((scheme, defaultPort, alternateScheme) in listOf(
            Triple("http", 80, "https"), Triple("https", 443, "http"),
        )) {
            val rules = OfflineUrlRules("${scheme.uppercase()}://EXAMPLE.test/app/", false)
            for (origin in listOf("$scheme://example.TEST", "$scheme://example.test:$defaultPort")) {
                assertMatches(rules, "$origin/app/index.html", "index.html")
            }
            assertRejected(rules, "$alternateScheme://example.test/app/index.html")
            assertRejected(rules, "$scheme://other.test/app/index.html")
        }
    }

    @Test
    fun dualSchemeTreatsImplicitAndExplicitDefaultPortsAsEquivalent() {
        val origins = listOf(
            "http://example.test", "http://example.test:80",
            "https://example.test", "https://example.test:443",
        )
        for (base in origins) {
            val rules = OfflineUrlRules("$base/app/", true)
            for (origin in origins) assertMatches(rules, "$origin/app/?token=secret#route", "index.html")
            assertRejected(rules, "ftp://example.test/app/")
            assertRejected(rules, "http://example.test:8080/app/")
            assertRejected(rules, "https://example.test:8443/app/")
        }
    }

    @Test
    fun dualSchemeStillRequiresSameNonstandardPort() {
        val rules = OfflineUrlRules("https://example.test:8443/app/", true)
        for (scheme in listOf("http", "https")) {
            assertMatches(rules, "$scheme://example.test:8443/app/", "index.html")
            assertRejected(rules, "$scheme://example.test/app/")
            assertRejected(rules, "$scheme://example.test:8080/app/")
        }
        val explicit443 = OfflineUrlRules("http://example.test:443/app/", true)
        assertMatches(explicit443, "https://example.test/app/", "index.html")
        assertRejected(explicit443, "http://example.test/app/")
        assertRejected(explicit443, "https://example.test:80/app/")
    }

    @Test
    fun basePathBoundaryAndDecodedResourcePathAreSharedWithoutRewritingUrl() {
        val rules = OfflineUrlRules("https://example.test/app/", true)
        assertMatches(rules, "https://example.test/app/assets/main.js?x=1#route", "assets/main.js")
        assertMatches(rules, "https://example.test/app/%E4%B8%AD%E6%96%87.html", "中文.html")
        assertRejected(rules, "https://example.test/app")
        assertRejected(rules, "https://example.test/application/index.html")
        assertRejected(rules, "https://user@example.test/app/")
        assertRejected(rules, "https://example.test/app/%")
        assertRejected(rules, "not a URL")
    }

    @Test
    fun pageSelectionRejectsUnsafeDecodedPathsBeforeAnyFileObservation() {
        val rules = OfflineUrlRules("https://example.test/app/", false)
        for ((path, relative) in listOf(
            "./index.html" to "./index.html",
            "../private.js" to "../private.js",
            "%2e%2e/private.js" to "../private.js",
            "assets/%5cprivate.js" to "assets/\\private.js",
        )) {
            val url = "https://example.test/app/$path"
            assertFalse(url, rules.matchesPage(url))
            // 资源规则只返回候选路径；是否越界、符号链接或文件缺失仍在拦截器检查。
            assertEquals(relative, rules.resourcePath(url))
        }
        assertFalse(OfflineUrlRules.matchesPage(
            "https://example.test/app/../index.html", "https://example.test/app/../", false,
        ))
    }

    @Test
    fun emptyPagePathMeansRootButDoesNotChangeResourceLookupBehavior() {
        val rules = OfflineUrlRules("https://example.test/", false)
        assertTrue(rules.matchesPage("https://example.test?query=1#route"))
        assertNull(rules.resourcePath("https://example.test?query=1#route"))
        assertMatches(rules, "https://example.test/", "index.html")
    }

    @Test
    fun invalidBaseFallsBackForPageSelectionAndStillRejectsInterceptorConfiguration() {
        for (base in listOf(
            "ftp://example.test/app/", "https:/app/", "https://user@example.test/app/",
            "https://example.test/app", "https://example.test/app/?query=1",
            "https://example.test/app/#route", "not a URL",
        )) {
            assertFalse(base, OfflineUrlRules.matchesPage("https://example.test/app/", base, true))
            assertTrue(base, runCatching { OfflineUrlRules(base, true) }.isFailure)
        }
    }

    private fun assertMatches(rules: OfflineUrlRules, url: String, relative: String) {
        assertTrue(url, rules.matchesPage(url))
        assertEquals(url, relative, rules.resourcePath(url))
    }

    private fun assertRejected(rules: OfflineUrlRules, url: String) {
        assertFalse(url, rules.matchesPage(url))
        assertNull(url, rules.resourcePath(url))
    }
}
