package com.bishnu.stresswelfarebridge.ml

import java.time.Duration
import java.time.Instant

/**
 * Per-account personal baseline: "what normal looks like for *this* person".
 *
 * A heart rate of 90 bpm means nothing on its own; it is only interpretable against that
 * individual's own resting level (Phase 5). This class maintains that reference and nothing else.
 *
 * Design choices, and the reasoning behind them:
 *
 *  - **Median + MAD, not mean + standard deviation.** A single sensor glitch or one panicked
 *    afternoon must not be allowed to redefine "normal". The median tolerates up to 50 %
 *    contamination, which is what "protect the baseline from rapid contamination" requires
 *    structurally rather than by a special-case rule.
 *  - **A baseline is never produced from one measurement.** [zScore] returns null until
 *    [MIN_SAMPLES] qualifying observations exist, so the feature stays missing and the model treats
 * it as missing ” it never sees a z-score derived from a single noisy reading.
 *  - **Only physiologically plausible values are admitted.** [observe] refuses anything outside the
 *    range in [ranges], so an implausible reading is excluded rather than widening the reference.
 *    Admitting a measurement at all is the caller's decision: it owns the quality gates, and is
 *    expected to pass only measurements that passed them.
 *  - **A bounded, time-limited window.** Samples older than [WINDOW] stop counting, so the baseline
 *    tracks the person over weeks instead of being frozen by old data.
 *  - **Floored scale.** The MAD is floored per metric, because dividing by a near-zero spread turns
 *    a 1 bpm wobble into a 30-sigma "deviation".
 */
