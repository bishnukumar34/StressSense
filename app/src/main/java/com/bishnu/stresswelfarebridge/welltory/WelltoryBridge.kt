package com.bishnu.stresswelfarebridge.welltory

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.ml.CameraStressBridge
import com.bishnu.stresswelfarebridge.ml.FeatureSchema
import com.bishnu.stresswelfarebridge.ml.StressEstimator
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressObservation
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import com.bishnu.stresswelfarebridge.auth.Session
import java.time.Instant

/**
 * Feeds a validated Welltory measurement into the **existing** StressSense physiological pipeline.
 *
 * ## The data path, end to end
 *
 * ```
 * Welltory camera measurement
 *      -> RMSSD / SDNN in the result Intent
 *      -> validated (see WelltoryCallbackParser / WelltoryCallbackValidator)
 *      -> StressObservation  (the model's own input type)
 *      -> FeatureExtractor   (unchanged)
 *      -> wesad-loso-logreg-v1 (unchanged, deployed asset, unchanged coefficients)
 *      -> StressZoneMapping   (unchanged thresholds)
 *      -> StressMeasurementRecord (unchanged store, source = WELLTORY_CAMERA)
 *      -> Home / Stress / Trends / Commander
 * ```
 *
 * There is no second model, no new formula and no network call. [CameraStressBridge.evaluateObservation]
 * is the same entry point the native camera and the demo seeder already use, so the cold-start gate
 * and the scoring rules cannot drift between sources.
 *
 * ## Feature sourcing (exactly how each model feature is filled)
 *
 * The model has 22 features. Welltory supplies two directly; the rest come from legitimate existing
 * StressSense sources or are left **missing**. The model was trained with missing-value handling
 * (median imputation plus an explicit missingness indicator), so a genuinely absent feature is
 * handled correctly rather than invented.
 *
 * | model feature | source |
 * |---|---|
 * | `rmssd_ms` | **Welltory** `rmssd` (validated as ms) |
 * | `sdnn_ms`  | **Welltory** `sdnn` (validated as ms) |
 * | `hr_bpm`   | real Health Connect HR for today, else `null` - never invented |
 * | `hr_z`, `rmssd_z`, `sdnn_z` | the person's own persisted `PersonalBaseline`, else null until established |
 * | `resp_rate_bpm`, `resp_rate_z` | real Health Connect respiratory rate, else null |
 * | `activity_recent_norm` | real Health Connect steps/exercise, else null |
 * | `*_delta_recent`, `*_slope_recent` | this person's existing `ObservationHistory`, else null |
 * | `mean_nn_ms`, `sdsd_ms`, `pnn50_pct` | **not derivable** from Welltory's extras - left null |
 * | `ppg_quality`, `confidence`, `valid_nn_ratio`, `artifact_ratio`, `duration_s` | not exposed by Welltory - left null |
 *
 * **Heart rate in particular is never fabricated.** Welltory's documented extras contain no HR field.
 * When a real recent Health Connect HR exists it is attached through
 * [WelltoryMeasurement.attachRealHeartRate], which itself refuses implausible values. Otherwise it
 * stays null and the model sees a missing feature - which is the honest outcome, because the
 * baseline gate is keyed on heart-rate samples and substituting a default would let a measurement
 * establish a personal baseline from a number that was never measured.
 *
 * ## Welltory's proprietary stress score
 *
 * [WelltoryMeasurement.welltoryStress] and its siblings are never placed in a feature vector and never
 * assigned to `StressMeasurementRecord.stressScore`. A Welltory `stress` of 0.72 is not shown as 72
 * and is not our model output. The score in this pipeline always comes from `wesad-loso-logreg-v1`.
 */
object WelltoryBridge {

    /** What happened to a Welltory measurement once it reached the model. */
    sealed class Result {
        /** The deployed model produced a real score. [record] is persisted and published. */
        data class Scored(
            val score: Int,
            val zone: String,
            val confidence: Int,
            val record: StressMeasurementRecord
        ) : Result()

        /**
         * The measurement was real and was saved, but the model correctly withheld a number - the
         * person is still building their personal baseline, or too few features were genuinely
         * measured. No score is invented.
         */
        data class NotScoredYet(val reason: String) : Result()

        /** The measurement could not be used at all. Nothing is persisted as valid. */
        data class Refused(val reason: String) : Result()
    }

