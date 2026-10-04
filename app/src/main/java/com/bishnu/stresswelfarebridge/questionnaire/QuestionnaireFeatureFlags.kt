package com.bishnu.stresswelfarebridge.questionnaire

/**
 * Release-gated capabilities for the self-report layer.
 *
 * The point of this file is that the risky things are **off by default and cannot be switched on by
 * data**. A future build may enable [tier3Screening], but enabling it does nothing on its own: see
 * [SafetyProtocolGate].
 */
object QuestionnaireFeatureFlags {

    /** Daily Pulse - our own instrument, safe to ship. */
    const val DAILY_PULSE = true

    /**
     * DASS-21 presentation.
     *
     * Off until the official wording is pasted in and verified. The scoring is already implemented;
     * what is missing is verified wording, which is a human/licensing step, not a code step.
     */
    val DASS_21_PRESENTATION: Boolean = false

    /** PSS-10 monthly baseline - blocked pending permission/licence. */
    const val PSS_10 = false

    /**
     * Tier 3 screening (PHQ-2/9, GAD-2/7, PC-PTSD-5/PCL-5).
     *
     * **Disabled by design.** These instruments include items about self-harm and suicidal thoughts.
     * Turning this on without an approved clinical response protocol would mean collecting
     * disclosures the app has no approved way to respond to, which is worse than not collecting them.
     */
    const val tier3Screening: Boolean = false
}

/**
 * Gate for any Tier 3 clinical or crisis behaviour.
 *
 * Deliberately unimplemented and deliberately impossible to satisfy by accident. Tier 3 stays off
 * until the unit's medical/psychological authority has approved a response protocol, in writing.
 *
 * ### What is intentionally absent
 *  - **No crisis phone numbers.** They are deployment configuration supplied by the unit, never
 *    hard-coded. A hard-coded number would be wrong for every deployment but one, and could be
 *    harmful in the one that is.
 *  - **No automated clinical escalation.** Nothing here decides that a person is at risk or acts on
 *    their behalf.
 *  - **No diagnosis.** No Tier 3 score is ever described as a diagnosis, however high it is.
 */
object SafetyProtocolGate {

    /** Set to true only when a written, approved protocol is in place. Not a runtime config value. */
    const val APPROVED_CLINICAL_PROTOCOL_IN_PLACE: Boolean = false

    /** Whether Tier 3 may ever run. Currently no. */
    val tier3Allowed: Boolean
        get() = QuestionnaireFeatureFlags.tier3Screening && APPROVED_CLINICAL_PROTOCOL_IN_PLACE

    /**
     * Crisis support details. Empty means "not configured for this deployment" - the UI must show a
     * neutral message rather than an empty or invented number.
     */
    data class CrisisContact(val label: String, val detail: String)

    /** Supplied by deployment configuration, never hard-coded. */
    fun configuredContacts(): List<CrisisContact> = emptyList()

    fun contactsConfigured(): Boolean = configuredContacts().isNotEmpty()
}