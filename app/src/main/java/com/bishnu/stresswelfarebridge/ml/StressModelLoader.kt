package com.bishnu.stresswelfarebridge.ml

import org.json.JSONObject

/**
 * Loads the validated StressSense model from app assets.
 *
 * Kept separate from [com.bishnu.stresswelfarebridge.StressEngine]'s `stress_model.json`, which is
 * the older 5-feature research baseline still driving the existing dashboard. The two artifacts
 * coexist deliberately: this one is the model that was actually validated under leave-one-subject-out
 * and documented in `ml/REPORT.md`.
 */
object StressModelLoader {

    const val ASSET = "stress_model_ml.json"

    fun load(context: android.content.Context): StressModel {
        val json = context.assets.open(ASSET).bufferedReader().use { JSONObject(it.readText()) }
        return StressModel.fromJson(json).also(StressModel::verifySchema)
    }
}