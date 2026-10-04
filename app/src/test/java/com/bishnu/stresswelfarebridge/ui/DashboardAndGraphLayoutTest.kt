package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layout guards for the Home dashboard card order and the three trend graphs.
 *
 * Composables cannot be unit-tested without a device, so these assert against the source of truth,
 * which is the same approach the existing navigation tests already use.
 */
class DashboardAndGraphLayoutTest {

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
    private fun charts() = source("ui/StressTrendCharts.kt")

    private fun home(): String {
        val src = main()
        val s = src.indexOf("private fun PersonnelHome(")
        val e = src.indexOf("private val DashboardIconSize")
        require(s >= 0 && e > s) { "PersonnelHome not found" }
        return src.substring(s, e)
    }

    private fun trendsScreen(): String {
        val src = main()
        val s = src.indexOf("private fun TrendsScreen(")
        val e = src.indexOf("private fun StressSection(")
        require(s >= 0 && e > s) { "TrendsScreen not found" }
        return src.substring(s, e)
    }

    // == Camera HRV card position ==============================================================

    @Test
    fun cameraHrvCardExistsOnTheDashboard() {
        assertTrue(home().contains("CameraHrvCard("))
    }

    @Test
    fun cameraHrvCardSitsDirectlyBelowHeartRateAndStressLevel() {
        val h = home()
        val heart = h.indexOf("\"HEART RATE\"")
        val stress = h.indexOf("StressCard(result")
        val camera = h.indexOf("CameraHrvCard(")
        assertTrue("the Heart Rate card must come first", heart >= 0)
        assertTrue("the Stress Level card must be present", stress >= 0)
        assertTrue("the Camera HRV card must be present", camera >= 0)
        assertTrue(
            "order must be Heart Rate -> Stress Level -> Camera HRV",
            heart < stress && stress < camera
        )
        // "Directly below" means nothing else may sit between the Stress Level card and the camera
        // card other than the closing of that Row.
        assertFalse(
            "no other card may sit between Stress Level and Camera HRV",
            h.substring(stress, camera).contains("StepsCard(")
        )
    }

    @Test
    fun theCameraCardStillUsesTheRealCameraResult() {
        // The card must keep reading the actual camera PPG result, never a constant.
        val card = source("camera/CameraHrvCard.kt")
        assertTrue(card.contains("CameraHrvResult?"))
        assertTrue(card.contains("result?.let"))
        assertTrue(card.contains("Measure your pulse with the rear camera"))
        assertFalse("no fabricated HRV constant may appear", card.contains("RMSSD 42"))
    }

    @Test
    fun theCameraCardKeepsItsHonestUnavailableState() {
        val card = source("camera/CameraHrvCard.kt")
        assertTrue("with no measurement the card must prompt for one", card.contains("result == null"))
        assertTrue(card.contains("Not enough history yet"))
    }

    // == the three graphs exist and are separate ==================================================

    @Test
    fun allThreeGraphsAreRenderedOnTheTrendsScreen() {
        val t = trendsScreen()
        assertTrue(t.contains("PhysiologicalStressTrendChart("))
        assertTrue(t.contains("PerceivedStressTrendChart("))
        assertTrue(t.contains("DailyContextTrendChart("))
    }

    @Test
    fun eachGraphHasItsOwnTitleAndSubtitle() {
        val c = charts()
        assertTrue(c.contains("\"How your body is responding\""))
        assertTrue(c.contains("\"Physiological Stress\""))
        assertTrue(c.contains("\"How stressed you feel\""))
        assertTrue(c.contains("\"Perceived Stress - PSS-10\""))
        assertTrue(c.contains("\"Today's situation\""))
        assertTrue(c.contains("\"Daily Context\""))
    }

    @Test
    fun axesAreCorrectlyScaledPerGraph() {
        val c = charts()
        // Physiological: 0-100. Perceived: 0-40. Context: the three words, no numbers.
        assertTrue(c.contains("AxisLabels(listOf(\"100\", \"50\", \"0\"))"))
        assertTrue(c.contains("AxisLabels(listOf(\"40\", \"20\", \"0\"))"))
        assertTrue(c.contains("AxisLabels(listOf(\"High\", \"Medium\", \"Low\"))"))
    }

    @Test
    fun theDailyContextGraphShowsNoNumberAtAll() {
        val c = charts()
        val section = c.substring(c.indexOf("fun DailyContextTrendChart("))
        assertFalse("Daily Context must never be shown out of 100", section.contains("/ 100"))
        assertFalse(section.contains("out of 100"))
        // No Low/Medium/High to 33/66/100 conversion may appear.
        assertFalse(section.contains("33"))
        assertFalse(section.contains("66"))
    }

