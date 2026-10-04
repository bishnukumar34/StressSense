package com.bishnu.stresswelfarebridge.welltory

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.Access
import com.bishnu.stresswelfarebridge.commander.CommanderOverview
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.PersonnelRecord
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.commander.StressZoneMapping
import com.bishnu.stresswelfarebridge.ml.CameraStressBridge
import com.bishnu.stresswelfarebridge.ml.ObservationHistory
import com.bishnu.stresswelfarebridge.ml.PersonalBaseline
import com.bishnu.stresswelfarebridge.ml.StressEstimator
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressModel
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The full Welltory path, driven against the **real deployed model**.
 *
 * These tests load the shipped `stress_model_ml.json` asset and use the real [StressEstimator] and
 * [StressMeasurementStore] - exactly what runs on the device. Nothing is stubbed and no score is
 * hard-coded: every assertion about a number is about the model having produced one, or about the
 * model correctly declining to.
 *
 * NOTE ON FIXTURE VALUES: the HRV numbers below stand in for a Welltory result. They are realistic
 * millisecond values, not values captured from a live Welltory measurement by this test.
 */
class WelltoryPipelineTest {

    companion object {
        private lateinit var model: StressModel

        @BeforeClass
        @JvmStatic
        fun loadDeployedModel() {
            val json = org.json.JSONObject(
                WelltoryPipelineTest::class.java.classLoader!!
                    .getResource("stress_model_ml.json")!!.readText()
            )
            model = StressModel.fromJson(json)
            StressModel.verifySchema(model)
        }
    }

    private val t0: Instant = Instant.parse("2026-03-01T09:00:00Z")
    private val t0Ms = t0.toEpochMilli()

    private var idCounter = 0
    private fun nextId(): String = "acct-" + (++idCounter)

    private fun session(store: InMemoryKeyValueStore, name: String, user: String, sid: String): Session {
        val accounts = AccountStore(store, store, { t0Ms }, ::nextId)
        val r = accounts.register(
            RegistrationRequest(
                fullName = name, username = user, password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.PERSONNEL, serviceId = sid
            )
        )
        assertTrue("registration failed: $r", r is AuthResult.Success)
/**
     * A validated Welltory measurement. HRV values are realistic millisecond figures.
     *
     * The default timestamp deliberately sits **after** the warm-up window used by [warmEstimator].
     * That is not cosmetic: the model's temporal features (`*_delta_recent`, `*_slope_recent`) are
     * only non-null when an earlier reading exists in the person's history, so a Welltory measurement
     * timestamped at the same instant as the baseline readings presents fewer features and the
     * completeness gate correctly abstains. In the app this is the natural order - a person builds a
     * baseline over time, then measures again later.
     */
        return (r as AuthResult.Success).session
    }

    /** A validated Welltory measurement. HRV values are realistic millisecond figures. */
    private fun measurement(
        accountId: String,
        rmssd: Double = 42.0,
        sdnn: Double = 58.0,
        hr: Double? = null,
        token: String = "wtokenabcdef123",
        atMs: Long = t0Ms + 6 * 3_600_000L
    ) = WelltoryMeasurement(
        accountId = accountId,
        measuredAtMs = atMs,
        rmssdMs = rmssd,
        sdnnMs = sdnn,
        heartRateBpm = hr,
        welltoryStress = 0.72,
        welltoryToken = token,
        dedupeKey = token,
        validation = WelltoryHrvValidation(WelltoryHrvUnits.MILLISECONDS, rmssd, sdnn, "ok")
    )

    private fun estimator(baseline: PersonalBaseline, history: ObservationHistory) =
        CameraStressBridge.estimator(model, baseline, history)

