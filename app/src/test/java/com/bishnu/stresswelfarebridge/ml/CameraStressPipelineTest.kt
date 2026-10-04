package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.ppg.HrvCalculator
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant

/**
 * The end-to-end contract this feature exists for: a valid camera HRV capture is turned into a real
 * stress score by the deployed model, and that score is persisted and shown consistently.
 *
 * Nothing here stubs the model. These tests load the shipped `stress_model_ml.json` asset and drive
 * the real [StressEstimator], which is exactly what the app does at runtime.
 *
 * NOTE ON THE VALUES BELOW: the numbers used here are *synthetic fixtures* standing in for a camera
 * capture. They are realistic (68 bpm, 46 valid intervals, 0 rejected, high quality) but they are
 * fixtures. They were not measured on a device by this test.
 */
class CameraStressPipelineTest {

    companion object {
        private lateinit var model: StressModel

        @BeforeClass
        @JvmStatic
        fun loadDeployedModel() {
            val json = org.json.JSONObject(
                CameraStressPipelineTest::class.java.classLoader!!
                    .getResource("stress_model_ml.json")!!.readText()
            )
            model = StressModel.fromJson(json)
            StressModel.verifySchema(model)
        }
    }

    private val t0: Instant = Instant.parse("2026-03-01T09:00:00Z")

    /** Distinct account ids, so per-account storage isolation is actually exercised. */
    private var idCounter = 0
    private fun nextId(): String = "acct-" + (++idCounter)

    /** A realistic GOOD-quality capture: 68 bpm, 46 valid NN intervals, none rejected. */
    private fun camera(
        hr: Double = 68.0,
        rmssd: Double = 34.0,
        sdnn: Double = 45.0,
        quality: SignalQuality = SignalQuality.GOOD
    ) = CameraHrvResult(
        rmssdMs = rmssd,
        sdnnMs = sdnn,
        meanIbiMs = 60000.0 / hr,
        heartRateBpm = hr,
        measurementStartMs = t0.toEpochMilli() - 45_000L,
        measurementEndMs = t0.toEpochMilli(),
        durationSeconds = 45.0,
        validIbiCount = 46,
        rejectedIbiCount = 0,
        totalIbiCount = 46,
        validPercent = 100.0,
        signalQuality = quality,
        confidence = 0.94,
        source = CameraHrvResult.SOURCE_CAMERA_PPG,
        sdsdMs = 33.0,
        pnn50Pct = 18.0
    )

    /** A fresh estimator with no personal history - the state a brand-new account is in. */
    private fun coldEstimator() = CameraStressBridge.estimator(
        model, PersonalBaseline { t0 }, ObservationHistory()
    )

