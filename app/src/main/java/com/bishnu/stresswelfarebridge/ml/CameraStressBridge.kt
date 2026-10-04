package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.auth.KeyValueStore
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Turns a completed camera HRV measurement into a **real** physiological stress score produced by
 * the deployed ML model, and persists the result.
 *
 * This is the missing link the pipeline needed: the camera produced excellent HRV numbers that were
 * only ever *displayed*. The deployed model expects a 22-feature vector, and the camera already
 * supplies six of those features directly. The rest are pulled from real Health Connect data when
 * it exists and left **missing** when it does not - the model was trained with missing-value
 * handling (median imputation plus an explicit missingness flag), so an absent feature is handled
 * correctly rather than being invented.
 *
 * Nothing here fabricates a number. If the camera result is unusable the score is null and the
 * reason is recorded.
 */
object CameraStressBridge {

    /**
     * Result of running the deployed model over a camera measurement.
     *
     * [score] is null only when the estimator abstained, and [reason] then explains why.
     */
    data class CameraStressOutcome(
        val score: Int?,
        val zone: StressZone,
        val probability: Double?,
        val confidence: Int,
        val reason: String,
        val source: String = CameraHrvResult.SOURCE_CAMERA_PPG,
        /** Further qualifying readings needed before a score can be issued. 0 once the baseline is ready. */
        val readingsUntilBaselineReady: Int = 0
    )

    /**
     * Builds the model input from a camera result plus any real Health Connect context.
     *
     * Heart and HRV come from the camera. Respiration and activity come from Health Connect only when
     * those records actually exist. Personal baseline features (the `_z` columns) start empty and are
     * filled by the estimator as the person's own history accumulates - they are genuinely unknown on
     * a first measurement and are represented as missing, not as zero.
     */
    fun toObservation(
        result: CameraHrvResult,
        health: DailyHealth? = null,
        measuredAt: Instant
    ): StressObservation = StressObservation(
        measuredAt = measuredAt,
        source = CameraHrvResult.SOURCE_CAMERA_PPG,
        hrBpm = result.heartRateBpm,
        rmssdMs = result.rmssdMs,
        sdnnMs = result.sdnnMs,
        meanNnMs = result.meanIbiMs,
        sdsdMs = result.sdsdMs,
        pnn50Pct = result.pnn50Pct,
        // Real Health Connect values only; never a placeholder.
        respRateBpm = health?.respiratoryRate,
        // Physical load context, normalised 0..1 from real step/exercise data.
        activityRecentNorm = normaliseActivity(health),
        ppgQuality = result.confidence,
        confidence = result.confidence,
        validNnRatio = result.validPercent / 100.0,
        artifactRatio = if (result.totalIbiCount > 0) {
            result.rejectedIbiCount.toDouble() / result.totalIbiCount
        } else {
            1.0
        },
        durationSeconds = result.durationSeconds
    )

    /**
     * Maps real activity data onto the model's 0..1 "recent physical load" feature.
     *
     * Returns null when Health Connect has no activity data, so the model sees the feature as
     * missing rather than as "no exercise" - the two mean different things.
     */
    private fun normaliseActivity(health: DailyHealth?): Double? {
        val h = health ?: return null
        val steps = h.steps
        val exerciseMinutes = h.exerciseMinutes
        if (steps <= 0L && exerciseMinutes <= 0L) return null
        val fromSteps = (steps / 8000.0).coerceIn(0.0, 1.0)
        val fromExercise = (exerciseMinutes / 45.0).coerceIn(0.0, 1.0)
        return maxOf(fromSteps, fromExercise)
    }

    /**
     * Runs the deployed model over a camera measurement.
     *
     * Uses the real [StressEstimator] and the real deployed weights. There is no fallback formula
     * and no default score: if the estimator abstains, [CameraStressOutcome.score] is null.
     */
    fun evaluate(
        estimator: StressEstimator,
        result: CameraHrvResult,
        health: DailyHealth? = null,
        measuredAt: Instant = Instant.now()
    ): CameraStressOutcome =
        evaluateObservation(estimator, toObservation(result, health, measuredAt), result.signalQuality)

    /**
     * The same cold-start gate and scoring path, starting from an observation that already exists.
     *
     * Exists so a caller already holding a [StressObservation] - the demo seeder, or any future
     * replay path - goes through this identical logic instead of reimplementing it and drifting.
     */
    fun evaluateObservation(
        estimator: StressEstimator,
        observation: StressObservation,
        quality: SignalQuality
    ): CameraStressOutcome {
        // -- Cold-start gate -------------------------------------------------------------------------
        // The deployed model leans heavily on personal-deviation features (`hr_z`, `rmssd_z`, ...) and
        // temporal deltas. Until this person has enough readings for a baseline those columns are
        // imputed, and the model answers that mostly-empty vector with an extreme logit that displays
        // as a confident "0/100, you are fine". That number is not a stress assessment - it is the model
        // reporting that it has no context. So the reading is still learned from, but no score is issued
        // until the baseline genuinely exists.
        val remaining = estimator.readingsUntilBaselineReady()
        if (remaining > 0 && estimator.warmUp(observation, quality)) {
            return CameraStressOutcome(
                score = null,
                zone = StressZone.LOW,
                probability = null,
                confidence = 0,
                reason = "Learning your personal baseline: $remaining more reading(s) needed " +
                    "before a stress score can be given. This reading was recorded.",
                source = observation.source,
                readingsUntilBaselineReady = remaining
            )
        }

        val prediction = estimator.evaluate(observation, quality)
        return CameraStressOutcome(
            score = prediction.score,
            zone = prediction.zone,
            probability = prediction.probability,
            confidence = prediction.confidence,
            reason = prediction.reason
        )
    }

