package com.bishnu.stresswelfarebridge.commander

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.StressEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the stress → zone mapping.
 *
 * The central guarantee: the zone boundaries are the **existing** [StressEngine] thresholds, not new
 * invented ones, and the mapping agrees with the engine's own "Low"/"Moderate"/"High" label.
 */
class StressZoneMappingTest {

    private fun result(score: Int?, usingRealData: Boolean = true, level: String = "") = StressEngine.Result(
        score = score,
        level = level,
        confidence = 70,
        hrContribution = 50,
        hrvContribution = 50,
        recoveryContribution = 50,
        message = "",
        usingRealData = usingRealData
    )

    @Test
    fun zoneBoundariesMatchTheExistingEngineThresholds() {
        // These are StressEngine's own values — the mapping must not introduce different ones.
        assertEquals(35, StressEngine.LOW_SCORE_MAX)
        assertEquals(65, StressEngine.MODERATE_SCORE_MAX)

        assertEquals(StressZone.GREEN, StressZoneMapping.fromScore(34))
        assertEquals(StressZone.YELLOW, StressZoneMapping.fromScore(35))
        assertEquals(StressZone.YELLOW, StressZoneMapping.fromScore(64))
        assertEquals(StressZone.RED, StressZoneMapping.fromScore(65))
    }

    @Test
    fun extremeScoresAreHandled() {
        assertEquals(StressZone.GREEN, StressZoneMapping.fromScore(0))
        assertEquals(StressZone.RED, StressZoneMapping.fromScore(100))
    }

    @Test
    fun zoneAgreesWithTheEnginesOwnLevelLabelForEveryScore() {
        // Runs the real engine over a range of scores and checks the two never disagree.
        val days = List(5) {
            DailyHealth(date = LocalDate.now()).apply {
                avgHr = 72.0; avgHrv = 50.0; restingHr = 62.0
                heartRates.add(72.0); hrvValues.add(50.0); restingHeartRates.add(62.0)
                steps = 6000; sleepMinutes = 420
            }
        }
        val baseline = StressEngine.baseline(days)
        val model = StressEngine.Model(List(5) { 1.0 }, 0.0)

        var checked = 0
        // Vary the day's HR/HRV to sweep the score range.
        for (hr in 60..120 step 3) {
            val day = DailyHealth(date = LocalDate.now(), steps = 6000, sleepMinutes = 420).apply {
                avgHr = hr.toDouble(); avgHrv = 20.0; restingHr = 62.0
                heartRates.add(hr.toDouble()); hrvValues.add(20.0); restingHeartRates.add(62.0)
            }
            val r = StressEngine.infer(day, baseline, model)
            if (r.score == null || !r.usingRealData) continue
            checked++
            assertNotNull("level '${r.level}' should map to a zone", StressZoneMapping.fromEngineLevel(r.level))
            assertSame("score ${r.score} / level ${r.level}", StressZoneMapping.fromEngineLevel(r.level), StressZoneMapping.fromScore(r.score))
        }
        assertTrue("expected to sweep several scores, checked $checked", checked >= 5)
    }

    @Test
    fun engineLevelsMapToTheRightZones() {
        assertEquals(StressZone.GREEN, StressZoneMapping.fromEngineLevel("Low"))
        assertEquals(StressZone.YELLOW, StressZoneMapping.fromEngineLevel("Moderate"))
        assertEquals(StressZone.RED, StressZoneMapping.fromEngineLevel("High"))
        assertEquals(StressZone.GREEN, StressZoneMapping.fromEngineLevel(" low "))
    }

    @Test
    fun awaitingDataIsNotAZone() {
        assertNull(StressZoneMapping.fromEngineLevel("Awaiting data"))
        assertNull(StressZoneMapping.fromEngineLevel(""))
    }

    @Test
    fun aResultWithoutAScoreIsNotClassified() {
        assertNull(StressZoneMapping.fromResult(result(score = null, usingRealData = false)))
    }

    @Test
    fun aResultNotBasedOnRealDataIsNotClassified() {
        // Even with a score present, if the engine did not use real wearable data we refuse to
        // file the person into a stress zone.
        assertNull(StressZoneMapping.fromResult(result(score = 10, usingRealData = false)))
    }

    @Test
    fun aRealResultIsClassifiedByScore() {
        assertEquals(StressZone.GREEN, StressZoneMapping.fromResult(result(score = 12)))
        assertEquals(StressZone.YELLOW, StressZoneMapping.fromResult(result(score = 50)))
        assertEquals(StressZone.RED, StressZoneMapping.fromResult(result(score = 88)))
    }

