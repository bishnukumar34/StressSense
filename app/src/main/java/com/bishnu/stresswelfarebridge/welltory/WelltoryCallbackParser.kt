package com.bishnu.stresswelfarebridge.welltory

/**
 * Parses the documented Welltory result Intent into a validated [WelltoryMeasurement].
 *
 * ## Design notes
 *
 * The parser takes an [Extras] abstraction rather than `android.content.Intent` so the whole
 * validation surface is testable with plain JVM unit tests - no Robolectric, no device. The Android
 * layer supplies an Intent-backed implementation in `WelltoryCallbackActivity`.
 *
 * ## What is deliberately NOT trusted
 *
 * `Intent` extras are attacker-controllable: any app on the device can send this Activity an Intent
 * with arbitrary contents. So the parser:
 *
 *  1. requires the single-use nonce we issued for this exact request (proves the result answers a
 *     measurement *we* started, and stops one request being replayed);
 *  2. requires the account id we issued it to;
 *  3. reads numbers as `Double` via [Extras.optDouble] and rejects NaN/infinity/negatives;
 *  4. rejects rather than defaults - a missing RMSSD is an error, never 0.
 *
 * ## Welltory's proprietary scores
 *
 * `stress`, `energy`, `productivity` and `power` are parsed and carried as [WelltoryMeasurement]'s
 * source metadata only. They are **never** used as, substituted into, or blended with the StressSense
 * physiological stress score, which comes solely from the deployed `wesad-loso-logreg-v1` model.
 */
object WelltoryCallbackParser {

    /** A valid parsed measurement. */
    data class Parsed(
        val measurement: WelltoryMeasurement,
        /** Human-readable summary for the UI ("Welltory: RMSSD 42 ms"). */
        val summary: String
    )

    sealed class Outcome {
        data class Accepted(val parsed: Parsed) : Outcome()
        data class Invalid(val reason: String) : Outcome()

        val isAccepted: Boolean get() = this is Accepted
    }

    /** Minimal view of Intent extras, so parsing stays free of Android types. */
    interface Extras {
        fun has(key: String): Boolean
        fun optDouble(key: String): Double?
        fun optString(key: String): String?
    }

    /**
     * Parses and validates a callback.
     *
     * @param expectedNonce the nonce issued for the outstanding request; a callback carrying any other
     *   value is refused.
     * @param expectedAccountId the account the request was issued for.
     * @param receivedAtMs when the callback arrived, used as the measurement timestamp.
     */
    fun parse(
        extras: Extras,
        expectedNonce: String?,
        expectedAccountId: String?,
        receivedAtMs: Long,
        nowMs: Long = receivedAtMs
    ): Outcome {
        // -- Provenance -----------------------------------------------------------------------------
        // The document says Welltory only sends results to a callback it was asked for, but because
        // the callback Activity is exported it can also be reached by any app on the device. Matching
        // our single-use nonce is what makes an unsolicited or replayed Intent harmless.
        if (expectedNonce.isNullOrBlank()) {
            return Outcome.Invalid(
                "No measurement is currently in progress, so this result was not expected and has " +
                    "been ignored."
            )
        }
        val nonce = extras.optString(WelltoryConfig.NONCE_PARAM)
        if (nonce.isNullOrBlank() || nonce != expectedNonce) {
            return Outcome.Invalid(
                "This result does not match the measurement StressSense started, so it was ignored."
            )
        }
        if (expectedAccountId.isNullOrBlank()) {
            return Outcome.Invalid("This result is not associated with a signed-in account.")
        }
        val accountId = extras.optString(WelltoryConfig.ACCOUNT_PARAM)
        if (accountId != expectedAccountId) {
            return Outcome.Invalid(
                "This result belongs to a different account than the one signed in, so it was ignored."
            )
        }

        // -- Numbers --------------------------------------------------------------------------------
        val rmssd = extras.optDouble("rmssd")
        if (rmssd == null) {
            return Outcome.Invalid(
                "Welltory returned no RMSSD value, so there is no real HRV measurement to record."
            )
        }
        val sdnn = extras.optDouble("sdnn")
        if (sdnn == null) {
            return Outcome.Invalid(
                "Welltory returned no SDNN value, so there is no real HRV measurement to record."
            )
        }
        val validation = WelltoryCallbackValidator.interpret(rmssd, sdnn)
        if (!validation.usable) {
            return Outcome.Invalid(validation.reason)
        }
        if (!WelltoryCallbackValidator.isPlausibleTimestamp(receivedAtMs, nowMs)) {
            return Outcome.Invalid("Welltory returned an implausible measurement time.")
        }
        // -- Optional, metadata-only fields ---------------------------------------------------------
        // Welltory's proprietary scores. Validated for shape and stored as provenance metadata only.
        val welltoryStress = extras.optDouble("stress")
            ?.takeIf { WelltoryCallbackValidator.isWellFormedWelltoryScore(it) }
        val welltoryEnergy = extras.optDouble("energy")
            ?.takeIf { WelltoryCallbackValidator.isWellFormedWelltoryScore(it) }
        val welltoryProductivity = extras.optDouble("productivity")
            ?.takeIf { WelltoryCallbackValidator.isWellFormedWelltoryScore(it) }
        val welltoryPower = extras.optDouble("power")
            ?.takeIf { WelltoryCallbackValidator.isWellFormedWelltoryScore(it) }

        // Documented interpretation colours: green / yellow / red / unknown. Informational only.
        val stressColor = extras.optString("stress_c")?.takeIf { it.isNotBlank() }
        val energyColor = extras.optString("energy_c")?.takeIf { it.isNotBlank() }
        val productivityColor = extras.optString("productivity_c")?.takeIf { it.isNotBlank() }

        val token = extras.optString("token")?.takeIf { WelltoryCallbackValidator.isWellFormedToken(it) }

        // Idempotency: the token identifies the Welltory measurement itself, so the same result
        // delivered twice (onNewIntent and then a recreated onCreate) collapses to one record. When
        // Welltory omits a token we fall back to the nonce, which is equally single-use.
        val dedupeKey = token ?: "nonce:$nonce"

        val measurement = WelltoryMeasurement(
            accountId = accountId,
            measuredAtMs = receivedAtMs,
            rmssdMs = validation.rmssdMs!!,
            sdnnMs = validation.sdnnMs!!,
            // Welltory's documented callback carries no heart rate, so it stays null. See
            // WelltoryMeasurement for why it is never invented or defaulted.
            heartRateBpm = null,
            welltoryStress = welltoryStress,
            welltoryEnergy = welltoryEnergy,
            welltoryProductivity = welltoryProductivity,
            welltoryTotalPower = welltoryPower,
            welltoryToken = token,
            dedupeKey = dedupeKey,
            stressColor = stressColor,
            energyColor = energyColor,
            productivityColor = productivityColor,
            source = WelltoryConfig.SOURCE_WELLTORY_CAMERA,
            validation = validation
        )
        return Outcome.Accepted(
            Parsed(
                measurement = measurement,
                summary = "Welltory returned RMSSD %.0f ms and SDNN %.0f ms.".format(
                    measurement.rmssdMs, measurement.sdnnMs
                )
            )
        )
    }
}