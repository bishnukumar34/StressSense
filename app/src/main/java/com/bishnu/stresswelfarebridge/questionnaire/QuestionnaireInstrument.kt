package com.bishnu.stresswelfarebridge.questionnaire

/**
 * Registry of every questionnaire the app knows about.
 *
 * The critical property here is [QuestionnaireDefinition.verification]. DASS-21's scoring,
 * subscales and severity bands are fully implemented and unit-tested, but its **wording is not in
 * this repository**: it must be pasted verbatim from the official UNSW download (linked below)
 * into [DASS_21], and its verification flipped to [VerificationStatus.VERIFIED] by a human who has
 * checked it against the source. Until then [DASS_21.isShippable] is false and the UI refuses to
 * present it.
 *
 * This is deliberate. Transcribing 21 items from memory would risk silently altering a published
 * instrument, which is the one error a self-report tool must never make.
 *
 * NOTE: the constants below are declared *before* the definitions on purpose. Kotlin initialises an
 * object's properties in declaration order, so a definition built from a later-declared constant
 * would capture it uninitialised.
 */
object QuestionnaireInstrument {

    const val DASS_21_ID = "dass21"
    const val PSS_10_ID = "pss10"

    // -- published item maps (DASS-21) ---------------------------------------------------------------
    val DASS_STRESS_ITEMS = listOf(1, 6, 8, 11, 12, 14, 18)
    val DASS_ANXIETY_ITEMS = listOf(2, 4, 7, 9, 15, 19, 20)
    val DASS_DEPRESSION_ITEMS = listOf(3, 5, 10, 13, 16, 17, 21)

    /** Bands from the DASS manual. Re-verify against the manual before release. */
    private val DASS_STRESS_BANDS = listOf(
        SeverityBand("Normal", 0, 14),
        SeverityBand("Mild", 15, 18),
        SeverityBand("Moderate", 19, 25),
        SeverityBand("Severe", 26, 33),
        SeverityBand("Extremely severe", 34, null)
    )
    private val DASS_ANXIETY_BANDS = listOf(
        SeverityBand("Normal", 0, 7),
        SeverityBand("Mild", 8, 9),
        SeverityBand("Moderate", 10, 14),
        SeverityBand("Severe", 15, 19),
        SeverityBand("Extremely severe", 20, null)
    )
    private val DASS_DEPRESSION_BANDS = listOf(
        SeverityBand("Normal", 0, 9),
        SeverityBand("Mild", 10, 13),
        SeverityBand("Moderate", 14, 18),
        SeverityBand("Severe", 19, 27),
        SeverityBand("Extremely severe", 28, null)
    )

    /** PSS-10 items 4, 5, 7 and 8 are phrased in the opposite direction. */
    val PSS_REVERSE_ITEMS = setOf(4, 5, 7, 8)

    /**
     * The ten PSS-10 item texts, in presentation order.
     *
     * ### PROTOTYPE WORDING - REPLACE BEFORE PRODUCTION DEPLOYMENT
     *
     * Held in one constant so the authorised wording can be dropped in with a single edit. Permission
     * for PSS-10 has **not** been granted; see [PSS_10] before deploying this to anyone real.
     *
     * The ordering here is aligned so that the positively worded items are exactly
     * [PSS_REVERSE_ITEMS] (4, 5, 7, 8). That alignment is deliberate and load-bearing: the scorer
     * reverses those four positions unconditionally, so if this text were reordered without also
     * updating the reverse set, the total would still land in 0-40 while measuring the wrong thing.
     * `Pss10Test.reverseScoredItemsArePositivelyWorded` guards that relationship.
     *
     * Every item is prefixed with the instrument's own reference period, because PSS-10 asks about the
     * *last month* and the result screen says so.
     */
    val PSS_ITEM_TEXTS: List<String> = listOf(
        "In the last month, how often have you felt nervous and \"stressed\"?",            // 1
        "In the last month, how often have you felt that you couldn't cope with all the " +
            "things you had to do?",                                                          // 2
        "In the last month, how often have you felt that you were unable to control the " +
            "important things in your life?",                                                // 3
        "In the last month, how often have you felt confident and capable of handling all " +
            "the problems?",                                                                 // 4  reverse
        "In the last month, how often have you felt able to control the important things in " +
            "your life?",                                                                    // 5  reverse
        "In the last month, how often have you felt that you had so many demands on you " +
            "that you couldn't keep up?",                                                     // 6
        "In the last month, how often have you felt that things were going your way?",     // 7  reverse
        "In the last month, how often have you felt able to control the annoyances in your " +
            "life?",                                                                          // 8  reverse
        "In the last month, how often have you felt angry that things were happening to you " +
            "in an annoying way?",                                                            // 9
        "In the last month, how often have you felt that difficulties were piling up so high " +
            "that you couldn't overcome them?"                                                // 10
    )

    /** Tier 3 screening instruments. Registered for architecture only " never enabled automatically. */
    val TIER_3_IDS = listOf("phq2", "phq9", "gad2", "gad7", "pcptsd5", "pcl5")

