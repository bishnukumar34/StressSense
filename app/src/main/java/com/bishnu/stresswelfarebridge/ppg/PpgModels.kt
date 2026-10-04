package com.bishnu.stresswelfarebridge.ppg

/**
 * Pure (Android-free) data types for the camera PPG → HRV pipeline. Keeping these free of Android
 * types lets the whole signal-processing chain be verified with plain JVM unit tests.
 */

/** One acquisition sample extracted from a single usable camera frame (frame itself is discarded). */
data class PpgSample(val timeMs: Double, val red: Double, val green: Double, val blue: Double)

/** A uniformly sampled signal ready for filtering (t[0] is 0). */
data class ResampledSignal(
    val tMs: DoubleArray,
    val signal: DoubleArray,
    val fsHz: Double,
    /** Camera timestamps that were not strictly increasing and therefore had to be dropped. */
    val droppedTimestamps: Int = 0
) {
    val size: Int get() = signal.size
    val durationMs: Double get() = if (size == 0) 0.0 else tMs[size - 1]
}

/** A detected pulse peak, positioned in time; carries the Abed-style condition flags for reporting. */
data class PulsePeak(
    val timeMs: Double,
    val amplitude: Double,
    val prominence: Double,
    val conditions: PeakConditions
)

/**
 * The four independent conditions a candidate must satisfy to be accepted as a pulse peak
 * (Abed et al., "Peak detection algorithm for real-time ECG applications", 2019). A peak is valid
 * only when all four are true, which is what prevents "every local maximum" false positives.
 */
data class PeakConditions(
    val aboveThreshold: Boolean,
    val localMaximum: Boolean,
    val sufficientAmplitude: Boolean,
    val spacedFromPrevious: Boolean
) {
    val allTrue: Boolean get() = aboveThreshold && localMaximum && sufficientAmplitude && spacedFromPrevious
}

/** A single accepted normal-to-normal (NN) interval in milliseconds. */
data class Ibi(val ibiMs: Double)

/** Outcome of artifact rejection over the raw IBI series. */
data class IbiValidation(
    val validNn: List<Double>,
    val totalIntervals: Int,
    val rejectedIntervals: Int,
    val rejectionReasons: Map<String, Int>,
    /**
     * Intervals discarded specifically because they formed a physiologically implausible
     * long/short (or short/long) compensatory pair with their neighbours — the signature of a
     * missed or double-counted beat rather than of real autonomic modulation.
     */
    val suspectPairCount: Int = 0
) {
    val validPercent: Double
        get() = if (totalIntervals <= 0) 0.0 else 100.0 * validNn.size / totalIntervals

    /** Fraction (0..1) of *all* detected intervals that had to be discarded. */
    val rejectedRatio: Double
        get() = if (totalIntervals <= 0) 1.0 else rejectedIntervals.toDouble() / totalIntervals
}

/**
 * Structural statistics of the **validated NN series** itself.
 *
 * This is deliberately separate from the signal statistics (amplitude, frame continuity): the
 * evaluator must be able to refuse a GOOD rating when the beat-to-beat *timing* is incoherent,
 * no matter how clean the raw camera signal looked. Nothing here is derived from RMSSD, so no
 * threshold in this type can be mistaken for a cap on HRV.
 *
 * @param medianIbiMs median validated NN interval (the subject's dominant rhythm).
 * @param ibiCv sample standard deviation of NN ÷ mean NN (dimensionless).
 * @param largeDiffRatio fraction of successive NN differences larger than
 *   [ArtifactRejection.LARGE_DIFF_FRAC] × median NN.
 * @param suspectRatio fraction of *all* detected intervals removed by the local
 *   beat-to-beat plausibility check.
 * @param hrConsistency fraction of NN whose instantaneous rate lies within
 *   [ArtifactRejection.HR_AGREEMENT_FRAC] of the session heart rate.
 * @param rejectionRatio fraction of all detected intervals discarded for any reason.
 */
data class NnSeriesStats(
    val medianIbiMs: Double,
    val ibiCv: Double,
    val largeDiffRatio: Double,
    val suspectRatio: Double,
    val hrConsistency: Double,
    val rejectionRatio: Double
)

/** Heart-rate-variability metrics derived from validated NN intervals. */
data class HrvMetrics(
    val rmssdMs: Double,
    val sdnnMs: Double,
    val meanIbiMs: Double,
    val heartRateBpm: Double,
    val nnCount: Int,
    /**
     * SDSD and pNN50, computed from the same validated NN series.
     *
     * These are required features of the deployed physiological ML model. The camera result
     * is an input to that model, not merely a number shown on a card, so the features it
     * already measures must be carried through.
     */
    val sdsdMs: Double? = null,
    val pnn50Pct: Double? = null
)

/** Coarse quality band shown to the user. */
enum class SignalQuality { GOOD, FAIR, POOR }

/** Result of scoring the signal quality + confidence. */
data class QualityAssessment(val quality: SignalQuality, val confidence: Double, val reason: String)

/**
 * A completed, camera-derived HRV measurement.
 * The source is explicit and never replaces or overrides Health Connect HRV data.
 */
data class CameraHrvResult(
    val rmssdMs: Double,
    val sdnnMs: Double,
    val meanIbiMs: Double,
    val heartRateBpm: Double,
    val measurementStartMs: Long,
    val measurementEndMs: Long,
    val durationSeconds: Double,
    val validIbiCount: Int,
    val rejectedIbiCount: Int,
    val totalIbiCount: Int,
    val validPercent: Double,
    val signalQuality: SignalQuality,
    val confidence: Double,
    val source: String = SOURCE_CAMERA_PPG,
    /** SDSD and pNN50 - required features of the deployed physiological ML model. */
    val sdsdMs: Double? = null,
    val pnn50Pct: Double? = null
) {
    companion object {
        const val SOURCE_CAMERA_PPG = "CAMERA_PPG"
    }
}