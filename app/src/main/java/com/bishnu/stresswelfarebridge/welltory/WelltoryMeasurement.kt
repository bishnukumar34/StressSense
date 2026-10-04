package com.bishnu.stresswelfarebridge.welltory

/**
 * One validated Welltory camera measurement, owned by exactly one StressSense account.
 *
 * ## What is ours and what is Welltory's
 *
 * Fields prefixed `welltory` are **Welltory's proprietary outputs**. They are retained purely as
 * provenance metadata - so the app can show what Welltory itself reported alongside, and so a
 * measurement can be audited later - and they are deliberately excluded from the feature vector.
 *
 * The StressSense physiological stress score is produced only by the deployed `wesad-loso-logreg-v1`
 * model over this measurement's HRV. A Welltory `stress` of 0.72 is **not** shown as 72 and **not**
 * used as our model output; see [WelltoryBridge].
 *
 * ## Heart rate
 *
 * [heartRateBpm] is null whenever Welltory supplied no heart rate, which is the normal case: its
 * documented callback extras contain no HR field. A value is only ever attached here when it is a
 * real, validated reading carried over from another legitimate StressSense source (see
 * [WelltoryBridge.attachRealHeartRate]). No default, estimate or placeholder is ever substituted -
 * a missing heart rate stays missing and the model treats it as a missing feature, which is exactly
 * what its median-imputation plus missingness-indicator design was trained to handle.
 */
data class WelltoryMeasurement(
    /** The StressSense account that owns this measurement. Ownership is never inferred. */
    val accountId: String,
    val measuredAtMs: Long,

    // -- Real physiological HRV actually returned by Welltory, in milliseconds -------------------
    val rmssdMs: Double,
    val sdnnMs: Double,

    /** Real HR only when legitimately available from another StressSense source; else null. */
    val heartRateBpm: Double? = null,

    // -- Welltory proprietary values: metadata only, never a StressSense score --------------------
    val welltoryStress: Double? = null,
    val welltoryEnergy: Double? = null,
    val welltoryProductivity: Double? = null,
    val welltoryTotalPower: Double? = null,
    val welltoryToken: String? = null,

    /**
     * Identity of this Welltory measurement for de-duplication.
     *
     * Welltory's share token when present, otherwise the single-use request nonce. Either way the
     * same result delivered twice resolves to one record, so a callback arriving in both
     * `onNewIntent` and a recreated `onCreate` cannot double-count.
     */
    val dedupeKey: String,

    // -- Documented interpretation colours: green / yellow / red / unknown -----------------------
    val stressColor: String? = null,
    val energyColor: String? = null,
    val productivityColor: String? = null,

    /** Always [WelltoryConfig.SOURCE_WELLTORY_CAMERA] so it is never shown as Health Connect. */
    val source: String = WelltoryConfig.SOURCE_WELLTORY_CAMERA,

    /** The unit validation that admitted this measurement. */
    val validation: WelltoryHrvValidation
) {
    /**
     * Stable id for the persisted record.
     *
     * Derived from [dedupeKey] so persisting the same Welltory measurement twice overwrites the same
     * row rather than appending a duplicate - which is what makes callback handling idempotent.
     */
    val recordId: String get() = "wt-$dedupeKey"

    /**
     * True when a heart rate is genuinely present. Callers must check this rather than defaulting,
     * because the model's cold-start gate is keyed on heart-rate baseline samples.
     */
    val hasRealHeartRate: Boolean get() = heartRateBpm != null

    /**
     * Returns a copy carrying a real, validated heart rate.
     *
     * Refuses anything that is not a physiologically plausible bpm value, so a caller cannot use this
     * to smuggle a fabricated heart rate into a model input.
     */
    fun attachRealHeartRate(bpm: Double?): WelltoryMeasurement {
        if (bpm == null) return this
        require(WelltoryCallbackValidator.isRealisticHeartRate(bpm)) {
            "Refusing to attach an implausible heart rate ($bpm) to a Welltory measurement."
        }
        return copy(heartRateBpm = bpm)
    }
}