    /**
     * An estimator that has already seen [n] qualifying readings, i.e. the state a returning user is
     * in and the only state in which the deployed model has personal context to work from.
     */
    private fun warmEstimator(n: Int = PersonalBaseline.MIN_SAMPLES): StressEstimator {
        val est = coldEstimator()
        repeat(n) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
        }
        return est
    }

    // == the headline assertions ================================================================

    @Test
    fun validCameraHrvProducesANumericStressScore() {
        val outcome = CameraStressBridge.evaluate(warmEstimator(), camera(), null, t0)
        assertNotNull(
            "a GOOD camera capture with an established baseline must produce a score; reason: " +
                outcome.reason,
            outcome.score
        )
        assertTrue("score must be 0..100", outcome.score!! in 0..100)
        assertNotNull(outcome.probability)
        assertEquals(CameraHrvResult.SOURCE_CAMERA_PPG, outcome.source)
    }

    @Test
    fun scoreIsProducedByTheDeployedWeightsNotAConstant() {
        val calm = CameraStressBridge.evaluate(
            warmEstimator(), camera(hr = 58.0, rmssd = 55.0, sdnn = 60.0), null, t0
        )
        val loaded = CameraStressBridge.evaluate(
            warmEstimator(), camera(hr = 95.0, rmssd = 14.0, sdnn = 22.0), null, t0
        )
        assertNotNull(calm.score)
        assertNotNull(loaded.score)
        assertTrue(
            "different physiology must not collapse to one score (${calm.score} vs ${loaded.score})",
            calm.score != loaded.score
        )
        assertTrue(
            "elevated physiology (${loaded.score}) must score above calm (${calm.score})",
            loaded.score!! > calm.score!!
        )
    }

    @Test
    fun zoneIsDerivedFromTheScore() {
        val outcome = CameraStressBridge.evaluate(warmEstimator(), camera(), null, t0)
        assertEquals(StressZoneMapping.fromScore(outcome.score!!), outcome.zone)
    }

    @Test
    fun repeatedEvaluationOfTheSameCaptureIsDeterministic() {
        val a = CameraStressBridge.evaluate(warmEstimator(), camera(), null, t0)
        val b = CameraStressBridge.evaluate(warmEstimator(), camera(), null, t0)
        assertEquals(a.score, b.score)
    }

    // == cold start: never a fabricated "you are fine" ==========================================

    @Test
    fun coldStartWithholdsAScoreInsteadOfClaimingZero() {
        val est = coldEstimator()
        assertEquals(PersonalBaseline.MIN_SAMPLES, est.readingsUntilBaselineReady())

        val outcome = CameraStressBridge.evaluate(est, camera(), null, t0)
        assertNull(
            "with no personal baseline the model's output is not a stress assessment and must " +
                "not be shown as 0/100",
            outcome.score
        )
        assertTrue(outcome.reason.contains("baseline", ignoreCase = true))
        assertTrue(
            "the reason must tell the user how many readings remain",
            outcome.readingsUntilBaselineReady > 0
        )
    }

    @Test
    fun withheldColdStartReadingsStillBuildTheBaseline() {
        val est = coldEstimator()
        repeat(PersonalBaseline.MIN_SAMPLES) {
            val outcome = CameraStressBridge.evaluate(est, camera(), null, t0)
            assertNull("still warming up", outcome.score)
        }
        assertEquals(0, est.readingsUntilBaselineReady())

        // and the very next reading produces a real score
        val outcome = CameraStressBridge.evaluate(est, camera(), null, t0)
        assertNotNull("baseline is established, a score must now be issued", outcome.score)
    }

    @Test
    fun poorQualityAbstainsRatherThanReturningAFakeScore() {
        val outcome = CameraStressBridge.evaluate(
            warmEstimator(), camera(quality = SignalQuality.POOR), null, t0
        )
        assertNull("POOR signal must not yield a score", outcome.score)
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun poorQualityDuringColdStartReportsTheQualityProblemNotWarmUp() {
        // A bad reading must never be counted as baseline training, and the user must be told the
        // real reason (the signal) rather than being told to come back when history is longer.
        val est = coldEstimator()
        val outcome = CameraStressBridge.evaluate(est, camera(quality = SignalQuality.POOR), null, t0)
        assertNull(outcome.score)
        assertTrue(
            "expected a signal-quality reason, got: ${outcome.reason}",
            outcome.reason.contains("quality", ignoreCase = true) ||
                outcome.reason.contains("artefact", ignoreCase = true) ||
                outcome.reason.contains("Retake", ignoreCase = true)
        )
        assertEquals(
            "a POOR reading must not advance the baseline",
            PersonalBaseline.MIN_SAMPLES,
            est.readingsUntilBaselineReady()
        )
    }

    // == the model really receives the camera features ============================================

    @Test
    fun cameraHeartAndHrvFeaturesReachTheModelInput() {
        val o = CameraStressBridge.toObservation(camera(), null, t0)
        assertEquals(68.0, o.hrBpm!!, 1e-9)
        assertEquals(34.0, o.rmssdMs!!, 1e-9)
        assertEquals(45.0, o.sdnnMs!!, 1e-9)
        assertEquals(33.0, o.sdsdMs!!, 1e-9)
        assertEquals(18.0, o.pnn50Pct!!, 1e-9)
        assertEquals(CameraHrvResult.SOURCE_CAMERA_PPG, o.source)
    }

    @Test
    fun cameraQualityAndArtifactFeaturesReachTheModelInput() {
        val o = CameraStressBridge.toObservation(camera(), null, t0)
        assertEquals(1.0, o.validNnRatio!!, 1e-9)
        assertEquals(0.0, o.artifactRatio!!, 1e-9)
        assertEquals(0.94, o.ppgQuality!!, 1e-9)
    }

    @Test
    fun rejectedIntervalsBecomeTheArtifactRatio() {
        val partial = camera().copy(
            validIbiCount = 38, rejectedIbiCount = 8, totalIbiCount = 46, validPercent = 82.6
        )
        val o = CameraStressBridge.toObservation(partial, null, t0)
        assertEquals(8.0 / 46.0, o.artifactRatio!!, 1e-9)
    }

    @Test
    fun unavailableContextStaysMissingRatherThanBeingInvented() {
        val o = CameraStressBridge.toObservation(camera(), health = null, measuredAt = t0)
        assertNull("no Health Connect respiration means no value, not zero", o.respRateBpm)
        assertNull("no Health Connect activity means no value, not zero", o.activityRecentNorm)
    }

    // == persistence =============================================================================

    private fun session(store: InMemoryKeyValueStore, name: String, user: String, sid: String): Session {
        val accounts = AccountStore(store, store, { t0.toEpochMilli() }, ::nextId)
        val r = accounts.register(
            RegistrationRequest(
                fullName = name, username = user, password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.PERSONNEL, serviceId = sid
            )
        )
        assertTrue("registration failed: $r", r is AuthResult.Success)
        return (r as AuthResult.Success).session
    }

    @Test
    fun stressMeasurementIsPersistedWithSourceCameraPpg() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val outcome = CameraStressBridge.evaluate(warmEstimator(), camera(), null, t0)
        assertNotNull(outcome.score)

        val ms = StressMeasurementStore(store)
        assertTrue(
            ms.save(
                s,
                StressMeasurementRecord(
                    id = "cam-1",
                    userId = s.accountId,
                    measuredAtMs = t0.toEpochMilli(),
                    source = CameraHrvResult.SOURCE_CAMERA_PPG,
                    heartRateBpm = 68.0,
                    rmssdMs = 34.0,
                    sdnnMs = 45.0,
                    signalQuality = "GOOD",
                    stressScore = outcome.score,
                    stressZone = outcome.zone.name,
                    confidence = outcome.confidence,
                    modelVersion = model.modelVersion,
                    featureSchemaVersion = FeatureSchema.FEATURE_SCHEMA_VERSION
                )
            )
        )

        assertEquals(1, ms.history(s).size)
        val latest = ms.latest(s)!!
        assertEquals(CameraHrvResult.SOURCE_CAMERA_PPG, latest.source)
        assertNotNull(latest.stressScore)
        assertEquals(outcome.score, latest.stressScore)
        assertEquals(68.0, latest.heartRateBpm!!, 1e-9)
        assertEquals(34.0, latest.rmssdMs!!, 1e-9)
        assertEquals(45.0, latest.sdnnMs!!, 1e-9)
        assertEquals(model.modelVersion, latest.modelVersion)
    }

    @Test
    fun persistedHistorySurvivesRestartAndStaysPerAccount() {
        val store = InMemoryKeyValueStore()
        val a = session(store, "Alice Smith", "alice", "SVC-1")
        val b = session(store, "Bob Jones", "bob", "SVC-2")

        val ms = StressMeasurementStore(store)
        val outcome = CameraStressBridge.evaluate(warmEstimator(), camera(), null, t0)
        ms.save(
            a,
            StressMeasurementRecord(
                "c1", a.accountId, t0.toEpochMilli(), CameraHrvResult.SOURCE_CAMERA_PPG,
                68.0, 34.0, 45.0, "GOOD", outcome.score, outcome.zone.name, 80,
                model.modelVersion, FeatureSchema.FEATURE_SCHEMA_VERSION
            )
        )

        val reopened = StressMeasurementStore(store)
        assertEquals(1, reopened.history(a).size)
        assertEquals(outcome.score, reopened.latest(a)!!.stressScore)
        assertTrue("another account must not see it", reopened.history(b).isEmpty())
    }

    @Test
    fun commanderNeverReadsOrWritesIndividualMeasurements() {
        val store = InMemoryKeyValueStore()
        val personnel = session(store, "Pat soldier", "pat", "SVC-9")
        val ms = StressMeasurementStore(store)
        ms.save(
            personnel,
            StressMeasurementRecord(
                "c1", personnel.accountId, t0.toEpochMilli(), CameraHrvResult.SOURCE_CAMERA_PPG,
                68.0, 34.0, 45.0, "GOOD", 40, "MODERATE", 80,
                model.modelVersion, FeatureSchema.FEATURE_SCHEMA_VERSION
            )
        )

        val accounts = AccountStore(store, store, { t0.toEpochMilli() }, ::nextId)
        val cmd = accounts.register(
            RegistrationRequest(
                fullName = "Cara Officer", username = "cara", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.COMMANDER, serviceId = "SVC-C"
            )
        )
        assertTrue(cmd is AuthResult.Success)
        val commander = (cmd as AuthResult.Success).session
        assertTrue(commander.role.isCommander)
        assertTrue(ms.history(commander).isEmpty())
        assertNull(ms.latest(commander))
        assertFalse(ms.save(commander, ms.history(personnel).first()))
    }

    // == HRV calculator supplies the model's HRV features ==========================================

    @Test
    fun hrvCalculatorProducesSdsdAndPnn50FromTheNnSeries() {
        val nn = (0 until 60).map { if (it % 2 == 0) 800.0 else 840.0 }
        val m = HrvCalculator.compute(nn)!!
        assertEquals(40.0, m.rmssdMs, 1e-6)
        assertNotNull("sdsd is an ML feature", m.sdsdMs)
        assertNotNull("pNN50 is an ML feature", m.pnn50Pct)
        assertEquals(0.0, m.pnn50Pct!!, 1e-6)
    }

    @Test
    fun pnn50CountsDifferencesOverFiftyMilliseconds() {
        val nn = MutableList(61) { 800.0 }
        nn[30] = 1000.0
        val m = HrvCalculator.compute(nn)!!
        assertTrue("a 200 ms jump must register in pNN50", m.pnn50Pct!! > 0.0)
    }
}
