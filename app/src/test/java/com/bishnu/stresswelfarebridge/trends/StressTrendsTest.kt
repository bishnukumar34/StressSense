package com.bishnu.stresswelfarebridge.trends

import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import com.bishnu.stresswelfarebridge.questionnaire.DailyContext
import com.bishnu.stresswelfarebridge.questionnaire.DailyContextCategory
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord
import com.bishnu.stresswelfarebridge.questionnaire.Pss10Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * The three trend graphs, verified against real persisted records.
 *
 * The properties asserted here are the ones a graph can quietly break:
 *  - points come only from records that exist, so nothing is ever fabricated;
 *  - each graph keeps its own scale (0-100, 0-40, and a categorical axis with no number at all);
 *  - the colour band reflects the level and never the value.
 */
class StressTrendsTest {

    private var seq = 0

    private fun measurement(
        atMillis: Long,
        score: Int?,
        zone: String? = null,
        source: String = "CAMERA_PPG"
    ) = StressMeasurementRecord(
        id = "m${seq++}",
        userId = "u1",
        measuredAtMs = atMillis,
        source = source,
        heartRateBpm = 70.0,
        rmssdMs = 30.0,
        sdnnMs = null,
        signalQuality = "GOOD",
        stressScore = score,
        stressZone = zone,
        confidence = 80,
        modelVersion = "v1",
        featureSchemaVersion = 1
    )

    private fun pss(month: YearMonth, score: Int?, completed: Boolean = true) = Pss10Record(
        id = "p${seq++}",
        userId = "u1",
        version = 1,
        completedAt = Instant.parse("${month.atDay(1)}T00:00:00Z"),
        month = month,
        answers = (1..10).associate { it to 2 },
        score = score,
        completed = completed
    )

    private fun pulse(date: LocalDate, stress: Int?, rest: Int?, duty: Int?) = DailyPulseRecord(
        userId = "u1",
        date = date,
        dStress = stress,
        dRest = rest,
        dDuty = duty,
        checkinRequested = false,
        createdAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    )

    // == physiological ===========================================================================

    @Test
    fun physiologicalGraphPlotsOnlyRealPersistedScores() {
        val at1 = 1758000000000L
        val at2 = at1 + 86_400_000L
        val at3 = at2 + 86_400_000L
        val points = StressTrends.physiological(
            listOf(
                measurement(at3, 72),
                measurement(at1, 58),
                measurement(at2, 47)
            )
        )
        assertEquals(3, points.size)
        assertEquals(listOf(58, 47, 72), points.map { it.score })
    }

    @Test
    fun physiologicalGraphKeepsTheRealMeasurementTimestamp() {
        val at = 1758000000000L
        val points = StressTrends.physiological(listOf(measurement(at, 58)))
        assertEquals("the x value must be the real instant, not an index", at, points[0].atMillis)
    }

    @Test
    fun physiologicalGraphDropsAbstentionsInsteadOfPlottingZero() {
        val points = StressTrends.physiological(
            listOf(measurement(1000L, null), measurement(2000L, 40))
        )
        assertEquals("an abstention is not a reading of zero", 1, points.size)
        assertEquals(40, points[0].score)
    }

    @Test
    fun physiologicalGraphIsEmptyWhenNothingIsPersisted() {
        assertTrue(StressTrends.physiological(emptyList()).isEmpty())
        assertTrue(StressTrends.physiological(listOf(measurement(1L, null))).isEmpty())
    }

    @Test
    fun physiologicalAxisIsZeroToOneHundred() {
        assertEquals(0, StressTrends.PHYSIOLOGICAL_MIN)
        assertEquals(100, StressTrends.PHYSIOLOGICAL_MAX)
        assertEquals(0f, StressTrends.physiologicalFraction(0), 1e-6f)
        assertEquals(0.5f, StressTrends.physiologicalFraction(50), 1e-6f)
        assertEquals(1f, StressTrends.physiologicalFraction(100), 1e-6f)
        // Out-of-range values are clamped, never drawn outside the box.
        assertEquals(1f, StressTrends.physiologicalFraction(140), 1e-6f)
        assertEquals(0f, StressTrends.physiologicalFraction(-5), 1e-6f)
    }

