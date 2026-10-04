package com.bishnu.stresswelfarebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StressEngineTest {

    /** Local test fixtures only. The production app never fabricates health data; these
     *  synthetic days exist purely to exercise the inference math in a JVM unit test. */
    private fun day(hr: Double?, hrv: Double?, resting: Double? = 62.0, sleep: Long = 420, steps: Long = 6000): DailyHealth =
        DailyHealth(date = LocalDate.now(), sleepMinutes = sleep, steps = steps).apply {
            avgHr = hr
            avgHrv = hrv
            restingHr = resting
            if (hrv != null) hrvValues.add(hrv)
        }

    @Test
    fun realDataProducesBoundedScore() {
        val days = listOf(
            day(70.0, 50.0), day(72.0, 48.0), day(71.0, 52.0), day(100.0, 20.0)
        )
        val baseline = StressEngine.baseline(days)
        val model = StressEngine.Model(listOf(3.6839, -4.5766, 1.8077, -0.6930, 0.2932), -0.5960)
        val result = StressEngine.infer(days.last(), baseline, model)
        assertTrue(result.usingRealData)
        assertNotNull(result.score)
        assertTrue(result.score!! in 0..100)
    }

    @Test
    fun missingDataReturnsAwaitingState() {
        val empty = day(null, null)
        val baseline = StressEngine.baseline(listOf(empty))
        val result = StressEngine.infer(empty, baseline, StressEngine.Model(listOf(1.0), 0.0))
        assertFalse(result.usingRealData)
        assertNull(result.score)
    }

    @Test
    fun implausibleValuesAreIgnoredByBaseline() {
        val days = listOf(day(70.0, 50.0), day(0.0, 400.0))
        val baseline = StressEngine.baseline(days)
        assertTrue(baseline.hrv in 1.0..300.0)
    }
}

