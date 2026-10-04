package com.bishnu.stresswelfarebridge.questionnaire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Combined interpretation wording.
 *
 * The text on this screen is safety-relevant: it is read by someone who may be stressed, about
 * themselves. Phrasing that asserts a cause, or that dismisses what someone reported, is a
 * correctness bug rather than a copy preference - so it is asserted here.
 */
class StressInsightBuilderTest {

    // Perceived stress is PSS-10 (0-40); physiological load is 0-100.
    private fun build(phys: Int?, perc: Int?) =
        StressInsightBuilder.build(phys, null, perc, if (perc == null) null else "PSS-10")

    @Test
    fun theTwoDimensionsAreKeptSeparate() {
        val i = build(70, 12)
        assertEquals(StressDimension.PHYSIOLOGICAL, i.physiological.dimension)
        assertEquals(StressDimension.PERCEIVED, i.perceived.dimension)
        assertEquals(70, i.physiological.score)
        assertEquals(12, i.perceived.score)
    }

    @Test
    fun thereIsNoCombinedScoreAnywhere() {
        // No averaging: the two readings must remain individually addressable.
        val i = build(80, 8)
        val fields = StressInsight::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("combined", true) })
        assertFalse(fields.any { it.contains("average", true) })
        assertFalse(fields.any { it.contains("master", true) })
    }

    @Test
    fun bothElevatedAgreesInNeutralLanguage() {
        val t = build(72, 30).interpretation
        assertTrue(t.contains("Both"))
        assertTrue(t.contains("may"))
    }

    @Test
    fun highPerceivedWithCalmBodyIsNotDismissed() {
        val t = build(30, 34).interpretation
        // The person's report must be treated as the more useful reading, not overruled.
        assertFalse("must never tell someone they are not really stressed",
            t.contains("not really stressed", ignoreCase = true))
        assertTrue(t.contains("You report high stress"))
        assertTrue(t.contains("more useful"))
    }

    @Test
    fun highBodyWithLowerReportOffersContextNotContradiction() {
        val t = build(75, 10).interpretation
        assertTrue(t.contains("poor sleep") || t.contains("heavy activity") || t.contains("physical factors"))
        assertTrue(t.contains("may") || t.contains("can"))
    }

    @Test
    fun bothLowIsStatedPlainly() {
        assertTrue(build(25, 8).interpretation.contains("both relatively low"))
    }

    @Test
    fun missingReadingsDoNotProduceAConfidentConclusion() {
        val i = build(null, null)
        assertNull(i.physiological.score)
        assertEquals(InsightLevel.UNKNOWN, i.physiological.level)
        assertTrue(i.interpretation.contains("not enough usable data"))
    }

    @Test
    fun oneSidedAbsenceIsHandledGracefully() {
        val t = build(70, null).interpretation
        assertTrue(t.contains("elevated strain"))
        assertEquals(InsightLevel.UNKNOWN, build(70, null).perceived.level)
    }

    @Test
    fun absentScoreIsNotZero() {
        // A missing reading must not masquerade as a healthy zero.
        val i = build(null, 34)
        assertNull(i.physiological.score)
        assertEquals(InsightLevel.UNKNOWN, i.physiological.level)
    }

    @Test
    fun interpretationNeverClaimsACause() {
        val texts = listOf(
            build(70, 30).interpretation,
            build(20, 34).interpretation,
            build(80, 8).interpretation,
            build(10, 4).interpretation
        )
        for (t in texts) {
            assertFalse(t.contains("because you", ignoreCase = true))
            assertFalse(t.contains("you are suffering", ignoreCase = true))
            assertFalse(t.contains("diagnosis", ignoreCase = true))
            assertFalse(t.contains("you are depressed", ignoreCase = true))
        }
    }

    @Test
    fun disclaimerIsAlwaysPresent() {
        assertEquals("Wellness insight, not a medical diagnosis.", StressInsight.DISCLAIMER)
        assertEquals(StressInsight.DISCLAIMER, build(70, 12).disclaimer)
    }

    @Test
    fun productLanguageIsCorrect() {
        assertEquals("Physiological Stress Load", StressDimension.PHYSIOLOGICAL.title)
        assertEquals("Perceived Stress", StressDimension.PERCEIVED.title)
        // The sensor result must never be called "mental stress".
        assertFalse(StressDimension.PHYSIOLOGICAL.title.contains("Mental", true))
        // The questionnaire result must never be called "physical stress".
        assertFalse(StressDimension.PERCEIVED.title.contains("Physical", true))
    }

    @Test
    fun levelsAreDerivedFromTheScore() {
        assertEquals(InsightLevel.NORMAL, StressInsightBuilder.levelFor(10, 100))
        assertEquals(InsightLevel.ELEVATED, StressInsightBuilder.levelFor(55, 100))
        assertEquals(InsightLevel.HIGH, StressInsightBuilder.levelFor(80, 100))
        // The same fraction of a 0-40 PSS-10 is recognised at the same level.
        assertEquals(InsightLevel.HIGH, StressInsightBuilder.levelFor(32, 40))
        assertEquals(InsightLevel.NORMAL, StressInsightBuilder.levelFor(8, 40))
        assertEquals(InsightLevel.UNKNOWN, StressInsightBuilder.levelFor(null))
    }

    @Test
    fun abstentionReasonSurfacesOnTheReading() {
        val i = StressInsightBuilder.build(null, "Camera signal too poor", 12, "PSS-10")
        assertEquals("Camera signal too poor", i.physiological.reason)
    }
}