class PersonalBaseline(
    private val clock: () -> Instant = { Instant.now() }
) {

    /** One metric's recent sample window. */
    private class Metric {
        /** Parallel arrays of (instant, value); oldest first. */
        val times = ArrayList<Instant>()
        val values = ArrayList<Double>()

        fun add(at: Instant, v: Double) {
            times.add(at); values.add(v)
        }

        fun evictBefore(cutoff: Instant) {
            var drop = 0
            while (drop < times.size && times[drop].isBefore(cutoff)) drop++
            if (drop > 0) {
                times.subList(0, drop).clear()
                values.subList(0, drop).clear()
            }
        }

        val count: Int get() = values.size
    }

    private val metrics = HashMap<String, Metric>()

    private fun metricFor(name: String): Metric = metrics.getOrPut(name) { Metric() }

    /**
     * Admit one qualifying observation into this metric's baseline window.
     *
     * @return false when the value is absent or outside the metric's physiological range, in which
     *   case nothing is stored.
     */
    fun observe(name: String, value: Double?, at: Instant = clock()): Boolean {
        if (value == null || !value.isFinite()) return false
        val r = ranges[name] ?: return false
        if (value !in r) return false
        metricFor(name).add(at, value)
        return true
    }

    /** How many qualifying observations back this metric's baseline. */
    fun sampleCount(name: String): Int = metrics[name]?.count ?: 0

    /** True once enough observations exist for the metric's baseline to be trusted. */
    fun isEstablished(name: String): Boolean = sampleCount(name) >= MIN_SAMPLES

    /** Robust centre (median) of the window, or null when not yet established. */
    fun center(name: String): Double? {
        val m = metrics[name] ?: return null
        m.evictBefore(clock().minus(WINDOW))
        if (m.count < MIN_SAMPLES) return null
        return median(m.values)
    }

    /**
     * Standardised deviation of [value] from this person's baseline, or null when the baseline is
 * not established or the value is absent ” a genuinely missing feature, not an invented one.
     */
    fun zScore(name: String, value: Double?): Double? {
        val v = value ?: return null
        if (!v.isFinite()) return null
        val mu = center(name) ?: return null
        return (v - mu) / scale(name)
    }

 /** Robust spread (1.4826 MAD), floored so a near-constant metric cannot explode. */
    fun scale(name: String): Double {
        val m = metrics[name] ?: return 1.0
        if (m.count < MIN_SAMPLES) return 1.0
        val med = median(m.values)
        val mad = median(m.values.map { kotlin.math.abs(it - med) })
        val floor = floors[name] ?: 1.0
        val raw = 1.4826 * mad
        return if (raw.isFinite() && raw > floor) raw else floor
    }

    /** Forget everything. */
    fun clear() = metrics.clear()

    // -- persistence ---------------------------------------------------------------------------------
    //
    // The baseline is the thing the deployed model leans on hardest (`hr_z`, `rmssd_z`, `sdnn_z` and
    // `mean_nn_z` carry the largest weights), and `MIN_SAMPLES` gates when a score may be issued at
    // all. If those samples lived only in memory, a person who measured four times and then reopened
    // the app would be told "1 more reading needed" forever and could never reach a real score.
    //
    // These two methods make the state transferable without changing any of the statistics or the
    // admission rules below. The serialisation itself lives in [StressBaselineStore]; these are
    // deliberately plain data so this class stays Android-free and JVM-testable.

    /** Every metric's current sample window, oldest first. */
    fun snapshot(): BaselineSnapshot = BaselineSnapshot(
        metrics.mapValues { (_, m) ->
            m.times.indices.map { i -> BaselineSample(m.times[i].toEpochMilli(), m.values[i]) }
        }
    )

    /**
     * Replaces the window with [snapshot], subject to the *existing* admission rules.
     *
     * Two rules are deliberately preserved rather than bypassed:
     *
     *  1. Each sample is re-admitted through [observe], so a value outside [ranges] is still refused.
     *     Corrupted storage cannot widen someone's personal reference.
     *  2. The [WINDOW] is applied on restore. [sampleCount] deliberately does not evict, so restoring
     *     samples older than the window would let stale readings satisfy [MIN_SAMPLES]. Applying the
     *     window here *enforces* the existing 14-day intent rather than extending it.
     */
    fun restore(snapshot: BaselineSnapshot) {
        clear()
        val cutoff = clock().minus(WINDOW).toEpochMilli()
        snapshot.metrics.forEach { (name, samples) ->
            samples.forEach { sample ->
                if (sample.atMillis >= cutoff) {
                    observe(name, sample.value, Instant.ofEpochMilli(sample.atMillis))
                }
            }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    companion object {
        /** Never establish a baseline from fewer than this many qualifying observations. */
        const val MIN_SAMPLES = 5

        /** How far back a sample still counts toward the baseline. */
        val WINDOW: Duration = Duration.ofDays(14)

        /**
 * Minimum per-metric spread used as the z-score denominator ” the scales below which a
         * difference stops being physiologically meaningful.
         */
        val floors: Map<String, Double> = mapOf(
            "hr_bpm" to 2.0,
            "rmssd_ms" to 1.0,
            "sdnn_ms" to 2.0,
            "mean_nn_ms" to 5.0,
            "sdsd_ms" to 1.0,
            "pnn50_pct" to 1.0,
            "resp_rate_bpm" to 0.5,
            "activity_recent_norm" to 0.05,
            "duration_s" to 2.0
        )

        /** Physiological acceptance ranges; values outside are not admitted to the baseline. */
        val ranges: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
            "hr_bpm" to 30.0..220.0,
            "rmssd_ms" to 1.0..300.0,
            "sdnn_ms" to 1.0..300.0,
            "mean_nn_ms" to 200.0..2400.0,
            "sdsd_ms" to 0.0..300.0,
            "pnn50_pct" to 0.0..100.0,
            "resp_rate_bpm" to 4.0..60.0,
            "activity_recent_norm" to 0.0..1.0,
            "duration_s" to 1.0..1800.0
        )
    }
}

/** One stored baseline sample: the instant it was measured and the value as measured. */
data class BaselineSample(val atMillis: Long, val value: Double)

/**
 * A transferable copy of a whole [PersonalBaseline].
 *
 * Plain data with no Android or storage types, so [PersonalBaseline] stays unit-testable on a plain
 * JVM and [StressBaselineStore] remains the only class that knows how this is written to disk.
 */
data class BaselineSnapshot(
    /** Metric name (e.g. `hr_bpm`) to its samples, oldest first. */
    val metrics: Map<String, List<BaselineSample>> = emptyMap()
) {
    /** Samples currently held for [metric]. */
    fun countOf(metric: String): Int = metrics[metric]?.size ?: 0

    companion object {
        val EMPTY = BaselineSnapshot()
    }
}