    /**
     * DASS-21 (Lovibond & Lovibond).
     *
     * Official source: https://www.psy.unsw.edu.au/dass/ " the DASS is stated there to be **in the
     * public domain** and free to download. The wording and scoring template are distributed as Word
     * documents and the manual is sold separately; the bands above come from that manual and should
     * be re-checked against it before release.
     */
    val DASS_21: QuestionnaireDefinition = QuestionnaireDefinition(
        id = DASS_21_ID,
        version = 1,
        title = "Stress check-in (DASS-21)",
        items = (1..21).map { n ->
            QuestionnaireItem(
                id = "dass_$n",
                // PLACEHOLDER. Replace with the official wording from
                // https://www.psy.unsw.edu.au/dass/ before this instrument is shown to anyone.
                textKey = "PLACEHOLDER_DASS_ITEM_$n",
                subscale = when (n) {
                    in DASS_STRESS_ITEMS -> "stress"
                    in DASS_ANXIETY_ITEMS -> "anxiety"
                    in DASS_DEPRESSION_ITEMS -> "depression"
                    else -> null
                }
            )
        },
        scale = ResponseScale(0, 3, "Did not apply at all", "Applied very much"),
        // Order matters: the first subscale is what `score.primary` reports, i.e. stress.
        subscales = mapOf(
            "stress" to DASS_STRESS_ITEMS.map { "dass_$it" },
            "anxiety" to DASS_ANXIETY_ITEMS.map { "dass_$it" },
            "depression" to DASS_DEPRESSION_ITEMS.map { "dass_$it" }
        ),
        bands = mapOf(
            "stress" to DASS_STRESS_BANDS,
            "anxiety" to DASS_ANXIETY_BANDS,
            "depression" to DASS_DEPRESSION_BANDS
        ),
        // DASS subscale sums are multiplied by 2 so the result lands on 0-42.
        doubleScoredSubscales = setOf("stress", "anxiety", "depression"),
        triggerTypes = setOf(TriggerType.TRIGGERED, TriggerType.MANUAL),
        // Not shippable until the official wording is pasted in and checked.
        verification = VerificationStatus.PENDING_WORDING,
        licensingNote =
            "DASS is stated by UNSW (https://www.psy.unsw.edu.au/dass/) to be in the public domain. " +
                "Wording MUST be taken verbatim from that source. Severity bands come from the DASS " +
                "manual (sold separately) and must be re-verified against it. Citation: Lovibond, " +
                "S.H. & Lovibond, S.H. (1995). Manual for the Depression Anxiety Stress Scales. " +
                "Psychology Foundation of Australia.",
        isPsychologicalInstrument = true
    )

    /**
     * Perceived Stress Scale (Cohen, Kamarck & Mermelstein, 1983).
     *
     * PSS-10 also has no officially endorsed clinical cut-offs, so presenting unofficial thresholds
     * as clinical bands would misrepresent it. It is used here for personal trend comparison rather
     * than severity labelling, and [PSS_10] ships with no [bands] for exactly that reason.
     *
     * ### WORDING - PROTOTYPE, MUST BE REPLACED BEFORE PRODUCTION
     *
     * The wording below was entered for this research build so the flow is usable and testable. It is
     * held in one constant ([PSS_ITEM_TEXTS]) precisely so it can be replaced in a single edit with the
     * verbatim text from the authorised source, with no other code change.
     *
     * **[VerificationStatus.PROTOTYPE_PENDING_LICENCE] is a deliberate, honest status.** Permission has
     * NOT been granted. Nothing in this file asserts that it has, and [isShippable] remains false so
     * no release path can mistake this for clearance.
     *
     * **One item must be checked before any real deployment.** PSS-10 reverse-scores four positively
     * worded items. The set [PSS_REVERSE_ITEMS] is fixed and authoritative, and the wording has been
     * aligned to it, but published transcriptions of this instrument differ in item *ordering*. If
     * the authorised source orders them differently, the wording must be reordered to match - otherwise
     * the total silently measures the wrong thing while still looking like a valid 0-40 score.
     */
    val PSS_10: QuestionnaireDefinition = QuestionnaireDefinition(
        id = PSS_10_ID,
        version = 1,
        title = "Perceived Stress (PSS-10)",
        items = (1..10).map { n ->
            QuestionnaireItem(
                id = "pss_$n",
                textKey = PSS_ITEM_TEXTS[n - 1],
                subscale = "perceived_stress",
                reverseScored = n in PSS_REVERSE_ITEMS
            )
        },
        scale = ResponseScale(0, 4, "Never", "Very often"),
        subscales = mapOf("perceived_stress" to (1..10).map { "pss_$it" }),
        // Intentionally empty: no official clinical bands exist for PSS-10.
        bands = emptyMap(),
        triggerTypes = setOf(TriggerType.MONTHLY),
        // Usable in this prototype build; explicitly NOT cleared for release.
        verification = VerificationStatus.PROTOTYPE_PENDING_LICENCE,
        licensingNote =
            "PSS-10 is a copyrighted instrument (Cohen, S., Kamarck, T., & Mermelstein, S. (1983). " +
                "A global measure of perceived stress. Journal of Personality and Social Psychology, " +
                "54(6), 1064-1078). PERMISSION HAS NOT BEEN GRANTED. The wording in this build is " +
                "prototype text that must be replaced with the verbatim authorised wording before " +
                "production deployment, and the item ordering verified against that source. The " +
                "instrument authors publish no clinical cut-offs, so unofficial severity bands must " +
                "not be presented as official. Citation must accompany any use.",
        isPsychologicalInstrument = true
    )

    private val all = listOf(DailyPulse.definition, DASS_21, PSS_10)

    fun byId(id: String): QuestionnaireDefinition? = all.firstOrNull { it.id == id }

    /** Everything registered, including instruments that are not yet shippable. */
    fun all(): List<QuestionnaireDefinition> = all

    /** Only those cleared for presentation. */
    fun shippable(): List<QuestionnaireDefinition> = all.filter { it.isShippable }
}
