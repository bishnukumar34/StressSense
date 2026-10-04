package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-pass artifact rejection over the detected peak series, producing the NN-interval series
 * used for HRV.
 *
 * Every stage works in **peak-index space**, so "neighbour" always means *physiologically*
 * adjacent (adjacent beats), never merely adjacent survivors of an earlier stage.
 *
 * Order of operations:
 *
 *  1. **Physiological range** — intervals outside 300–2000 ms (30–200 BPM) are impossible for the
 *     measured window and are dropped immediately.
 *  2. **Pacing** — intervals shorter than the detector's refractory gap are dropped (double-counted
 *     peaks from a notched systolic upstroke).
 *  3. **Bounded robust median/MAD outlier test** — the classic Kubios-style "deviation from the
 *     median" test, but with an explicit ceiling on the tolerance (see [OUTLIER_LIMIT_FRAC]).
 *  4. **Beat-to-beat plausibility (compensatory pair)** — see [PAIR_EXCURSION_FRAC]: an interval
 *     that departs sharply from the reference built out of its own two neighbours, *while those two
 *     neighbours agree with each other*, is the signature of a missed or an extra beat
 *     (e.g. `630, 1250, 640` or `630, 315, 635`), not of autonomic modulation. Genuine sinus
 *     arrhythmia moves the series *smoothly* — consecutive steps are correlated — so a large middle
 *     excursion immediately followed by re-convergence to the neighbours' level does not occur.
 *     Because it runs last and is purely local, it still catches pairs whose global statistics look
 *     ordinary (long/short compensatory pairs around a drifting rhythm).
 *
 * The ceiling in stage 3 is the critical correction. Without it the tolerance is
 * `max(3σ_robust, 0.10 × median)` and σ_robust is estimated from the very data that is wrong.
 * When detection errors are *systematic* — intervals alternating long/short, which is exactly what
 * an unstable adaptive threshold or an intermittent missed beat produces — MAD is large, 3σ becomes
 * enormous, nothing is rejected, `validPercent` reports ~100 % and the quality scorer sees a
 * "clean" series while RMSSD is inflated by precisely the alternation that made MAD large. Robust
 * spread estimators are robust to *isolated* outliers, not to a mixture where a large share of the
 * points are wrong.
 *
 * No value is ever replaced: rejected intervals are excluded from the NN series, never
 * interpolated, never smoothed, never fabricated.
 */
object ArtifactRejection {

    /**
     * Maximum distance from the reference built out of a middle interval's two neighbours at which
     * that middle interval may still be genuine physiology: 30 % of the reference. A beat-to-beat
     * RR change beyond ~30 % of the local rhythm that immediately returns to the neighbours' level
     * is a detection artefact (missed/extra beat) or an ectopic beat; both must leave the NN series.
     * Legitimate high HRV moves the series *gradually*, so its successive steps stay below this
     * bound — the "legitimate high HRV" tests demonstrate that explicitly.
     */
    const val PAIR_EXCURSION_FRAC = 0.30

    /**
     * The two neighbours must agree with each other to within 15 % of their reference before the
     * middle interval may be blamed. This is the guard that stops the check from deleting real
     * variability: if the neighbours themselves disagree, the series is genuinely variable and no
     * single interval is singled out.
     */
    const val PAIR_NEIGHBOUR_AGREEMENT_FRAC = 0.15

    /** A successive NN difference larger than this fraction of the median RR counts as "large". */
    const val LARGE_DIFF_FRAC = 0.30

    /** An NN is consistent with the session rate when its instantaneous rate is within this band. */
    const val HR_AGREEMENT_FRAC = 0.15

    /**
     * Largest index gap the compensatory-pair check may bridge when looking for the two
     * neighbours. Keeps the reference local: a reference built across several discarded beats is
     * no longer the *immediate* rhythm and could misattribute a genuine interval.
     */
    const val MAX_PAIR_BRIDGE = 2

    /**
     * Hard ceiling on the stage-4 tolerance: an interval may never sit more than 20 % away from the
     * session median RR and still be called part of the subject's rhythm. A 20 % RR deviation is a
     * ~20 % instantaneous rate change in a single beat; during a resting window respiratory sinus
     * arrhythmia and baroreflex modulation stay inside that, whereas a detection error or an ectopic
     * beat generally does not. The ceiling only ever makes stage 4 *stricter*, never more permissive.
     */
    const val OUTLIER_LIMIT_FRAC = 0.20