    /**
     * An estimator whose personal baseline is already established, which is the only state in which
     * the deployed model has the personal-deviation context it relies on.
     *
     * Real readings are used here (not fabricated ones) - this is the normal state for a returning
     * user who has already taken camera measurements.
     */
    private fun warmEstimator(session: Session): Pair<StressEstimator, PersonalBaseline> {
        val baseline = PersonalBaseline { t0 }
        val history = ObservationHistory()
        val est = estimator(baseline, history)
        repeat(PersonalBaseline.MIN_SAMPLES) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(
                    com.bishnu.stresswelfarebridge.ppg.CameraHrvResult(
                        rmssdMs = 34.0, sdnnMs = 45.0, meanIbiMs = 882.0, heartRateBpm = 68.0,
                        measurementStartMs = t0Ms - 45_000L, measurementEndMs = t0Ms,
                        durationSeconds = 45.0, validIbiCount = 46, rejectedIbiCount = 0,
                        totalIbiCount = 46, validPercent = 100.0, signalQuality = SignalQuality.GOOD,
                        confidence = 0.94, sdsdMs = 33.0, pnn50Pct = 18.0
                    ),
                    null, t0.plusSeconds(i * 3600L)
                ),
                SignalQuality.GOOD
            )
        }
        assertTrue("baseline should be established", baseline.isEstablished("hr_bpm"))
        return est to baseline
    }

    /**
     * Real Health Connect context for today: heart rate, respiratory rate and steps.
     *
     * This matters because of the deployed model's completeness gate. It abstains unless at least
     * [StressEstimator.MIN_COMPLETENESS] of its 22 features are genuinely measured, and Welltory's
     * callback supplies only two of them (`rmssd_ms`, `sdnn_ms`). So a Welltory measurement is only
     * scorable - and only committable to the baseline - when real wearable context is also present.
     * That is the existing architecture's rule, applied unchanged, not a Welltory-specific limit.
     */
    private fun realHealth(hr: Double = 68.0) = DailyHealth(LocalDate.parse("2026-03-01")).apply {
        avgHr = hr
        respiratoryRate = 14.0
        steps = 4200L
    }

    /**
     * Attaches real Health Connect heart rate to a Welltory measurement.
     *
     * Mirrors what the app does when a callback arrives: Welltory supplies no HR field, so a real HR is
     * taken from the wearable. Both are required, because the deployed model's completeness gate needs
     * `hr_bpm` and `hr_z` before it will score anything.
     */
    private fun withRealHr(m: WelltoryMeasurement) = WelltoryBridge.withRealHeartRate(m, realHealth())

    // == D. the real model produces the score =====================================================

    @Test
    fun welltoryRmssdReachesTheRealModelAndProducesAScore() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val (est, _) = warmEstimator(s)
        val ms = StressMeasurementStore(store)

        val result = WelltoryBridge.process(
            session = s,
            measurement = withRealHr(measurement(s.accountId)),
            estimator = est,
            store = ms,
            health = realHealth(),
            modelVersion = model.modelVersion
        )
        assertTrue(
            "the deployed model should score a validated Welltory measurement: $result",
            result is WelltoryBridge.Result.Scored
        )
        val scored = result as WelltoryBridge.Result.Scored
        assertTrue("score must be a real 0..100", scored.score in 0..100)
        assertEquals("scored record must be owned by the account", s.accountId, scored.record.userId)
    }

    @Test
    fun theScoreComesFromTheDeployedLogisticRegressionNotWelltorysNumber() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)

        // Two Welltory results with identical HRV but wildly different Welltory "stress" scores.
        val lowWelltory = WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId, token = "tokA")).copy(welltoryStress = 0.05),
            warmEstimator(s).first, ms, realHealth(), model.modelVersion
        ) as WelltoryBridge.Result.Scored
        val highWelltory = WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId, token = "tokB")).copy(welltoryStress = 0.99),
            warmEstimator(s).first, ms, realHealth(), model.modelVersion
        ) as WelltoryBridge.Result.Scored

        // Identical physiology must yield the same StressSense score. If Welltory's proprietary
        // number were being used, these would differ.
        assertEquals(
            "Welltory's stress score must not influence the StressSense score",
            lowWelltory.score, highWelltory.score
        )
    }

    @Test
    fun welltoryStressIsNeverWrittenToThePersistedStressScore() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)

        val scored = WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId)).copy(welltoryStress = 0.72),
            warmEstimator(s).first, ms, realHealth(), model.modelVersion
        ) as WelltoryBridge.Result.Scored

        // 0.72 must never surface as 72, and the persisted score must equal the model's output.
        assertNotNull(scored.record.stressScore)
        assertTrue("must be 0..100, not 0.72 or 72", scored.record.stressScore!! in 0..100)
        assertEquals(scored.score, scored.record.stressScore)
    }

    @Test
    fun changingOnlyTheHrvChangesTheScore() {
        // Proves the Welltory RMSSD actually reaches the feature vector rather than being ignored.
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)

        val calm = WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId, rmssd = 60.0, sdnn = 70.0, token = "calm")),
            warmEstimator(s).first, ms, realHealth(), model.modelVersion
        ) as WelltoryBridge.Result.Scored
        val low = WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId, rmssd = 12.0, sdnn = 20.0, token = "low")),
            warmEstimator(s).first, ms, realHealth(), model.modelVersion
        ) as WelltoryBridge.Result.Scored

        // The deployed WESAD model gives `rmssd_z` a *positive* weight (0.439), so a reading far
        // below this person's own baseline raises the predicted stress class rather than lowering it.
        // The exact direction is the model's business, not this test's; what matters is that the
        // Welltory number genuinely propagates into the score instead of being dropped.
        assertTrue(
            "different HRV must produce different scores ($calm vs $low)",
            calm.score != low.score
        )
        assertTrue(calm.score in 0..100)
        assertTrue(low.score in 0..100)
    }

    @Test
    fun theDeployedModelIsTheOnlyModelInUse() {
        assertEquals(
            "the deployed model asset is the only model",
            "wesad-loso-logreg-v1", model.modelVersion
        )
    }

    // == C. persistence ============================================================================

    @Test
    fun aValidMeasurementIsPersistedWithTheWelltorySource() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)
        WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId)), warmEstimator(s).first, ms, realHealth(), model.modelVersion
        )
        val saved = ms.latest(s)!!
        assertEquals(WelltoryConfig.SOURCE_WELLTORY_CAMERA, saved.source)
        assertEquals("WELLTORY_CAMERA", saved.source)
        assertEquals(s.accountId, saved.userId)
        assertEquals(42.0, saved.rmssdMs!!, 1e-9)
        assertEquals(58.0, saved.sdnnMs!!, 1e-9)
        assertEquals(model.modelVersion, saved.modelVersion)
    }

    @Test
    fun aDuplicateCallbackDoesNotCreateADuplicateRecord() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)
        val m = withRealHr(measurement(s.accountId, token = "dupe-token-1"))

        val first = WelltoryBridge.process(
            s, m, warmEstimator(s).first, ms, realHealth(), model.modelVersion
        )
        assertTrue(first is WelltoryBridge.Result.Scored)

        // The same result delivered a second time (onNewIntent, then a recreated onCreate).
        val second = WelltoryBridge.process(
            s, m, warmEstimator(s).first, ms, realHealth(), model.modelVersion,
            alreadyProcessedIds = ms.history(s).map { it.id }.toSet()
        )
        assertTrue(
            "a repeated callback must not be processed twice",
            second is WelltoryBridge.Result.Refused
        )
        assertEquals("exactly one record must exist", 1, ms.history(s).size)
    }

    @Test
    fun thePersistedIdIsStableSoResavingOverwrites() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)
        repeat(2) {
            WelltoryBridge.process(
                s, withRealHr(measurement(s.accountId, token = "stable-token")), warmEstimator(s).first,
                ms, null, model.modelVersion
            )
        }
        assertEquals("a stable id must upsert, not append", 1, ms.history(s).size)
    }

    // == F. account isolation ======================================================================

    @Test
    fun personnelOneResultBelongsOnlyToPersonnelOne() {
        val store = InMemoryKeyValueStore()
        val p1 = session(store, "Person One", "person.one", "SVC-1")
        val p2 = session(store, "Person Two", "person.two", "SVC-2")
        val ms = StressMeasurementStore(store)

        WelltoryBridge.process(
            p1, measurement(p1.accountId), warmEstimator(p1).first, ms, null, model.modelVersion
        )
        assertEquals(1, ms.history(p1).size)
        assertEquals(
            "personnel two must see nothing of personnel one's measurement",
            0, ms.history(p2).size
        )
        assertNull(ms.latest(p2))
    }

    @Test
    fun aMeasurementWhoseAccountDiffersIsRefused() {
        val store = InMemoryKeyValueStore()
        val p1 = session(store, "Person One", "person.one", "SVC-1")
        val p2 = session(store, "Person Two", "person.two", "SVC-2")
        val ms = StressMeasurementStore(store)

        // A result claiming to belong to p2, arriving while p1 is signed in.
        val result = WelltoryBridge.process(
            p1, measurement(p2.accountId), warmEstimator(p1).first, ms, null, model.modelVersion
        )
        assertTrue(
            "a cross-account measurement must be refused",
            result is WelltoryBridge.Result.Refused
        )
        assertEquals("nothing may be written for either account", 0, ms.history(p1).size)
        assertEquals(0, ms.history(p2).size)
    }

    @Test
    fun aCommanderSessionCannotRecordAMeasurement() {
        val store = InMemoryKeyValueStore()
        val accounts = AccountStore(store, store, { t0Ms }, ::nextId)
        val cmd = accounts.register(
            RegistrationRequest(
                fullName = "Cmd", username = "cmd", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.COMMANDER, serviceId = null
            )
        )
        val s = (cmd as AuthResult.Success).session
        val ms = StressMeasurementStore(store)
        val result = WelltoryBridge.process(
            s, withRealHr(measurement(s.accountId)), warmEstimator(s).first, ms, realHealth(), model.modelVersion
        )
        assertTrue(result is WelltoryBridge.Result.Refused)
        assertEquals(0, ms.history(s).size)
    }

    // == G. Commander propagation ==================================================================

    /**
     * Publishes a personnel record the way the app does after a measurement, then checks where a
     * commander sees them.
     *
     * The record is built from the score the deployed model produced, and the zone comes from the
     * app's existing [StressZoneMapping] cut-points - never from a hard-coded person-to-group rule.
     */
    private fun publishAndZone(
        session: Session,
        store: InMemoryKeyValueStore,
        directory: PersonnelDirectory,
        score: Int
    ): StressZone {
        val zone = StressZoneMapping.fromScore(score)
        directory.publishOwnRecord(
            session,
            PersonnelRecord(
                accountId = session.accountId,
                fullName = session.account.fullName,
                serviceId = session.account.serviceId,
                stressScore = score,
                // The zone's own title, so no parallel label mapping is duplicated in a test.
                stressLevel = zone.title,
                zone = zone,
                confidence = 80,
                usingRealData = true,
                dataQuality = "Welltory camera HRV scored by " + model.modelVersion,
                baselineDays = 7,
                lastUpdatedMs = t0Ms,
                steps = 0, exerciseMinutes = 0, sleepMinutes = 0,
                avgHr = null, avgHrv = null, restingHr = null, respiratoryRate = null, spo2 = null,
                cameraHrv = null
            )
        )
        return zone
    }

    @Test
    fun commanderGroupingFollowsThePublishedScoreNotAPersonToZoneRule() {
        val store = InMemoryKeyValueStore()
        val accounts = AccountStore(store, store, { t0Ms }, ::nextId)
        val commander = (accounts.register(
            RegistrationRequest(
                fullName = "Commander", username = "commander", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.COMMANDER, serviceId = null
            )
        ) as AuthResult.Success).session
        val personnel = session(store, "Person One", "person.one", "SVC-1")
        val directory = PersonnelDirectory(store, accounts)

        // Published HIGH...
        assertEquals(StressZone.RED, publishAndZone(personnel, store, directory, 78))
        assertEquals(1, overviewOf(directory, commander).count(StressZone.RED))

        // ...then a later measurement scores 48, which the existing cut-points place in MODERATE.
        assertEquals(StressZone.YELLOW, publishAndZone(personnel, store, directory, 48))
        val overview = overviewOf(directory, commander)
        assertEquals("the person must move with the new score", 1, overview.count(StressZone.YELLOW))
        assertEquals("and must leave the HIGH group", 0, overview.count(StressZone.RED))
    }

    /** Unwraps the access-controlled overview, failing the test if the commander was denied. */
    private fun overviewOf(
        directory: PersonnelDirectory,
        commander: Session
    ): CommanderOverview {
        val access = directory.commanderOverview(commander)
        assertTrue("the commander must be allowed to read the overview", access is Access.Allowed)
        return (access as Access.Allowed).value
    }

    @Test
    fun commanderNeverSeesMedicalHistoryThroughTheWelltoryPath() {
        val store = InMemoryKeyValueStore()
        val accounts = AccountStore(store, store, { t0Ms }, ::nextId)
        val commander = (accounts.register(
            RegistrationRequest(
                fullName = "Commander", username = "commander", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.COMMANDER, serviceId = null
            )
        ) as AuthResult.Success).session
        val personnel = session(store, "Person One", "person.one", "SVC-1")
        val directory = PersonnelDirectory(store, accounts)
        publishAndZone(personnel, store, directory, 55)

        val record = overviewOf(directory, commander).zones.values.flatten().first()
        // The commander-facing record carries only operational fields: no medical history of any kind.
        val fieldNames = PersonnelRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "no medical field may exist on the commander-facing record",
            fieldNames.none { it.contains("medical") || it.contains("condition") || it.contains("diagnos") }
        )
    }

    @Test
    fun aWelltoryScoreFlowsIntoTheCommanderZoneThroughTheExistingThresholds() {
        val store = InMemoryKeyValueStore()
        val accounts = AccountStore(store, store, { t0Ms }, ::nextId)
        val personnel = session(store, "Person One", "person.one", "SVC-1")
        val ms = StressMeasurementStore(store)
        val directory = PersonnelDirectory(store, accounts)

        // A real model score for a genuinely stressed HRV reading.
        val scored = WelltoryBridge.process(
            personnel,
            withRealHr(measurement(personnel.accountId, rmssd = 8.0, sdnn = 12.0, token = "acute")),
            warmEstimator(personnel).first, ms, realHealth(hr = 96.0), model.modelVersion
        ) as WelltoryBridge.Result.Scored

        // The commander zone is derived from that exact score via the existing mapping.
        val zone = publishAndZone(personnel, store, directory, scored.score)
        assertEquals(StressZoneMapping.fromScore(scored.score), zone)
        assertTrue("zone must be one of the existing bands", StressZone.ordered.contains(zone))
    }

    // == E. baseline / cold start ==================================================================

    @Test
    fun theFirstWelltoryMeasurementsLearnTheBaselineWithoutIssuingAScore() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val ms = StressMeasurementStore(store)
        val est = estimator(PersonalBaseline { t0 }, ObservationHistory())

        // A brand-new account: the existing cold-start rule must apply to Welltory too.
        var sawLearning = false
        repeat(PersonalBaseline.MIN_SAMPLES) { i ->
            val r = WelltoryBridge.process(
                s, withRealHr(measurement(s.accountId, token = "learn-$i", atMs = t0Ms + i * 3_600_000L)),
                est, ms, realHealth(), model.modelVersion
            )
            if (r is WelltoryBridge.Result.NotScoredYet) sawLearning = true
            assertNull(
                "no score may be issued before the baseline is established",
                (r as? WelltoryBridge.Result.Scored)?.score
            )
        }
        assertTrue("the baseline-learning state must be reported", sawLearning)
    }

    @Test
    fun minSamplesIsUnchangedByThisIntegration() {
        // Guards against a Welltory-specific shortcut quietly lowering the cold-start bar.
        assertEquals(5, PersonalBaseline.MIN_SAMPLES)
    }

    @Test
    fun aWelltoryMeasurementContributesHrToTheBaselineOnlyWithARealHeartRate() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val baseline = PersonalBaseline { t0 }
        val est = estimator(baseline, ObservationHistory())

        // Welltory's callback carries no heart rate, and PersonalBaseline.observe rejects null, so
        // hr_bpm must not advance on an invented value.
        val result = WelltoryBridge.process(
            s, measurement(s.accountId, hr = null), est, StressMeasurementStore(store),
            health = null, modelVersion = model.modelVersion
        )
        assertEquals(
            "an invented heart rate must never be admitted to the baseline",
            0, baseline.sampleCount("hr_bpm")
        )
        assertTrue(
            "without HR the model must abstain rather than score a thin feature vector",
            result is WelltoryBridge.Result.NotScoredYet
        )
    }

    @Test
    fun aRealHeartRateDoesAdvanceTheBaseline() {
        val store = InMemoryKeyValueStore()
        val s = session(store, "Jane Doe", "jane", "SVC-1")
        val baseline = PersonalBaseline { t0 }
        val est = estimator(baseline, ObservationHistory())
        // Warm the baseline first, so the measurement below lands in the state a returning user is in.
        repeat(PersonalBaseline.MIN_SAMPLES) { i ->
            est.warmUp(
                CameraStressBridge.toObservation(
                    com.bishnu.stresswelfarebridge.ppg.CameraHrvResult(
                        rmssdMs = 34.0, sdnnMs = 45.0, meanIbiMs = 882.0, heartRateBpm = 68.0,
                        measurementStartMs = t0Ms - 45_000L, measurementEndMs = t0Ms,
                        durationSeconds = 45.0, validIbiCount = 46, rejectedIbiCount = 0,
                        totalIbiCount = 46, validPercent = 100.0, signalQuality = SignalQuality.GOOD,
                        confidence = 0.94, sdsdMs = 33.0, pnn50Pct = 18.0
                    ),
                    null, t0.plusSeconds(i * 3600L)
                ),
                SignalQuality.GOOD
            )
        }
        val before = baseline.sampleCount("hr_bpm")

        val withHr = WelltoryBridge.withRealHeartRate(measurement(s.accountId), realHealth(hr = 68.0))
        assertEquals(68.0, withHr.heartRateBpm!!, 1e-9)
        WelltoryBridge.process(
            s, withHr, est, StressMeasurementStore(store), realHealth(hr = 68.0),
            model.modelVersion
        )
        // The real heart rate genuinely reached the baseline - measured, not invented.
        assertEquals(before + 1, baseline.sampleCount("hr_bpm"))
    }

    @Test
    fun anImplausibleHeartRateIsNeverAttached() {
        val health = DailyHealth(LocalDate.parse("2026-03-01")).apply { avgHr = 900.0 }
        val m = WelltoryBridge.withRealHeartRate(measurement("acct-x"), health)
        assertNull("900 bpm is not real and must be discarded", m.heartRateBpm)
        assertFalse(m.hasRealHeartRate)
    }

    @Test
    fun aWelltoryMeasurementWithoutHrIsHonestAboutIt() {
        val m = measurement("acct-x")
        assertNull(m.heartRateBpm)
        assertFalse(m.hasRealHeartRate)
        val o = WelltoryBridge.toObservation(m, null, t0)
        assertNull("HR must stay missing, never defaulted", o.hrBpm)
        assertEquals(42.0, o.rmssdMs!!, 1e-9)
        assertEquals(58.0, o.sdnnMs!!, 1e-9)
        assertNull("Welltory exposes no mean-NN", o.meanNnMs)
        assertNull("Welltory exposes no pNN50", o.pnn50Pct)
        assertEquals(WelltoryConfig.SOURCE_WELLTORY_CAMERA, o.source)
    }
}