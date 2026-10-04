package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.max
import kotlin.math.min

/**
 * Scores the reliability of a completed measurement from **two independent sources**: the raw
 * camera signal, and the validated NN series itself.
 *
 * The historical version took only signal-side inputs (interval survival, beat count, peak
 * amplitude, mean HR). That is exactly how a nonsense result such as RMSSD 274 ms at 95 BPM could
 * be labelled GOOD: enough beats existed, amplitude was fine, frames were continuous and 95 BPM is
 * a perfectly plausible *mean* — none of which says anything about whether the beat-to-beat timing
 * makes sense. Signal statistics and NN-series statistics are therefore scored separately.
 *
 * **Signal side**
 *  - **validRatio** (25 %) — fraction of detected intervals that survived artifact rejection.
 *  - **beatAdequacy** (15 %) — how close the validated NN count is to [FULL_NN] (40). HRV variance
 *    estimators are noisy on short series; below [HrvCalculator.MIN_NN] no result exists at all.
 *  - **amplitudeStability** (10 %) — CV of the accepted peak amplitudes (motion/contact artefact).
 *  - **hrPlausibility** (10 %) — resting mean HR inside 45–110 BPM.
 *
 * **NN-series side — [nnTrust] (40 %, the single largest term)**
 *  - fraction of successive NN differences larger than 30 % of the median RR,
 *  - fraction of intervals the local beat-to-beat plausibility check had to discard,
 *  - NN coefficient of variation (dispersion about the subject's dominant rhythm),
 *  - fraction of NN whose instantaneous rate agrees with the session rate,
 *  - fraction of intervals that survived rejection at all.
 *
 * **Gates.** A weighted average alone is not enough, because a strong term can hide a broken one.
 * GOOD additionally requires the NN series to be structurally credible: [nnTrust] ≥ 0.75, at most
 * 20 % of intervals rejected and at most 15 % removed by the plausibility check. FAIR uses the
 * relaxed bounds 0.50 / 40 % / 35 %. Anything below that is POOR regardless of how clean the
 * camera signal looked.
 *
 * Note what is *absent*: there is no threshold on RMSSD, on SDNN or on any HRV value. The gates
 * are properties of the detection process (how many intervals were discarded, how incoherent the
 * beat-to-beat steps are), not a cap on how much variability a person is allowed to have, so
 * genuinely high HRV still scores GOOD.
 *
 * Bands: confidence ≥ 0.75 → GOOD, ≥ 0.55 → FAIR, otherwise POOR, each additionally subject to
 * the gates above. The caller must only display a numeric HRV for GOOD/FAIR; POOR results are
 * reported as "please retake" with no numbers.
 */
object SignalQualityEvaluator {

    const val FULL_NN = 40

    // -- Signal side --------------------------------------------------------------------------------
    private const val W_VALID = 0.25
    private const val W_BEATS = 0.15
    private const val W_AMP = 0.10
    private const val W_HR = 0.10
    // -- NN-series side: the largest single factor ---------------------------------------------------
    private const val W_NN = 0.40

    private const val GOOD_THRESHOLD = 0.75
    private const val FAIR_THRESHOLD = 0.55

    // -- Hard gates on the NN series (properties of the detection, never of the HRV value) -----------
    private const val GOOD_MIN_NN_TRUST = 0.75
    private const val FAIR_MIN_NN_TRUST = 0.50
    private const val GOOD_MAX_REJECTION = 0.20
    private const val FAIR_MAX_REJECTION = 0.40
    private const val GOOD_MAX_SUSPECT = 0.15
    private const val FAIR_MAX_SUSPECT = 0.35

    /** NN coefficient of variation at or above which the dispersion term contributes nothing. */
    private const val CV_UNRELIABLE = 0.25

    // -- Weights inside [nnTrust] (they sum to 1) ----------------------------------------------------
    private const val T_DIFF = 0.30
    private const val T_SUSPECT = 0.20
    private const val T_SPREAD = 0.15
    private const val T_RATE = 0.15
    private const val T_KEEP = 0.20

