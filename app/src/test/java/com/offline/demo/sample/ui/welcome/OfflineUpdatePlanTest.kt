package com.offline.demo.sample.ui.welcome

import com.offline.demo.PackageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineUpdatePlanTest {
    private val local = PackageRecord(10_001, "a".repeat(64))

    @Test
    fun newVersionInstalls() {
        assertEquals(OfflineUpdatePlan.Install, planUpdate(local, local.copy(version = 10_002), true))
    }

    @Test
    fun sameAvailableVersionIsKept() {
        assertEquals(OfflineUpdatePlan.Keep, planUpdate(local, local, true))
    }

    @Test
    fun sameVersionWithMissingFilesIsRepaired() {
        assertEquals(OfflineUpdatePlan.Install, planUpdate(local, local, false))
    }

    @Test
    fun sameVersionCannotReplaceDifferentContent() {
        assertEquals(
            OfflineUpdatePlan.Reject("VERSION_CONTENT_CONFLICT"),
            planUpdate(local, local.copy(sha256 = "b".repeat(64)), true)
        )
    }

    @Test
    fun olderVersionIsNotInstalled() {
        assertEquals(OfflineUpdatePlan.Keep, planUpdate(local, local.copy(version = 10_000), true))
        assertEquals(
            OfflineUpdatePlan.Reject("LOCAL_VERSION_UNAVAILABLE"),
            planUpdate(local, local.copy(version = 10_000), false)
        )
    }

    @Test
    fun invalidCandidateIsRejectedBeforeInstall() {
        assertTrue(planUpdate(null, local.copy(version = 9999), false) is OfflineUpdatePlan.Reject)
        assertTrue(planUpdate(null, local.copy(sha256 = "invalid"), false) is OfflineUpdatePlan.Reject)
    }
}