    /**
     * Builds a fully wired estimator using the deployed asset.
     *
     * [onCommitted] fires immediately after every qualifying measurement has been folded into the
     * personal baseline and history. Wiring [StressBaselineStore.save] into it means the five-reading
     * path to a real score survives an app restart instead of resetting to zero on every launch.
     */
    fun estimator(
        model: StressModel,
        baseline: PersonalBaseline,
        history: ObservationHistory,
        onCommitted: () -> Unit = {}
    ) = StressEstimator(model, baseline, history, { Instant.now() }, onCommitted)
}

/**
 * A persisted physiological stress measurement.
 *
 * This is the "stress history" record the Trends and Stress sections read. It records which source
 * produced the score so a camera-derived reading is never presented as a wearable one.
 */
data class StressMeasurementRecord(
    val id: String,
    val userId: String,
    val measuredAtMs: Long,
    /** Always the real source: `CAMERA_PPG` or `HEALTH_CONNECT`. */
    val source: String,
    val heartRateBpm: Double?,
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    val signalQuality: String?,
    /** 0-100, or null when the model abstained. Never defaulted to zero. */
    val stressScore: Int?,
    val stressZone: String?,
    val confidence: Int,
    val modelVersion: String,
    val featureSchemaVersion: Int
) {
    val hasScore: Boolean get() = stressScore != null
}

/** Local persistence for [StressMeasurementRecord], scoped to the authenticated account. */
class StressMeasurementStore(private val store: KeyValueStore) {

    fun save(session: Session?, record: StressMeasurementRecord): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        val owned = record.copy(userId = s.accountId)
        val list = read(s.accountId).toMutableList()
        // Upsert by id: re-saving the same capture must update it rather than append a duplicate,
        // so replaying a measurement (for example after a recomposition) cannot inflate the trend.
        val existing = list.indexOfFirst { it.id == owned.id }
        if (existing >= 0) list[existing] = owned else list.add(owned)
        write(s.accountId, list)
        return true
    }

    /** Newest first. */
    fun history(session: Session?): List<StressMeasurementRecord> {
        val s = session ?: return emptyList()
        if (s.role.isCommander) return emptyList()
        return read(s.accountId)
            .map { it.copy(userId = s.accountId) }
            .sortedByDescending { it.measuredAtMs }
    }

    fun latest(session: Session?): StressMeasurementRecord? = history(session).firstOrNull()

    /** Latest score for a specific source, so Health Connect and camera readings stay separable. */
    fun latestFrom(session: Session?, source: String): StressMeasurementRecord? =
        history(session).firstOrNull { it.source == source }

    private fun read(userId: String): List<StressMeasurementRecord> {
        val raw = store.getString(key(userId)) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun write(userId: String, list: List<StressMeasurementRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        store.putString(key(userId), arr.toString())
    }

    private fun toJson(r: StressMeasurementRecord) = JSONObject().apply {
        put("id", r.id)
        put("measuredAtMs", r.measuredAtMs)
        put("source", r.source)
        put("heartRateBpm", r.heartRateBpm ?: JSONObject.NULL)
        put("rmssdMs", r.rmssdMs ?: JSONObject.NULL)
        put("sdnnMs", r.sdnnMs ?: JSONObject.NULL)
        put("signalQuality", r.signalQuality ?: JSONObject.NULL)
        put("stressScore", r.stressScore ?: JSONObject.NULL)
        put("stressZone", r.stressZone ?: JSONObject.NULL)
        put("confidence", r.confidence)
        put("modelVersion", r.modelVersion)
        put("featureSchemaVersion", r.featureSchemaVersion)
    }

    private fun fromJson(j: JSONObject) = runCatching {
        StressMeasurementRecord(
            id = j.getString("id"),
            userId = "",
            measuredAtMs = j.getLong("measuredAtMs"),
            source = j.getString("source"),
            heartRateBpm = j.optDoubleOrNull("heartRateBpm"),
            rmssdMs = j.optDoubleOrNull("rmssdMs"),
            sdnnMs = j.optDoubleOrNull("sdnnMs"),
            signalQuality = j.optStringOrNull("signalQuality"),
            stressScore = j.optIntOrNull("stressScore"),
            stressZone = j.optStringOrNull("stressZone"),
            confidence = j.optInt("confidence", 0),
            modelVersion = j.optString("modelVersion", ""),
            featureSchemaVersion = j.optInt("featureSchemaVersion", 0)
        )
    }.getOrNull()

    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun key(userId: String) = "stress_measurement_v1_$userId"
}
