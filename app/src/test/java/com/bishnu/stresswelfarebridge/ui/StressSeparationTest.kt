package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the separation between the three things the Stress tab shows.
 *
 * The requirement is that these never blur into each other:
 *  1. **Physiological Stress Load** - 0-100, produced by the ML model from HR/HRV.
 *  2. **Perceived Stress (PSS-10)** - 0-40, from ten questionnaire items.
 *  3. **Daily Check-in** - three context answers that are deliberately NOT scored at all.
 *
 * Composables cannot be unit-tested without a device, so these assert against the source of truth.
 * The bug they exist to prevent is concrete: the Daily Check-in card was rendering the PSS-10 score
 * as "$score / 100", which displayed a 0-40 total on a 0-100 scale and read as a daily stress score.
 */
class StressSeparationTest {

    private fun source(path: String): String {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/$path"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/$path")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("could not locate $path (tried ${candidates.map { it.path }})")
        return f.readText()
    }

    // == the daily check-in must never show a calculated stress score =================================

    @Test
    fun dailyCheckInScreenShowsNoScoreOutOfAHundred() {
        val src = source("questionnaire/ui/DailyCheckInScreen.kt")
        assertFalse(
            "the Daily Check-in must not render a 0-100 stress score",
            src.contains("/ 100")
        )
        assertFalse(
            "the Daily Check-in must not read a score off the PSS-10 record",
            src.contains("savedScore") || src.contains("perceivedScore")
        )
    }

    @Test
    fun dailyCheckInCardShowsNoScoreOutOfAHundred() {
        val src = source("questionnaire/ui/DailyCheckInCard.kt")
        assertFalse(
            "the Daily Check-in card must not render a 0-100 stress score",
            src.contains("/ 100")
        )
        assertFalse(
            "it must not derive completion from the PSS-10 record",
            src.contains("perceivedScore")
        )
        assertTrue(
            "it should key off today's pulse instead",
            src.contains("todayPulse")
        )
    }

    @Test
    fun mainActivityDoesNotFeedAPss10ScoreIntoTheDailyCheckIn() {
        val src = source("MainActivity.kt")
        assertFalse(
            "the Daily Check-in must not be handed the perceived (PSS-10) score",
            src.contains("savedScore = qState.perceivedScore")
        )
        assertTrue(
            "it should be handed today's stored record, not a score",
            src.contains("existing = qState.todayPulse")
        )
    }

    // == PSS-10 is on its own 0-40 scale ============================================================

    @Test
    fun pss10IsLabelledZeroToForty() {
        val src = source("questionnaire/ui/Pss10Screen.kt")
        assertTrue("the PSS-10 card must show its score out of 40", src.contains("Pss10.MAX_SCORE"))
        assertFalse(
            "PSS-10 must never be presented on the 0-100 physiological scale",
            src.contains("/ 100")
        )
    }

    @Test
    fun pss10StartButtonIsNotDisabled() {
        val src = source("questionnaire/ui/Pss10Screen.kt")
        assertTrue("the Start button must exist", src.contains("Start PSS-10"))
        assertFalse(
            "the Start button must not be gated on licensing in a prototype build",
            src.contains("enabled = available &&")
        )
    }

    @Test
    fun theBlockingLicenceNoticeIsGoneButAPrototypeNoticeRemains() {
        val src = source("questionnaire/ui/Pss10Screen.kt")
        assertFalse(
            "the hard 'Pending verification and licensing' block must be removed",
            src.contains("Pending verification and licensing")
        )
        assertTrue(
            "a non-blocking prototype notice must remain",
            src.contains("Prototype questionnaire")
        )
        assertTrue(
            "the notice must state licensing is still required",
            src.contains("before production")
        )
    }

    // == the physiological path is untouched =======================================================

    @Test
    fun physiologicalStressLoadStillUsesTheExistingModelUnchanged() {
        val src = source("MainActivity.kt")
        val stress = src.substring(
            src.indexOf("private fun StressSection("),
            src.indexOf("private fun ProfileScreen(")
        )
        assertTrue(
            "Physiological Stress must still be computed by StressEngine",
            stress.contains("Physiological Stress") && stress.contains("StressEngine")
        )
        assertFalse(
            "the physiological score must not be averaged with the PSS-10 total",
            stress.contains("perceivedScore") && stress.contains("/ 100")
        )
    }

    @Test
    fun theDeployedMlAssetIsStillTheSourceOfPhysiologicalStress() {
        val src = source("ml/StressModelLoader.kt")
        assertTrue(
            "physiological stress must come from the deployed asset, not the legacy engine",
            src.contains("stress_model_ml.json")
        )
    }
}
