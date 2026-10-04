package com.bishnu.stresswelfarebridge.commander

import com.bishnu.stresswelfarebridge.StressEngine

/**
 * The three monitoring bands the commander dashboard groups personnel into.
 *
 * This is a **presentation mapping only**. It deliberately contains no new thresholds and no second
 * stress model: the score comes from the existing [StressEngine] and the band comes from the band
 * names the engine already produces.
 */
enum class StressZone(val title: String, val description: String, val emoji: String) {
    GREEN("LOW STRESS", "Low stress", "🟢"),
    YELLOW("MEDIUM STRESS", "Moderate stress", "🟡"),
    RED("HIGH STRESS", "High stress", "🔴");

    companion object {
        /** Display order used by the dashboard. */
        val ordered: List<StressZone> = listOf(GREEN, YELLOW, RED)
    }
}

/**
 * Stress score → stress zone.
 *
 * The cut-points are read from [StressEngine.LOW_SCORE_MAX] / [StressEngine.MODERATE_SCORE_MAX],
 * which are the *same* values `StressEngine.infer` uses to produce its "Low" / "Moderate" / "High"
 * level — they were extracted into named constants so there is exactly one definition. No threshold
 * is invented here, and nothing in this layer is a medical claim: it is the app's own research
 * stress estimate, bucketed for monitoring.
 */
object StressZoneMapping {

    /** Engine level strings that map to a zone; anything else means "not classifiable". */
    fun fromEngineLevel(level: String): StressZone? = when (level.trim().lowercase()) {
        "low" -> StressZone.GREEN
        "moderate", "medium" -> StressZone.YELLOW
        "high" -> StressZone.RED
        else -> null
    }

    fun fromScore(score: Int): StressZone = when {
        score < StressEngine.LOW_SCORE_MAX -> StressZone.GREEN
        score < StressEngine.MODERATE_SCORE_MAX -> StressZone.YELLOW
        else -> StressZone.RED
    }

    /**
     * Zone for a live [StressEngine.Result].
     *
     * Returns null when the engine has not produced a score (its "Awaiting data" state) or when the
     * score did not come from real Health Connect measurements. Such a person is **not** filed under
     * "low stress" — they are reported as not yet classified, so an absence of data can never be
     * displayed as a healthy result.
     */
    fun fromResult(result: StressEngine.Result): StressZone? {
        val score = result.score ?: return null
        if (!result.usingRealData) return null
        return fromScore(score)
    }
}
