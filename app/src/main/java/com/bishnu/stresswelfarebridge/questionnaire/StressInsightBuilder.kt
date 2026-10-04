package com.bishnu.stresswelfarebridge.questionnaire

/**
 * The two stress dimensions, kept **separate**.
 *
 * There is deliberately no `combined` field and no averaging anywhere in this type. Physiological
 * load and perceived stress measure different things and answer different questions, and collapsing
 * them into one number would hide exactly the disagreement this screen exists to show. Whether they
 * are related is an open question for future validation on real paired data - not something to assume
 * in code today.
 */
enum class StressDimension(val title: String, val source: String) {
    /** Sensor/ML result. NOT "mental stress" - it is a bodily response estimate. */
    PHYSIOLOGICAL("Physiological Stress Load", "Sensors + ML"),

    /** Questionnaire result. NOT "physical stress". */
    PERCEIVED("Perceived Stress", "Self-report")
}

/** How elevated one dimension currently looks, for display only. */
enum class InsightLevel { NORMAL, ELEVATED, HIGH, UNKNOWN }

data class DimensionReading(
    val dimension: StressDimension,
    /** 0..100, or null when there is no reading (not the same as zero). */
    val score: Int?,
    val level: InsightLevel,
    /** e.g. "Daily pulse" or "DASS-21", or null when nothing has been answered. */
    val sourceLabel: String?,
    /** Null when the physiological value is absent or was not good enough to score. */
    val reason: String? = null
)

/**
 * The combined interpretation screen's content.
 *
 * Pure and Android-free so the wording is unit-tested: phrasing that misleads a person under stress
 * is a correctness bug, not a copy preference.
 */
data class StressInsight(
    val physiological: DimensionReading,
    val perceived: DimensionReading,
    /**
     * Neutral "what this may mean" text.
     *
     * Always hedged with "may" / "can" / "could", never asserts a cause, and never tells a person
     * they are not stressed because their body says otherwise.
     */
    val interpretation: String,
    /** Always present on screen. */
    val disclaimer: String = DISCLAIMER
) {
    companion object {
        const val DISCLAIMER = "Wellness insight, not a medical diagnosis."
    }
}

/**
 * Builds the combined interpretation.
 *
 * The four cases are deliberately symmetric and non-judgemental. In particular, HIGH perceived with
 * NORMAL physiological is *not* treated as the person "being wrong" - worry and mental load often
 * simply do not appear in heart-related signals, and saying so is both true and more useful than
 * implying the reading is wrong.
 */
object StressInsightBuilder {

    // Physiological Stress Load is 0-100; PSS-10 is 0-40. Thresholds are expressed as fractions of
    // each own maximum so the two are compared like for like without being merged.
    private const val PHYS_HIGH = 65
    private const val PHYS_ELEVATED = 50
    private const val PERCEIVED_MAX = 40
    private const val PERCEIVED_HIGH_FRACTION = 0.65
    private const val PERCEIVED_ELEVATED_FRACTION = 0.50

    fun levelFor(score: Int?): InsightLevel = levelFor(score, 100)

    /**
     * Level for a score against its own maximum, so a PSS-10 of 27/40 and a physiological
     * 68/100 are recognised as comparably elevated without being arithmetically combined.
     */
    fun levelFor(score: Int?, max: Int): InsightLevel = when {
        score == null -> InsightLevel.UNKNOWN
        max <= 0 -> InsightLevel.UNKNOWN
        score * 100 >= max * 65 -> InsightLevel.HIGH
        score * 100 >= max * 50 -> InsightLevel.ELEVATED
        else -> InsightLevel.NORMAL
    }

    fun build(
        physiologicalScore: Int?,
        physiologicalReason: String? = null,
        perceivedScore: Int?,
        perceivedSourceLabel: String?
    ): StressInsight {
        val physLevel = levelFor(physiologicalScore, 100)
        val percLevel = levelFor(perceivedScore, PERCEIVED_MAX)

        val phys = DimensionReading(
            StressDimension.PHYSIOLOGICAL, physiologicalScore, physLevel,
            if (physiologicalScore == null) null else "Sensors + ML",
            physiologicalReason
        )
        val perc = DimensionReading(
            StressDimension.PERCEIVED, perceivedScore, percLevel, perceivedSourceLabel,
            if (perceivedScore == null) "Not answered yet" else null
        )

        val high = { l: InsightLevel -> l == InsightLevel.HIGH || l == InsightLevel.ELEVATED }

        val text = when {
            physLevel == InsightLevel.UNKNOWN && percLevel == InsightLevel.UNKNOWN ->
                "There is not enough usable data yet to compare how you feel with how your body is " +
                    "responding. Both a sensor reading and a short check-in would help."

            high(physLevel) && high(percLevel) ->
                "Both your self-report and physiological signals indicate elevated strain. The two " +
                    "readings agree, which may mean the strain is showing up both in how you feel " +
                    "and in your body's responses."

            high(percLevel) && !high(physLevel) ->
                "You report high stress while your current body signals look relatively calm. This " +
                    "can happen with worry or mental load that does not immediately appear in " +
                    "heart-related signals. Your own report is the more useful of the two readings " +
                    "here."

            !high(percLevel) && high(physLevel) ->
                "Your body signals show elevated strain while your reported stress is lower. " +
                    "Contributing factors can include poor sleep, heavy activity, or other physical " +
                    "factors. It may also be that stress is present but not fully noticed."

            else ->
                "Your current self-report and physiological signals are both relatively low."
        }

        return StressInsight(phys, perc, text)
    }
}