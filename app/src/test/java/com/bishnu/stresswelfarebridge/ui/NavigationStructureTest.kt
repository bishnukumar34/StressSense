package com.bishnu.stresswelfarebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for navigation and screen layout.
 *
 * The composables themselves cannot be unit-tested without a device, so these assert on the source
 * of truth instead. That catches the specific regressions this task was about: a fifth bottom-nav
 * destination reappearing, the Medical History card returning to Home, or the Floors card coming
 * back - all of which are invisible to a pure logic test but obvious to a user.
 */
class NavigationStructureTest {

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/$name"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/$name")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("could not locate $name (tried ${candidates.map { it.path }})")
        return f.readText()
    }

    private fun mainActivity() = source("MainActivity.kt")

    private fun between(text: String, startMarker: String, endMarker: String): String {
        val s = text.indexOf(startMarker)
        require(s >= 0) { "start marker not found: $startMarker" }
        val e = text.indexOf(endMarker, s)
        require(e > s) { "end marker not found after $startMarker: $endMarker" }
        return text.substring(s, e)
    }

    // -- bottom navigation ---------------------------------------------------------------------------

    @Test
    fun bottomNavigationHasExactlyFourDestinations() {
        val enumBlock = between(
            mainActivity(), "private enum class Tab", "\n"
        )
        val entries = Regex("""\b([A-Z]+)\("([^"]+)"\)""").findAll(enumBlock)
            .map { it.groupValues[2] }.toList()
        assertEquals("exactly four bottom navigation sections", 4, entries.size)
    }

    @Test
    fun insightIsNotABottomNavigationDestination() {
        val enumBlock = between(mainActivity(), "private enum class Tab", "\n")
        assertFalse(enumBlock.contains("INSIGHT"))
    }

    @Test
    fun theFourSectionsAreHomeStressTrendsProfile() {
        val enumBlock = between(mainActivity(), "private enum class Tab", "\n")
        val labels = Regex("""[A-Z]+\("([^"]+)"\)""").findAll(enumBlock)
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("Home", "Stress", "Trends", "Profile"), labels)
    }

    @Test
    fun allFourSectionsAreRoutedInTheWhenBlock() {
        val whenBlock = between(mainActivity(), "when (tab) {", "if (showProfile)")
        for (t in listOf("HOME", "TRENDS", "STRESS", "PROFILE")) {
            assertTrue("Tab.$t is not routed", whenBlock.contains("Tab.$t ->"))
        }
    }

    // -- Home -------------------------------------------------------------------------------------------

    @Test
    fun homeContainsNoMedicalHistoryCard() {
        val home = between(mainActivity(), "private fun PersonnelHome(", "private val DashboardIconSize")
        assertFalse("Medical History must not be on Home", home.contains("\"MEDICAL HISTORY\""))
    }

    @Test
    fun homeContainsNoFloorsCard() {
        val home = between(mainActivity(), "private fun PersonnelHome(", "private val DashboardIconSize")
        assertFalse("Floors must not be on Home", home.contains("\"FLOORS\""))
        assertFalse(home.contains("ic_stairs"))
    }

    @Test
    fun homeStillShowsSpO2FromHealthConnect() {
        val home = between(mainActivity(), "private fun PersonnelHome(", "private val DashboardIconSize")
        assertTrue(home.contains("\"SpO2\""))
        assertTrue(home.contains("today.spo2"))
    }

    @Test
    fun missingSpO2ShowsNotAvailableRatherThanZero() {
        val home = between(mainActivity(), "private fun PersonnelHome(", "private val DashboardIconSize")
        assertTrue(home.contains("Not available"))
    }

    // -- Profile ------------------------------------------------------------------------------------------

    @Test
    fun profileContainsTheMedicalHistoryCard() {
        val profile = between(mainActivity(), "private fun ProfileScreen(", "private fun ProfileSheet")
        assertTrue("Profile must offer Medical History", profile.contains("MedicalHistorySummaryCard"))
    }

    @Test
    fun profilePassesMedicalHistoryIntoTheSummary() {
        val profile = between(mainActivity(), "private fun ProfileScreen(", "private fun ProfileSheet")
        assertTrue(profile.contains("medicalHistory"))
    }

    // -- Stress section -------------------------------------------------------------------------------------

    @Test
    fun stressSectionContainsPss10DailyCheckInAndInsight() {
        val stress = between(mainActivity(), "private fun StressSection(", "private fun ProfileScreen")
        assertTrue(stress.contains("Pss10Card"))
        assertTrue(stress.contains("Daily Check-in") || stress.contains("Today's Check-in"))
        assertTrue(stress.contains("View Combined Insight"))
    }

    @Test
    fun stressSectionShowsPhysiologicalLoad() {
        val stress = between(mainActivity(), "private fun StressSection(", "private fun ProfileScreen")
        assertTrue(stress.contains("Physiological Stress"))
        assertTrue(stress.contains("Awaiting valid data"))
    }
}