    fun evaluate(
        metrics: HrvMetrics,
        validation: IbiValidation,
        amplitudeCv: Double
    ): QualityAssessment {
        val stats = ArtifactRejection.stats(validation)

        val validRatio = if (validation.totalIntervals <= 0) 0.0
        else validation.validNn.size.toDouble() / validation.totalIntervals

        val beatAdequacy = min(1.0, metrics.nnCount.toDouble() / FULL_NN)

        val stability = 1.0 - min(1.0, max(0.0, amplitudeCv))

        val hr = metrics.heartRateBpm
        val hrPlausibility = when {
            hr in 45.0..110.0 -> 1.0
            hr < 35.0 || hr > 150.0 -> 0.0
            else -> {
                // Linear ramp from the acceptable band out to the hard bounds.
                val dist = if (hr < 45.0) 45.0 - hr else hr - 110.0
                val span = if (hr < 45.0) 10.0 else 40.0
                1.0 - min(1.0, dist / span)
            }
        }

        val trust = nnTrust(stats)

        val confidence = clamp01(
            W_VALID * validRatio + W_BEATS * beatAdequacy + W_AMP * stability +
                W_HR * hrPlausibility + W_NN * trust
        )

        // Gates first: a high weighted average can never buy a GOOD/FAIR rating when the NN series
        // itself is structurally untrustworthy.
        val quality = when {
            confidence >= GOOD_THRESHOLD &&
                trust >= GOOD_MIN_NN_TRUST &&
                stats.rejectionRatio <= GOOD_MAX_REJECTION &&
                stats.suspectRatio <= GOOD_MAX_SUSPECT -> SignalQuality.GOOD
            confidence >= FAIR_THRESHOLD &&
                trust >= FAIR_MIN_NN_TRUST &&
                stats.rejectionRatio <= FAIR_MAX_REJECTION &&
                stats.suspectRatio <= FAIR_MAX_SUSPECT -> SignalQuality.FAIR
            else -> SignalQuality.POOR
        }

        val reason = when (quality) {
            SignalQuality.GOOD ->
                "Signal quality good — beat intervals passed the plausibility checks."
            SignalQuality.FAIR ->
                "Usable with caution — ${"%.0f".format(stats.rejectionRatio * 100)}% of beat " +
                    "intervals were discarded as artefacts. Repeat the measurement for a " +
                    "more reliable reading."
            SignalQuality.POOR -> when {
                stats.rejectionRatio > FAIR_MAX_REJECTION ->
                    "Unreliable beat intervals (${"%.0f".format(stats.rejectionRatio * 100)}% of " +
                        "intervals discarded) — poor finger contact or movement. Retake."
                stats.suspectRatio > FAIR_MAX_SUSPECT ->
                    "Beat-to-beat timing was not physiologically consistent — possible movement " +
                        "or double/missed beats. Retake."
                else -> "Unreliable signal — poor finger contact or movement. Retake."
            }
        }
        return QualityAssessment(quality, confidence, reason)
    }

    /**
     * How physiologically coherent the beat-to-beat timing is, in [0, 1]. Computed from the NN
     * series (not from RMSSD, and therefore never a disguised cap on HRV), it is the term that
     * refuses a GOOD rating when an inflated RMSSD is caused by detection artefacts rather than by
     * autonomic modulation.
     */
    fun nnTrust(stats: NnSeriesStats): Double {
        // Successive steps must be physiologically sized relative to the person's own rhythm.
        val diffTrust = clamp01(1.0 - stats.largeDiffRatio)
        // The plausibility check must not have had to throw away a large share of the beats.
        val suspectTrust = clamp01(1.0 - stats.suspectRatio)
        // Dispersion about the dominant rhythm: 0 dispersion is perfectly trustworthy timing, a CV
        // of [CV_UNRELIABLE] (i.e. SDNN ≈ 25 % of mean NN) contributes nothing.
        val spreadTrust = clamp01(1.0 - stats.ibiCv / CV_UNRELIABLE)
        // Most NN must agree with the session heart rate they are supposed to average into.
        val rateTrust = clamp01(stats.hrConsistency)
        // Most detected intervals must have survived rejection at all.
        val keepTrust = clamp01(1.0 - stats.rejectionRatio)

        return clamp01(
            T_DIFF * diffTrust + T_SUSPECT * suspectTrust + T_SPREAD * spreadTrust +
                T_RATE * rateTrust + T_KEEP * keepTrust
        )
    }

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
}
