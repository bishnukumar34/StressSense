package com.bishnu.stresswelfarebridge.ml

import org.json.JSONObject
import kotlin.math.exp

/**
 * The trained StressSense stress model, and everything needed to run it identically on device.
 *
 * The model is a **logistic regression** over the canonical feature vector. That choice is
 * deliberate, and the measured reason is recorded in `ml/REPORT.md`: gradient-boosted trees and
 * random forests were trained and compared under the same leave-one-subject-out protocol and did not
 * earn their place. A linear model also has properties that matter more than accuracy here:
 *
 *  - it is auditable — every score decomposes into per-feature contributions, which is what the
 *    developer-level explanation of Phase 20 needs;
 *  - it degrades gracefully when features are missing rather than silently extrapolating through
 *    regions of feature space it never saw;
 *  - it runs on-device with no runtime dependency and no Python.
 *
 * ### Missing data (Phase 8)
 * A missing feature is **not** treated as zero. It is replaced by the training-set median for that
 * feature (carried in the model file as `impute`), *and* a binary "was missing" flag is appended to
 * the input. The flags let the model distinguish "sleep was 0 hours" from "sleep was never
 * recorded", which is exactly the distinction median-imputation alone destroys.
 *
 * ### Calibration
 * [predict] returns three separate things, never collapsed into one (Phase 13): the raw decision
 * function, the Platt-calibrated probability, and the 0-100 display score.
 */
class StressModel private constructor(
    val modelVersion: String,
    val featureNames: List<String>,
    /** Weights laid out as `[features..., missing flags...]`; length is `2 * featureNames.size`. */
    private val weights: DoubleArray,
    private val bias: Double,
    private val impute: DoubleArray,
    private val calibrationA: Double,
    private val calibrationB: Double
) {

    val inputDim: Int get() = weights.size

    /**
     * Builds the exact numeric input the model expects: imputed feature values followed by the
     * missingness flags. Exposed so parity tests can assert on it directly.
     */
    fun designMatrix(vector: FeatureVector): DoubleArray {
        require(vector.names == featureNames) {
            "feature order mismatch: model expects ${featureNames.size} features in schema order"
        }
        val n = vector.size
        val out = DoubleArray(2 * n)
        for (i in 0 until n) {
            out[i] = if (vector[i].isNaN()) impute[i] else vector[i]
            out[n + i] = if (vector[i].isNaN()) 1.0 else 0.0
        }
        return out
    }

    /** Raw decision-function value (logit). Not a probability. */
    fun rawScore(vector: FeatureVector): Double {
        val x = designMatrix(vector)
        var z = bias
        for (i in weights.indices) z += weights[i] * x[i]
        return z
    }

    /**
     * Calibrated P(stress) in 0..1 via Platt scaling on the raw logit.
     *
     * Calibration parameters come from out-of-fold predictions during training, so this probability
     * approximates a real event frequency rather than being a bare classifier output.
     */
    fun calibratedProbability(vector: FeatureVector): Double =
        sigmoid(calibrationA * rawScore(vector) + calibrationB)

    /**
     * The 0-100 StressSense display score, derived from the **calibrated** probability.
     *
     * Explicitly not a clinical quantity: a monotone rescaling of a research classifier, used for
     * display and for the LOW / MEDIUM / HIGH banding.
     */
    fun displayScore(vector: FeatureVector): Int =
        (calibratedProbability(vector) * 100.0).toInt().coerceIn(0, 100)

    /**
     * Per-feature contribution to the raw logit, i.e. `weight * value` (zero for a missing feature,
     * whose imputed value and missing flag are what the model actually used).
     *
     * This is the developer-level explanation of Phase 20. It describes how the model computed the
     * number; it is **not** a medical statement about why a person is stressed.
     */
    fun contributions(vector: FeatureVector): List<FeatureContribution> {
        val x = designMatrix(vector)
        val n = vector.size
        return featureNames.mapIndexed { i, name ->
            val primary = weights[i] * x[i]
            val missingFlag = weights[n + i] * x[n + i]
            FeatureContribution(
                name = name,
                value = vector[i],
                unit = FeatureSchema.feature(name)?.unit ?: "",
                group = FeatureSchema.feature(name)?.group ?: FeatureSchema.Group.ACUTE,
                missing = vector[i].isNaN(),
                contribution = primary + missingFlag
            )
        }
    }

    companion object {
        fun sigmoid(z: Double): Double {
            if (z >= 0) return 1.0 / (1.0 + exp(-z))
            val e = exp(z)
            return e / (1.0 + e)
        }

        /**
         * Parses the exported model JSON.
         *
         * The feature ordering is taken from the artifact rather than from [FeatureSchema], so the
         * model file defines its own contract; [verifySchema] asserts the two agree, making a stale
         * artifact a loud failure instead of a silent misalignment.
         */
        fun fromJson(json: JSONObject): StressModel {
            fun doubles(key: String): DoubleArray {
                val a = json.getJSONArray(key)
                return DoubleArray(a.length()) { a.getDouble(it) }
            }
            val names = json.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
            val cal = json.getJSONObject("calibration")
            return StressModel(
                modelVersion = json.getString("model_version"),
                featureNames = names,
                weights = doubles("weights"),
                bias = json.getDouble("bias"),
                impute = doubles("impute"),
                calibrationA = cal.getDouble("a"),
                calibrationB = cal.getDouble("b")
            )
        }

        /** Fails loudly when the shipped artifact and the code schema have diverged. */
        fun verifySchema(model: StressModel) {
            require(model.featureNames == FeatureSchema.NAMES) {
                "Model feature order does not match FeatureSchema.NAMES. Retrain and re-export."
            }
            require(model.weights.size == 2 * model.featureNames.size) {
                "Model weight vector must hold weights for features and for missing flags."
            }
            require(model.impute.size == model.featureNames.size) {
                "Imputation vector must have exactly one entry per feature."
            }
        }
    }
}

/** One feature's contribution to the model's output, for developer-facing explanation. */
data class FeatureContribution(
    val name: String,
    val value: Double,
    val unit: String,
    val group: FeatureSchema.Group,
    val missing: Boolean,
    val contribution: Double
) {
    /** Display form of the raw value; "missing" rather than a fabricated number when absent. */
    fun display(): String = if (missing) "missing" else "%.2f %s".format(value, unit)
}