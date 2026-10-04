package com.bishnu.stresswelfarebridge.questionnaire

import java.time.Instant
import java.time.LocalDate

/**
 * One stored Daily Check-in.
 *
 * There is deliberately **no score field**. These three answers are contextual self-report data;
 * the perceived-stress score comes from [Pss10Record]. Keeping a numeric field here would
 * invite exactly the conflation this model is meant to avoid.
 */
data class DailyPulseRecord(
    val userId: String,
    val date: LocalDate,
    /** Individual 1-5 answers, or null when that question was skipped. Never defaulted to 0. */
    val dStress: Int?,
    val dRest: Int?,
    val dDuty: Int?,
    /** The optional "I would like someone to check in with me" request. Not part of any score. */
    val checkinRequested: Boolean,
    val createdAt: Instant
)

/**
 * One completed PSS-10, kept per calendar month.
 *
 * Each month gets its own record, so completing in November never overwrites October.
 */
data class Pss10Record(
    val id: String,
    val userId: String,
    val version: Int,
    val completedAt: Instant,
    /** Calendar month this response belongs to, e.g. 2026-10. */
    val month: java.time.YearMonth,
    /** Item number (1-10) to raw 0-4 answer. */
    val answers: Map<Int, Int>,
    /** Sum of reverse-adjusted items, 0-40. Null when the response was incomplete. */
    val score: Int?,
    val completed: Boolean
)

/** A completed questionnaire response. */
data class QuestionnaireResponse(
    val id: String,
    val userId: String,
    val instrument: String,
    val version: Int,
    val administeredAt: Instant,
    val triggerType: TriggerType,
    /** Item id  answer. Raw, exactly as the person gave it. Never modified in storage. */
    val items: Map<String, Int>,
    val scores: QuestionnaireScore,
    val completed: Boolean,
    /**
     * Whether the completed response is still waiting to be sent to a backend.
     *
     * A response is persisted the moment it is completed, online or not. [pendingSync] is what makes
     * "works offline" real rather than aspirational: nothing is lost, and the queue drains later.
     */
    val pendingSync: Boolean = true
)

/**
 * An episode of persistent elevated physiological stress.
 *
 * This tracks **physiological** load only. It is deliberately not a "mental health episode" and
 * must not be described as one - the questionnaire offered during an episode is a self-report, not
 * an assessment.
 */
data class StressEpisode(
    val id: String,
    val userId: String,
    val openedAt: Instant,
    val closedAt: Instant?,
    /** Level at which the most recent offer was made; a new offer must exceed it by [TriggerPolicy.sharpRise]. */
    val lastTriggerLevel: Int,
    /** Whether the questionnaire offer has been surfaced for this episode. */
    val offerMade: Boolean = false,
    /**
     * When the most recent questionnaire was actually offered.
     *
     * This - not [openedAt] - is what the 14-day cooldown is measured from. An episode can open long
     * before a questionnaire is offered, and a person must never receive two offers less than the
     * cooldown apart just because the episode has been open for weeks. Null until the first offer.
     */
    val lastOfferedAt: Instant? = null,
    /** Whether the person dismissed the offer, so we remind exactly once. */
    val dismissedAt: Instant? = null,
    /** Whether the single follow-up reminder has been sent. */
    val reminderSent: Boolean = false
)

enum class EpisodeState { NORMAL, EPISODE_OPEN }

/**
 * What Commander is permitted to know about a person's questionnaire use.
 *
 * The full answers never leave the personnel record. This is the entire vocabulary a commander-side
 * view is allowed to use - there is deliberately no type here that can carry an item response.
 */
enum class CheckInStatus {
    /** No questionnaire activity recorded. */
    NONE,

    /** A questionnaire was offered and dismissed. */
    OFFERED,

    /** A check-in was offered and is awaiting a response. */
    AWAITING,

    /** A questionnaire was completed. Only the fact of completion, never the content. */
    COMPLETED
}

/** Commander-visible summary. Contains no answers, no items, no subscale detail. */
data class PersonnelCheckInSummary(
    val userId: String,
    val displayName: String,
    val status: CheckInStatus,
    val lastCompletedAt: Instant?,
    val checkInRequested: Boolean
)
