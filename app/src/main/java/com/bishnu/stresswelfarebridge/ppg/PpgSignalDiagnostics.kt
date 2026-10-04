package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.abs

/**
 * Where a camera-PPG measurement currently stands, stage by stage, *before* any HRV number exists.
 *
 * The point of this type is diagnosis, not relaxation: when a measurement fails we must be able to
 * say whether the camera never delivered frames, delivered frames with no usable level, delivered a
 * level with no pulsatile variation, or delivered a clean pulse the detector then missed. Each stage
 * is a property of the *signal*; none of them inspects RMSSD, SDNN or any other HRV value, so no
 * stage can be mistaken for a cap on how variable a person is allowed to be.
 */
enum class SignalStage(val label: String, val remedy: String) {
    IDLE("Idle", "Start the measurement."),

    NO_FRAMES(
        "No camera frames",
        "The analysis stream delivered no decodable frame. Check no other app is holding the camera."
    ),

    TOO_FEW_FRAMES(
        "Too few frames",
        "Frames are arriving but the buffer is not full yet — keep the finger still for a moment."
    ),

    SIGNAL_TOO_DARK(
        "Signal too dark",
        "Almost no light reaches the sensor. Check the torch is lit and the fingertip covers the lens."
    ),

    SIGNAL_SATURATED(
        "Signal saturated",
        "The image is blown out to white: the finger is pressed too hard or sits too close to the " +
            "torch. Ease the pressure until the level drops below 250."
    ),

    NO_PULSATILE_VARIATION(
        "No pulsatile variation",
        "Frames arrive at a usable brightness but the intensity is flat: there is no pulse to " +
            "measure. Re-seat the fingertip over the lens and torch and keep still."
    ),

    NO_BEATS_DETECTED(
        "Pulse present but no beats detected",
        "The waveform varies but the peak detector accepted nothing; the filtered trace is in the " +
            "device log for inspection."
    ),

    TOO_FEW_BEATS(
        "Too few valid beat intervals",
        "Only a handful of intervals survived artefact rejection; HRV needs a longer clean window."
    ),

    READY("Signal ready", "Pulsatile signal acquired; the measurement window is being collected.")
}

/**
 * Pure stage classifier plus the raw statistics that justify it. Kept Android-free so the exact
 * verdict of every stage can be unit-tested.
 *
 * Thresholds are properties of the *sensor*, not of the heart:
 *  - [DARK_LEVEL] / [SATURATED_LEVEL] bracket the usable part of the 8-bit intensity range;
 *  - [MIN_PULSATILE_STD] is the smallest band-passed amplitude that can still clear the
 *    quantisation floor of the 8-bit ROI mean — below it the trace carries no recoverable pulse.
 */
object PpgSignalDiagnostics {

    const val DARK_LEVEL = 12.0
    const val SATURATED_LEVEL = 252.0
    const val MIN_PULSATILE_STD = 0.05
    const val MIN_FRAMES_FOR_STAGE = 10

    /** Everything the device log and the UI need to explain a failure without inventing numbers. */
    data class Report(
        val stage: SignalStage,
        val frames: Int,
        val samples: Int,
        val levelMean: Double,
        val levelMin: Double,
        val levelMax: Double,
        val levelStd: Double,
        val rawStd: Double,
        val filteredStd: Double,
        val peaks: Int,
        val validNn: Int
    ) {
        /** One-line verdict for the screen: what failed, and what to do about it. */
        fun headline(): String = "${stage.label} — ${stage.remedy}"

        /** Compact numeric evidence for logcat. */
        fun detail(): String =
            "stage=${stage.name} frames=$frames samples=$samples " +
                "level[mean=${f(levelMean, 1)},min=${f(levelMin, 1)},max=${f(levelMax, 1)}," +
                "std=${f(levelStd, 2)}] rawStd=${f(rawStd, 3)} " +
                "filteredStd=${f(filteredStd, 3)} peaks=$peaks validNN=$validNn"
    }

    /**
     * Classifies the current state. The order matters: stages are tested from the earliest failure
     * to the latest, so the reported stage is always the *first* thing that is wrong.
     */
    fun classify(
        frames: Int,
        samples: Int,
        levelMean: Double,
        levelStd: Double = 0.0,
        rawStd: Double = 0.0,
        filteredStd: Double = 0.0,
        peaks: Int = 0,
        validNn: Int = 0
    ): SignalStage = when {
        frames <= 0 -> SignalStage.NO_FRAMES
        samples < MIN_FRAMES_FOR_STAGE -> SignalStage.TOO_FEW_FRAMES
        levelMean <= DARK_LEVEL -> SignalStage.SIGNAL_TOO_DARK
        levelMean >= SATURATED_LEVEL -> SignalStage.SIGNAL_SATURATED
        filteredStd < MIN_PULSATILE_STD || rawStd < MIN_PULSATILE_STD ->
            SignalStage.NO_PULSATILE_VARIATION
        peaks <= 0 -> SignalStage.NO_BEATS_DETECTED
        validNn < HrvCalculator.MIN_NN -> SignalStage.TOO_FEW_BEATS
        else -> SignalStage.READY
    }

    /** Convenience overload that builds the full [Report] in one call. */
    fun report(
        frames: Int,
        samples: Int,
        levelMean: Double,
        levelMin: Double = 0.0,
        levelMax: Double = 0.0,
        levelStd: Double = 0.0,
        rawStd: Double = 0.0,
        filteredStd: Double = 0.0,
        peaks: Int = 0,
        validNn: Int = 0
    ): Report = Report(
        stage = classify(frames, samples, levelMean, levelStd, rawStd, filteredStd, peaks, validNn),
        frames = frames,
        samples = samples,
        levelMean = levelMean,
        levelMin = levelMin,
        levelMax = levelMax,
        levelStd = levelStd,
        rawStd = rawStd,
        filteredStd = filteredStd,
        peaks = peaks,
        validNn = validNn
    )

    /** Locale-stable fixed-point formatting (this file stays free of java.util imports). */
    private fun f(v: Double, decimals: Int): String {
        if (v.isNaN() || abs(v) == Double.POSITIVE_INFINITY) return "--"
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return (kotlin.math.round(v * scale) / scale).toString()
    }
}

