package com.bishnu.stresswelfarebridge.questionnaire

/**
 * One item in a questionnaire, and the answer given to it.
 *
 * Item definitions are **data**, not UI: the same list drives rendering, scoring and storage, so a
 * questionnaire can be corrected or re-versioned without touching screen code.
 */
data class QuestionnaireItem(
    /** Stable identifier, unique within an instrument. Never renumber an existing item. */
    val id: String,
    /** Display text supplied by the instrument definition. */
    val textKey: String,
    /** Subscale this item contributes to (e.g. "stress"), or null for ungrouped instruments. */
    val subscale: String? = null,
    /**
     * Whether the response must be reversed before scoring, i.e. `max - response`.
     *
     * Used by instruments such as PSS-10 whose positively-worded items would otherwise inflate the
     * total. The reversal is applied inside the scorer, never by the UI, so the stored answer stays
     * exactly as the person gave it.
     */
    val reverseScored: Boolean = false,
    /**
     * Whether this item is required for a complete score. Optional items may be skipped without
     * invalidating the response.
     */
    val required: Boolean = true
)

/** A response scale definition. */
data class ResponseScale(
    val min: Int,
    val max: Int,
    /** Label shown at [min]. */
    val lowLabel: String,
    /** Label shown at [max]. */
    val highLabel: String,
    /**
     * Whether higher values indicate *more* of the thing being measured.
     *
     * Daily Pulse item 2 ("How rested do you feel?") is `false`: higher means better, so it is
     * reversed before it contributes to a stress score. Recording this on the scale keeps the
     * reversal a property of the data rather than a special case buried in the scorer.
     */
    val higherIsWorse: Boolean = true
) {
    fun valid(value: Int): Boolean = value in min..max
    fun count(): Int = max - min + 1
}

/** A severity band, used to present a subscale score in words. */
data class SeverityBand(
    val name: String,
    /** Inclusive lower bound. */
    val min: Int,
    /** Inclusive upper bound, or null for open-ended top band. */
    val maxInclusive: Int?
) {
    fun contains(score: Int): Boolean =
        score >= min && (maxInclusive == null || score <= maxInclusive)
}