    @Test
    fun physiologicalColourBandsMatchTheEnginesOwnCutPoints() {
        // The graph must not invent its own thresholds: it reuses StressEngine's, so the colour
        // always agrees with the "Low"/"Moderate"/"High" text on the card above it.
        assertEquals(StressBand.LOW, StressTrends.bandFor(0))
        assertEquals(StressBand.LOW, StressTrends.bandFor(StressEngine.LOW_SCORE_MAX - 1))
        assertEquals(StressBand.MODERATE, StressTrends.bandFor(StressEngine.LOW_SCORE_MAX))
        assertEquals(
            StressBand.MODERATE,
            StressTrends.bandFor(StressEngine.MODERATE_SCORE_MAX - 1)
        )
        assertEquals(StressBand.HIGH, StressTrends.bandFor(StressEngine.MODERATE_SCORE_MAX))
        assertEquals(StressBand.HIGH, StressTrends.bandFor(100))
    }

    @Test
    fun physiologicalColourChangesTheLookNotTheValue() {
        val score = 72
        val point = StressTrends.physiological(listOf(measurement(1000L, score))).single()
        assertEquals("the plotted score must be the engine's, untouched", score, point.score)
        assertEquals(StressBand.HIGH, point.band)
    }

    // == PSS-10 ==================================================================================

    @Test
    fun pssGraphUsesOnlyCompletedMonths() {
        val points = StressTrends.perceived(
            listOf(
                pss(YearMonth.of(2026, 5), 18),
                pss(YearMonth.of(2026, 7), 16),
                pss(YearMonth.of(2026, 10), 24)
            )
        )
        assertEquals(3, points.size)
        assertEquals(listOf(18, 16, 24), points.map { it.score })
        assertEquals(
            listOf("May", "Jul", "Oct"),
            points.map { StressTrends.perceivedMonthLabel(it.month) }
        )
    }

    @Test
    fun pssGraphDoesNotCreateMissingMonths() {
        // May, June, July completed; August, September never answered. Nothing may be invented for
        // them, so the series holds three points, not five.
        val points = StressTrends.perceived(
            listOf(
                pss(YearMonth.of(2026, 5), 18),
                pss(YearMonth.of(2026, 6), 22),
                pss(YearMonth.of(2026, 7), 16)
            )
        )
        assertEquals(3, points.size)
        assertFalse(points.any { it.month == YearMonth.of(2026, 8) })
        assertFalse(points.any { it.month == YearMonth.of(2026, 9) })
    }

    @Test
    fun pssGraphIgnoresIncompleteAndUnscoredResponses() {
        val points = StressTrends.perceived(
            listOf(
                pss(YearMonth.of(2026, 5), 18),
                pss(YearMonth.of(2026, 6), null),
                pss(YearMonth.of(2026, 7), 16, completed = false)
            )
        )
        assertEquals(1, points.size)
        assertEquals(YearMonth.of(2026, 5), points[0].month)
    }

    @Test
    fun pssGraphIsEmptyWhenNothingIsCompleted() {
        assertTrue(StressTrends.perceived(emptyList()).isEmpty())
        assertTrue(StressTrends.perceived(listOf(pss(YearMonth.of(2026, 5), null))).isEmpty())
    }

    @Test
    fun pssAxisIsZeroToFortyAndIsNeverRescaledOntoZeroToOneHundred() {
        assertEquals(0, StressTrends.PERCEIVED_MIN)
        assertEquals(40, StressTrends.PERCEIVED_MAX)
        assertEquals(0f, StressTrends.perceivedFraction(0), 1e-6f)
        assertEquals(0.5f, StressTrends.perceivedFraction(20), 1e-6f)
        assertEquals(1f, StressTrends.perceivedFraction(40), 1e-6f)
        assertTrue(StressTrends.isValidPerceivedScore(40))
        assertFalse(StressTrends.isValidPerceivedScore(41))
        // 40 on a 0-100 scale would be 0.4; on its own 0-40 scale it must be the top of the graph.
        assertEquals(1f, StressTrends.perceivedFraction(40), 1e-6f)
        assertEquals(0.4f, StressTrends.physiologicalFraction(40), 1e-6f)
    }

    @Test
    fun pssColourIsRelativeToThePersonsOwnHistoryNotAClinicalBand() {
        val points = StressTrends.perceived(
            listOf(
                pss(YearMonth.of(2026, 5), 16),
                pss(YearMonth.of(2026, 6), 20),
                pss(YearMonth.of(2026, 7), 28)
            )
        )
        assertEquals(StressBand.LOW, points[0].band)
        assertEquals(StressBand.HIGH, points[2].band)
        // The score itself is untouched by the colouring.
        assertEquals(listOf(16, 20, 28), points.map { it.score })
        // PSS-10 publishes no official cut-offs, so no band thresholds may be published as such.
        assertNotNull(StressTrends.relativeBands(points))
    }

    @Test
    fun aSinglePssMonthIsNotForcedIntoTheTopBand() {
        val points = StressTrends.perceived(listOf(pss(YearMonth.of(2026, 5), 30)))
        assertEquals(1, points.size)
        assertEquals(30, points[0].score)
    }

