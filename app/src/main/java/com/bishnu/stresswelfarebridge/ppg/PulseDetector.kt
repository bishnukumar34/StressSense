package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.abs

/**
 * Adaptive threshold pulse-peak detector for a filtered, zero-mean PPG signal.
 *
 * Implementation follows the four-condition peak gating of Abed et al. (2019), "Peak detection
 * algorithm for real-time ECG applications": a candidate is accepted only when it clears an
 * adaptive threshold (h1), is a genuine local maximum (h2), has enough local swing (h3), and is
 * separated from the previous accepted beat by a physiologically plausible distance (h4).
 * The adaptive level follows the Pan–Tompkins signal/noise peak tracking so the threshold follows
 * signal amplitude changes instead of relying on a single hardcoded cutoff.
 */
object PulseDetector {

    /** Fastest interval accepted as a beat: 200 BPM. */
    const val MIN_IBI_MS = 300.0
    /** Slowest interval accepted as a beat: 30 BPM. */
    const val MAX_IBI_MS = 2000.0
    /** Envelope window for local min/max estimation (seconds). */
    private const val ENVELOPE_SEC = 2.0
    /** Fraction of the local swing that must be reached for h3. */
    private const val AMPLITUDE_FACTOR = 0.4
    /**
     * Floor (seconds) of the h2 local-maximum half-window, used before any RR estimate exists.
     * This is the historical fixed window; it is far narrower than a RR interval, which is why it
     * is only a *floor* for the window computed inside [detectPeaks].
     */
    private const val LOCAL_MAX_SEC = 0.12
    /** Ceiling (ms) of the h2 local-maximum half-window so it can never swallow the next beat. */
    private const val LOCAL_MAX_CEILING_MS = 500.0
    /**
     * Fraction of the tracked RR used as the h2 local-maximum half-window. At 0.5 × RR the window
     * always excludes the neighbouring systolic peaks (0.5 < 1) yet is wide enough that a dicrotic
     * wave sitting 300–450 ms after the systolic peak finds a *higher* systolic peak inside its
     * window and is therefore rejected instead of being counted as a second beat.
     */
    private const val LOCAL_MAX_RR_FRAC = 0.50
    /**
     * Minimum accepted-candidate amplitude as a fraction of the previous accepted peak, applied
     * **only while no rhythm estimate exists yet** (the first interval). Without an RR estimate the
     * h2 window falls back to its short floor and cannot tell a dicrotic wave apart from a beat by
     * spacing alone; comparing heights does. A systolic peak is ~1.0 of the local swing while a
     * dicrotic wave is ~0.3–0.6, so 0.7 separates them, and once one clean interval has been
     * measured the RR-scaled window takes over and this rule is dropped.
     */
    private const val BOOTSTRAP_PEAK_RATIO = 0.70
    /** Asymmetric (Pan–Tompkins style) adaptive threshold factors. */
    private const val SIGNAL_ALPHA = 0.125
    private const val NOISE_ALPHA = 0.125
    /**
     * Fraction of the tracked signal/noise peak gap that forms the adaptive acceptance level —
     * the Pan–Tompkins "threshold = noise + ¼(signal − noise)" rule. This constant was previously
     * declared but never referenced; the detector only used the local-envelope clause, so the
     * threshold did not track the signal the way the class documentation claimed.
     */
    private const val THRESHOLD_FRACTION = 0.25
    /** Accepted peaks required before the running signal-peak level is trusted for gating. */
    private const val PEAKS_BEFORE_TRACKING = 3
    /**
     * How far above the tracked typical peak the local envelope may reach before it is treated as a
     * motion/contact transient. Without this cap a single spike inflates the envelope swing and the
     * 50 %-of-swing gate then suppresses several genuine beats in a row — manufacturing a missed
     * beat, i.e. a doubled IBI, followed by a short recovery IBI.
     */
    private const val SPIKE_HEADROOM = 1.75