    /**
     * Builds the model input for a Welltory measurement.
     *
     * @param health today's real Health Connect data, used only for genuine HR / respiration /
     *   activity context. Null when unavailable - the features are then simply missing.
     */
    fun toObservation(
        measurement: WelltoryMeasurement,
        health: DailyHealth?,
        measuredAt: Instant
    ): StressObservation = StressObservation(
        measuredAt = measuredAt,
        source = WelltoryConfig.SOURCE_WELLTORY_CAMERA,
        // The two real values Welltory returned.
        rmssdMs = measurement.rmssdMs,
        sdnnMs = measurement.sdnnMs,
        // Real HR only when legitimately available; null otherwise. Never defaulted.
        hrBpm = measurement.heartRateBpm,
        // Welltory's callback exposes no mean-NN, SDSD or pNN50, so these stay missing rather than
        // being approximated from RMSSD/SDNN, which would not be the same quantity.
        meanNnMs = null,
        sdsdMs = null,
        pnn50Pct = null,
        respRateBpm = health?.respiratoryRate,
        activityRecentNorm = activityNorm(health),
        // Welltory does not expose its PPG signal metrics, so none are claimed.
        ppgQuality = null,
        confidence = null,
        validNnRatio = null,
        artifactRatio = null,
        durationSeconds = null
    )

    /** Real step/exercise data mapped onto the model's 0..1 load feature; null when there is none. */
    private fun activityNorm(health: DailyHealth?): Double? {
        val h = health ?: return null
        if (h.steps <= 0L && h.exerciseMinutes <= 0L) return null
        val fromSteps = (h.steps / 8000.0).coerceIn(0.0, 1.0)
        val fromExercise = (h.exerciseMinutes / 45.0).coerceIn(0.0, 1.0)
        return maxOf(fromSteps, fromExercise)
    }

    /**
     * Attaches a real heart rate read from another legitimate StressSense source (Health Connect).
     *
     * Returns the measurement unchanged when no plausible real HR exists. This is the only sanctioned
     * route by which a Welltory measurement can carry a heart rate, and it is deliberately narrow:
     * a real reading or nothing.
     */
    fun withRealHeartRate(measurement: WelltoryMeasurement, health: DailyHealth?): WelltoryMeasurement {
        val hr = health?.avgHr
        if (hr == null || !WelltoryCallbackValidator.isRealisticHeartRate(hr)) return measurement
        return measurement.attachRealHeartRate(hr)
    }

    /**
     * Runs the measurement through the deployed model and persists whatever it genuinely produces.
     *
     * @param session the **currently authenticated** account. The record is force-owned by
     *   `session.accountId` via [StressMeasurementStore], so a result can never be written to
     *   another account even if [WelltoryMeasurement.accountId] disagreed.
     * @param alreadyProcessed ids (see [WelltoryMeasurement.recordId]) already stored, used to make
     *   a repeated callback a no-op.
     */
    fun process(
        session: Session,
        measurement: WelltoryMeasurement,
        estimator: StressEstimator,
        store: StressMeasurementStore,
        health: DailyHealth?,
        modelVersion: String,
        alreadyProcessedIds: Set<String> = emptySet()
    ): Result {
        // Account safety first: a measurement is only ever stored for the signed-in personnel account.
        if (session.isCommander) {
            return Result.Refused("Commander accounts cannot record physiological measurements.")
        }
        if (measurement.accountId != session.accountId) {
            return Result.Refused(
                "This measurement belongs to a different account than the one signed in."
            )
        }
        if (measurement.recordId in alreadyProcessedIds) {
            // The same Welltory result delivered twice (onNewIntent plus a recreated onCreate).
            return Result.Refused("This Welltory result was already recorded.")
        }

        val measuredAt = Instant.ofEpochMilli(measurement.measuredAtMs)
        val observation = toObservation(measurement, health, measuredAt)

        // Same cold-start gate and scoring path as the native camera. Quality is FAIR rather than
        // GOOD because Welltory's proprietary in-app filtering is not our signal: we did not measure
        // the pulse ourselves, so we do not claim a PPG confidence we never computed. It is not POOR
        // either, since the HRV values themselves passed validation.
        val outcome = CameraStressBridge.evaluateObservation(
            estimator, observation, SignalQuality.FAIR
        )

        // The measurement is real and validated, so it is always persisted - even when the model
        // abstains. A person who took a genuine measurement should see it in their history, with
        // stressScore = null rather than a fabricated number.
        val record = StressMeasurementRecord(
            id = measurement.recordId,
            userId = session.accountId,
            measuredAtMs = measurement.measuredAtMs,
            source = WelltoryConfig.SOURCE_WELLTORY_CAMERA,
            heartRateBpm = measurement.heartRateBpm,
            rmssdMs = measurement.rmssdMs,
            sdnnMs = measurement.sdnnMs,
            // Welltory's index colours are not a PPG signal-quality grade we can honestly claim.
            signalQuality = null,
            stressScore = outcome.score,
            stressZone = if (outcome.score != null) outcome.zone.name else null,
            confidence = outcome.confidence,
            modelVersion = modelVersion,
            featureSchemaVersion = FeatureSchema.FEATURE_SCHEMA_VERSION
        )
        if (!store.save(session, record)) {
            return Result.Refused("The measurement could not be saved for this account.")
        }

        return if (outcome.score != null) {
            Result.Scored(
                score = outcome.score,
                zone = outcome.zone.name,
                confidence = outcome.confidence,
                record = record
            )
        } else {
            Result.NotScoredYet(outcome.reason)
        }
    }
}