    @Test
    fun zonesExposeTheirLabelsForTheDashboard() {
        assertEquals(listOf(StressZone.GREEN, StressZone.YELLOW, StressZone.RED), StressZone.ordered)
        assertEquals("LOW STRESS", StressZone.GREEN.title)
        assertEquals("MEDIUM STRESS", StressZone.YELLOW.title)
        assertEquals("HIGH STRESS", StressZone.RED.title)
    }
}

/**
 * Tests that a personnel record is built from real values only, with camera HRV kept separate from
 * Health Connect HRV.
 */
class PersonnelRecordFactoryTest {

    private val session = com.bishnu.stresswelfarebridge.auth.Session(
        token = "t",
        account = com.bishnu.stresswelfarebridge.auth.AccountSummary(
            id = "acc-1",
            fullName = "Rahul Kumar",
            username = "rahul",
            role = com.bishnu.stresswelfarebridge.auth.Role.PERSONNEL,
            serviceId = "P1024"
        )
    )

    private fun today() = DailyHealth(
        date = LocalDate.now(),
        steps = 7421,
        sleepMinutes = 405,
        exerciseMinutes = 45
    ).apply {
        avgHr = 78.0
        avgHrv = 41.0
        restingHr = 63.0
        spo2 = 97.0
        respiratoryRate = 14.0
    }

    private fun engineResult(score: Int?, usingRealData: Boolean) = StressEngine.Result(
        score = score, level = if (usingRealData) "Moderate" else "Awaiting data",
        confidence = 80, hrContribution = 55, hrvContribution = 48,
        recoveryContribution = 60, message = "test", usingRealData = usingRealData,
        dataQuality = "Baseline built from 3 day(s).", baselineDays = 3
    )

    @Test
    fun theRecordCarriesRealMeasuredValues() {
        val record = PersonnelRecordFactory.from(
            session, today(), engineResult(50, usingRealData = true), cameraHrv = null, nowMs = 123L
        )
        assertEquals("acc-1", record.accountId)
        assertEquals("Rahul Kumar", record.fullName)
        assertEquals("P1024", record.serviceId)
        assertEquals(50, record.stressScore)
        assertEquals(StressZone.YELLOW, record.zone)
        assertEquals(80, record.confidence)
        assertEquals(3, record.baselineDays)
        assertEquals(123L, record.lastUpdatedMs)
        assertEquals(7421L, record.steps)
        assertEquals(78.0, record.avgHr!!, 1e-9)
        assertEquals(41.0, record.avgHrv!!, 1e-9)
        assertEquals(63.0, record.restingHr!!, 1e-9)
        assertEquals(405L, record.sleepMinutes)
        assertEquals(97.0, record.spo2!!, 1e-9)
    }

    @Test
    fun missingMeasurementsStayNullAndAreNeverDefaultedToHealthyValues() {
        val empty = DailyHealth(date = LocalDate.now())
        val record = PersonnelRecordFactory.from(
            session, empty, engineResult(null, usingRealData = false), cameraHrv = null, nowMs = 1L
        )
        assertNull(record.avgHr)
        assertNull(record.avgHrv)
        assertNull(record.restingHr)
        assertNull(record.spo2)
        assertNull(record.stressScore)
        assertNull(record.zone)
        assertEquals(0L, record.steps)
        assertTrue(!record.hasClassifiableStress)
    }

    @Test
    fun cameraHrvIsStoredSeparatelyAndLabelledWithItsOwnSource() {
        val camera = com.bishnu.stresswelfarebridge.ppg.CameraHrvResult(
            rmssdMs = 42.0, sdnnMs = 55.0, meanIbiMs = 800.0, heartRateBpm = 75.0,
            measurementStartMs = 1000L, measurementEndMs = 41000L, durationSeconds = 40.0,
            validIbiCount = 48, rejectedIbiCount = 2, totalIbiCount = 50, validPercent = 96.0,
            signalQuality = com.bishnu.stresswelfarebridge.ppg.SignalQuality.GOOD,
            confidence = 0.82
        )
        val record = PersonnelRecordFactory.from(
            session, today(), engineResult(50, usingRealData = true), camera, nowMs = 5L
        )

        val summary = record.cameraHrv!!
        assertEquals(42.0, summary.rmssdMs, 1e-9)
        assertEquals(75.0, summary.heartRateBpm, 1e-9)
        assertEquals("GOOD", summary.signalQuality)
        assertEquals(48, summary.validBeatCount)
        assertEquals(41000L, summary.measuredAtMs)
        // The source label keeps it distinguishable from Health Connect.
        assertEquals(com.bishnu.stresswelfarebridge.ppg.CameraHrvResult.SOURCE_CAMERA_PPG, summary.source)
        // Camera RMSSD must not overwrite the wearable HRV value.
        assertEquals(41.0, record.avgHrv!!, 1e-9)
    }
}

