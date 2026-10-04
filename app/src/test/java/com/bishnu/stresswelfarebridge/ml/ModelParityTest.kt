package com.bishnu.stresswelfarebridge.ml

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Python ↔ Android equivalence (Phase 21).
 *
 * `ml/train_wesad_stress.py` writes `parity_vectors.json`: a set of real feature rows together with
 * the raw logit, calibrated probability and display score that the Python model produces for them.
 * This test feeds those exact rows through the on-device Kotlin implementation and asserts the same
 * numbers come out.
 *
 * If this test fails, the training pipeline and the app disagree about feature order, imputation or
 * arithmetic - which would make every reported validation metric meaningless on device. It is the
 * single most important guard in this subsystem.
 */
class ModelParityTest {

    private fun loadModel(): StressModel {
        val json = JSONObject(javaClass.classLoader!!.getResource("parity_vectors.json")!!.readText())
        // The validated ML artifact - not the legacy 5-feature StressEngine baseline.
        val modelJson = JSONObject(
            javaClass.classLoader!!.getResource("stress_model_ml.json")!!.readText()
        )
        // The parity file must have been generated against this exact model version.
        assertEquals(modelJson.getString("model_version"), json.getString("model_version"))
        val model = StressModel.fromJson(modelJson)
        StressModel.verifySchema(model)
        return model
    }

    @Test
    fun androidInferenceMatchesPythonForEveryParityVector() {
        val parity = JSONObject(javaClass.classLoader!!.getResource("parity_vectors.json")!!.readText())
        val model = loadModel()
        val order = FeatureSchema.NAMES

        val vectors = parity.getJSONArray("vectors")
        assertTrue("no parity vectors were generated", vectors.length() > 0)

        for (i in 0 until vectors.length()) {
            val entry = vectors.getJSONObject(i)
            val feats = entry.getJSONObject("features")

            // Build the vector in schema order; a JSON null becomes NaN (missing), never zero.
            val values = DoubleArray(order.size) { idx ->
                val key = order[idx]
                if (!feats.has(key) || feats.isNull(key)) Double.NaN
                else feats.getDouble(key)
            }
            val vector = FeatureVector(order, values)

            val expectedLogit = entry.getDouble("expected_raw_logit")
            val expectedProb = entry.getDouble("expected_probability")
            val expectedScore = entry.getInt("expected_score")

            assertEquals(
                "raw logit mismatch on vector $i", expectedLogit, model.rawScore(vector), 1e-4
            )
            assertEquals(
                "calibrated probability mismatch on vector $i",
                expectedProb, model.calibratedProbability(vector), 1e-4
            )
            assertEquals(
                "display score mismatch on vector $i", expectedScore, model.displayScore(vector)
            )
        }
    }

    @Test
    fun shippedAssetMatchesTheFeatureSchema() {
        StressModel.verifySchema(loadModel())
    }

    @Test
    fun missingValuesAreImputedNotZeroed() {
        val model = loadModel()
        val allPresent = FeatureVector(
            FeatureSchema.NAMES, DoubleArray(FeatureSchema.NAMES.size) { 1000.0 }
        )
        // Same vector but with one entry absent: the imputed design matrix must differ from the
        // fully-present one, proving absence is not silently read as the number 0.
        val oneMissing = FeatureVector(
            FeatureSchema.NAMES, DoubleArray(FeatureSchema.NAMES.size) { 1000.0 }.also {
                it[FeatureSchema.indexOf("rmssd_ms")] = Double.NaN
            }
        )
        assertTrue(
            "a missing feature must not be treated as 0.0",
            model.rawScore(allPresent) != model.rawScore(oneMissing)
        )
    }
}