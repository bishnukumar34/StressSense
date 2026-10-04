package com.bishnu.stresswelfarebridge.questionnaire

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * One day's physiological context, as produced by the **existing** physiological path.
 *
 * This layer never computes a stress score. It consumes what the sensor/ML pipeline already
 * produced and decides only whether a questionnaire *offer* is warranted. The physiological model
 * is untouched by this module.
 *
 * [quality] and [activityFlag] are what make context gating possible: a day whose rise is fully
 * explained by exercise or by a bad camera trace must not be read as psychological strain.
 */
data class DailySensorDay(
    val date: LocalDate,
    /** Physiological Stress Load 0..100 from the existing ML model. */
    val stressSensor: Int,
    /** 0..1 acquisition quality; null when unknown. */
    val quality: Double?,
    val hrvRmssd: Double? = null,
    val restingHr: Double? = null,
    val spo2: Double? = null,
    val sleepHours: Double? = null,
    /** True when the day included exercise. */
    val activityFlag: Boolean = false,
    /** True when the measurement fell inside sleeping hours. */
    val duringSleep: Boolean = false,
    /** True when the person was on a configured night duty. */
    val duringNightDuty: Boolean = false
)

/** Configurable thresholds. Every value is policy, not physiology, so it lives here. */
data class TriggerPolicy(
    val enterThreshold: Int = ENTER_THRESHOLD,
    val exitThreshold: Int = EXIT_THRESHOLD,
    val windowDays: Int = WINDOW_DAYS,
    val cooldownDays: Int = COOLDOWN_DAYS,
    val sharpRise: Int = SHARP_RISE,
    /** Acquisition quality below which a day is discarded. */
    val minQuality: Double = 0.5
) {
    companion object {
        /** Expository constants from the specification, also used as defaults. */
        const val ENTER_THRESHOLD = 65
        const val EXIT_THRESHOLD = 55
        const val WINDOW_DAYS = 3
        const val COOLDOWN_DAYS = 14
        const val SHARP_RISE = 15

        val DEFAULT = TriggerPolicy()
    }
}

/** What the engine decided, and why. */
data class TriggerDecision(
    val state: EpisodeState,
    /** True when a questionnaire should be offered right now. */
    val shouldOffer: Boolean,
    val reason: String,
    /** Days excluded by context gating, for transparency in developer tooling. */
    val excludedDays: Int = 0
)

/**
 * Decides when to *offer* a questionnaire, from a persistent physiological trend.
 *
 * The central design rule: **one elevated day is never enough.** A single reading of 65 from a bad
 * camera trace after a run is not a stress episode, and offering a psychological questionnaire
 * because of it would be both wrong and potentially alarming. The engine requires a sustained
 * pattern, after removing days a better explanation already accounts for.
 *
 * Context gating removes days *before* they are counted: exercise, sleeping hours, night duty and
 * low-quality acquisitions. Exercise and night duty in particular raise physiological load for
 * entirely non-psychological reasons, and reading those as strain is the most likely way this
 * feature could cause harm.
 */
