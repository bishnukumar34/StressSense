package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Filtering pipeline for a raw camera-PPG signal.
 *
 * Physiological rationale for the passband 0.7–3.5 Hz: a resting adult heart rate lies roughly in
 * 42–210 BPM (0.7–3.5 Hz). Restricting the passband to this range removes slow baseline drift
 * (respiration, finger pressure changes, torch warm-up) below 0.7 Hz and high-frequency sensor
 * noise above 3.5 Hz, while preserving the systolic upstroke and the inter-beat *timing* required
 * for reliable IBI detection. The high cut at 3.5 Hz is above the third pulse harmonic of a normal
 * rate, so the pulse morphology needed for peak detection is retained.
 *
 * Implementation: a 2nd-order Butterworth band-pass formed by cascading a 2nd-order low-pass and a
 * 2nd-order high-pass biquad (RBJ cookbook design), applied forward and backward (zero-phase
 * "filtfilt") so no group delay shifts the beat times. Reflection padding avoids edge transients.
 */
object PpgSignalProcessor {

    const val PHYSIO_LOW_HZ = 0.7   // 42 BPM
    const val PHYSIO_HIGH_HZ = 3.5  // 210 BPM
    const val ANALYSIS_HZ = 50.0    // uniform rate for analysis

    /** Removes DC offset and linear baseline drift via least-squares linear detrending. */
    fun detrend(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return x.copyOf()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val xi = i.toDouble()
            sx += xi; sy += x[i]; sxx += xi * xi; sxy += xi * x[i]
        }
        val denom = n * sxx - sx * sx
        val slope = if (abs(denom) < 1e-12) 0.0 else (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        return DoubleArray(n) { i -> x[i] - (slope * i + intercept) }
    }

    /** Zero-phase Butterworth band-pass; returns a copy when the input is too short to filter. */
    fun bandPass(
        x: DoubleArray,
        fs: Double,
        lowHz: Double = PHYSIO_LOW_HZ,
        highHz: Double = PHYSIO_HIGH_HZ
    ): DoubleArray {
        if (x.size < 8 || fs <= 0.0) return x.copyOf()
        val nyquist = fs / 2.0
        val high = highHz.coerceAtMost(nyquist * 0.98)
        val sections = listOf(Biquad.lowPass(high, fs), Biquad.highPass(lowHz, fs))
        val pad = (fs * 0.5).toInt().coerceIn(1, x.size - 1)
        return filtFilt(sections, x, pad)
    }

    /** True when the highest frequency component (>= 3rd harmonic of the rate) fits under Nyquist. */
    fun isHighRateDistorted(fs: Double): Boolean = PHYSIO_HIGH_HZ * 3.0 > fs / 2.0

    /** Reflection padding so forward-backward filtering does not create edge artefacts. */
    private fun reflectPad(x: DoubleArray, pad: Int): DoubleArray {
        val n = x.size
        val p = pad.coerceAtMost(n - 1).coerceAtLeast(1)
        val out = DoubleArray(n + 2 * p)
        for (i in 0 until p) out[i] = x[p - i]
        for (i in 0 until n) out[p + i] = x[i]
        for (i in 0 until p) out[p + n + i] = x[n - 2 - i]
        return out
    }

    private fun filtFilt(sections: List<Biquad>, x: DoubleArray, pad: Int): DoubleArray {
        val padded = reflectPad(x, pad)
        var y = padded
        for (s in sections) y = s.forward(y)
        y = y.reversedArray()
        for (s in sections) y = s.forward(y)
        y = y.reversedArray()
        return y.copyOfRange(pad, pad + x.size)
    }

    /**
     * Linear-interpolates an irregularly timestamped acquisition onto a uniform grid, which the
     * IIR filters and peak detector assume. Returns null when there is too little span/data.
     *
     * Timestamps are sanitised first: real devices occasionally deliver a repeated or very slightly
     * out-of-order `ImageProxy.imageInfo.timestamp`. A non-monotonic time axis inverts the
     * interpolation below and manufactures a phantom beat (a spurious short IBI followed by a long
     * one), so non-increasing samples are dropped here. Only the scalar time/value pairs are
     * touched — raw frames are never present at this point.
     */
    fun resample(timesMs: DoubleArray, values: DoubleArray, outputHz: Double = ANALYSIS_HZ): ResampledSignal? {
        val n = timesMs.size
        if (n < 4 || values.size != n) return null

        // Strictly increasing prefix of the acquisition.
        val ts = DoubleArray(n)
        val vs = DoubleArray(n)
        var count = 0
        for (i in 0 until n) {
            if (count == 0 || timesMs[i] > ts[count - 1]) {
                ts[count] = timesMs[i]
                vs[count] = values[i]
                count++
            }
        }
        val dropped = n - count
        if (count < 4) return null

        val t0 = ts[0]
        val spanMs = ts[count - 1] - t0
        if (spanMs < 1000.0) return null
        val step = 1000.0 / outputHz
        val gridCount = (spanMs / step).toInt() + 1
        if (gridCount < 8) return null
        val tOut = DoubleArray(gridCount) { it * step }
        val yOut = DoubleArray(gridCount)
        var j = 0
        for (i in 0 until gridCount) {
            val t = t0 + tOut[i]
            while (j < count - 2 && ts[j + 1] < t) j++
            val t1 = ts[j]
            val t2 = ts[j + 1]
            val frac = if (t2 > t1) ((t - t1) / (t2 - t1)).coerceIn(0.0, 1.0) else 0.0
            val v1 = vs[j]
            yOut[i] = v1 + frac * (vs[j + 1] - v1)
        }
        return ResampledSignal(tOut, yOut, outputHz, dropped)
    }
}

/** Direct-form-I biquad with normalized (a0 = 1) coefficients. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    fun forward(x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            y[i] = yi
        }
        return y
    }

    companion object {
        private const val Q = 0.7071067811865476 // 1/sqrt(2): Butterworth (maximally flat)

        fun lowPass(fc: Double, fs: Double): Biquad {
            val w0 = 2.0 * PI * fc / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            return Biquad(
                (1.0 - cosW) / 2.0 / a0,
                (1.0 - cosW) / a0,
                (1.0 - cosW) / 2.0 / a0,
                (-2.0 * cosW) / a0,
                (1.0 - alpha) / a0
            )
        }

        fun highPass(fc: Double, fs: Double): Biquad {
            val w0 = 2.0 * PI * fc / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            return Biquad(
                (1.0 + cosW) / 2.0 / a0,
                -(1.0 + cosW) / a0,
                (1.0 + cosW) / 2.0 / a0,
                (-2.0 * cosW) / a0,
                (1.0 - alpha) / a0
            )
        }
    }
}