package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unit tests for the feature pipeline: schema, baseline, temporal context, missing data,
 * abstention, zoning, explanation and versioning.
 *
 * The observations here are synthetic on purpose - they exercise the mapping rules, and no test
 * asserts anything physiological about a real person.
 */
class StressEstimatorTest {

    private val t0: Instant = Instant.parse("2026-01-15T08:00:00Z")

    private fun observation(
        at: Instant = t0,
        hr: Double? = 70.0,
        rmssd: Double? = 40.0,
        sdnn: Double? = 50.0,
        meanNn: Double? = 857.0,
        resp: Double? = 15.0,
        quality: Double? = 0.8,
        validRatio: Double? = 0.9,
        activity: Double? = 0.1
    ) = StressObservation(
        measuredAt = at,
        source = "TEST",
        hrBpm = hr,
        rmssdMs = rmssd,
        sdnnMs = sdnn,
        meanNnMs = meanNn,
        sdsdMs = 30.0,
        pnn50Pct = 20.0,
        respRateBpm = resp,
        activityRecentNorm = activity,
        ppgQuality = quality,
        confidence = quality,
        validNnRatio = validRatio,
        artifactRatio = 1.0 - (validRatio ?: 1.0),
        durationSeconds = 60.0
    )

    /** A deterministic, clearly separating model: higher HR and lower RMSSD raise the score. */
    private fun testModel(bias: Double = 0.0): StressModel {
        val n = FeatureSchema.NAMES.size
        val weights = DoubleArray(2 * n)
        weights[FeatureSchema.indexOf("hr_bpm")] = 0.10
        weights[FeatureSchema.indexOf("rmssd_ms")] = -0.05
        return StressModel.fromJson(
            JSONObject(
                """
                {"model_version":"test","features":${jsonArray(FeatureSchema.NAMES)},
                 "weights":${jsonArrayD(weights)},"bias":$bias,
                 "impute":${jsonArrayD(DoubleArray(n) { 50.0 })},
                 "calibration":{"a":1.0,"b":0.0}}
                """.trimIndent()
            )
        )
    }

    private fun jsonArray(items: List<String>) = items.joinToString(",", "[", "]") { "\"$it\"" }

    private fun jsonArrayD(values: DoubleArray) = values.joinToString(",", "[", "]") { it.toString() }

    private fun estimator(bias: Double = 0.0) =
        StressEstimator(testModel(bias), PersonalBaseline { t0 }, ObservationHistory())

    // -- schema -----------------------------------------------------------------------------

    @Test
    fun featureOrderIsUniqueAndNonEmpty() {
        assertTrue(FeatureSchema.NAMES.isNotEmpty())
        assertEquals(FeatureSchema.NAMES.size, FeatureSchema.NAMES.toSet().size)
    }

    /**
     * The features that are deliberately *not* standardised against a personal baseline.
     *
     * SDSD and pNN50 are omitted on purpose: both are near-zero for most people (a healthy resting
     * pNN50 is often 0-10%), so dividing by their MAD would turn ordinary beat-to-beat noise into a
     * multi-sigma "deviation". Their raw values still reach the model.
     */
    private val notStandardised = setOf("sdsd_ms", "pnn50_pct")

    @Test
    fun everyBaselineableFeatureHasAZScoreCompanion() {
        for (name in FeatureSchema.namesIn(FeatureSchema.Group.ACUTE) +
            FeatureSchema.namesIn(FeatureSchema.Group.RESPIRATION)) {
            if (name in notStandardised) continue
            val zName = name.substringBeforeLast('_') + "_z"
            assertTrue("missing z-score companion for $name", zName in FeatureSchema.NAMES)
        }
    }

    @Test
    fun deliberatelyUnstandardisedFeaturesArePresentButHaveNoZScore() {
        for (name in notStandardised) {
            assertTrue("$name should still be a model input", name in FeatureSchema.NAMES)
            val zName = name.substringBeforeLast('_') + "_z"
            assertFalse("$name should not have a z-score", zName in FeatureSchema.NAMES)
        }
    }

    @Test
    fun modelSchemaMatchesFeatureSchema() {
        StressModel.verifySchema(testModel())
    }

    // -- baseline ---------------------------------------------------------------------------

    @Test
    fun baselineIsNotEstablishedFromASingleMeasurement() {
        val b = PersonalBaseline { t0 }
        b.observe("hr_bpm", 70.0, t0)
        assertFalse(b.isEstablished("hr_bpm"))
        assertNull("z-score must stay missing until enough samples exist", b.zScore("hr_bpm", 70.0))
    }

    @Test
    fun baselineIsEstablishedAfterEnoughMeasurements() {
        val b = PersonalBaseline { t0 }
        for (i in 0 until PersonalBaseline.MIN_SAMPLES) {
            b.observe("hr_bpm", 60.0 + i, t0.plus(i.toLong(), ChronoUnit.HOURS))
        }
        assertTrue(b.isEstablished("hr_bpm"))
        assertNotNull(b.zScore("hr_bpm", 60.0))
    }

