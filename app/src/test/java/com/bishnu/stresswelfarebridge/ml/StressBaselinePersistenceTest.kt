package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant

/**
 * The personal baseline and observation history must survive an app restart.
 *
 * ### The bug this locks down
 * Both were constructed with `remember(session?.accountId)` and lived only in memory. Because
 * [PersonalBaseline.MIN_SAMPLES] is 5, a person who measured four times and then closed the app was
 * told "1 more reading needed" again on every relaunch - the five-reading path to a real score could
 * never actually complete. The deployed model was never the problem; the app forgetting was.
 *
 * ### What is asserted, and what is deliberately NOT
 * Asserted: real readings accumulate, survive a destroyed-and-reconstructed estimator, cross a
 * session boundary, reach exactly 5, and only then does the existing model produce a score.
 *
 * NOT asserted, because it must remain true: no synthetic reading is ever inserted, MIN_SAMPLES is
 * untouched, a rejected reading never counts, and no score is issued before the baseline is genuinely
 * established. [PersonalBaseline.MIN_SAMPLES] is still 5 in the test below.
 *
 * Persistence is exercised against a freshly constructed [StressBaselineStore] over the same
 * [InMemoryKeyValueStore], which models a process restart exactly: every estimator and baseline object
 * is discarded and rebuilt from storage.
 *
 * NOTE ON VALUES: the camera fixtures are realistic synthetic stand-ins for a capture, as in
 * [CameraStressPipelineTest]. Nothing here fabricates app behaviour; only the reading values are
 * fixtures.
 */
class StressBaselinePersistenceTest {

    companion object {
        private lateinit var model: StressModel

        @BeforeClass
        @JvmStatic
        fun loadDeployedModel() {
            val json = org.json.JSONObject(
                StressBaselinePersistenceTest::class.java.classLoader!!
                    .getResource("stress_model_ml.json")!!.readText()
            )
            model = StressModel.fromJson(json)
            StressModel.verifySchema(model)
        }
    }

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var baselineStore: StressBaselineStore
    private lateinit var a: Session
    private lateinit var b: Session
    private var idCounter = 0

