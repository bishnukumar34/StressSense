package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How usable the camera signal currently is — deliberately **not** the same thing as HRV quality.
 *
 * This answers "is the phone actually looking at a fingertip with a beating pulse?", which is a
 * question about the *sensor*, and it must be answerable before any beat has been detected. Keeping
 * it separate is what stops the app from silently sitting at "Quality 0 %" for a whole capture while
 * the real fault is that the torch is off or the finger is crushing the sensor into saturation.
 */
enum class SignalAcquisitionState {
    /** Nothing but a level: the lens is uncovered, dark, or blocked. */
    NO_SIGNAL,

    /** Something varies, but not with cardiac periodicity — motion, exposure hunting, noise. */
    WEAK_SIGNAL,

    /** A quasi-periodic component in the cardiac band survives: beat detection is worth attempting. */
    USABLE_SIGNAL
}

/**
 * Quantitative acquisition evidence.
 *
 * [acToDc] and [acAmplitude] are expressed in the camera's own 0–255 intensity counts, so they are
 * directly interpretable and are never invented. [snr] and [periodicity] are dimensionless ratios.
 */
data class AcquisitionScore(
    val state: SignalAcquisitionState,
    /** Peak-to-peak amplitude of the band-passed signal, in intensity counts. */
    val acAmplitude: Double,
    /** AC amplitude as a fraction of the DC level — the standard PPG signal-to-background ratio. */
    val acToDc: Double,
    /** Cardiac-band RMS divided by out-of-band RMS. */
    val snr: Double,
    /** Peak of the normalised autocorrelation at a physiologically plausible IBI. */
    val periodicity: Double,
    /** Fraction of raw samples at the very top or very bottom of the range (clipping). */
    val saturationFraction: Double,
    /** Combined 0..1 acquisition score, used for region/channel ranking. */
    val score: Double
)


/**
 * Signal-acquisition analysis, run **before** peak detection.
 *
 * Design notes:
 *  - The cardiac component of a fingertip PPG is small (typically 0.2–5 % of the DC level), so the
 *    samples are judged after DC removal and band-pass; judging the raw level alone would rate a
 *    perfectly good measurement as "no signal" purely because it is bright.
 *  - Periodicity is measured by autocorrelation over a physiologically plausible IBI range rather
 *    than by peak detection, so it works even on a trace too noisy to detect beats in — which is
 *    exactly the situation that needs the answer.
 *  - Saturation is called out separately because a blown-out frame has *large* variance while
 *    carrying *no* usable information; without this term a saturated capture can look "active".
 */
object PpgAcquisition {

    /**
     * Minimum cardiac-band SNR for a signal to be considered more than noise.
     *
     * This is deliberately modest. The out-of-band residual of a fingertip PPG is dominated by
     * *respiratory drift*, which the band-pass removes and which does not invalidate the pulse that
     * survives beside it; treating all of it as "noise" would reject perfectly good captures whose
     * SNR sits near 1. Periodicity and AC amplitude are the direct evidence that a pulse is present,
     * and they carry the decision — this threshold only removes the case where there is no
     * cardiac-band content at all.
     */
    const val MIN_SNR = 0.5

    /** SNR at which the signal term of the combined score saturates. */
    const val GOOD_SNR = 2.0

    /** Autocorrelation peak needed before beats are worth attempting. */
    const val MIN_PERIODICITY = 0.30

    const val GOOD_PERIODICITY = 0.50

    /** Minimum AC amplitude (counts) that can survive the 8-bit quantisation of the ROI mean. */
    const val MIN_AC_AMPLITUDE = 0.05

    /** Saturation fraction above which the frames are clipped and must not be trusted. */
    const val MAX_SATURATION_FRACTION = 0.25

    /**
     * Minimum DC level (intensity counts) for an acquisition to be judged on its AC content at all.
     *
     * Below this the sensor receives so little reflected light that the AC/DC ratio is dominated by
     * read noise, and a dark noisy patch can otherwise produce a convincing-looking modulation
     * figure. 8/255 is where a fingertip lit by the torch sits comfortably above.
     */
    const val MIN_DC_LEVEL = 8.0

    /** Raw sample values at or above this are considered clipped at the top of the range. */
    const val SATURATION_HIGH = 252.0