    @Test
    fun implausibleValuesAreRefusedByTheBaseline() {
        val b = PersonalBaseline { t0 }
        assertFalse(b.observe("hr_bpm", 0.0, t0))
        assertFalse(b.observe("hr_bpm", 900.0, t0))
        assertFalse(b.observe("rmssd_ms", -5.0, t0))
        assertEquals(0, b.sampleCount("hr_bpm"))
    }

    @Test
    fun baselineResistsContaminationByOutliers() {
        val b = PersonalBaseline { t0 }
        for (i in 0 until 20) b.observe("hr_bpm", 65.0 + (i % 3), t0.plus(i.toLong(), ChronoUnit.HOURS))
        val before = b.center("hr_bpm")!!
        for (i in 0 until 5) b.observe("hr_bpm", 190.0, t0.plus((30 + i).toLong(), ChronoUnit.HOURS))
        val after = b.center("hr_bpm")!!
        assertTrue("median shifted from $before to $after", after - before < 12.0)
    }

    @Test
    fun baselinesAreIndependentPerInstance() {
        val a = PersonalBaseline { t0 }
        val other = PersonalBaseline { t0 }
        for (i in 0 until 10) a.observe("hr_bpm", 60.0, t0.plus(i.toLong(), ChronoUnit.HOURS))
        assertEquals(0, other.sampleCount("hr_bpm"))
    }

    // -- missing data ------------------------------------------------------------------------

    @Test
    fun missingFeatureIsNaNAndNotZero() {
        val v = FeatureExtractor(PersonalBaseline { t0 }, ObservationHistory())
            .extract(observation(resp = null))
        assertTrue(
            "missing respiration must be NaN, not 0.0",
            v[FeatureSchema.indexOf("resp_rate_bpm")].isNaN()
        )
    }

    @Test
    fun missingFeatureIsImputedFromTrainingMedianAndFlagged() {
        val model = testModel()
        val v = FeatureVector(FeatureSchema.NAMES, DoubleArray(FeatureSchema.NAMES.size) { Double.NaN })
        val x = model.designMatrix(v)
        val n = FeatureSchema.NAMES.size
        assertEquals(2 * n, x.size)
        // Everything imputed to the training median, and every missing flag raised.
        assertTrue(x.take(n).all { it == 50.0 })
        assertTrue(x.drop(n).all { it == 1.0 })
    }

    @Test
    fun presentFeaturesAreNeitherImputedNorFlagged() {
        val model = testModel()
        val v = FeatureVector(FeatureSchema.NAMES, DoubleArray(FeatureSchema.NAMES.size) { 1.0 })
        val x = model.designMatrix(v)
        assertTrue(x.take(FeatureSchema.NAMES.size).all { it == 1.0 })
        assertTrue(x.drop(FeatureSchema.NAMES.size).all { it == 0.0 })
    }

    @Test
    fun everyPartialFeatureScenarioProducesAWellFormedVector() {
        // The Phase 8 scenarios: heart only, heart+HRV, no respiration, no activity. Any subset must
        // yield a full-length vector whose absent entries are missing rather than zero.
        val scenarios = mapOf(
            "hr only" to observation(hr = 70.0, rmssd = null, sdnn = null, meanNn = null, resp = null),
            "hr+hrv" to observation(),
            "no respiration" to observation(resp = null),
            "no activity" to observation(activity = null)
        )
        for ((label, o) in scenarios) {
            val v = FeatureExtractor(PersonalBaseline { t0 }, ObservationHistory()).extract(o)
            assertEquals("$label produced wrong vector length", FeatureSchema.NAMES.size, v.size)
            assertTrue("$label produced a non-finite value",
                v.values.all { it.isNaN() || it.isFinite() })
        }
    }

    // -- temporal ----------------------------------------------------------------------------

    @Test
    fun temporalDeltaIsMissingWithoutHistory() {
        val v = FeatureExtractor(PersonalBaseline { t0 }, ObservationHistory()).extract(observation())
        assertTrue(v[FeatureSchema.indexOf("hr_delta_recent")].isNaN())
        assertTrue(v[FeatureSchema.indexOf("hr_slope_recent")].isNaN())
    }

    @Test
    fun temporalDeltaUsesOnlyThePrecedingMeasurement() {
        val e = FeatureExtractor(PersonalBaseline { t0 }, ObservationHistory())
        e.commit(observation(at = t0, hr = 60.0))
        val v = e.extract(observation(at = t0.plus(1, ChronoUnit.HOURS), hr = 75.0))
        assertEquals(15.0, v[FeatureSchema.indexOf("hr_delta_recent")], 1e-9)
    }

    @Test
    fun slopeNeedsThreePoints() {
        val e = FeatureExtractor(PersonalBaseline { t0 }, ObservationHistory())
        e.commit(observation(at = t0, hr = 60.0))
        e.commit(observation(at = t0.plus(1, ChronoUnit.HOURS), hr = 62.0))
        // Two prior points define a line but not a trend.
        val v = e.extract(observation(at = t0.plus(2, ChronoUnit.HOURS), hr = 64.0))
        assertTrue(v[FeatureSchema.indexOf("hr_slope_recent")].isNaN())
        e.commit(observation(at = t0.plus(2, ChronoUnit.HOURS), hr = 64.0))
        val v2 = e.extract(observation(at = t0.plus(3, ChronoUnit.HOURS), hr = 66.0))
        assertTrue(v2[FeatureSchema.indexOf("hr_slope_recent")].isFinite())
    }

