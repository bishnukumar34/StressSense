package com.bishnu.stresswelfarebridge.welltory

/**
 * How a Welltory `rmssd`/`sdnn` extra is interpreted, and the honest limits of that interpretation.
 *
 * ## The problem
 *
 * Welltory's integration documentation lists its callback extras as:
 *
 * | extra | documented description |
 * |---|---|
 * | `rmssd` | "user's **rmssd index**" |
 * | `sdnn`  | "user's **sdnn index**"  |
 * | `power` | "user's power index"    |
 *
 * "Index" is not a unit. Welltory's consumer app presents RMSSD and SDNN to users as normalised
 * 0-100 scores, whereas StressSense's deployed model was trained on WESAD and expects genuine
 * interval durations **in milliseconds** (`rmssd_ms`, `sdnn_ms`; its RMSSD training median is
 * 43.78 ms and its SDNN median is 65.91 ms).
 *
 * Treating a 0-100 index as a millisecond value would be a silent unit error: the number still looks
 * plausible, the model still returns a confident score, and nobody can tell the result is meaningless.
 * That is precisely the class of bug this file exists to prevent.
 *
 * ## What we do instead
 *
 * We validate that a returned value is a **physiologically plausible millisecond HRV measurement**
 * before it is allowed anywhere near the model:
 *
 *  - RMSSD must fall in [WelltoryConfig.RMSSD_MS_RANGE] (1-300 ms)
 *  - SDNN must fall in [WelltoryConfig.SDNN_MS_RANGE] (1-400 ms)
 *
 * Anything outside those bounds is rejected with [WelltoryHrvUnits] reporting a unit mismatch, and
 * **no stress score is produced**. We never clamp, rescale or re-map the value into range, because
 * inventing a conversion factor would manufacture physiological data.
 *
 * ## Why accepting in-range values is still legitimate
 *
 * The bounds are not a guess: short-term RMSSD and SDNN from a resting camera measurement genuinely
 * occupy roughly this range in milliseconds, so a value inside it is consistent with a real HRV
 * measurement in the units the model expects. Where the interpretation is genuinely uncertain, the
 * outcome is *abstention* (an honest "no score") rather than a wrong number.
 */
enum class WelltoryHrvUnits {

    /** Returned values were plausible as millisecond HRV measurements; safe to feed the model. */
    MILLISECONDS,

    /**
     * Values fell outside physiological millisecond bounds, so they are almost certainly Welltory's
     * 0-100 index rather than a duration. Rejected - no score, no persisted HRV.
     */
    INDEX_NOT_MILLISECONDS
}

/** Outcome of validating one returned HRV pair. */
data class WelltoryHrvValidation(
    val units: WelltoryHrvUnits,
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    /** Human-readable explanation, surfaced in the UI when a measurement is refused. */
    val reason: String
) {
    /** True only when both values are real millisecond measurements. */
    val usable: Boolean
        get() = units == WelltoryHrvUnits.MILLISECONDS && rmssdMs != null && sdnnMs != null
}