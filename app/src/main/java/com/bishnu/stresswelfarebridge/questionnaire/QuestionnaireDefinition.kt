package com.bishnu.stresswelfarebridge.questionnaire

/**
 * Why and when a questionnaire was offered. Recorded with every response so a later analysis can
 * distinguish a routine daily check-in from one offered because an episode opened.
 */
enum class TriggerType {
    /** Voluntary daily pulse. */
    DAILY,

    /** Offered automatically because a persistent elevated-stress episode opened. */
    TRIGGERED,

    /** Scheduled periodic instrument (e.g. PSS-10), subject to licensing approval. */
    MONTHLY,

    /** Started by the person themselves. */
    MANUAL
}

/**
 * Whether an instrument's wording and terms are cleared for release.
 *
 * This exists because the difference between "we implemented the scoring" and "we are permitted and
 * able to show these exact questions" is easy to lose. An instrument that is not
 * [VERIFIED] must not be presented to users, and [QuestionnaireInstrument.isShippable] is the gate.
 */
enum class VerificationStatus {
    /** Implemented but wording still to be pasted from the official source. */
    PENDING_WORDING,

    /** Wording, bands and terms checked against the official source. */
    VERIFIED,

    /** Architecture exists; blocked pending permission/licensing. */
    BLOCKED_PENDING_LICENCE,

    /**
     * Presentable in a **prototype/research build only**, licensing still outstanding.
     *
     * This status exists so the PSS-10 flow can be exercised and tested end to end before the
     * permission is cleared, without ever asserting that the permission *has* been cleared.
     *
     * The distinction it protects is important and is encoded in [QuestionnaireDefinition.isPresentable]
     * vs [QuestionnaireDefinition.isShippable]:
     *  - [isPresentable] is true - a prototype build may show the instrument.
     *  - [isShippable] is **false** - no production/release path may treat it as cleared.
     *
     * [QuestionnaireDefinition.requireShippable] therefore still refuses it, so anything guarding a
     * release cannot be satisfied by flipping this status.
     */
    PROTOTYPE_PENDING_LICENCE
}

/**
 * A complete, versioned questionnaire definition.
 *
 * Deliberately data-driven: nothing about a specific instrument is encoded in the scorer or the UI.
 * Adding or re-versioning an instrument is a data change plus tests.
 */
data class QuestionnaireDefinition(
    val id: String,
    val version: Int,
    val title: String,
    val items: List<QuestionnaireItem>,
    val scale: ResponseScale,
    /** Subscale name  item ids, in scoring order. Empty for single-scale instruments. */
    val subscales: Map<String, List<String>>,
    /** Subscale name  severity bands. Empty for instruments with no official bands. */
    val bands: Map<String, List<SeverityBand>>,
    /**
     * Whether a subscale total is multiplied by 2 after summing, as DASS requires.
     *
     * DASS-21 subscale sums are doubled so the result lands on a 0-42 scale; without this flag a
     * DASS score would be silently halved and every band would be wrong.
     */
    val doubleScoredSubscales: Set<String> = emptySet(),
    val triggerTypes: Set<TriggerType> = setOf(TriggerType.MANUAL),
    val verification: VerificationStatus,
    /** Provenance, ownership and permitted-use note. Shown in developer tooling, never to users. */
    val licensingNote: String,
    /** True when this instrument reports on mental-health constructs rather than daily feeling. */
    val isPsychologicalInstrument: Boolean = false
) {
    val itemById: Map<String, QuestionnaireItem> = items.associateBy { it.id }

    fun item(id: String): QuestionnaireItem? = itemById[id]

    fun subscaleItems(name: String): List<QuestionnaireItem> =
        subscales[name].orEmpty().mapNotNull { itemById[it] }

    /**
     * Whether this definition may be shown to a person.
     *
     * True for a cleared instrument, and also for a prototype build explicitly marked
     * [VerificationStatus.PROTOTYPE_PENDING_LICENCE]. This is the gate the UI consults.
     */
    val isPresentable: Boolean
        get() = verification == VerificationStatus.VERIFIED ||
            verification == VerificationStatus.PROTOTYPE_PENDING_LICENCE

    /**
     * This definition may be shown to a person only when its wording and terms are cleared.
     *
     * Deliberately **stays false** for [VerificationStatus.PROTOTYPE_PENDING_LICENCE]: being usable
     * in a research build is not the same as being cleared for release, and nothing that guards a
     * production path should read a prototype status as approval.
     */
    val isShippable: Boolean get() = verification == VerificationStatus.VERIFIED

    /** Fails loudly rather than letting an unverified instrument reach a user. */
    fun requireShippable() {
        require(isShippable) {
            "$id v$version is $verification and must not be presented to users. " +
                "See licensingNote."
        }
    }

    fun bandFor(subscale: String, score: Int): SeverityBand? =
        bands[subscale]?.firstOrNull { it.contains(score) }
}