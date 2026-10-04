package com.bishnu.stresswelfarebridge.questionnaire

/**
 * The StressSense **Daily Check-in** - our own three-question daily self-report.
 *
 * This is explicitly **not** a validated psychological instrument and must never be described as
 * one.
 *
 * ### It does not produce a stress score
 * The three answers are stored as **contextual self-report data** only. There is deliberately no
 * `self_daily = 100 * (...)` formula here any more: the perceived-stress score comes from PSS-10
 * (see [Pss10]), and having a second, differently-weighted 0-100 number from three questions made
 * two things compete for the same meaning.
 *
 * The answers are still valuable - they are daily, cheap, and the only self-report a person will
 * actually complete - so they are retained as longitudinal context that can be compared against the
 * physiological signal during future paired-data validation. They are **not** fed into the
 * physiological ML model, whose input vector is fixed by its trained weights.
 */
object DailyPulse {

    const val INSTRUMENT_ID = "stresssense_daily_pulse"
    const val VERSION = 1

    const val ITEM_STRESS = "d_stress"
    const val ITEM_REST = "d_rest"
    const val ITEM_DUTY = "d_duty"

    const val CHECK_IN_REQUEST_KEY = "checkin_requested"

    /**
     * The three scored-context items, in presentation order.
     *
     * `ITEM_REST` carries `higherIsWorse = false` on its scale, which records that "more rested" is
     * the better end. The wording is deliberately paraphrased rather than borrowed from any published
     * scale, because this is our own instrument.
     */
    val items: List<QuestionnaireItem> = listOf(
        QuestionnaireItem(ITEM_STRESS, "How stressed do you feel today?"),
        QuestionnaireItem(ITEM_REST, "How rested do you feel?"),
        QuestionnaireItem(ITEM_DUTY, "How heavy was today's duty load?")
    )

    val scale = ResponseScale(
        min = 1,
        max = 5,
        lowLabel = "Not at all",
        highLabel = "Extremely",
        higherIsWorse = true
    )

    val definition: QuestionnaireDefinition = QuestionnaireDefinition(
        id = INSTRUMENT_ID,
        version = VERSION,
        title = "Today's pulse",
        items = items,
        scale = scale,
        subscales = emptyMap(),
        bands = emptyMap(),
        triggerTypes = setOf(TriggerType.DAILY, TriggerType.MANUAL),
        // Our own instrument, so there is no third-party licence to clear.
        verification = VerificationStatus.VERIFIED,
        licensingNote =
            "StressSense-authored daily check-in. Not a validated psychological test and not " +
                "derived from any published scale. Produces contextual self-report data only; the " +
                "perceived-stress score is produced by PSS-10.",
        isPsychologicalInstrument = false
    )

    /** Item 2 has its own scale direction; every other item uses [scale]. */
    fun scaleFor(itemId: String): ResponseScale = when (itemId) {
        ITEM_REST -> scale.copy(highLabel = "Fully", higherIsWorse = false)
        ITEM_DUTY -> scale.copy(lowLabel = "Light", highLabel = "Very heavy")
        else -> scale
    }

    /** True when at least one question was answered. Nothing answered means nothing is stored. */
    fun hasAnyAnswer(stress: Int?, rest: Int?, duty: Int?): Boolean =
        stress != null || rest != null || duty != null
}

/**
 * The pure rules behind the Daily Check-in screen's save/update button.
 *
 * These live outside the composable for two reasons:
 *
 *  1. **The bug this fixes.** The button used to be wired to "a record already exists for today"
 *     and to a stress score that is deliberately always null, so opening a completed check-in left
 *     the update button permanently disabled and its answers could never be revised.
 *  2. **Testability.** Composables cannot be exercised by plain JVM unit tests, but these rules
 *     can - so the behaviour is asserted for real rather than inferred from reading the source.
 *
 * ### What the button depends on, and what it must never depend on
 * It depends on exactly three things: the answers are valid, whether a record for today already
 * exists, and whether the current answers differ from the saved ones.
 *
 * It must **never** depend on a stress score. [DailyPulse] produces no score at all, so
 * `dailyStressScore` is permanently null by design and gating a save on one would disable the
 * button forever. The same applies to "completed today": having already completed a check-in is
 * the normal case for editing, never a reason to refuse the save.
 */
object DailyCheckInForm {

    /** True when all three questions have an answer. */
    fun hasAllThree(stress: Int?, rest: Int?, duty: Int?): Boolean =
        stress != null && rest != null && duty != null