    /**
     * State that persists across streaming analysis passes so the adaptive threshold, the last
     * accepted peak time, the tracked rhythm, and the resulting IBI sequence accumulate correctly
     * during a measurement. Reset when a new measurement starts.
     */
    /**
     * Development-only bookkeeping explaining *why* each local maximum was or was not accepted.
     *
     * This exists because "the waveform looks fine but no beats come out" is otherwise
     * unanswerable: without per-condition counters the only visible symptom is an empty peak list.
     * Nothing here participates in detection — the counters are written beside the decision, never
     * into it — and they are off unless [State.collectDiagnostics] is set.
     */
    class DetectionDiagnostics {
        /** Samples that satisfied h2 (genuine local maximum) and were therefore considered. */
        var candidates = 0; private set
        var accepted = 0; private set

        /** Condition-by-condition rejection counts (a candidate can fail more than one). */
        var rejectedThreshold = 0; private set
        var rejectedLocalMax = 0; private set
        var rejectedAmplitude = 0; private set
        var rejectedSpacing = 0; private set

        /** Smallest / largest local swing and candidate amplitude seen, in signal units. */
        var minSwing = Double.MAX_VALUE; private set
        var maxSwing = 0.0; private set
        var minCandidateAmplitude = Double.MAX_VALUE; private set
        var maxCandidateAmplitude = 0.0; private set

        /** Largest absolute sample value in the whole analysed trace. */
        var peakToPeak = 0.0; private set

        /** Up to [MAX_EXAMPLES] sample times per rejection reason, for the device log. */
        val thresholdExamples = ArrayList<Double>()
        val amplitudeExamples = ArrayList<Double>()
        val spacingExamples = ArrayList<Double>()

        fun reset() {
            candidates = 0
            accepted = 0
            rejectedThreshold = 0
            rejectedLocalMax = 0
            rejectedAmplitude = 0
            rejectedSpacing = 0
            minSwing = Double.MAX_VALUE
            maxSwing = 0.0
            minCandidateAmplitude = Double.MAX_VALUE
            maxCandidateAmplitude = 0.0
            peakToPeak = 0.0
            thresholdExamples.clear()
            amplitudeExamples.clear()
            spacingExamples.clear()
        }

        private fun ArrayList<Double>.addExample(t: Double) {
            if (size < MAX_EXAMPLES) add(t)
        }

        internal fun countCandidate(tMs: Double, swing: Double, amplitude: Double) {
            candidates++
            if (swing < minSwing) minSwing = swing
            if (swing > maxSwing) maxSwing = swing
            if (amplitude < minCandidateAmplitude) minCandidateAmplitude = amplitude
            if (amplitude > maxCandidateAmplitude) maxCandidateAmplitude = amplitude
            lastCandidateTimeMs = tMs
        }

        internal fun countReject(reason: Int, tMs: Double) {
            when (reason) {
                REASON_THRESHOLD -> { rejectedThreshold++; thresholdExamples.addExample(tMs) }
                REASON_LOCAL_MAX -> rejectedLocalMax++
                REASON_AMPLITUDE -> { rejectedAmplitude++; amplitudeExamples.addExample(tMs) }
                REASON_SPACING -> { rejectedSpacing++; spacingExamples.addExample(tMs) }
            }
        }

        internal fun countAccepted() {
            accepted++
        }

        internal var lastCandidateTimeMs = 0.0

        /** Largest absolute sample value seen in the analysed trace, filled in by the detector. */
        internal fun markAmplitudeExtent(absMax: Double) {
            peakToPeak = 2.0 * absMax
        }

        fun summary(): String =
            "candidates=$candidates accepted=$accepted " +
                "rej[threshold=$rejectedThreshold,localMax=$rejectedLocalMax," +
                "amplitude=$rejectedAmplitude,spacing=$rejectedSpacing] " +
                "swing[min=${fmt(minSwing)},max=${fmt(maxSwing)}] " +
                "amp[min=${fmt(minCandidateAmplitude)},max=${fmt(maxCandidateAmplitude)}] " +
                "peakToPeak=${fmt(peakToPeak)}"

        private fun fmt(v: Double): String =
            if (v.isNaN() || v == Double.MAX_VALUE || v == -Double.MAX_VALUE) "--"
            else String.format(java.util.Locale.US, "%.4g", v)

