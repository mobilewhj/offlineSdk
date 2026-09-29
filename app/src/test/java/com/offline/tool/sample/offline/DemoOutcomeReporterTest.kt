package com.offline.tool.sample.offline

import com.offline.tool.InstallationOutcome
import com.offline.tool.PackageRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DemoOutcomeReporterTest {
    @Test
    fun ordinaryFailureIsLocalAndDoesNotPreventNextReport() = runTest {
        var calls = 0
        val failures = mutableListOf<Exception>()
        val reporter = DemoOutcomeReporter(
            report = { if (++calls == 1) throw IOException("network unavailable") },
            onFailure = failures::add,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.setPrivacyAllowed(true)
        reporter.submit(installed)
        runCurrent()
        reporter.submit(installed)
        runCurrent()

        assertEquals(2, calls)
        assertEquals(1, failures.size)
        reporter.setPrivacyAllowed(false)
    }

    @Test
    fun privacyWithdrawalCancelsInFlightReportWithoutRecordingFailure() = runTest {
        var calls = 0
        var cancelled = false
        val failures = mutableListOf<Exception>()
        val reporter = DemoOutcomeReporter(
            report = {
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            },
            onFailure = failures::add,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.setPrivacyAllowed(true)
        reporter.submit(installed)
        runCurrent()
        reporter.setPrivacyAllowed(false)
        reporter.submit(installed)
        runCurrent()

        assertTrue(cancelled)
        assertTrue(failures.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun withdrawalBeforeDispatchDropsPendingReportAndRegrantDoesNotReplay() = runTest {
        var calls = 0
        val reporter = DemoOutcomeReporter(
            report = { calls++ },
            onFailure = { throw AssertionError(it) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.setPrivacyAllowed(true)
        reporter.submit(installed)
        reporter.setPrivacyAllowed(false)
        runCurrent()
        reporter.setPrivacyAllowed(true)
        runCurrent()
        assertEquals(0, calls)

        reporter.submit(installed)
        runCurrent()
        assertEquals(1, calls)
        reporter.setPrivacyAllowed(false)
    }

    private companion object {
        val installed = InstallationOutcome.Installed(PackageRecord(10000, "a".repeat(64)))
    }
}