    @Test
    fun thePssGraphNeverUsesTheZeroToOneHundredScale() {
        val section = charts().substring(
            charts().indexOf("fun PerceivedStressTrendChart("),
            charts().indexOf("fun DailyContextTrendChart(")
        )
        assertFalse(section.contains("/ 100"))
        assertTrue(section.contains("PSS-10: \$score/\${StressTrends.PERCEIVED_MAX}"))
    }

    @Test
    fun emptyStatesAreHonestAndSpecific() {
        val c = charts()
        assertTrue(c.contains("No physiological stress measurements yet."))
        assertTrue(c.contains("No PSS-10 results yet."))
        assertTrue(c.contains("No daily check-ins yet."))
    }

    @Test
    fun colourBandsAreGreenAmberRed() {
        val c = charts()
        assertTrue("a green low band is required", c.contains("Color(0xFF3FB27F)"))
        assertTrue("an amber moderate band is required", c.contains("Color(0xFFE0A33E)"))
        assertTrue("a red high band is required", c.contains("Color(0xFFE0605A)"))
        assertTrue("bands must be drawn behind the line", c.contains("drawRect("))
    }

    @Test
    fun colourNeverAltersThePlottedValue() {
        // Fractions come from the score alone; the band only picks a colour.
        val trends = source("trends/StressTrends.kt")
        assertTrue(trends.contains("fun physiologicalFraction(score: Int)"))
        assertTrue(trends.contains("fun bandFor(score: Int)"))
        val physFraction = trends.substring(
            trends.indexOf("fun physiologicalFraction("),
            trends.indexOf("fun perceivedFraction(")
        )
        assertFalse("the plotted position must not depend on the band", physFraction.contains("band"))
    }

    @Test
    fun pssColourDoesNotClaimOfficialClinicalCategories() {
        assertTrue(charts().contains("PSS-10 has no official clinical"))
        assertTrue(source("trends/StressTrends.kt").contains("no officially endorsed clinical cut-offs"))
    }

    @Test
    fun theGraphsOfferTooltipsAndEmphasisOnTheSelectedPoint() {
        val c = charts()
        assertTrue("a tap must select a point", c.contains("detectTapGestures"))
        assertTrue(c.contains("TrendTooltip("))
        assertTrue("the selected point must be visually emphasised", c.contains("val selected ="))
        assertTrue("physiological tooltip", c.contains("Stress: \$score/100"))
        assertTrue("PSS-10 tooltip", c.contains("PSS-10: \$score/"))
        assertTrue("Daily Context tooltip", c.contains("Today's situation: "))
    }

    @Test
    fun graphsAreDarkModeFriendlyRoundedAndGridded() {
        val c = charts()
        assertTrue("graphs live in a rounded card", c.contains("RoundedCornerShape(20.dp)"))
        assertTrue("theme colours only, so dark mode follows the app", c.contains("MaterialTheme.colorScheme"))
        assertTrue("subtle gridlines", c.contains("dashPathEffect"))
    }

    @Test
    fun graphsAreNeverCombinedIntoASingleNumber() {
        val c = charts()
        assertFalse(c.contains("perceivedFraction(score) / 2"))
        assertFalse(c.contains("(result.score ?: 0) +"))
        val trends = source("trends/StressTrends.kt")
        assertFalse(trends.contains("combinedScore"))
        assertFalse(trends.contains("overallStress"))
    }

    @Test
    fun graphsUseOnlyRealPersistedData() {
        val trends = source("trends/StressTrends.kt")
        assertTrue(trends.contains("records.filter { it.stressScore != null }"))
        assertTrue(trends.contains("it.completed && it.score != null"))
        assertTrue(trends.contains("DailyContext.category(r)"))
        // No Random, no generated series, no seeded demo data.
        assertFalse(trends.contains("Random("))
        assertFalse(trends.contains("Math.random"))
    }

    @Test
    fun bottomNavigationIsUntouchedByThisWork() {
        val enumBlock = main().substring(main().indexOf("private enum class Tab"))
            .substringBefore("\n")
        assertTrue(enumBlock.contains("HOME"))
        assertTrue(enumBlock.contains("TRENDS"))
        assertFalse("no new bottom-nav destination may appear", enumBlock.contains("INSIGHT"))
    }

    @Test
    fun medicalHistoryStaysOnProfile() {
        val src = main()
        val s = src.indexOf("private fun ProfileScreen(")
        val e = src.indexOf("private fun ProfileSheet")
        assertTrue(src.substring(s, e).contains("MedicalHistorySummaryCard"))
        assertFalse(home().contains("\"MEDICAL HISTORY\""))
    }
}