    @Test
    fun historyIgnoresMeasurementsAfterTheCurrentOne() {
        val h = ObservationHistory()
        h.add(observation(at = t0.plus(5, ChronoUnit.HOURS), hr = 120.0))
        // Evaluating an earlier observation must not see the later one.
        assertNull(h.latestHrBefore(t0))
    }

    // -- abstention --------------------------------------------------------------------------

    @Test
    fun poorSignalQualityAbstainsWithNoScore() {
        val r = estimator().evaluate(observation(), SignalQuality.POOR)
        assertNull("POOR quality must never produce a score", r.score)
        assertTrue(r.reason.isNotBlank())
    }

    @Test
    fun noHeartDataAbstains() {
        assertNull(estimator().evaluate(observation(hr = null, rmssd = null), SignalQuality.GOOD).score)
    }

    @Test
    fun heavyArtifactAbstains() {
        assertNull(estimator().evaluate(observation(validRatio = 0.2), SignalQuality.FAIR).score)
    }

    @Test
    fun goodMeasurementProducesBoundedScore() {
        val r = estimator().evaluate(observation(), SignalQuality.GOOD)
        assertNotNull(r.score)
        assertTrue(r.score!! in 0..100)
        assertNotNull(r.probability)
        assertNotNull(r.rawLogit)
    }

    @Test
    fun rawLogitProbabilityAndScoreRemainDistinct() {
        val r = estimator().evaluate(observation(), SignalQuality.GOOD)
        assertTrue(r.probability!! in 0.0..1.0)
        // The display score is the calibrated probability rescaled, never the raw logit.
        assertEquals((r.probability!! * 100).toInt().coerceIn(0, 100), r.score)
        assertTrue(r.rawLogit != r.probability)
    }

    @Test
    fun abstainedMeasurementIsNotCommittedToTheBaseline() {
        val baseline = PersonalBaseline { t0 }
        val est = StressEstimator(testModel(), baseline, ObservationHistory())
        est.evaluate(observation(hr = 120.0, quality = 0.1, validRatio = 0.1), SignalQuality.POOR)
        assertEquals("a refused measurement must not become the person's normal",
            0, baseline.sampleCount("hr_bpm"))
    }

    // -- zones -------------------------------------------------------------------------------

    @Test
    fun zoneBoundariesMatchTheExistingEngineCutPoints() {
        assertEquals(StressZone.LOW, StressZoneMapping.fromScore(0))
        assertEquals(StressZone.LOW, StressZoneMapping.fromScore(34))
        assertEquals(StressZone.MEDIUM, StressZoneMapping.fromScore(35))
        assertEquals(StressZone.MEDIUM, StressZoneMapping.fromScore(64))
        assertEquals(StressZone.HIGH, StressZoneMapping.fromScore(65))
        assertEquals(StressZone.HIGH, StressZoneMapping.fromScore(100))
    }

    // -- explanation and versioning ----------------------------------------------------------

    @Test
    fun contributionsCoverEveryFeatureAndReportMissingAsMissing() {
        val r = estimator().evaluate(observation(resp = null), SignalQuality.GOOD)
        assertEquals(FeatureSchema.NAMES.size, r.contributions.size)
        assertEquals("missing", r.contributions.first { it.name == "resp_rate_bpm" }.display())
    }

    @Test
    fun versioningIsRecordedOnEveryPrediction() {
        val r = estimator().evaluate(observation(), SignalQuality.GOOD)
        assertEquals(FeatureSchema.MODEL_VERSION, r.modelVersion)
        assertEquals(FeatureSchema.FEATURE_SCHEMA_VERSION, r.featureSchemaVersion)
        assertEquals(FeatureSchema.BASELINE_VERSION, r.baselineVersion)
        assertEquals(FeatureSchema.CALIBRATION_VERSION, r.calibrationVersion)
    }

    // -- activity is context, not stress (Phase 15) -------------------------------------------

    @Test
    fun activityIsAModelInputRatherThanAPreFilter() {
        // Identical physiology, very different activity context. Both must be scored: activity is
        // context the model discounts, not a reason to refuse a high heart rate.
        val resting = estimator().evaluate(observation(hr = 120.0, activity = 0.05), SignalQuality.GOOD)
        val afterRun = estimator().evaluate(observation(hr = 120.0, activity = 0.95), SignalQuality.GOOD)
        assertNotNull("high HR after exercise must still be scored", resting.score)
        assertNotNull(afterRun.score)
        assertEquals(0.05, resting.contributions.first { it.name == "activity_recent_norm" }.value, 1e-9)
        assertEquals(0.95, afterRun.contributions.first { it.name == "activity_recent_norm" }.value, 1e-9)
    }
}