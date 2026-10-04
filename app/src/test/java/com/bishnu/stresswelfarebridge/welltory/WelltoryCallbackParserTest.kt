package com.bishnu.stresswelfarebridge.welltory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Callback parsing and validation: the boundary where a third-party app's data enters the app.
 *
 * Pure JVM tests - [WelltoryCallbackParser] takes an [WelltoryCallbackParser.Extras] abstraction, so
 * no Android/Robolectric is needed and every rejection path is directly reachable.
 */
class WelltoryCallbackParserTest {

    /** In-memory extras so a test can place any value - including deliberately hostile ones. */
    private class FakeExtras(
        private val values: Map<String, Any?> = emptyMap()
    ) : WelltoryCallbackParser.Extras {
        override fun has(key: String): Boolean = values.containsKey(key)

        override fun optDouble(key: String): Double? = when (val v = values[key]) {
            null -> null
            is Float -> v.toDouble().takeIf { it.isFinite() }
            is Double -> v.takeIf { it.isFinite() }
            is Int -> v.toDouble()
            is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }

        override fun optString(key: String): String? = values[key]?.toString()
    }

    private val nonce = "abc123nonce"
    private val account = "acct-42"
    private val t0 = 1_770_000_000_000L

    private fun valid(
        rmssd: Any? = 42.0f,
        sdnn: Any? = 58.0f,
        extra: Map<String, Any?> = emptyMap()
    ) = FakeExtras(
        buildMap {
            put(WelltoryConfig.NONCE_PARAM, nonce)
            put(WelltoryConfig.ACCOUNT_PARAM, account)
            if (rmssd != null) put("rmssd", rmssd)
            if (sdnn != null) put("sdnn", sdnn)
            putAll(extra)
        }
    )

    private fun parse(extras: FakeExtras, now: Long = t0) =
        WelltoryCallbackParser.parse(extras, nonce, account, receivedAtMs = t0, nowMs = now)

    // == A. valid callback ==========================================================================

    @Test
    fun validCallbackIsAccepted() {
        assertTrue("a well-formed callback must be accepted", parse(valid()).isAccepted)
    }

    @Test
    fun rmssdIsParsedInMilliseconds() {
        val m = (parse(valid(rmssd = 37.5f)) as WelltoryCallbackParser.Outcome.Accepted)
            .parsed.measurement
        assertEquals(37.5, m.rmssdMs, 1e-9)
    }

    @Test
    fun sdnnIsParsedInMilliseconds() {
        val m = (parse(valid(sdnn = 61.25f)) as WelltoryCallbackParser.Outcome.Accepted)
            .parsed.measurement
        assertEquals(61.25, m.sdnnMs, 1e-9)
    }

