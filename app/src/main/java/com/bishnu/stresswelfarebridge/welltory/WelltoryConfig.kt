package com.bishnu.stresswelfarebridge.welltory

/**
 * The single, documented configuration point for the Welltory integration.
 *
 * ## Where the documented mechanism comes from
 *
 * Welltory publishes **no Android SDK**. The integration is a plain `Intent` exchange, documented in
 * Welltory's own reference repository (`Welltory/measure-stress-hrv-android`, MIT):
 *
 *  - **Launch.** First request:
 *    `https://play.google.com/store/apps/details?id=com.welltory.client.android&referrer=<utf8 params>`
 *    which resolves to the measurement when Welltory is installed and falls through to the Play
 *    listing when it is not. Every later request uses the direct scheme
 *    `welltory://branch/Measurement/Start/<utf8 params>`.
 *  - **Params.** `source` (shown in Welltory's UI), `callback` (`<package>/<activity>`), plus any
 *    custom parameters, which Welltory echoes back on the result Intent.
 *  - **Flags.** `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`.
 *  - **Callback.** Delivered to the activity named in `callback`, which must be `exported="true"`,
 *    arriving in **either** `onNewIntent` (app still alive) **or** `onCreate` (Android recreated the
 *    process). Both are handled.
 *  - **Result extras.** Floats `stress`, `energy`, `productivity`, `rmssd`, `sdnn`, `power`;
 *    String `token`; Strings `stress_c`, `energy_c`, `productivity_c`; plus the custom parameters.
 *
 * Nothing here reverse-engineers, scrapes or automates Welltory. Only the documented extras are read.
 *
 * ## Partner approval (NOT bypassed)
 *
 * Welltory requires each integrator to complete their Integration Request Form before the callback
 * domain is approved for their package. This app has **not** been approved. Consequently:
 *
 *  - [APPROVED_PARTNER_ID] is empty on purpose. No token, client id or credential is invented here.
 *  - Until Welltory approves this package, a real Welltory install may decline to return results.
 *    The app therefore treats "launched but nothing came back" as a first-class, honest state
 *    instead of pretending a measurement happened.
 *
 * Nothing in the pipeline is gated on approval: parsing, validation, persistence and scoring are all
 * fully implemented and unit-tested, so the moment approval lands the flow works end to end.
 */
object WelltoryConfig {

    /** Shown in Welltory's own UI as the referring application. */
    const val SOURCE_APP_NAME: String = "StressSense"

    /** Play Store id of the Welltory app, used for the documented install fallback. */
    const val WELLTORY_PACKAGE: String = "com.welltory.client.android"

    /** Direct measurement scheme for subsequent requests. */
    const val MEASUREMENT_SCHEME_URI: String = "welltory://branch/Measurement/Start/"

    /** Install/install-or-measure link used for the first request and when Welltory is absent. */
    const val PLAY_STORE_URI_TEMPLATE: String =
        "https://play.google.com/store/apps/details?id=$WELLTORY_PACKAGE&referrer=%s"

    /**
     * The Welltory-issued partner identifier, supplied to us only after the Integration Request
     * Form is approved.
     *
     * **Empty until that approval exists.** This is deliberately not a fabricated placeholder: an
     * invented credential would be a fake integration credential, which the integration contract
     * forbids and which could not authenticate anything anyway. Populate it from the value Welltory
     * issues, and the UI will stop reporting that the integration is not yet approved.
     */
    const val APPROVED_PARTNER_ID: String = ""

    /**
     * Custom parameter sent to Welltory and echoed back on the result Intent.
     *
     * This is the request nonce. Because it is random and single-use, an arbitrary third-party Intent
     * (from another app, a malicious link, or a stray broadcast) cannot forge a measurement: it would
     * have to guess a value we generated moments ago. The callback is rejected unless it carries the
     * nonce belonging to a request this app actually issued.
     */
    const val NONCE_PARAM: String = "ssn"

    /**
     * Custom parameter carrying the StressSense account id that issued the request.
     *
     * Echoed back so a result is attributed to the account that actually started the measurement,
     * even if the person signs out and back in with a different account while Welltory is in front.
     */
    const val ACCOUNT_PARAM: String = "ssa"

    /** Source label persisted with every Welltory-derived record and shown in the UI. */
    const val SOURCE_WELLTORY_CAMERA: String = "WELLTORY_CAMERA"

    /**
     * Physiological plausibility bounds, in **milliseconds**, used to validate that a returned HRV
     * number really is an interval-duration measurement before it is allowed near the model.
     *
     * Why this exists: Welltory's documentation calls its `rmssd`/`sdnn` extras *"RMSSD index"* and
     * *"SDNN index"*, and its app presents them as 0-100 scores. StressSense's deployed model expects
     * `rmssd_ms`/`sdnn_ms` in **milliseconds** (its WESAD training median for RMSSD is 43.78 ms).
     * Feeding a 0-100 index into a millisecond feature would be a silent unit error that skews the
     * score without ever looking wrong.
     *
     * So a value outside these bounds is **rejected as a unit mismatch** rather than clamped,
     * rescaled or guessed at. See `WelltoryHrvUnits` for how the ambiguity is surfaced.
     */
    val RMSSD_MS_RANGE: ClosedFloatingPointRange<Double> = 1.0..300.0
    val SDNN_MS_RANGE: ClosedFloatingPointRange<Double> = 1.0..400.0

    /** Heart rate accepted as physiologically real when carried over from Health Connect. */
    val HEART_RATE_RANGE: ClosedFloatingPointRange<Double> = 25.0..250.0

    /**
     * Welltory's own 0-1 scalars (stress/energy/productivity).
     *
     * Documented range is 0.0-1.0. These are **Welltory's proprietary scores**, retained only as
     * source metadata. They are never used as, substituted for, or blended into the StressSense
     * physiological stress score, which comes solely from `wesad-loso-logreg-v1`.
     */
    val WELLTORY_SCORE_RANGE: ClosedFloatingPointRange<Double> = 0.0..1.0
}