        companion object {
            const val MAX_EXAMPLES = 8
            internal const val REASON_THRESHOLD = 0
            internal const val REASON_LOCAL_MAX = 1
            internal const val REASON_AMPLITUDE = 2
            internal const val REASON_SPACING = 3
        }
    }

    class State {
        var signalPeakLevel = 0.0
        var noisePeakLevel = 0.0
        var lastPeakTimeMs = Double.NaN
        var lastPeakAmplitude = 0.0
        /**
         * Rolling median of the most recent accepted intervals — the detector's estimate of the
         * subject's current rhythm. Drives the RR-scaled local-maximum window and is exposed for
         * development logging. [Double.NaN] until at least two beats have been accepted.
         */
        var recentRrMs = Double.NaN
        val peaks = mutableListOf<PulsePeak>()

        /**
         * Enables the development-only rejection counters in [diagnostics]. Left off in normal use
         * so the detection loop stays free of bookkeeping; it changes no detection outcome either
         * way, because the counters are written beside each decision and never into it.
         */
        var collectDiagnostics = false

        /** Populated only while [collectDiagnostics] is true. */
        val diagnostics = DetectionDiagnostics()

        private val recentIntervals = mutableListOf<Double>()

        fun reset() {
            signalPeakLevel = 0.0
            noisePeakLevel = 0.0
            lastPeakTimeMs = Double.NaN
            lastPeakAmplitude = 0.0
            recentRrMs = Double.NaN
            diagnostics.reset()
            recentIntervals.clear()
            peaks.clear()
        }

        fun pushInterval(ms: Double) {
            recentIntervals.add(ms)
            while (recentIntervals.size > ROLLING_RR_COUNT) recentIntervals.removeAt(0)
            recentRrMs = ArtifactRejection.medianOf(recentIntervals)
        }

        companion object {
            /** Number of recent intervals used for the rolling rhythm estimate. */
            const val ROLLING_RR_COUNT = 7
        }
    }

