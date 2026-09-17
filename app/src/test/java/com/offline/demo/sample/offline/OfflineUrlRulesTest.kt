package com.offline.demo.sample.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineUrlRulesTest {
    @Test
    fun encodedBasePathIsNotEncodedTwice() {
        assertEquals(
            "https://example.com/h5%20app/",
            matchingOfflineBase("https://example.com/h5%20app/assets/app.js", "https://example.com/h5%20app/")
        )
    }

    @Test
    fun entryKeepsQueryAndHashOutOfResourceMapping() {
        assertEquals(
            "https://example.com/web/",
            matchingOfflineBase(
                "https://example.com/web/index.html?channel=app#/policy", "https://example.com/web/index.html"
            )
        )
    }

    @Test
    fun directoryEntryUsesConfiguredOriginAndDefaultPort() {
        assertEquals(
            "https://example.com/web/",
            matchingOfflineBase("https://example.com:443/web/#/home", "https://example.com/web/")
        )
    }

    @Test
    fun unconfiguredOrThirdPartyPagesStayOnline() {
        assertNull(matchingOfflineBase("https://example.com/index.html", ""))
        assertNull(matchingOfflineBase("https://example.com.attacker.test/web/index.html", "https://example.com/web/"))
        assertNull(matchingOfflineBase("https://example.com:8443/web/index.html", "https://example.com/web/"))
        assertNull(matchingOfflineBase("https://other.test/web/index.html", "https://example.com/web/"))
    }

    @Test
    fun businessPagesAcceptHttpAndHttpsOnTheirDefaultPorts() {
        for (base in listOf("https://example.com/web/", "http://example.com/web/")) {
            val origins = listOf(
                "http://example.com", "http://example.com:80",
                "https://example.com", "https://example.com:443",
            )
            for (origin in origins) {
                assertEquals(base, matchingOfflineBase("$origin/web/#/policy", base))
            }
        }
    }

    @Test
    fun schemesMatchRegardlessOfCaseInConfigurationAndRequest() {
        val requestOrigins = listOf(
            "HTTP://example.com", "hTtP://example.com:80",
            "HTTPS://example.com", "hTtPs://example.com:443",
        )
        for (scheme in listOf("HTTP", "hTtP", "HTTPS", "hTtPs")) {
            val base = "$scheme://example.com/web/"
            for (origin in requestOrigins) {
                assertEquals(base, matchingOfflineBase("$origin/web/#/policy", base))
            }
        }
    }

    @Test
    fun dualSchemeStillRejectsOtherProtocolsPortsAndPaths() {
        val base = "https://example.com/web/"
        val rejected = listOf(
            "ftp://example.com/web/", "http://example.com:8080/web/",
            "https://example.com:8443/web/", "http://other.test/web/",
            "http://example.com/web-other/", "http://user@example.com/web/",
        )
        for (url in rejected) {
            assertNull(matchingOfflineBase(url, base))
        }
        assertNull(matchingOfflineBase("http://example.com/web/", "https://example.com:8443/web/"))
        assertEquals(
            "https://example.com:8443/web/",
            matchingOfflineBase("http://example.com:8443/web/", "https://example.com:8443/web/"),
        )
    }

    @Test
    fun unrelatedPagesAndMalformedConfigurationAreNotMapped() {
        assertEquals(
            "https://example.com/web/",
            matchingOfflineBase("https://example.com/web/pay.html", "https://example.com/web/")
        )
        assertNull(matchingOfflineBase("https://example.com/web-other/pay.html", "https://example.com/web/"))
        assertNull(matchingOfflineBase("https://example.com/web/%2e%2e/pay.html", "https://example.com/web/"))
        assertNull(matchingOfflineBase("https://example.com/web/index.html", "https://example.com/web"))
        assertNull(matchingOfflineBase("https://example.com/web/index.html", "https://user@example.com/web/"))
        assertNull(matchingOfflineBase("https://example.com/web/index.html", "https://example.com/web/?config=1"))
    }
}
