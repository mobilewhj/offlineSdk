package com.offline.tool.sample.ui.welcome

import com.offline.tool.ManagedProgress
import com.offline.tool.sample.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WelcomeProgressPresentationTest {
    @Test
    fun waitingWithoutOwnerProgressUsesNeutralPresentation() {
        val presentation = presentProgress(WelcomeUiState.Preparing())
        assertEquals(R.string.preparing_offline, presentation.statusResource)
        assertNull(presentation.percent)
    }

    @Test
    fun completedDownloadAndExtractionRemainBelowOverallCompletion() {
        assertEquals(99, present(ManagedProgress.Downloading(100, 100)).percent)
        assertEquals(99, present(ManagedProgress.Extracting(100)).percent)
        assertNull(present(ManagedProgress.Saving).percent)
    }

    @Test
    fun preparationAndConfirmationUseSingleProgressStream() {
        val preparing = present(ManagedProgress.Preparing(10_000))
        assertEquals(R.string.installing, preparing.statusResource)
        assertNull(preparing.percent)

        val confirming = present(ManagedProgress.Confirming)
        assertEquals(R.string.confirming, confirming.statusResource)
        assertNull(confirming.percent)
    }

    @Test
    fun onlySdkCompleteShowsCompletedLabelAndOneHundredPercent() {
        val presentation = present(ManagedProgress.Complete)
        assertEquals(R.string.preparation_complete, presentation.statusResource)
        assertEquals(100, presentation.percent)
    }

    @Test
    fun missingDownloadSizeHasNoInventedPercentage() {
        assertNull(present(ManagedProgress.Downloading(100, null)).percent)
        assertNull(present(ManagedProgress.Downloading(100, 0)).percent)
    }

    private fun present(progress: ManagedProgress) = presentProgress(WelcomeUiState.Preparing(progress = progress))
}