    /** True when every answer sits inside the 1-5 scale, and nothing is missing. */
    fun isValid(stress: Int?, rest: Int?, duty: Int?): Boolean =
        hasAllThree(stress, rest, duty) &&
            stress!! in DailyPulse.scale.min..DailyPulse.scale.max &&
            rest!! in DailyPulse.scale.min..DailyPulse.scale.max &&
            duty!! in DailyPulse.scale.min..DailyPulse.scale.max

    /**
     * Whether the answers on screen now differ from what is stored for today.
     *
     * Also true when no record exists yet but answers have been given, because those answers still
     * have to be written. Returns false for an untouched empty form, so a blank screen does not
     * claim to be an edit.
     */
    fun isChanged(
        existing: DailyPulseRecord?,
        stress: Int?,
        rest: Int?,
        duty: Int?,
        checkinRequested: Boolean
    ): Boolean {
        if (!hasAllThree(stress, rest, duty)) return false
        if (existing == null) return true
        return stress != existing.dStress ||
            rest != existing.dRest ||
            duty != existing.dDuty ||
            checkinRequested != existing.checkinRequested
    }

    /**
     * Whether the save/update button should be enabled.
     *
     * - **New check-in:** enabled as soon as all three questions are answered.
     * - **Existing check-in:** enabled once any answer actually changes. With nothing changed there
     *   is genuinely nothing to write, so the button stays off rather than inviting a save that
     *   would only rewrite identical data.
     *
     * Note what is absent: no stress score, no `completedToday`, no `dailyStressScore`.
     */
    fun canSave(
        existing: DailyPulseRecord?,
        stress: Int?,
        rest: Int?,
        duty: Int?,
        checkinRequested: Boolean
    ): Boolean {
        if (!isValid(stress, rest, duty)) return false
        if (existing == null) return true
        return isChanged(existing, stress, rest, duty, checkinRequested)
    }

    /** Button label. "Update" whenever a record already exists for today. */
    fun buttonLabel(existing: DailyPulseRecord?): String =
        if (existing == null) "Save Check-in" else "Update Check-in"

    /** One line explaining the current state, so a disabled button is never a dead end. */
    fun helperText(
        existing: DailyPulseRecord?,
        stress: Int?,
        rest: Int?,
        duty: Int?
    ): String = when {
        !hasAllThree(stress, rest, duty) ->
            "Answer all three questions to save today's check-in."
        !isValid(stress, rest, duty) ->
            "Each answer must be between ${DailyPulse.scale.min} and ${DailyPulse.scale.max}."
        existing == null ->
            "Your answers are saved to today's check-in."
        !isChanged(existing, stress, rest, duty, existing.checkinRequested) ->
            "Saved. Change any answer to update today's check-in."
        else -> "Tap to update today's check-in."
    }
}
/**
 * The three daily answers reduced to a plain **context category**, for display only.
 *
 * ### This is not a stress score
 * There is no number here on purpose. The old `self_daily = 100 * (...)` formula was removed, and
 * this replaces it with something that cannot be mistaken for a measurement: three words.
 *
 * - It is never persisted. [DailyPulseRecord] has no field for it and no storage format was added.
 * - It is never fed to the physiological ML model, whose input vector is fixed by trained weights.
 * - It is never combined with, averaged with, or compared numerically against Physiological Stress
 *   (0-100) or PSS-10 (0-40). The three sections of the Stress tab stay independent.
 *
 * ### How it is derived
 * The mean of the three answers, with "How rested do you feel?" inverted because a higher answer
 * there means *better*, not worse. That gives a 1-5 burden mean, split at 2.5 and 3.5. It is a
 * plain summary of what the person answered, not an instrument with published cut-offs.
 */
enum class DailyContextCategory(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}

/** Derives the display-only Daily Context category. See [DailyContextCategory]. */
object DailyContext {

    /**
     * Category for a completed three-question check-in, or null when not all three were answered.
     *
     * Requires all three answers on purpose. A category built from one of three questions would
     * look like a reading of the day while actually describing almost nothing.
     */
    fun category(stress: Int?, rest: Int?, duty: Int?): DailyContextCategory? {
        if (stress == null || rest == null || duty == null) return null
        if (stress !in 1..5 || rest !in 1..5 || duty !in 1..5) return null
        // rest: 1-5 where 5 is "fully rested", so 6 - rest puts it on the same "more is worse"
        // direction as the other two before averaging.
        val burden = (stress + (6 - rest) + duty) / 3.0
        return when {
            burden < 2.5 -> DailyContextCategory.LOW
            burden < 3.5 -> DailyContextCategory.MEDIUM
            else -> DailyContextCategory.HIGH
        }
    }

    /** Category from a stored record, or null when today's check-in is absent or partial. */
    fun category(record: DailyPulseRecord?): DailyContextCategory? =
        record?.let { category(it.dStress, it.dRest, it.dDuty) }
}