    fun validate(peaks: List<PulsePeak>): IbiValidation {
        val reasons = mutableMapOf<String, Int>()
        fun reject(key: String) { reasons[key] = (reasons[key] ?: 0) + 1 }

        if (peaks.size < 2) return IbiValidation(emptyList(), 0, 0, reasons)

        val total = peaks.size - 1
        val raw = DoubleArray(total) { i -> peaks[i + 1].timeMs - peaks[i].timeMs }
        val alive = BooleanArray(total) { true }

        // ---- Stages 1 + 2: hard physiological range and detector pacing. ----------------------
        for (i in 0 until total) {
            when {
                raw[i] < PulseDetector.MIN_IBI_MS -> { reject("too_short"); alive[i] = false }
                raw[i] > PulseDetector.MAX_IBI_MS -> { reject("too_long"); alive[i] = false }
            }
        }

        // ---- Stage 3: bounded robust median / MAD outlier test over the range survivors. ------
        val surviving = ArrayList<Double>(total)
        for (i in 0 until total) if (alive[i]) surviving.add(raw[i])
        var suspect = 0
        if (surviving.isEmpty()) {
            return IbiValidation(emptyList(), total, total, reasons, suspect)
        }
        val median = medianOf(surviving)
        val mad = medianOf(surviving.map { abs(it - median) })
        val sigma = 1.4826 * mad
        val limit = minOf(maxOf(3.0 * sigma, 0.10 * median), OUTLIER_LIMIT_FRAC * median)
        for (i in 0 until total) {
            if (!alive[i]) continue
            if (abs(raw[i] - median) > limit) {
                reject("outlier")
                alive[i] = false
            }
        }

        // ---- Stage 4: beat-to-beat plausibility on the remaining series. ---------------------
        // Runs *after* the global test so it only sees what a global statistic could not explain.
        // Its reference comes from the interval's own neighbours, so it still works when the
        // session median is a poor summary (rhythm drift) or when MAD was inflated by a systematic
        // alternation that stage 3's ceiling could not fully absorb.
        val aliveIdx = ArrayList<Int>(total)
        for (i in 0 until total) if (alive[i]) aliveIdx.add(i)
        for (j in 1 until aliveIdx.size - 1) {
            val leftIdx = aliveIdx[j - 1]
            val midIdx = aliveIdx[j]
            val rightIdx = aliveIdx[j + 1]
            // Stay local: never build a reference across a wide gap of discarded beats.
            if (midIdx - leftIdx > MAX_PAIR_BRIDGE || rightIdx - midIdx > MAX_PAIR_BRIDGE) continue
            val left = raw[leftIdx]
            val right = raw[rightIdx]
            val reference = 0.5 * (left + right)
            if (reference <= 0.0) continue
            val excursion = abs(raw[midIdx] - reference)
            val neighbourGap = abs(left - right)
            if (excursion > PAIR_EXCURSION_FRAC * reference &&
                neighbourGap <= PAIR_NEIGHBOUR_AGREEMENT_FRAC * reference
            ) {
                reject("compensatory_pair")
                alive[midIdx] = false
                suspect++
            }
        }

        val valid = ArrayList<Double>(total)
        for (i in 0 until total) if (alive[i]) valid.add(raw[i])

        return IbiValidation(valid, total, total - valid.size, reasons, suspect)
    }

    /**
     * Structural statistics of a validated NN series — the inputs the quality scorer needs in
     * order to judge the *timing* rather than the raw camera signal. Computed only from intervals
     * that survived rejection, plus the rejection bookkeeping itself.
     */
    fun stats(validation: IbiValidation): NnSeriesStats {
        val nn = validation.validNn
        val rejectionRatio = validation.rejectedRatio
        val suspectRatio = if (validation.totalIntervals <= 0) 1.0
        else validation.suspectPairCount.toDouble() / validation.totalIntervals

        if (nn.isEmpty()) {
            return NnSeriesStats(0.0, 1.0, 1.0, suspectRatio, 0.0, rejectionRatio)
        }

        val median = medianOf(nn)
        val mean = nn.average()
        val denom = (nn.size - 1).coerceAtLeast(1)
        var ss = 0.0
        for (x in nn) ss += (x - mean) * (x - mean)
        val cv = if (mean > 0.0) sqrt(ss / denom) / mean else 1.0

        val bound = LARGE_DIFF_FRAC * median
        var large = 0
        for (i in 1 until nn.size) if (abs(nn[i] - nn[i - 1]) > bound) large++
        val largeDiffRatio = if (nn.size < 2) 1.0 else large.toDouble() / (nn.size - 1)

        val sessionHr = HrvCalculator.heartRate(mean)
        var agree = 0
        if (sessionHr > 0.0) {
            val tol = HR_AGREEMENT_FRAC * sessionHr
            for (x in nn) if (abs(HrvCalculator.heartRate(x) - sessionHr) <= tol) agree++
        }
        val hrConsistency = if (sessionHr > 0.0) agree.toDouble() / nn.size else 0.0

        return NnSeriesStats(median, cv, largeDiffRatio, suspectRatio, hrConsistency, rejectionRatio)
    }

    internal fun medianOf(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}