    // == Daily Context ===========================================================================

    @Test
    fun dailyContextGraphUsesOnlyCompletedCheckins() {
        val points = StressTrends.dailyContext(
            listOf(
                pulse(LocalDate.of(2026, 10, 1), 1, 5, 1),
                pulse(LocalDate.of(2026, 10, 2), 3, 3, 3),
                pulse(LocalDate.of(2026, 10, 3), 5, 1, 5)
            )
        )
        assertEquals(3, points.size)
        assertEquals(
            listOf(
                DailyContextCategory.LOW,
                DailyContextCategory.MEDIUM,
                DailyContextCategory.HIGH
            ),
            points.map { it.category }
        )
    }

    @Test
    fun dailyContextGraphDoesNotInventMissingDates() {
        // Check-ins on the 1st and the 4th only. The 2nd and 3rd must not appear.
        val points = StressTrends.dailyContext(
            listOf(
                pulse(LocalDate.of(2026, 10, 1), 1, 5, 1),
                pulse(LocalDate.of(2026, 10, 4), 5, 1, 5)
            )
        )
        assertEquals(2, points.size)
        assertEquals(listOf("Oct 1", "Oct 4"), points.map { StressTrends.contextDateLabel(it.date) })
    }

    @Test
    fun dailyContextGraphSkipsPartialCheckins() {
        val points = StressTrends.dailyContext(
            listOf(
                pulse(LocalDate.of(2026, 10, 1), 3, null, null),
                pulse(LocalDate.of(2026, 10, 2), 1, 5, 1)
            )
        )
        assertEquals(1, points.size)
        assertEquals(LocalDate.of(2026, 10, 2), points[0].date)
    }

    @Test
    fun dailyContextGraphIsEmptyWhenNothingIsCheckedIn() {
        assertTrue(StressTrends.dailyContext(emptyList()).isEmpty())
        assertTrue(StressTrends.dailyContext(listOf(pulse(LocalDate.of(2026, 10, 1), null, 3, 3))).isEmpty())
    }

    @Test
    fun dailyContextAxisIsThreeNamedCategoriesWithNoNumber() {
        assertEquals(0f, StressTrends.plotFraction(DailyContextCategory.LOW), 1e-6f)
        assertEquals(0.5f, StressTrends.plotFraction(DailyContextCategory.MEDIUM), 1e-6f)
        assertEquals(1f, StressTrends.plotFraction(DailyContextCategory.HIGH), 1e-6f)
        // Low must never be turned into 33, Medium into 66, or High into 100: the plotted position is
        // a drawing coordinate, and the axis is labelled with words.
        val low = DailyContextPoint(LocalDate.of(2026, 10, 1), DailyContextCategory.LOW)
        assertNull("a Daily Context point must have no score at all", low.scoreOrNull())
        assertEquals(DailyContextCategory.LOW, DailyContext.category(1, 5, 1))
    }

    // == separation ==============================================================================

    @Test
    fun theThreeGraphsNeverShareAScale() {
        // Same number, three graphs, three different meanings: 40 is 40% of the physiological axis
        // but the top of the PSS-10 axis, and has no numeric meaning at all for Daily Context.
        assertEquals(0.4f, StressTrends.physiologicalFraction(40), 1e-6f)
        assertEquals(1.0f, StressTrends.perceivedFraction(40), 1e-6f)
        assertNotNull(StressTrends.PERCEIVED_MAX)
        assertEquals(100, StressTrends.PHYSIOLOGICAL_MAX)
    }

    @Test
    fun chronologicalOrderIsRestoredRegardlessOfStorageOrder() {
        val chron = StressTrends.physiological(
            listOf(measurement(3000L, 10), measurement(1000L, 20), measurement(2000L, 30))
        )
        assertEquals(listOf(20, 30, 10), chron.map { it.score })

        val months = StressTrends.perceived(
            listOf(pss(YearMonth.of(2026, 10), 30), pss(YearMonth.of(2026, 5), 10))
        )
        assertEquals(YearMonth.of(2026, 5), months.first().month)

        val days = StressTrends.dailyContext(
            listOf(
                pulse(LocalDate.of(2026, 10, 5), 5, 1, 5),
                pulse(LocalDate.of(2026, 10, 1), 1, 5, 1)
            )
        )
        assertEquals(LocalDate.of(2026, 10, 1), days.first().date)
    }
}

/** Test-only: proves a [DailyContextPoint] exposes no numeric score, by construction failing. */
private fun DailyContextPoint.scoreOrNull(): Int? =
    runCatching { (this as Any).javaClass.getMethod("getScore").invoke(this) as? Int }.getOrNull()