    @Test
    fun sourceIsLabelledWelltoryCamera() {
        val m = (parse(valid()) as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertEquals(WelltoryConfig.SOURCE_WELLTORY_CAMERA, m.source)
        assertEquals("WELLTORY_CAMERA", m.source)
    }

    @Test
    fun measurementIsOwnedByTheRequestingAccount() {
        val m = (parse(valid()) as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertEquals(account, m.accountId)
    }

    // == the proprietary-score guard =================================================================

    @Test
    fun welltoryStressIsRetainedAsMetadataButHasNoStressSenseCounterpart() {
        val extras = valid(extra = mapOf("stress" to 0.72f, "energy" to 0.4f))
        val m = (parse(extras) as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        // Retained for provenance... (a delta is needed because the extra arrives as a 32-bit Float,
        // so 0.72f widens to 0.7200000286102295 in Double precision.)
        assertEquals(0.72, m.welltoryStress!!, 1e-6)
        assertEquals(0.4, m.welltoryEnergy!!, 1e-6)
        // ...but the model has no field for it at all, so it cannot become our score.
        assertTrue(
            "WelltoryMeasurement must expose no StressSense stress field",
            WelltoryMeasurement::class.java.declaredFields.none {
                it.name == "stressScore" || it.name == "score"
            }
        )
    }

    @Test
    fun welltoryStressOfZeroPointSevenTwoIsNotSeventyTwo() {
        val extras = valid(extra = mapOf("stress" to 0.72f))
        val m = (parse(extras) as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertTrue("must remain a 0..1 scalar", m.welltoryStress!! <= 1.0)
        assertFalse("must never equal the 0-100 presentation of 72", m.welltoryStress == 72.0)
    }

    @Test
    fun anOutOfRangeWelltoryScoreIsDroppedRatherThanStored() {
        // 72 is not on Welltory's documented 0..1 scale, so it is discarded, not reinterpreted.
        val m = (parse(valid(extra = mapOf("stress" to 72.0f)))
            as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertNull(m.welltoryStress)
    }

    // == B. invalid callbacks ======================================================================

    @Test
    fun missingRmssdIsRejected() {
        val outcome = parse(valid(rmssd = null))
        assertFalse(outcome.isAccepted)
        assertTrue((outcome as WelltoryCallbackParser.Outcome.Invalid).reason.contains("RMSSD"))
    }

    @Test
    fun missingSdnnIsRejected() {
        val outcome = parse(valid(sdnn = null))
        assertFalse(outcome.isAccepted)
        assertTrue((outcome as WelltoryCallbackParser.Outcome.Invalid).reason.contains("SDNN"))
    }

    @Test
    fun nanRmssdIsRejected() {
        // A real Float NaN, as an Intent could carry. The extras layer filters non-finite to null.
        assertFalse("NaN must never reach the model", parse(valid(rmssd = Float.NaN)).isAccepted)
    }

    @Test
    fun infinitySdnnIsRejected() {
        assertFalse(parse(valid(sdnn = Float.POSITIVE_INFINITY)).isAccepted)
    }

    @Test
    fun negativeHrvIsRejected() {
        val outcome = parse(valid(rmssd = -5.0f, sdnn = -1.0f))
        assertFalse("negative HRV is physically impossible", outcome.isAccepted)
        assertTrue((outcome as WelltoryCallbackParser.Outcome.Invalid).reason.contains("negative"))
    }

    @Test
    fun aZeroRmssdIsRejectedRatherThanStored() {
        // Zero is not a real measurement; the range check catches it instead of storing a
        // real-looking 0.
        assertFalse(parse(valid(rmssd = 0.0f)).isAccepted)
    }

    @Test
    fun nonNumericRmssdIsRejected() {
        assertFalse(parse(valid(rmssd = "not-a-number")).isAccepted)
    }

    @Test
    fun anImpossibleHrvValueIsRejectedAsAUnitMismatch() {
        // 5000 ms is not a real HRV measurement, which is also how a mis-scaled Welltory index would
        // show up. Refusing beats silently rescaling it.
        val outcome = parse(valid(rmssd = 5000.0f, sdnn = 4000.0f))
        assertFalse(outcome.isAccepted)
        assertTrue(
            (outcome as WelltoryCallbackParser.Outcome.Invalid).reason.contains("milliseconds")
        )
    }

    @Test
    fun plausibleMillisecondHrvInRangeIsAccepted() {
        // Guards the boundary: the rejection above must not be over-eager for genuine ms values.
        assertTrue(parse(valid(rmssd = 42.0f, sdnn = 58.0f)).isAccepted)
    }

    // == C. provenance / replay protection =========================================================

    @Test
    fun aCallbackWithNoOutstandingRequestIsRejected() {
        val outcome = WelltoryCallbackParser.parse(
            valid(), expectedNonce = null, expectedAccountId = account,
            receivedAtMs = t0, nowMs = t0
        )
        assertFalse(outcome.isAccepted)
    }

    @Test
    fun aCallbackWithTheWrongNonceIsRejected() {
        val extras = FakeExtras(
            mapOf(
                WelltoryConfig.NONCE_PARAM to "forged-nonce",
                WelltoryConfig.ACCOUNT_PARAM to account,
                "rmssd" to 42.0f, "sdnn" to 58.0f
            )
        )
        val outcome = parse(extras)
        assertFalse("a forged nonce must not be accepted", outcome.isAccepted)
        assertTrue(
            (outcome as WelltoryCallbackParser.Outcome.Invalid).reason.contains("does not match")
        )
    }

    @Test
    fun aCallbackMissingTheNonceIsRejected() {
        val extras = FakeExtras(
            mapOf(WelltoryConfig.ACCOUNT_PARAM to account, "rmssd" to 42.0f, "sdnn" to 58.0f)
        )
        assertFalse(parse(extras).isAccepted)
    }

    @Test
    fun aCallbackForAnotherAccountIsRejected() {
        val extras = FakeExtras(
            mapOf(
                WelltoryConfig.NONCE_PARAM to nonce,
                WelltoryConfig.ACCOUNT_PARAM to "somebody-else",
                "rmssd" to 42.0f, "sdnn" to 58.0f
            )
        )
        val outcome = parse(extras)
        assertFalse(outcome.isAccepted)
        assertTrue(
            (outcome as WelltoryCallbackParser.Outcome.Invalid).reason.contains("different account")
        )
    }

    @Test
    fun anImplausibleTimestampIsRejected() {
        val outcome = WelltoryCallbackParser.parse(
            valid(), nonce, account,
            receivedAtMs = t0 + 86_400_000L * 30, // a month in the future
            nowMs = t0
        )
        assertFalse(outcome.isAccepted)
    }

    // == tokens and colours =========================================================================

    @Test
    fun aWellFormedTokenIsKept() {
        val m = (parse(valid(extra = mapOf("token" to "abc123def456")))
            as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertEquals("abc123def456", m.welltoryToken)
    }

    @Test
    fun aMalformedTokenIsDiscardedButTheMeasurementStillCounts() {
        val m = (parse(valid(extra = mapOf("token" to "!!!")))
            as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertNull("a malformed token is not stored", m.welltoryToken)
        // The measurement is still real, so a bad token must not discard it.
        assertNotNull(m.dedupeKey)
    }

    @Test
    fun documentedColoursAreCaptured() {
        val extras = valid(
            extra = mapOf("stress_c" to "red", "energy_c" to "green", "productivity_c" to "yellow")
        )
        val m = (parse(extras) as WelltoryCallbackParser.Outcome.Accepted).parsed.measurement
        assertEquals("red", m.stressColor)
        assertEquals("green", m.energyColor)
        assertEquals("yellow", m.productivityColor)
    }
}