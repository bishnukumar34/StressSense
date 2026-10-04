package com.bishnu.stresswelfarebridge.ml

/**
 * Turns a raw [StressObservation] plus this person's baseline and history into the canonical
 * [FeatureVector].
 *
 * The extractor is the single place where "what the model sees" is defined. Two properties matter:
 *
 *  1. **Nothing is invented.** A feature that cannot be computed from real data is left as `NaN`
 *     (missing). There is no path in this class that substitutes a plausible-looking constant.
 *  2. **Context is explicit.** Baseline z-scores and temporal deltas are separate columns from the
 *     raw values, so the model can learn "HR is high *and* that is 2 SD above my own normal while I
 *     am well rested and inactive" rather than the much weaker "HR is high".
 *
 * This class is pure and Android-free so the whole mapping is verified by plain JVM unit tests and
 * is shared, unchanged, by training-time replay and on-device inference.
 */
class FeatureExtractor(
    private val baseline: PersonalBaseline,
    private val history: ObservationHistory
) {

    fun extract(observation: StressObservation): FeatureVector {
        val names = FeatureSchema.NAMES
        val v = HashMap<String, Double?>()

        // -- A. Acute physiology, exactly as measured ------------------------------------------------
        v["hr_bpm"] = observation.hrBpm
        v["rmssd_ms"] = observation.rmssdMs
        v["sdnn_ms"] = observation.sdnnMs
        v["mean_nn_ms"] = observation.meanNnMs
        v["sdsd_ms"] = observation.sdsdMs
        v["pnn50_pct"] = observation.pnn50Pct

        // -- B. Deviation from this person's own baseline (Phase 5/6) -------------------------------
        // Null unless the baseline is actually established, so the first few measurements are
        // missing rather than standardised against a single sample.
        v["hr_z"] = baseline.zScore("hr_bpm", observation.hrBpm)
        v["rmssd_z"] = baseline.zScore("rmssd_ms", observation.rmssdMs)
        v["sdnn_z"] = baseline.zScore("sdnn_ms", observation.sdnnMs)
        v["mean_nn_z"] = baseline.zScore("mean_nn_ms", observation.meanNnMs)

        // -- C. Respiration ---------------------------------------------------------------------------
        v["resp_rate_bpm"] = observation.respRateBpm
        v["resp_rate_z"] = baseline.zScore("resp_rate_bpm", observation.respRateBpm)

        // -- D. Physical load context (Phase 15) ------------------------------------------------------
        v["activity_recent_norm"] = observation.activityRecentNorm

        // -- E. Temporal context (Phase 7) -------------------------------------------------------------
        val now = observation.measuredAt
        v["hr_delta_recent"] = history.hrChange(now, observation.hrBpm)
        v["rmssd_delta_recent"] = history.rmssdChange(now, observation.rmssdMs)
        v["hr_slope_recent"] = history.hrSlope(now)
        v["rmssd_slope_recent"] = history.rmssdSlope(now)

        // -- F. Signal quality (also the abstention gate) ---------------------------------------------
        v["ppg_quality"] = observation.ppgQuality
        v["confidence"] = observation.confidence
        v["valid_nn_ratio"] = observation.validNnRatio
        v["artifact_ratio"] = observation.artifactRatio
        v["duration_s"] = observation.durationSeconds

        return FeatureVector(names, DoubleArray(names.size) { i -> v[names[i]] ?: Double.NaN })
    }

    /**
     * Admits this observation into the baseline and history **after** the gates have been checked.
     *
     * Called only for measurements that passed the quality gate, so the personal reference is built
     * from trustworthy data. The current measurement is added to history but never to its own
     * temporal features (the extractor reads history from strictly before `measuredAt`).
     */
    fun commit(observation: StressObservation) {
        val at = observation.measuredAt
        baseline.observe("hr_bpm", observation.hrBpm, at)
        baseline.observe("rmssd_ms", observation.rmssdMs, at)
        baseline.observe("sdnn_ms", observation.sdnnMs, at)
        baseline.observe("mean_nn_ms", observation.meanNnMs, at)
        baseline.observe("sdsd_ms", observation.sdsdMs, at)
        baseline.observe("pnn50_pct", observation.pnn50Pct, at)
        baseline.observe("resp_rate_bpm", observation.respRateBpm, at)
        baseline.observe("activity_recent_norm", observation.activityRecentNorm, at)
        history.add(observation)
    }
}