    /** Raw sample values at or below this are considered crushed at the bottom. */
    const val SATURATION_LOW = 3.0

    /** Resting-plus-moderate heart-rate range used for the periodicity search (IBI, seconds). */
    const val MIN_IBI_SEC = 0.30
    const val MAX_IBI_SEC = 2.00

    /** Reported SNR ceiling used when the out-of-band residual is numerically zero. */
    const val MAX_SNR = 50.0

    /**
     * Scores a resampled, filtered acquisition.
     *
     * @param raw uniformly sampled raw intensity (DC still present)
     * @param filtered the same samples after DC removal + band-pass
     * @param fsHz sample rate of both arrays
     */
    fun score(raw: DoubleArray, filtered: DoubleArray, fsHz: Double): AcquisitionScore {
        if (raw.size < 8 || filtered.size != raw.size || fsHz <= 0.0) {
            return AcquisitionScore(SignalAcquisitionState.NO_SIGNAL, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        }

        val saturation = saturationFraction(raw)
        val acAmplitude = peakToPeak(filtered)
        val dc = mean(raw)
        val acToDc = if (dc > 1e-6) acAmplitude / dc else 0.0

        val cardiacRms = rms(filtered)

        // The noise floor is the part of the residual that survives *the same band-pass* the signal
        // did. Two things must be excluded, and simply taking rms(raw - filtered) excludes neither:
        //  - the DC level, which would otherwise dominate the RMS completely, and
        //  - residual slow illumination drift. A 25-count, 0.05 Hz exposure wobble leaves a large
        //    low-frequency remainder in the residual (including filtfilt edge transients), and
        //    counting that as "noise" drove genuinely clean captures to SNR ≈ 0.03. Slow drift does
        //    not invalidate a pulse that survives beside it, so it must not be scored as noise.
        // What remains after re-filtering is the in-band content the band-pass could not remove:
        // sensor noise and pulse harmonics. That is the floor this metric is meant to measure.
        val residual = DoubleArray(raw.size) { raw[it] - filtered[it] }
        val residualMean = mean(residual)
        for (i in residual.indices) residual[i] -= residualMean
        val inBandResidual = bandPassIfPossible(residual, fsHz)
        val noiseRms = rms(inBandResidual)
        val snr = when {
            noiseRms > 1e-9 -> cardiacRms / noiseRms
            cardiacRms > 1e-9 -> MAX_SNR
            else -> 0.0
        }
        val periodicity = autocorrelationPeak(filtered, fsHz)

        return AcquisitionScore(
            state = classify(snr, periodicity, acAmplitude, saturation, dc),
            acAmplitude = acAmplitude,
            acToDc = acToDc,
            snr = snr,
            periodicity = periodicity,
            saturationFraction = saturation,
            score = combinedScore(snr, periodicity, acAmplitude, saturation, acToDc)
        )
    }

    /** The state decision, separated so it can be unit-tested against hand-picked numbers. */
    fun classify(
        snr: Double,
        periodicity: Double,
        acAmplitude: Double,
        saturationFraction: Double,
        dcLevel: Double = 100.0
    ): SignalAcquisitionState = when {
        // A clipped frame has variance but no information, so clipping is tested before SNR.
        saturationFraction > MAX_SATURATION_FRACTION -> SignalAcquisitionState.NO_SIGNAL
        // Too little light for the AC/DC ratio to mean anything.
        dcLevel < MIN_DC_LEVEL -> SignalAcquisitionState.NO_SIGNAL
        acAmplitude < MIN_AC_AMPLITUDE -> SignalAcquisitionState.NO_SIGNAL
        snr < MIN_SNR -> SignalAcquisitionState.WEAK_SIGNAL
        periodicity < MIN_PERIODICITY -> SignalAcquisitionState.WEAK_SIGNAL
        else -> SignalAcquisitionState.USABLE_SIGNAL
    }

    /** Exposed so the weighting can be asserted directly against real-world failure modes. */
    internal fun combinedScoreForTest(
        snr: Double,
        periodicity: Double,
        ac: Double,
        saturation: Double,
        acToDc: Double
    ): Double = combinedScore(snr, periodicity, ac, saturation, acToDc)

    private fun combinedScore(
        snr: Double,
        periodicity: Double,
        acAmplitude: Double,
        saturation: Double,
        acToDc: Double
    ): Double {
        val snrTerm = (snr / GOOD_SNR).coerceIn(0.0, 1.0)
        val periodTerm = (periodicity / GOOD_PERIODICITY).coerceIn(0.0, 1.0)
        val amplitudeTerm = (acAmplitude / (MIN_AC_AMPLITUDE * 6.0)).coerceIn(0.0, 1.0)
        val modulationTerm = (acToDc / 0.05).coerceIn(0.0, 1.0)
        val clipTerm = (1.0 - saturation / MAX_SATURATION_FRACTION).coerceIn(0.0, 1.0)
        val total = 0.34 * snrTerm + 0.28 * periodTerm + 0.18 * amplitudeTerm +
            0.10 * modulationTerm + 0.10 * clipTerm
        // A clipped window must never win region/channel selection on its inflated variance.
        return if (saturation > MAX_SATURATION_FRACTION) total * 0.2 else total
    }

    /**
     * Applies the same cardiac band-pass used for the signal, falling back to the input when the
     * array is too short to filter. Keeps the noise estimate comparable to the signal it is
     * compared against.
     */
    private fun bandPassIfPossible(x: DoubleArray, fsHz: Double): DoubleArray {
        if (x.size < 8 || fsHz <= 0.0) return x
        val filtered = PpgSignalProcessor.bandPass(PpgSignalProcessor.detrend(x), fsHz)
        return filtered
    }

    /** Fraction of raw samples pinned at either end of the intensity range. */
    fun saturationFraction(raw: DoubleArray): Double {
        if (raw.isEmpty()) return 1.0
        var clipped = 0
        for (v in raw) {
            if (v >= SATURATION_HIGH || v <= SATURATION_LOW) clipped++
        }
        return clipped.toDouble() / raw.size
    }

    fun peakToPeak(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (v in x) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        return maxV - minV
    }

    fun rms(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var sum = 0.0
        for (v in x) sum += v * v
        return sqrt(sum / x.size)
    }

    private fun mean(x: DoubleArray): Double {
        var sum = 0.0
        for (v in x) sum += v
        return sum / x.size
    }

    /**
     * Normalised autocorrelation peak searched over a physiologically plausible IBI range.
     *
     * The search excludes lag 0, so a slow drift cannot masquerade as a heartbeat, and it is
     * damped by how many cycles the window spans, because a correlation peak measured across many
     * beats is more trustworthy than one measured across two.
     */
    fun autocorrelationPeak(x: DoubleArray, fsHz: Double): Double {
        if (x.size < 16 || fsHz <= 0.0) return 0.0
        val minLag = (MIN_IBI_SEC * fsHz).toInt().coerceAtLeast(1)
        val maxLag = (MAX_IBI_SEC * fsHz).toInt().coerceAtMost(x.size - 1)
        if (maxLag <= minLag) return 0.0

        val m = mean(x)
        var zeroEnergy = 0.0
        for (v in x) {
            val d = v - m
            zeroEnergy += d * d
        }
        if (zeroEnergy <= 1e-12) return 0.0

        var best = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            val limit = x.size - lag
            for (i in 0 until limit) sum += (x[i] - m) * (x[i + lag] - m)
            val r = sum / zeroEnergy
            if (r > best) best = r
        }
        if (best <= 0.0) return 0.0

        val ibiSeconds = (minLag + maxLag) / (2.0 * fsHz)
        val cycles = (x.size / fsHz) / ibiSeconds
        val confidence = (cycles / 6.0).coerceIn(0.30, 1.0)
        return (best * confidence).coerceIn(0.0, 1.0)
    }

    /**
     * Median absolute deviation, in counts.
     *
     * Used for frame-level outlier rejection: one corrupted frame (glare, a partially delivered
     * buffer) must not be allowed to drag the resampled trace, and a median/MAD rule ignores
     * outliers instead of being dragged by them the way a mean/sigma rule would be.
     */
    fun medianAbsoluteDeviation(x: DoubleArray): Double {
        if (x.size < 3) return 0.0
        val deviations = DoubleArray(x.size) { abs(x[it] - median(x)) }
        deviations.sort()
        return median(deviations)
    }

    /** Median of a copy, leaving the caller's array untouched. */
    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val sorted = x.copyOf()
        sorted.sort()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

