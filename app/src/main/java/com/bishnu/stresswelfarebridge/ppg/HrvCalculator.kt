package com.bishnu.stresswelfarebridge.ppg

import kotlin.math.sqrt

/**
 * Time-domain HRV metrics computed from validated NN intervals.
 *
 * Definitions used (standard time-domain measures):
 *  - **RMSSD** = sqrt( (1/(N−1)) · Σ_{i=1..N−1} (NN_{i+1} − NN_i)² )
 *    Root mean square of successive NN differences. A vagal (parasympathetic) index and the
 *    short-term HRV measure we report.
 *  - **SDNN** = sqrt( (1/(N−1)) · Σ_i (NN_i − mean(NN))² )
 *    Sample standard deviation of NN intervals (N−1 denominator), reported as an additional
 *    conventional context value.
 *  - **Mean NN / HR** = 60000 / mean(NN ms).
 *
 * Guardrails: metrics are only returned when there are at least [MIN_NN] valid intervals, so a
 * result is never computed from only a few beats.
 */
object HrvCalculator {

    /** Minimum validated NN intervals required before any HRV number is produced. */
    const val MIN_NN = 20

    fun rmssd(nn: List<Double>): Double? {
        if (nn.size < MIN_NN) return null
        var sum = 0.0
        var count = 0
        for (i in 1 until nn.size) {
            val d = nn[i] - nn[i - 1]
            sum += d * d
            count++
        }
        if (count == 0) return null
        return sqrt(sum / count)
    }

    fun sdnn(nn: List<Double>): Double? {
        if (nn.size < MIN_NN) return null
        val mean = nn.average()
        var sum = 0.0
        for (v in nn) sum += (v - mean) * (v - mean)
        return sqrt(sum / (nn.size - 1))
    }

    /** SDSD = sample SD of successive NN differences. */
    fun sdsd(nn: List<Double>): Double? {
        if (nn.size < MIN_NN) return null
        val diffs = nn.zipWithNext { a, b -> b - a }
        if (diffs.size < 2) return null
        val mean = diffs.average()
        var sum = 0.0
        for (d in diffs) sum += (d - mean) * (d - mean)
        return sqrt(sum / (diffs.size - 1))
    }

    /** pNN50 = percentage of successive NN differences greater than 50 ms. */
    fun pnn50(nn: List<Double>): Double? {
        if (nn.size < MIN_NN) return null
        val diffs = nn.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        if (diffs.isEmpty()) return null
        return 100.0 * diffs.count { it > 50.0 } / diffs.size
    }

    fun meanIbi(nn: List<Double>): Double? = if (nn.isEmpty()) null else nn.average()

    fun heartRate(meanIbiMs: Double): Double =
        if (meanIbiMs > 0.0) 60000.0 / meanIbiMs else 0.0

    /**
     * Returns complete metrics, or null when there are not enough validated NN intervals.
     * Callers must treat null as "insufficient data" and must not substitute any estimate.
     */
    fun compute(nn: List<Double>): HrvMetrics? {
        val r = rmssd(nn) ?: return null
        val s = sdnn(nn) ?: return null
        val m = meanIbi(nn) ?: return null
        if (m <= 0.0) return null
        return HrvMetrics(
            rmssdMs = r,
            sdnnMs = s,
            meanIbiMs = m,
            heartRateBpm = heartRate(m),
            nnCount = nn.size,
            sdsdMs = sdsd(nn),
            pnn50Pct = pnn50(nn)
        )
    }
}