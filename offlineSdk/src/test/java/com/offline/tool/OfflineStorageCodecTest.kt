package com.offline.tool

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStorageCodecTest {
    private val digest = "a".repeat(64)

    @Test fun oldAppActiveUsesSameFieldsAndLeavesEligibilityToManager() {
        val record = PackageRecord(100001, digest)
        assertEquals(record, OfflineStorageCodec.decodeActive("""{"sha256":"$digest","version":100001,"ignored":1}"""))
        val encoded = JSONObject(OfflineStorageCodec.encodeActive(record))
        assertEquals(2, encoded.length())
        assertEquals(record, OfflineStorageCodec.decodeActive(encoded.toString()))
        assertEquals(PackageRecord(1, "invalid"), OfflineStorageCodec.decodeActive("""{"version":1,"sha256":"invalid"}"""))
    }

    @Test fun oldAppHistoryKeepsAllFactsAndNewWritesExcludeDetails() {
        val failure = ManagedFailure(ManagedFailureReason.INSTALL, ManagedStage.VERIFY, 100001, digest,
            FailureReason.HASH_MISMATCH, 206, occurredAtMillis = 1234L, activeRollbackFailed = true)
        val json = """
            {"initialPreparationFinished":true,"latestFailure":{"reason":"install","stage":"verify",
            "targetVersion":100001,"targetSha256":"$digest","installReason":"hash_mismatch","httpStatus":206,
            "occurredAtMillis":1234,"activeRollbackFailed":true,"detail":"https://old.test?token=secret"}}
        """.trimIndent()
        assertEquals(PreparationHistory(true, failure), OfflineStorageCodec.decodeHistory(json))
        val encoded = OfflineStorageCodec.encodeHistory(PreparationHistory(true,
            failure.copy(detail = "https://signed.test?token=secret")))
        assertFalse(encoded.contains("secret"))
        assertTrue(encoded.contains("\"installReason\":\"hash_mismatch\""))
        assertEquals(PreparationHistory(true, failure), OfflineStorageCodec.decodeHistory(encoded))
    }

    @Test fun demoUppercaseAliasesReadButAlwaysWriteLowercase() {
        val history = OfflineStorageCodec.decodeHistory("""
            {"initialPreparationFinished":true,"latestFailure":{"reason":"INSTALL","stage":"DOWNLOAD",
            "installReason":"DOWNLOAD_TIMEOUT","targetVersion":100002,"occurredAtMillis":25}}
        """.trimIndent())
        assertEquals(FailureReason.DOWNLOAD_TIMEOUT, history.latestFailure!!.installReason)
        assertEquals(ManagedStage.DOWNLOAD, history.latestFailure!!.stage)
        val json = JSONObject(OfflineStorageCodec.encodeHistory(history)).getJSONObject("latestFailure")
        assertEquals("install", json.getString("reason"))
        assertEquals("download", json.getString("stage"))
        assertEquals("download_timeout", json.getString("installReason"))
    }

    @Test fun unknownCodesAndMissingTimeDropOnlyDiagnosis() {
        for (encoded in listOf(
            """{"reason":"future","stage":"download","occurredAtMillis":1}""",
            """{"reason":"install","stage":"future","occurredAtMillis":1}""",
            """{"reason":"install","stage":"download","installReason":"future","occurredAtMillis":1}""",
            """{"reason":"storage_write","stage":"save_active"}""",
        )) assertEquals(PreparationHistory(true), OfflineStorageCodec.decodeHistory(
            """{"initialPreparationFinished":true,"latestFailure":$encoded}"""))
        assertNull(OfflineStorageCodec.decodeHistory("{}").latestFailure)
    }

    @Test fun emptyNullWrongTopLevelAndTrailingDataAreReadFaults() {
        for (json in listOf("", " ", "null", "[]", "true", "{} {}", "{")) {
            assertReadFault { OfflineStorageCodec.decodeActive(json) }
            assertReadFault { OfflineStorageCodec.decodeHistory(json) }
        }
    }

    @Test fun wrongKeyTypesDoNotCoerceOrDisappear() {
        for (json in listOf(
            """{"version":"100001","sha256":"$digest"}""",
            """{"version":100001.0,"sha256":"$digest"}""",
            """{"version":2147483648,"sha256":"$digest"}""",
            """{"version":null,"sha256":"$digest"}""",
            """{"version":100001,"sha256":false}""",
        )) assertReadFault { OfflineStorageCodec.decodeActive(json) }
        for (json in listOf(
            """{"initialPreparationFinished":"true"}""",
            """{"initialPreparationFinished":null}""",
            """{"latestFailure":"bad"}""",
            """{"latestFailure":{"reason":1,"stage":"download","occurredAtMillis":1}}""",
            """{"latestFailure":{"reason":"future","targetVersion":"100001"}}""",
            """{"latestFailure":{"reason":"install","stage":"download","occurredAtMillis":"1"}}""",
            """{"latestFailure":{"reason":"install","stage":"download","activeRollbackFailed":null}}""",
        )) assertReadFault { OfflineStorageCodec.decodeHistory(json) }
    }

    @Test fun strictSyntaxRejectsIllegalTokensAcrossActiveAndHistory() {
        for (json in listOf(
            """{"version":100001,"sha256":undefined}""",
            """{version:100001,"sha256":"$digest"}""",
            """{'version':100001,"sha256":"$digest"}""",
            """{"version":100001,"sha256":"$digest",}""",
            """{"version":100001,/* comment */"sha256":"$digest"}""",
            """{"version":100001,"sha256":"$digest","extra":TRUE}""",
            """{"version":100001,"sha256":"$digest","extra":NULL}""",
            """{"version":100001,"sha256":"$digest","extra"=1}""",
            """{"version":100001,"sha256":"$digest","extra":0x10}""",
            """{"version":100001,"sha256":"$digest","extra":1.}""",
            """{"version":100001,"sha256":"$digest","extra":1e}""",
            """{"version":100001,"sha256":"$digest","extra":NaN}""",
            """{"version":100001,"sha256":"$digest","extra":01}""",
            """{"version":100001,"sha256":"$digest","extra":"\x41"}""",
            "{\"version\":100001,\"sha256\":\"$digest\",\"extra\":\"raw\nline\"}",
            "{\"version\":100001,\"sha256\":\"$digest\",\"extra\":\"raw\tcontrol\"}",
        )) assertReadFault { OfflineStorageCodec.decodeActive(json) }
        for (json in listOf(
            """{"initialPreparationFinished":true,"extra":undefined}""",
            """{"initialPreparationFinished":true,"extra":[1,]}""",
            """{"initialPreparationFinished":true,"extra":{"value":False}}""",
        )) assertReadFault { OfflineStorageCodec.decodeHistory(json) }
    }

    @Test fun syntaxFaultDoesNotExposeStoredPayloadOrParserCause() {
        val json = """{"version":100001,"sha256":"$digest","token-secret":undefined}"""
        try {
            OfflineStorageCodec.decodeActive(json)
            throw AssertionError("非法语法必须传播读取故障")
        } catch (failure: JSONException) {
            assertEquals("Stored JSON is invalid", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun activeRequiredFactsDoNotBecomeDefaults() {
        for (json in listOf(
            "{}",
            """{"sha256":"$digest"}""",
            """{"version":100001}""",
            """{"version":100001,"sha256":null}""",
        )) assertReadFault { OfflineStorageCodec.decodeActive(json) }
    }

    @Test fun validJsonExtensionsAndBusinessInvalidFactsRemainStoredFacts() {
        val json = """{"version":100001,"sha256":"$digest","extra":{"array":[null,true,1,"\n\t\u0041"]}}"""
        assertEquals(PackageRecord(100001, digest), OfflineStorageCodec.decodeActive(json))
        for (record in listOf(PackageRecord(1, "invalid"), PackageRecord(100001, ""))) {
            assertEquals(record, OfflineStorageCodec.decodeActive(OfflineStorageCodec.encodeActive(record)))
        }
        assertEquals(PreparationHistory(true), OfflineStorageCodec.decodeHistory(
            """{"initialPreparationFinished":true,"extra":{"array":[1,null,false]}}"""))
    }

    @Test fun everyCurrentCodeRoundTripsWithStableStorageFormat() {
        for (reason in ManagedFailureReason.entries) for (stage in ManagedStage.entries) {
            val history = PreparationHistory(true, ManagedFailure(reason, stage, occurredAtMillis = 1))
            assertEquals(history, OfflineStorageCodec.decodeHistory(OfflineStorageCodec.encodeHistory(history)))
        }
        for (install in FailureReason.entries) {
            val history = PreparationHistory(true, ManagedFailure(ManagedFailureReason.INSTALL,
                ManagedStage.DOWNLOAD, installReason = install, occurredAtMillis = 1))
            assertEquals(history, OfflineStorageCodec.decodeHistory(OfflineStorageCodec.encodeHistory(history)))
        }
    }

    private fun assertReadFault(block: () -> Unit) {
        try { block(); throw AssertionError("损坏存储不得当作缺键") } catch (_: JSONException) { }
    }
}
