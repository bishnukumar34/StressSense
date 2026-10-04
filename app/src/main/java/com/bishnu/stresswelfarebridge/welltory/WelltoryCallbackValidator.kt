package com.bishnu.stresswelfarebridge.welltory

/**
 * Validates the numbers Welltory hands back, before any of them reaches storage or the ML model.
 *
 * Everything here is pure and Android-free so it is covered by plain JVM unit tests. The rules are
 * deliberately strict: this is the boundary between a third-party app and a person's health record,
 * so the default answer to anything doubtful is "reject", never "assume".
 */
object WelltoryCallbackValidator {

    /** A measurement token, if usable, must look like an opaque id - not a number or an error blob. */
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_.:-]{8,128}$")

    /**
     * A received HRV pair, already known to be finite and present.
     *
     * Returned values are kept as the **raw** Welltory numbers; interpretation happens in
     * [interpret]. Nothing is rounded, clamped or converted.
     */
    fun interpret(rmssd: Double, sdnn: Double): WelltoryHrvValidation {
        // Non-finite values never reach here via parse(), but the check is repeated so this function
        // is safe to call directly from anywhere.
        if (!rmssd.isFinite()) return reject("Welltory returned a non-finite RMSSD value.")
        if (!sdnn.isFinite()) return reject("Welltory returned a non-finite SDNN value.")
        if (rmssd < 0.0 || sdnn < 0.0) {
            return reject("Welltory returned a negative HRV value, which is not physically possible.")
        }
        val rmssdOk = rmssd in WelltoryConfig.RMSSD_MS_RANGE
        val sdnnOk = sdnn in WelltoryConfig.SDNN_MS_RANGE
        if (!rmssdOk || !sdnnOk) {
            return reject(
                "Welltory reports RMSSD/SDNN as a 0-100 index, but StressSense's model needs real " +
                    "HRV in milliseconds. Refusing to convert, because a guessed scale factor would " +
                    "invent physiological data. Returned RMSSD=$rmssd, SDNN=$sdnn."
            )
        }
        return WelltoryHrvValidation(
            units = WelltoryHrvUnits.MILLISECONDS,
            rmssdMs = rmssd,
            sdnnMs = sdnn,
            reason = "RMSSD/SDNN accepted as millisecond HRV."
        )
    }

    /** A Welltory heart rate is accepted only if it is a real, finite, in-range bpm value. */
    fun isRealisticHeartRate(bpm: Double?): Boolean =
        bpm != null && bpm.isFinite() && bpm in WelltoryConfig.HEART_RATE_RANGE

    /** Tokens are opaque ids. Anything else (empty, numeric, over-long) is discarded. */
    fun isWellFormedToken(token: String?): Boolean =
        token != null && TOKEN_PATTERN.matches(token)

    /** Welltory's proprietary 0-1 scores are metadata only, never a StressSense input. */
    fun isWellFormedWelltoryScore(score: Double?): Boolean =
        score != null && score.isFinite() && score in WelltoryConfig.WELLTORY_SCORE_RANGE

    /**
     * A measurement timestamp: must be finite and positive, and not absurdly far in the future.
     *
     * Guards against a malformed extra producing a record dated years ahead, which would silently
     * become the "latest" reading and therefore the score every screen displays.
     */
    fun isPlausibleTimestamp(timestampMs: Long, nowMs: Long): Boolean {
        if (timestampMs <= 0L) return false
        val futureAllowanceMs = 60_000L * 60L          // one hour of clock drift
        return timestampMs <= nowMs + futureAllowanceMs
    }

    private fun reject(reason: String) = WelltoryHrvValidation(
        units = WelltoryHrvUnits.INDEX_NOT_MILLISECONDS,
        rmssdMs = null,
        sdnnMs = null,
        reason = reason
    )
}