class QuestionnaireTriggerEngine(
    private val policy: TriggerPolicy = TriggerPolicy.DEFAULT,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Hours treated as sleeping when a day carries no explicit sleep flag. */
    private val sleepingHours: IntRange = 0..6
) {

    /** Days that count towards a trend. */
    fun usableDays(days: List<DailySensorDay>): List<DailySensorDay> = days
        .filter { it.quality == null || it.quality >= policy.minQuality }
        .filterNot { it.activityFlag }
        .filterNot { it.duringSleep || it.duringNightDuty }
        .sortedBy { it.date }

    /**
     * @param episode the currently open episode, or null when in [EpisodeState.NORMAL].
     * @param lastTriggeredAt when the most recent questionnaire was last offered (with or without
     *   an open episode). This is what enforces the cooldown while in [EpisodeState.NORMAL]; it is
     *   tracked separately from the episode so a closed episode still suppresses a new one.
     * @param now injected so the cooldown maths is deterministic under test.
     */
    fun evaluate(
        history: List<DailySensorDay>,
        episode: StressEpisode?,
        lastTriggeredAt: Instant? = null,
        now: Instant = Instant.now()
    ): TriggerDecision {
        val usable = usableDays(history)
        val excluded = history.size - usable.size
        val last3 = usable.takeLast(policy.windowDays)
        val meanLast3 =
            if (last3.size == policy.windowDays) last3.map { it.stressSensor }.average() else null
        val cooldownElapsed = lastTriggeredAt == null ||
            Duration.between(lastTriggeredAt, now).toDays() >= policy.cooldownDays

        // ---- NORMAL: open an episode only on a persistent pattern ---------------------------------
        if (episode == null) {
            if (meanLast3 == null) {
                return TriggerDecision(
                    EpisodeState.NORMAL, false,
                    "Not enough usable days yet (need ${policy.windowDays}, have ${last3.size}).",
                    excluded
                )
            }
            val sustainedMean = meanLast3 >= policy.enterThreshold
            val countAbove = usable.takeLast(5).count { it.stressSensor >= policy.enterThreshold }
            val clusteredElevated = countAbove >= policy.windowDays

            if (!sustainedMean && !clusteredElevated) {
                return TriggerDecision(
                    EpisodeState.NORMAL, false,
                    "No persistent pattern: mean of last ${policy.windowDays} days is " +
                        "${"%.1f".format(meanLast3)}, below ${policy.enterThreshold}.", excluded
                )
            }
            if (!cooldownElapsed) {
                return TriggerDecision(
                    EpisodeState.NORMAL, false,
                    "Persistent pattern present, but the ${policy.cooldownDays}-day cooldown has " +
                        "not elapsed since $lastTriggeredAt.", excluded
                )
            }
            return TriggerDecision(
                EpisodeState.NORMAL, true,
                if (sustainedMean)
                    "Persistent elevation: mean of last ${policy.windowDays} days is " +
                        "${"%.0f".format(meanLast3)}."
                else
                    "Persistent elevation: $countAbove days at or above ${policy.enterThreshold} " +
                        "in the last 5 days.",
                excluded
            )
        }

        // ---- EPISODE_OPEN ----------------------------------------------------------------------------
        if (meanLast3 == null) {
            return TriggerDecision(
                EpisodeState.EPISODE_OPEN, false,
                "Episode open; not enough new usable days to judge.", excluded
            )
        }

        // Close only when EVERY one of the last windowDays days has settled.
        if (last3.all { it.stressSensor < policy.exitThreshold }) {
            return TriggerDecision(
                EpisodeState.NORMAL, false,
                "All of the last ${policy.windowDays} days are below ${policy.exitThreshold}; " +
                    "episode can close.", excluded
            )
        }

        // Re-escalation is the ONLY way to make a second offer within an episode. It needs both a genuine
        // sharp rise over the level of the previous offer, and the cooldown measured from that
        // offer - never from openedAt, which may be weeks earlier.
        val required = episode.lastTriggerLevel + policy.sharpRise
        val cooldownFrom = episode.lastOfferedAt ?: episode.openedAt
        val episodeCooldownElapsed =
            Duration.between(cooldownFrom, now).toDays() >= policy.cooldownDays

        if (meanLast3 >= required && episodeCooldownElapsed) {
            return TriggerDecision(
                EpisodeState.EPISODE_OPEN, true,
                "Re-escalation: mean ${"%.0f".format(meanLast3)} reached $required " +
                    "(previous offer level ${episode.lastTriggerLevel}).", excluded
            )
        }
        return TriggerDecision(
            EpisodeState.EPISODE_OPEN, false,
            when {
                !episodeCooldownElapsed ->
                    "Episode open; fewer than ${policy.cooldownDays} days since the last offer."
                episode.offerMade ->
                    "Episode open; mean ${"%.0f".format(meanLast3)} has not risen far enough above " +
                        "the last offer to warrant another questionnaire."
                else ->
                    "Episode open; mean ${"%.0f".format(meanLast3)} below re-escalation level $required."
            },
            excluded
        )
    }

    /** Level an episode should open at, stored as [StressEpisode.lastTriggerLevel]. */
    fun triggerLevel(history: List<DailySensorDay>): Int {
        val last3 = usableDays(history).takeLast(policy.windowDays)
        return if (last3.isEmpty()) 0 else last3.map { it.stressSensor }.average().toInt()
    }

    /** True when [at] falls inside the configured sleeping hours. */
    fun isSleepingTime(at: Instant): Boolean = at.atZone(zone).toLocalTime().hour in sleepingHours
}