    /**
     * Detects peaks on the full signal at once (offline/testing mode). Appends into [state].
     * [dtMs] is the fixed sample period of the uniformly resampled signal.
     */
    fun detectPeaks(signal: DoubleArray, dtMs: Double, state: State): List<PulsePeak> {
        val n = signal.size
        if (n < 16 || dtMs <= 0.0) return emptyList()
        val envWin = ((ENVELOPE_SEC * 1000.0) / dtMs).toInt().coerceAtLeast(4)
        val minGap = (MIN_IBI_MS / dtMs).toInt()

        // h2 half-window bounds, in samples: fixed floor before the rhythm is known, then a
        // fraction of the rolling RR (never above [LOCAL_MAX_CEILING_MS]). The effective window is
        // evaluated per sample because recentRrMs only becomes available *during* this pass.
        val floorWin = ((LOCAL_MAX_SEC * 1000.0) / dtMs).toInt().coerceAtLeast(1)
        val ceilingWin = ((LOCAL_MAX_CEILING_MS) / dtMs).toInt().coerceAtLeast(floorWin)

        // Development-only: null during normal use, so no bookkeeping runs on the hot path.
        val diag = if (state.collectDiagnostics) state.diagnostics else null
        var absMax = 0.0

        for (i in 0 until n) {
            val localWin: Int = if (state.recentRrMs.isNaN()) {
                floorWin
            } else {
                ((LOCAL_MAX_RR_FRAC * state.recentRrMs) / dtMs).toInt().coerceIn(floorWin, ceilingWin)
            }
            val lo = (i - envWin).coerceAtLeast(0)
            val hi = (i + envWin).coerceAtMost(n)
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (k in lo until hi) {
                val v = signal[k]
                if (v < minV) minV = v
                if (v > maxV) maxV = v
                val a = abs(v)
                if (a > absMax) absMax = a
            }
            // Cap the upper envelope by the tracked typical peak so one motion/contact transient
            // cannot inflate the swing and suppress several genuine beats in a row.
            val upper = if (state.peaks.size >= PEAKS_BEFORE_TRACKING && state.signalPeakLevel > 0.0) {
                minOf(maxV, state.signalPeakLevel * SPIKE_HEADROOM)
            } else {
                maxV
            }
            val swing = upper - minV
            if (swing <= 1e-12) continue
            val amplitude = signal[i] - minV
            val tMs = i * dtMs

            // h2: strict local maximum within +/-localWin samples (RR-scaled, see above).
            val lLo = (i - localWin).coerceAtLeast(0)
            val lHi = (i + localWin).coerceAtMost(n - 1)
            var isMax = true
            for (k in lLo until lHi) {
                if (k == i) continue
                if (signal[k] >= signal[i]) { isMax = false; break }
            }
            if (!isMax) {
                if (diag != null) {
                    diag.countReject(DetectionDiagnostics.REASON_LOCAL_MAX, tMs)
                }
                continue
            }
            if (diag != null) diag.countCandidate(tMs, swing, amplitude)

            val prevIdx = if (state.lastPeakTimeMs.isNaN()) -1 else (state.lastPeakTimeMs / dtMs).toInt()
            val sinceLast = if (prevIdx < 0) Double.MAX_VALUE else tMs - state.lastPeakTimeMs
            // h1: above the local-envelope midpoint, above the Pan–Tompkins adaptive level (noise
            // peak + THRESHOLD_FRACTION of the tracked signal/noise gap, so slow noise growth is
            // not accepted as beats), and — while no rhythm estimate exists yet — at least
            // [BOOTSTRAP_PEAK_RATIO] as tall as the previous accepted peak so the very first
            // interval cannot latch onto a dicrotic wave.
            val snrLevel = (state.noisePeakLevel +
                THRESHOLD_FRACTION * (state.signalPeakLevel - state.noisePeakLevel))
                .coerceAtLeast(0.0)
            val h1 = amplitude >= swing * 0.5 &&
                amplitude >= snrLevel &&
                (prevIdx < 0 || !state.recentRrMs.isNaN() ||
                    amplitude >= BOOTSTRAP_PEAK_RATIO * state.lastPeakAmplitude)
            val h3 = amplitude >= swing * AMPLITUDE_FACTOR
            // h4: physiologically plausible separation from the previous accepted beat. When no
            // beat has been accepted yet there is nothing to separate from — [sinceLast] is already
            // infinite — so the sample-index clause must not be evaluated against prevIdx == -1,
            // which would otherwise reject every genuine beat landing in the first [minGap] samples
            // and let a later dicrotic wave become the seed peak (manufacturing a short first IBI).
            val h4 = sinceLast >= MIN_IBI_MS && (prevIdx < 0 || (i - prevIdx) >= minGap)
            val conditions = PeakConditions(h1, isMax, h3, h4)

            if (diag != null) {
                if (!h1) diag.countReject(DetectionDiagnostics.REASON_THRESHOLD, tMs)
                if (!h3) diag.countReject(DetectionDiagnostics.REASON_AMPLITUDE, tMs)
                if (!h4) diag.countReject(DetectionDiagnostics.REASON_SPACING, tMs)
            }

            if (h1 && h3 && h4) {
                if (diag != null) diag.countAccepted()
                state.signalPeakLevel =
                    SIGNAL_ALPHA * amplitude + (1.0 - SIGNAL_ALPHA) * state.signalPeakLevel
                if (prevIdx >= 0) state.pushInterval(sinceLast)
                state.lastPeakTimeMs = tMs
                state.lastPeakAmplitude = amplitude
                state.peaks.add(PulsePeak(tMs, amplitude, swing, conditions))
            } else {
                state.noisePeakLevel =
                    NOISE_ALPHA * amplitude + (1.0 - NOISE_ALPHA) * state.noisePeakLevel
            }
        }
        if (diag != null) diag.markAmplitudeExtent(absMax)
        return state.peaks
    }

    /** Mean absolute deviation of the accepted peak amplitudes (used for quality scoring). */
    fun amplitudeVariation(peaks: List<PulsePeak>): Double {
        if (peaks.size < 3) return 1.0
        val amps = peaks.map { it.amplitude }
        val mean = amps.average()
        if (abs(mean) < 1e-12) return 1.0
        val sd = kotlin.math.sqrt(amps.sumOf { (it - mean) * (it - mean) } / amps.size)
        return (sd / abs(mean)).coerceIn(0.0, 1.0)
    }
}