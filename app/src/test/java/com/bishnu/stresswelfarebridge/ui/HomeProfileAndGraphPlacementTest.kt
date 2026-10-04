package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the exact user-facing placement rules this change set was about.
 *
 *  1. Home must NOT offer Medical History - it lives only in Profile.
 *  2. Home MUST offer a Daily Check-in card, and it must not be called Perceived Stress.
 *  3. Profile MUST still offer Medical History.
 *  4. The three historical graphs belong on Trends ONLY, never on Stress.
 *
 * Composables cannot be exercised by JVM unit tests, so these assert the source of truth - the same
 * approach the existing navigation and layout tests already use in this project.
 */
class HomeProfileAndGraphPlacementTest {

    private fun source(path: String): String {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/$path"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/$path")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("could not locate $path (tried ${candidates.map { it.path }})")
        return f.readText()
    }

    private fun main() = source("MainActivity.kt")

    private fun between(text: String, startMarker: String, endMarker: String): String {
        val s = text.indexOf(startMarker)
        require(s >= 0) { "start marker not found: $startMarker" }
        val e = text.indexOf(endMarker, s)
        require(e > s) { "end marker not found after $startMarker: $endMarker" }
        return text.substring(s, e)
    }

    private fun home() = between(main(), "private fun PersonnelHome(", "private val DashboardIconSize")
    private fun profile() = between(main(), "private fun ProfileScreen(", "private fun ProfileSheet")
    private fun trends() = between(main(), "private fun TrendsScreen(", "private fun StressSection(")
    private fun stress() = between(main(), "private fun StressSection(", "private fun ProfileScreen(")

    private fun checkInCard() = source("questionnaire/ui/DailyCheckInCard.kt")

    // == 1. no Medical History on Home ============================================================

    @Test
    fun homeContainsNoMedicalHistoryCard() {
        val h = home()
        assertFalse("Medical History must not appear on Home", h.contains("MedicalHistorySummaryCard"))
        assertFalse("no MEDICAL HISTORY heading on Home", h.contains("\"MEDICAL HISTORY\""))
        assertFalse("Home must not be handed the medical history object", h.contains("medicalHistory"))
    }

    @Test
    fun homeIsNotWiredToTheMedicalHistoryScreen() {
        // The Home call site must not pass an onMedicalHistory callback any more, otherwise the
        // screen is still reachable from Home even if no card is currently drawn.
        val homeCall = main().substring(main().indexOf("Tab.HOME ->"), main().indexOf("Tab.TRENDS ->"))
        assertFalse("Home must not open the Medical History screen", homeCall.contains("showMedicalHistory"))
    }

    @Test
    fun theMedicalHistoryScreenIsStillReachableFromProfile() {
        val p = profile()
        assertTrue("Profile must still offer the Medical History card", p.contains("MedicalHistorySummaryCard"))
        assertTrue("Profile must still pass the history object through", p.contains("medicalHistory"))
        // The callback that opens the screen must still exist.
        assertTrue(p.contains("onMedicalHistory"))
        // And it must actually be invoked.
        assertTrue(p.contains("onOpen = onMedicalHistory"))
    }

    // == 2/3. the Daily Check-in card on Home ====================================================

    @Test
    fun homeContainsADailyCheckInCard() {
        assertTrue("Home must render the Daily Check-in card", home().contains("DailyCheckInCard("))
    }

    /**
     * Strings the card actually renders, with comments and KDoc stripped.
     *
     * The file explains at length why the card used to be titled PERCEIVED STRESS, so a naive
     * substring search over the raw source would match the explanation rather than the UI. Only
     * code is checked here, which is what a user can actually see.
     */
    private fun renderableSource(path: String): String {
        val raw = source(path)
            .lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        // Keep only the inside of string literals, so prose in a message cannot match either.
        return Regex("\"([^\"]*)\"").findAll(raw).map { it.groupValues[1] }.joinToString("\n")
    }

    @Test
    fun theDailyCheckInCardIsNotLabelledPerceivedStress() {
        val rendered = renderableSource("questionnaire/ui/DailyCheckInCard.kt")
        assertFalse("must not be titled Perceived Stress", rendered.contains("PERCEIVED STRESS"))
        assertFalse("must not be titled Stress Score", rendered.contains("STRESS SCORE"))
        assertFalse("must not be titled Daily Stress", rendered.contains("DAILY STRESS"))
        assertFalse("must not be titled Physiological Stress", rendered.contains("PHYSIOLOGICAL STRESS"))
        assertFalse(
            "must not be subtitled 'How you feel today', which implied a PSS-10 reading",
            rendered.contains("How you feel today")
        )
        // And it must carry its own title and subtitle.
        assertTrue(rendered.contains("DAILY CHECK-IN"))
        assertTrue(rendered.contains("How are you doing today?"))
    }

    @Test
    fun theDailyCheckInCardSaysItIsNotAStressScore() {
        val card = checkInCard()
        assertTrue("it must state the answers are context", card.contains("saved as context"))
        assertTrue("it must state it is not a stress score", card.contains("not a stress score"))
    }

    @Test
    fun theDailyCheckInCardHasBothStates() {
        val card = checkInCard()
        // Not completed yet.
        assertTrue(card.contains("Take a moment to share how today is going."))
        assertTrue(card.contains("Complete Today's Check-in"))
        // Completed.
        assertTrue(card.contains("Completed today"))
        assertTrue(card.contains("Update Today's Check-in"))
        // Completion is derived from today's stored pulse, not from a score.
        assertTrue(card.contains("state.todayPulse != null"))
        assertFalse(card.contains("perceivedScore"))
        assertFalse(card.contains("/ 100"))
    }

    @Test
    fun theDailyCheckInCardKeepsTheApprovedPrivacyNotice() {
        assertTrue(checkInCard().contains("Your answers are private and are not shown to commanders."))
    }

    @Test
    fun theOldMislabeledCardNoLongerExists() {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/PerceivedStressCard.kt"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/PerceivedStressCard.kt")
        )
        assertTrue(
            "the mislabeled PERCEIVED STRESS check-in card must be deleted",
            candidates.none { it.exists() }
        )
        assertFalse(main().contains("PerceivedStressCard"))
    }

    // == 4. graphs on Trends only ==================================================================

    @Test
    fun trendsContainsAllThreeGraphs() {
        val t = trends()
        assertTrue(t.contains("PhysiologicalStressTrendChart("))
        assertTrue(t.contains("PerceivedStressTrendChart("))
        assertTrue(t.contains("DailyContextTrendChart("))
        // They must be fed by the real-record builders, not by invented data.
        assertTrue(t.contains("StressTrends.physiological("))
        assertTrue(t.contains("StressTrends.perceived("))
        assertTrue(t.contains("StressTrends.dailyContext("))
    }

    @Test
    fun stressContainsNoHistoricalGraphs() {
        val s = stress()
        assertFalse("no physiological graph on Stress", s.contains("PhysiologicalStressTrendChart"))
        assertFalse("no PSS-10 graph on Stress", s.contains("PerceivedStressTrendChart"))
        assertFalse("no Daily Context graph on Stress", s.contains("DailyContextTrendChart"))
        assertFalse("Stress must not build any series", s.contains("StressTrends."))
    }

    @Test
    fun stressStillShowsTheThreeCurrentReadings() {
        val s = stress()
        // 1. Physiological, from the live engine result.
        assertTrue(s.contains("HOW YOUR BODY IS RESPONDING"))
        assertTrue(s.contains("Physiological Stress"))
        assertTrue(s.contains("Awaiting valid data"))
        // 2. Perceived, from PSS-10 only.
        assertTrue(s.contains("Pss10Card("))
        // 3. Daily context, as a category with no number.
        assertTrue(s.contains("TODAY'S SITUATION"))
        assertTrue(s.contains("DailyContext.category"))
        assertTrue(s.contains("Not completed today"))
        assertTrue(s.contains("Complete Today's Check-in") || s.contains("Update Today's Check-in"))
    }

    @Test
    fun theGraphsAreNotDuplicatedAnywhereElse() {
        // Exactly one call site each, all inside TrendsScreen.
        val src = main()
        listOf("PhysiologicalStressTrendChart(", "PerceivedStressTrendChart(", "DailyContextTrendChart(")
            .forEach { chart ->
                val count = Regex(Regex.escape(chart)).findAll(src).count { m ->
                    // Ignore the import line.
                    !src.substring(0, m.range.first).endsWith("import com.bishnu.stresswelfarebridge.ui.")
                }
                assertEquals("exactly one call site for $chart", 1, count)
            }
    }

    // == graph scales must survive the move =========================================================

    @Test
    fun graphScalesAreUnchangedAfterMovingToTrendsOnly() {
        val c = source("ui/StressTrendCharts.kt")
        assertTrue("physiological axis is 0-100", c.contains("AxisLabels(listOf(\"100\", \"50\", \"0\"))"))
        assertTrue("PSS-10 axis is 0-40", c.contains("AxisLabels(listOf(\"40\", \"20\", \"0\"))"))
        assertTrue(
            "Daily Context axis is the three categories",
            c.contains("AxisLabels(listOf(\"High\", \"Medium\", \"Low\"))")
        )
        // Green / amber / red survives.
        assertTrue(c.contains("Color(0xFF3FB27F)"))
        assertTrue(c.contains("Color(0xFFE0A33E)"))
        assertTrue(c.contains("Color(0xFFE0605A)"))
        // Tooltips survive.
        assertTrue(c.contains("detectTapGestures"))
        assertTrue(c.contains("TrendTooltip("))
    }

    @Test
    fun noFakeGraphDataIsGenerated() {
        val trendsSource = source("trends/StressTrends.kt")
        assertFalse(trendsSource.contains("Random("))
        assertFalse(trendsSource.contains("Math.random"))
        // Records that carry no score are dropped, never defaulted to a value.
        assertTrue(trendsSource.contains("records.filter { it.stressScore != null }"))
        assertTrue(trendsSource.contains("it.completed && it.score != null"))
    }

    // == bottom navigation is untouched ==============================================================

    @Test
    fun bottomNavigationIsStillHomeStressTrendsProfile() {
        val enumBlock = between(main(), "private enum class Tab", "\n")
        val labels = Regex("""[A-Z]+\("([^"]+)"\)""").findAll(enumBlock)
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("Home", "Stress", "Trends", "Profile"), labels)
    }

}
