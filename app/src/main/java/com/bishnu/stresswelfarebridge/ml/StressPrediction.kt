package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import java.time.Instant

/**
 * Outcome of one stress estimate.
 *
 * [score] is null whenever the system abstains (Phase 14). An abstention is a first-class,
 * reportable outcome — never a zero, never a guess. [reason] says which gate refused, so the UI can
 * tell the user what to do rather than just showing a dash.
 */
data class StressPrediction(
    /** Calibrated 0-100 display score, or null when the system abstained. */
    val score: Int?,
    val zone: StressZone,
    /** Raw decision-function value. Kept separate from the calibrated score (Phase 13). */
    val rawLogit: Double?,
    /** Platt-calibrated P(stress). Kept separate from the display score. */
    val probability: Double?,
    /** 0-100 confidence in *this* estimate. Low confidence is a reason to abstain, not a valid score. */
    val confidence: Int,
    val contributions: List<FeatureContribution> = emptyList(),
    val reason: String = "",
    val modelVersion: String = FeatureSchema.MODEL_VERSION,
    val featureSchemaVersion: Int = FeatureSchema.FEATURE_SCHEMA_VERSION,
    val baselineVersion: Int = FeatureSchema.BASELINE_VERSION,
    val calibrationVersion: Int = FeatureSchema.CALIBRATION_VERSION
)

/** Monitoring band. Cut-points are the app's existing [StressEngine] thresholds, not new ones. */
enum class StressZone { LOW, MEDIUM, HIGH }

object StressZoneMapping {
    /** Same cut-points as [StressEngine], so score and band can never disagree. */
    fun fromScore(score: Int): StressZone = when {
        score < StressEngine.LOW_SCORE_MAX -> StressZone.LOW
        score < StressEngine.MODERATE_SCORE_MAX -> StressZone.MEDIUM
        else -> StressZone.HIGH
    }
}