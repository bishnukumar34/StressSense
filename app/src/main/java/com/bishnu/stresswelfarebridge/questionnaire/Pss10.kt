package com.bishnu.stresswelfarebridge.questionnaire

import java.time.YearMonth

/**
 * PSS-10 scoring and calendar-month availability.
 *
 * Two things are deliberately kept apart here:
 *
 *  1. **Scoring.** Sum of the reverse-adjusted item scores, 0-40. Items 4, 5, 7 and 8 are phrased in
 *     the opposite direction, so they are converted with `max - raw` before summing. No z-score, no
 *     weighting, no rescaling.
 *
 *  2. **Availability.** One completed PSS-10 per *calendar month*, not per rolling 30 days. Someone
 *     who completes on 4 October cannot complete again on 20 October, and becomes available again on
 *     1 November. Calendar-month semantics matter here because "30 days since last completion" would
 *     either lock someone out into November or let them re-take in late September after an early
 *     August completion, depending on the date.
 *
 * ### Wording
 * The item **text** is deliberately not present. PSS-10 is a copyrighted instrument and the project
 * has not cleared permission, so [QuestionnaireInstrument.PSS_10] stays
 * [VerificationStatus.BLOCKED_PENDING_LICENCE] and the UI shows a pending state instead of
 * invented questions. Everything else - navigation, monthly gating, scoring, persistence, history
 * and the result screen - is implemented and tested against that definition.
 */
object Pss10 {

    const val SCALE_MIN = 0
    const val SCALE_MAX = 4
    const val ITEM_COUNT = 10
    const val MAX_SCORE = 40

    /** Items phrased in the opposite direction and therefore reversed before summing. */
    val REVERSE_ITEMS: Set<Int> = setOf(4, 5, 7, 8)

    /**
     * Total score for a set of answers, or null when not every item was answered.
     *
     * Returns null rather than a partial total: a PSS-10 summed over 6 of 10 items is not a PSS-10
     * score, and reporting one would be a fabricated measurement.
     */
    fun score(answers: Map<Int, Int>): Int? {
        if (answers.size < ITEM_COUNT) return null
        var total = 0
        for (n in 1..ITEM_COUNT) {
            val raw = answers[n] ?: return null
            if (raw !in SCALE_MIN..SCALE_MAX) return null
            total += if (n in REVERSE_ITEMS) (SCALE_MAX - raw) else raw
        }
        return total
    }

    /**
     * Whether PSS-10 may be completed in [month].
     *
     * True when there is no completed response for that calendar month. Any prior month's responses
     * are unaffected and remain in history.
     */
    fun isAvailable(month: YearMonth, completedMonths: Set<YearMonth>): Boolean =
        month !in completedMonths

    /** The month a completion belongs to. */
    fun monthOf(
        epochMillis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): java.time.YearMonth =
        java.time.YearMonth.from(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))


    /** Human-facing next-availability note, used by the result screen. */
    fun nextAvailableMessage(): String = "Next questionnaire available next month."
}