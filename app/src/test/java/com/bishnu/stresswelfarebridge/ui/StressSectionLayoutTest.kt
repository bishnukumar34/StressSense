package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Stress tab must present three clearly separated kinds of information, each on its own scale:
 *
 *   1. HOW YOUR BODY IS RESPONDING / Physiological Stress - 0-100 from the existing ML pipeline
 *   2. HOW STRESSED YOU FEEL       / Perceived Stress     - 0-40 from PSS-10, monthly
 *   3. TODAY'S SITUATION           / Daily Context        - Low/Medium/High, no number
 *
 * The failure mode this guards is conflation: a physiological 0-100 blended with a questionnaire
 * 0-40, or a daily check-in presented as a stress score. Asserted against source because composables
 * cannot be unit-tested without a device.
 */
class StressSectionLayoutTest {

    private fun source(path: String): String {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/$path"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/$path")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("could not locate $path (tried ${candidates.map { it.path }})")
        return f.readText()
    }

    private fun mainActivity() = source("MainActivity.kt")

    private val stressSection: String by lazy {
        val src = mainActivity()
        val s = src.indexOf("private fun StressSection(")
        val e = src.indexOf("private fun ProfileScreen(")
        require(s >= 0 && e > s) { "StressSection not found" }
        src.substring(s, e)
    }

    // == the three headings exist, verbatim =======================================================

    @Test
    fun allThreeSectionsUseTheRequiredHeadings() {
        // Sections 1 and 3 are composed in StressSection; section 2 is rendered by the existing
        // Pss10Card, which StressSection places between them.
        assertTrue(stressSection.contains("HOW YOUR BODY IS RESPONDING"))
        assertTrue(stressSection.contains("Physiological Stress"))
        assertTrue(stressSection.contains("Pss10Card("))
        assertTrue(stressSection.contains("TODAY'S SITUATION"))
        assertTrue(stressSection.contains("Daily Context"))

        val perceived = source("questionnaire/ui/Pss10Screen.kt")
        assertTrue(perceived.contains("HOW STRESSED YOU FEEL"))
        assertTrue(perceived.contains("Perceived Stress"))

        // Ordering: body -> perceived -> daily context.
        val body = stressSection.indexOf("HOW YOUR BODY IS RESPONDING")
        val perceivedAt = stressSection.indexOf("Pss10Card(")
        val daily = stressSection.indexOf("TODAY'S SITUATION")
        assertTrue("sections must appear in order", body < perceivedAt && perceivedAt < daily)
    }

    @Test
    fun theConfusingTermPhysicalStressIsNotUsed() {
        val offenders = listOf(
            stressSection,
            source("questionnaire/ui/Pss10Screen.kt"),
            source("questionnaire/ui/DailyCheckInCard.kt")
        ).filter { it.contains("Physical Stress") }
        assertTrue("'Physical Stress' must not appear; use 'Physiological Stress'", offenders.isEmpty())
    }

    // == section 1: physiological ================================================================

    @Test
    fun physiologicalSectionUsesTheExistingPipelineAndShowsAZone() {
        // Reads the existing StressEngine result; no new formula is introduced.
        assertTrue(stressSection.contains("result.score"))
        assertTrue(stressSection.contains("usingRealData"))
        assertTrue(stressSection.contains("result.level"))
        assertTrue(stressSection.contains("/ 100"))
        assertTrue(
            "must explain what it is based on",
            stressSection.contains("heart rate, HRV and other body signals")
        )
    }

    @Test
    fun physiologicalSectionIsHonestWhenThereIsNoReading() {
        assertTrue(stressSection.contains("Not available"))
        assertTrue(stressSection.contains("Awaiting valid data"))
        assertTrue(stressSection.contains("This reading is not estimated"))
        // Never substitute a made-up number when data is missing.
        assertFalse(stressSection.contains("?: 0"))
    }

    @Test
    fun physiologicalSectionDoesNotClaimToDetectEmotions() {
        assertTrue(
            "must state it reads signals, not feelings",
            stressSection.contains("does not detect what you are feeling or thinking")
        )
    }

    @Test
    fun physiologicalScoreIsNotDerivedFromTheDailyQuestions() {
        // The section reads only `result`; the daily answers never feed it.
        val section1 = stressSection.substring(
            stressSection.indexOf("HOW YOUR BODY IS RESPONDING"),
            stressSection.indexOf("Pss10Card(")
        )
        assertFalse("must not read the daily pulse", section1.contains("todayPulse"))
        assertFalse("must not read DailyContext", section1.contains("DailyContext"))
    }

    // == section 2: perceived =====================================================================

    @Test
    fun perceivedSectionIsMonthlyAndOnItsOwnScale() {
        val pss = source("questionnaire/ui/Pss10Screen.kt")
        assertTrue(pss.contains("HOW STRESSED YOU FEEL"))
        assertTrue(pss.contains("Perceived Stress"))
        assertTrue(pss.contains("Pss10.MAX_SCORE"))
        assertTrue(pss.contains("past month"))
        assertTrue("an empty state is required", pss.contains("Not completed yet"))
    }

    @Test
    fun perceivedSectionIsNeverOnTheZeroToHundredScale() {
        assertFalse(source("questionnaire/ui/Pss10Screen.kt").contains("/ 100"))
    }

    // == section 3: daily context =================================================================

    @Test
    fun dailyContextSectionShowsACategoryAndNoNumber() {
        assertTrue(stressSection.contains("DailyContext.category"))
        assertTrue(stressSection.contains("Not completed today"))
        assertTrue(stressSection.contains("Completed today"))
        assertTrue(stressSection.contains("today's stress, rest and duty context"))
        assertFalse(
            "the daily section must not render a 0-100 score",
            stressSection.contains("Daily Stress")
        )
    }

    @Test
    fun dailyContextIsLabelledAsNotAStressScore() {
        assertTrue(stressSection.contains("It is not a stress score"))
    }

    // == the three are never merged ================================================================

    @Test
    fun theThreeSectionsAreNeverCombinedIntoOneNumber() {
        assertTrue(stressSection.contains("not combined into a single score"))
        // No averaging of the physiological score with a questionnaire total anywhere.
        assertFalse(stressSection.contains("perceivedScore / 2"))
        assertFalse(stressSection.contains("(result.score ?: 0) +"))
    }
}