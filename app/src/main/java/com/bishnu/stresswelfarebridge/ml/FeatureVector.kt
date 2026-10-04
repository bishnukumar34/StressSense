package com.bishnu.stresswelfarebridge.ml

import java.time.Instant

/**
 * A single point-in-time observation, assembled from whatever real measurements exist right now.
 *
 * Every field is nullable and **stays nullable**. A null means "this was not measured"; it is never
 * coerced to 0. That is the whole point of Phase 8: missing sleep is not 0 hours of sleep, and a
 * missing SpO2 is not a hypoxic reading.
 *
 * @param measuredAt when the underlying measurement was taken.
 * @param source provenance of the heart data, e.g. `CAMERA_PPG`.
 */
data class StressObservation(
    val measuredAt: Instant,
    val source: String,

    // -- acute physiology -------------------------------------------------------------------------
    val hrBpm: Double? = null,
    val rmssdMs: Double? = null,
    val sdnnMs: Double? = null,
    val meanNnMs: Double? = null,
    val sdsdMs: Double? = null,
    val pnn50Pct: Double? = null,
    val respRateBpm: Double? = null,

    // -- activity context ------------------------------------------------------------------------
    /** Recent physical load normalised to 0..1. See [FeatureSchema.ACTIVITY_NOTE]. */
    val activityRecentNorm: Double? = null,

    // -- signal quality --------------------------------------------------------------------------
    /** 0..1 signal-quality score from the acquisition layer. */
    val ppgQuality: Double? = null,
    /** 0..1 confidence from the acquisition layer. */
    val confidence: Double? = null,
    val validNnRatio: Double? = null,
    val artifactRatio: Double? = null,
    val durationSeconds: Double? = null
)

/**
 * An ordered feature vector.
 *
 * [values] is indexed by [FeatureSchema] order. **`Double.NaN` means missing**, which is
 * deliberately distinct from `0.0`. The model layer imputes missing entries from the training-set
 * median and additionally exposes missingness as an explicit signal, so an absent value can never be
 * read as a physiological zero.
 */
class FeatureVector(val names: List<String>, val values: DoubleArray) {

    init {
        require(names.size == values.size) { "names/values length mismatch" }
    }

    val size: Int get() = values.size

    operator fun get(i: Int): Double = values[i]

    fun indexOf(name: String): Int = names.indexOf(name)

    fun isMissing(i: Int): Boolean = values[i].isNaN()

    /** Named view for explanations and diagnostics (Phase 20). */
    fun asMap(): Map<String, Double> =
        names.indices.associate { names[it] to values[it] }

    /** Fraction of features actually present, in 0..1. Feeds the abstention logic. */
    fun completeness(): Double {
        if (values.isEmpty()) return 0.0
        val present = values.count { !it.isNaN() }
        return present.toDouble() / values.size
    }

    /** Values with missing entries replaced by [fill] — the view actually fed to the linear model. */
    fun imputed(fill: DoubleArray): DoubleArray =
        DoubleArray(values.size) { i -> if (values[i].isNaN()) fill[i] else values[i] }

    /** 1.0 where present, 0.0 where missing. Model input, so missingness is itself informative. */
    fun missingnessFlags(): DoubleArray = DoubleArray(values.size) { i ->
        if (values[i].isNaN()) 1.0 else 0.0
    }

    /** Adds two vectors element-wise, propagating NaN as missing. */
    operator fun plus(other: FeatureVector): FeatureVector {
        require(other.names == names) { "vector name mismatch" }
        return FeatureVector(names, DoubleArray(values.size) { i ->
            val a = values[i]; val b = other.values[i]
            if (a.isNaN() || b.isNaN()) Double.NaN else a + b
        })
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeatureVector) return false
        return names == other.names && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * names.hashCode() + values.contentHashCode()
}