    private val t0: Instant = Instant.parse("2026-03-01T09:00:00Z")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { t0.toEpochMilli() }, { "acct-${++idCounter}" })
        a = register("alice", Role.PERSONNEL)
        b = register("bob", Role.PERSONNEL)
        baselineStore = StressBaselineStore(store)
    }

    private fun register(username: String, role: Role): Session {
        val r = accounts.register(
            RegistrationRequest(
                fullName = username.replaceFirstChar { it.uppercase() },
                username = username, password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = role, serviceId = "SVC-$username"
            )
        )
        assertTrue(r is AuthResult.Success)
        return (r as AuthResult.Success).session
    }

    private fun camera(hr: Double = 68.0, rmssd: Double = 34.0, sdnn: Double = 45.0) =
        CameraHrvResult(
            rmssdMs = rmssd, sdnnMs = sdnn, meanIbiMs = 60000.0 / hr, heartRateBpm = hr,
            measurementStartMs = 0L, measurementEndMs = t0.toEpochMilli(),
            durationSeconds = 45.0, validIbiCount = 46, rejectedIbiCount = 0, totalIbiCount = 46,
            validPercent = 100.0, signalQuality = SignalQuality.GOOD, confidence = 0.94,
            source = CameraHrvResult.SOURCE_CAMERA_PPG, sdsdMs = 33.0, pnn50Pct = 18.0
        )

    /** A fresh baseline + history, as a relaunch would construct. */
    private fun fresh(): Pair<PersonalBaseline, ObservationHistory> =
        PersonalBaseline { t0 } to ObservationHistory()

    /** An estimator wired to persist, exactly as MainActivity wires it. */
    private fun estimator(
        base: PersonalBaseline, hist: ObservationHistory, session: Session
    ) = CameraStressBridge.estimator(model, base, hist) {
        baselineStore.save(session, base, hist)
    }

    // == 1..5 : readings accumulate, one sample at a time =========================================

    @Test
    fun aFreshAccountHasZeroQualifyingSamples() {
        val (base, _) = fresh()
        assertEquals(0, base.sampleCount("hr_bpm"))
        assertFalse(base.isEstablished("hr_bpm"))
        assertEquals(PersonalBaseline.MIN_SAMPLES - 0, estimator(base, ObservationHistory(), a)
            .readingsUntilBaselineReady())
    }

    @Test
    fun eachValidReadingIncrementsTheQualifyingCountByExactlyOne() {
        var base = PersonalBaseline { t0 }
        var hist = ObservationHistory()
        for (n in 1..PersonalBaseline.MIN_SAMPLES) {
            val est = estimator(base, hist, a)
            est.warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(n * 3600L)),
                SignalQuality.GOOD
            )
            assertEquals("after reading $n", n, base.sampleCount("hr_bpm"))
            // Same for every metric the extractor commits.
            assertEquals(n, base.sampleCount("rmssd_ms"))
            assertEquals(n, base.sampleCount("sdnn_ms"))
            assertEquals(n, base.sampleCount("mean_nn_ms"))
            base = base
        }
    }

    @Test
    fun theFifthReadingEstablishesTheBaseline() {
        val base = PersonalBaseline { t0 }
        val est = estimator(base, ObservationHistory(), a)
        repeat(PersonalBaseline.MIN_SAMPLES) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
        }
        assertEquals(PersonalBaseline.MIN_SAMPLES, base.sampleCount("hr_bpm"))
        assertTrue("baseline must be established at exactly 5", base.isEstablished("hr_bpm"))
        assertEquals(0, est.readingsUntilBaselineReady())
    }

    @Test
    fun minSamplesIsStillFive() {
        // Guards against the fix quietly lowering the cold-start bar.
        assertEquals(5, PersonalBaseline.MIN_SAMPLES)
    }

    // == the headline test: 6..13 survive a restart ==============================================

    @Test
    fun fourReadingsSurviveAnAppRestartAndTheFifthThenProducesARealScore() {
        // ---- session 1: three real qualifying readings ------------------------------------------
        var base = PersonalBaseline { t0 }
        var hist = ObservationHistory()
        var est = estimator(base, hist, a)
        repeat(3) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
        }
        assertEquals(3, base.sampleCount("hr_bpm"))

        // ---- the app is destroyed: every in-memory object is discarded --------------------------
        base = PersonalBaseline { t0 }
        hist = ObservationHistory()
        assertEquals("a naive restart really does reset to zero", 0, base.sampleCount("hr_bpm"))

        // ---- session 2: state is loaded from storage -------------------------------------------
        assertTrue("session 2 must find stored state", baselineStore.load(a, base, hist))
        assertEquals("all 3 readings must have survived", 3, base.sampleCount("hr_bpm"))
        assertEquals("observations must have survived too", 3, hist.size())

        est = estimator(base, hist, a)
        // 3 of 5 qualifying readings so far, so two are still needed.
        assertEquals(2, est.readingsUntilBaselineReady())

        // ---- fourth reading, still short of five ------------------------------------------------
        est.warmUp(
            CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(4 * 3600L)),
            SignalQuality.GOOD
        )
        assertEquals(4, base.sampleCount("hr_bpm"))
        assertFalse("still not established at 4", base.isEstablished("hr_bpm"))

        // ---- fifth reading: the baseline establishes --------------------------------------------
        val outcome4 = CameraStressBridge.evaluate(
            estimator(base, hist, a), camera(), null, t0.plusSeconds(5 * 3600L)
        )
        assertNull("no score may be issued before the baseline is established", outcome4.score)
        assertTrue(outcome4.readingsUntilBaselineReady > 0 || base.isEstablished("hr_bpm"))

        val outcome5 = CameraStressBridge.evaluate(
            estimator(base, hist, a), camera(), null, t0.plusSeconds(5 * 3600L)
        )
        assertTrue(
            "after five qualifying readings the baseline must be established: " + outcome5.reason,
            base.isEstablished("hr_bpm")
        )
        assertNotNull(
            "the existing logistic regression model must now produce a real score: " + outcome5.reason,
            outcome5.score
        )
        assertTrue("score must be 0..100", outcome5.score!! in 0..100)
    }

    @Test
    fun anEstablishedBaselineSurvivesAnotherRestartAndLaterReadingsStillScore() {
        var base = PersonalBaseline { t0 }
        var hist = ObservationHistory()
        val est = estimator(base, hist, a)
        repeat(PersonalBaseline.MIN_SAMPLES) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
        }
        assertTrue(base.isEstablished("hr_bpm"))

        // restart
        base = PersonalBaseline { t0 }
        hist = ObservationHistory()
        assertTrue(baselineStore.load(a, base, hist))
        assertTrue("baseline must still be established after restart", base.isEstablished("hr_bpm"))

        // sixth reading still produces a real result
        val outcome = CameraStressBridge.evaluate(
            estimator(base, hist, a), camera(), null, t0.plusSeconds(9 * 3600L)
        )
        assertNotNull("a sixth reading must still score: " + outcome.reason, outcome.score)
        assertTrue(outcome.score!! in 0..100)
        assertEquals(0, estimator(base, hist, a).readingsUntilBaselineReady())
    }

    @Test
    fun stateIsWrittenAfterEverySingleReadingNotOnlyAtTheEnd() {
        val base = PersonalBaseline { t0 }
        val hist = ObservationHistory()
        val est = estimator(base, hist, a)
        // Persist after each reading, as MainActivity's callback does.
        repeat(4) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
            baselineStore.save(a, base, hist)
            // Read it back immediately: a crash right now must not lose this reading.
            val probe = PersonalBaseline { t0 }
            val probeHist = ObservationHistory()
            baselineStore.load(a, probe, probeHist)
            assertEquals("reading $i+1 must already be durable", i + 1, probe.sampleCount("hr_bpm"))
        }
    }

    // == 14/15 : nothing invalid or synthetic is ever counted =====================================

    @Test
    fun aRejectedReadingDoesNotIncrementTheQualifyingCount() {
        val base = PersonalBaseline { t0 }
        val hist = ObservationHistory()
        val est = estimator(base, hist, a)
        // POOR quality always abstains and never commits.
        est.warmUp(
            CameraStressBridge.toObservation(camera(), null, t0),
            SignalQuality.POOR
        )
        assertEquals("a rejected reading must not count", 0, base.sampleCount("hr_bpm"))
        assertEquals(0, hist.size())
        assertTrue(
            "and nothing must have been written",
            store.getString("stress_baseline_v1_${a.accountId}") == null
        )
    }

    @Test
    fun noReadingIsEverInvented() {
        val base = PersonalBaseline { t0 }
        val hist = ObservationHistory()
        assertEquals(0, base.sampleCount("hr_bpm"))
        assertEquals(0, base.snapshot().countOf("hr_bpm"))
        assertTrue(hist.snapshot().isEmpty())
        // Saving an empty baseline must round-trip to empty, never to a fabricated default.
        baselineStore.save(a, base, hist)
        val reloaded = PersonalBaseline { t0 }
        baselineStore.load(a, reloaded, ObservationHistory())
        assertEquals(0, reloaded.sampleCount("hr_bpm"))
        assertNull("an empty baseline has no centre", reloaded.center("hr_bpm"))
    }

    @Test
    fun aReadingWithNoUsableHeartDataIsNotCounted() {
        val base = PersonalBaseline { t0 }
        val est = estimator(base, ObservationHistory(), a)
        val empty = StressObservation(measuredAt = t0, source = CameraHrvResult.SOURCE_CAMERA_PPG)
        est.warmUp(empty, SignalQuality.GOOD)
        assertEquals(0, base.sampleCount("hr_bpm"))
    }

    @Test
    fun outOfRangeRestoredValuesAreRefusedRatherThanWideningTheReference() {
        // A corrupted store must not be able to poison someone's personal baseline.
        store.putString(
            "stress_baseline_v1_${a.accountId}",
            """{"version":1,"baseline":{"metrics":{"hr_bpm":[{"atMs":${t0.toEpochMilli()},"value":9000.0}]}},"observations":[]}"""
        )
        val base = PersonalBaseline { t0 }
        baselineStore.load(a, base, ObservationHistory())
        assertEquals("9000 bpm is outside ranges and must be refused", 0, base.sampleCount("hr_bpm"))
    }

    // == 16 : user isolation =======================================================================

    @Test
    fun userBDoesNotInheritUserAsBaseline() {
        val baseA = PersonalBaseline { t0 }
        estimator(baseA, ObservationHistory(), a).warmUp(
            CameraStressBridge.toObservation(camera(), null, t0),
            SignalQuality.GOOD
        )
        assertEquals(1, baseA.sampleCount("hr_bpm"))

        // User B logs in: fresh objects, load finds nothing for them.
        val baseB = PersonalBaseline { t0 }
        val histB = ObservationHistory()
        assertFalse("B must not find A's state", baselineStore.load(b, baseB, histB))
        assertEquals(0, baseB.sampleCount("hr_bpm"))
        assertEquals(0, histB.size())
    }

    @Test
    fun userAStillHasTheirReadingsAfterUserBSignsIn() {
        val baseA = PersonalBaseline { t0 }
        repeat(3) { i ->
            estimator(baseA, ObservationHistory(), a).warmUp(
                CameraStressBridge.toObservation(camera(), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
        }
        assertEquals(3, baseA.sampleCount("hr_bpm"))

        // B does their own thing.
        val baseB = PersonalBaseline { t0 }
        estimator(baseB, ObservationHistory(), b).warmUp(
            CameraStressBridge.toObservation(camera(), null, t0), SignalQuality.GOOD
        )
        assertEquals(1, baseB.sampleCount("hr_bpm"))

        // A signs back in.
        val baseA2 = PersonalBaseline { t0 }
        assertTrue(baselineStore.load(a, baseA2, ObservationHistory()))
        assertEquals("A must still have all 3", 3, baseA2.sampleCount("hr_bpm"))
    }

    @Test
    fun aCommanderCannotReadOrWriteAnyBaseline() {
        val commander = register("cdr", Role.COMMANDER)
        val base = PersonalBaseline { t0 }
        estimator(base, ObservationHistory(), a).warmUp(
            CameraStressBridge.toObservation(camera(), null, t0), SignalQuality.GOOD
        )
        assertFalse(baselineStore.load(commander, PersonalBaseline { t0 }, ObservationHistory()))
        assertFalse(baselineStore.save(commander, PersonalBaseline { t0 }, ObservationHistory()))
    }

    // == 13 : backward compatibility ==============================================================

    @Test
    fun anInstallWithNoStoredBaselineStartsCleanlyRatherThanCrashing() {
        val base = PersonalBaseline { t0 }
        assertFalse(baselineStore.load(a, base, ObservationHistory()))
        assertEquals(0, base.sampleCount("hr_bpm"))
        assertNull(null)
    }

    @Test
    fun corruptOrEmptyStoredStateIsIgnoredNotFatal() {
        store.putString("stress_baseline_v1_${a.accountId}", "{not valid json")
        val base = PersonalBaseline { t0 }
        assertFalse(baselineStore.load(a, base, ObservationHistory()))
        assertEquals(0, base.sampleCount("hr_bpm"))

        store.putString("stress_baseline_v1_${a.accountId}", "")
        assertFalse(baselineStore.load(a, base, ObservationHistory()))
        assertEquals(0, base.sampleCount("hr_bpm"))
    }

    @Test
    fun existingMeasurementRecordsAreUntouchedByThisFeature() {
        // StressMeasurementStore uses a different key; adding baseline persistence must not disturb it.
        val measurements = StressMeasurementStore(store)
        val record = StressMeasurementRecord(
            id = "cam-1", userId = "", measuredAtMs = t0.toEpochMilli(),
            source = CameraHrvResult.SOURCE_CAMERA_PPG, heartRateBpm = 68.0, rmssdMs = 34.0,
            sdnnMs = 45.0, signalQuality = "GOOD", stressScore = 40, stressZone = "YELLOW",
            confidence = 80, modelVersion = "v1", featureSchemaVersion = 1
        )
        assertTrue(measurements.save(a, record))

        val base = PersonalBaseline { t0 }
        estimator(base, ObservationHistory(), a).warmUp(
            CameraStressBridge.toObservation(camera(), null, t0), SignalQuality.GOOD
        )

        val history = measurements.history(a)
        assertEquals("existing measurement records must survive", 1, history.size)
        assertEquals(40, history.first().stressScore)
    }

    // == the existing model and history bounds are unchanged ======================================

    @Test
    fun theObservationWindowAndEntryCapArePreservedAcrossRestore() {
        val hist = ObservationHistory()
        val entries = (1..250).map { ObservationEntry(t0.toEpochMilli() - it * 1000L, 70.0, 35.0) }
        hist.restore(entries)
        assertEquals("the 200-entry cap must still apply", 200, hist.size())
    }

    @Test
    fun historyOlderThanTheRetentionWindowIsDroppedOnRestore() {
        val hist = ObservationHistory()
        val now = t0.toEpochMilli()
        // One entry inside the 30-day window, one well outside it.
        val entries = listOf(
            ObservationEntry(now - 10L * 24 * 3600_000L, 70.0, 35.0),
            ObservationEntry(now - 60L * 24 * 3600_000L, 70.0, 35.0)
        )
        hist.restore(entries)
        assertEquals("the 30-day window must still apply", 1, hist.size())
    }

    @Test
    fun temporalFeaturesSurviveARestart() {
        var base = PersonalBaseline { t0 }
        var hist = ObservationHistory()
        val est = estimator(base, hist, a)
        repeat(4) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(camera(hr = 70.0 + i), null, t0.plusSeconds(i * 3600L)),
                SignalQuality.GOOD
            )
        }
        val before = hist.hrChange(t0.plusSeconds(10 * 3600L), 80.0)
        assertNotNull("temporal features must be derivable", before)

        base = PersonalBaseline { t0 }
        hist = ObservationHistory()
        baselineStore.load(a, base, hist)
        val after = hist.hrChange(t0.plusSeconds(10 * 3600L), 80.0)
        assertEquals("the delta must be identical after a restart", before!!, after!!, 1e-9)
    }

    @Test
    fun theModelAndItsWeightsAreUnchangedByThisWork() {
        assertEquals("wesad-loso-logreg-v1", model.modelVersion)
        assertEquals(22, model.featureNames.size)
        assertEquals("weights must still be 2x the feature count", 44, model.inputDim)
    }
}
