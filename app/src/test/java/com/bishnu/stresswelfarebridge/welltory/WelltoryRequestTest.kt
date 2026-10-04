package com.bishnu.stresswelfarebridge.welltory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * The documented request URIs and the single-use nonce that makes a callback provably ours.
 *
 * These assert the exact wire format Welltory documents, so a refactor cannot quietly change the
 * contract the integration depends on.
 */
class WelltoryRequestTest {

    private val t0 = 1_770_000_000_000L

    // == documented URI shape =====================================================================

    @Test
    fun measurementUriUsesTheDocumentedSchemeAndPath() {
        val uri = WelltoryRequestBuilder.measurementUri("com.app/com.app.Callback", "n1", "acct-1")
        assertTrue(
            "must use the documented welltory:// scheme",
            uri.startsWith("welltory://branch/Measurement/Start/")
        )
    }

    @Test
    fun parametersAreUtf8UrlEncodedAfterTheFinalSlash() {
        val encoded = WelltoryRequestBuilder.params("com.app/com.app.Callback", "n1", "acct-1")
        // Must be percent-encoded: no raw "&", "=" or "/" may survive inside the encoded blob.
        assertFalse("raw '&' would break parameter parsing", encoded.contains("&"))
        assertFalse("raw '=' would break parameter parsing", encoded.contains("="))
        assertFalse("raw '/' would be read as a path separator", encoded.contains("/"))

        val decoded = URLDecoder.decode(encoded, "UTF-8")
        assertTrue(decoded.startsWith("source=StressSense"))
        assertTrue(decoded.contains("callback=com.app/com.app.Callback"))
        assertTrue(decoded.contains("${WelltoryConfig.NONCE_PARAM}=n1"))
        assertTrue(decoded.contains("${WelltoryConfig.ACCOUNT_PARAM}=acct-1"))
    }

    @Test
    fun spacesAreEncodedAsPercentTwentyNotPlus() {
        // '+' means a literal plus in a URI path, so a space must not be encoded as '+'.
        assertEquals("a%20b", WelltoryRequestBuilder.urlEncode("a b"))
    }

    @Test
    fun playStoreUriCarriesTheSameParametersAsTheReferrer() {
        val uri = WelltoryRequestBuilder.playStoreUri("com.app/com.app.Callback", "n1", "acct-1")
        assertTrue(
            uri.startsWith(
                "https://play.google.com/store/apps/details?id=com.welltory.client.android&referrer="
            )
        )
        val decoded = URLDecoder.decode(uri.substringAfter("referrer="), "UTF-8")
        assertTrue(decoded.contains("callback=com.app/com.app.Callback"))
        assertTrue(decoded.contains("${WelltoryConfig.NONCE_PARAM}=n1"))
    }

    @Test
    fun theDirectSchemeIsUsedOnlyWhenWelltoryIsInstalled() {
        assertTrue(
            WelltoryRequestBuilder.uriToOpen(true, "c", "n", "a").startsWith("welltory://")
        )
        assertTrue(
            "without Welltory the documented Play link is used",
            WelltoryRequestBuilder.uriToOpen(false, "c", "n", "a")
                .startsWith("https://play.google.com/")
        )
    }

    @Test
    fun theInstallLinkPointsAtTheWelltoryPackage() {
        assertTrue(WelltoryRequestBuilder.installUri().contains("com.welltory.client.android"))
    }

    // == nonce / provenance =======================================================================

    @Test
    fun noncesAreUnpredictableAndUnique() {
        val nonces = (1..50).map { WelltoryRequestRegistry.newNonce() }
        assertEquals("every nonce must be distinct", 50, nonces.toSet().size)
        assertTrue("a nonce must be long enough to resist guessing", nonces.all { it.length >= 32 })
    }

    @Test
    fun aCallbackIsAcceptedOnlyForTheOutstandingRequest() {
        WelltoryRequestRegistry.cancel()
        val pending = WelltoryRequestRegistry.begin("acct-1", t0)
        assertEquals(pending, WelltoryRequestRegistry.consume(pending.nonce, t0))
    }

    @Test
    fun aForgedNonceMatchesNothing() {
        WelltoryRequestRegistry.cancel()
        WelltoryRequestRegistry.begin("acct-1", t0)
        assertNull("a forged nonce must not match", WelltoryRequestRegistry.consume("wrong", t0))
    }

    @Test
    fun aNonceIsSingleUseSoAReplayedCallbackIsRejected() {
        WelltoryRequestRegistry.cancel()
        val pending = WelltoryRequestRegistry.begin("acct-1", t0)
        assertTrue(WelltoryRequestRegistry.consume(pending.nonce, t0) != null)
        // The same result arriving a second time (onCreate after onNewIntent) finds nothing.
        assertNull(
            "a replayed callback must not be accepted twice",
            WelltoryRequestRegistry.consume(pending.nonce, t0)
        )
    }

    @Test
    fun theRequestIsBoundToTheAccountThatStartedIt() {
        WelltoryRequestRegistry.cancel()
        assertEquals("acct-7", WelltoryRequestRegistry.begin("acct-7", t0).accountId)
    }

    @Test
    fun startingANewRequestInvalidatesThePreviousOne() {
        WelltoryRequestRegistry.cancel()
        val first = WelltoryRequestRegistry.begin("acct-1", t0)
        val second = WelltoryRequestRegistry.begin("acct-2", t0)
        assertNotEquals(first.nonce, second.nonce)
        assertNull(
            "an abandoned request must not be able to deliver a result",
            WelltoryRequestRegistry.consume(first.nonce, t0)
        )
    }

    @Test
    fun anAbandonedRequestExpires() {
        WelltoryRequestRegistry.cancel()
        val pending = WelltoryRequestRegistry.begin("acct-1", t0)
        assertNull(
            "a stale request must not accept a result",
            WelltoryRequestRegistry.consume(pending.nonce, t0 + 60L * 60 * 1000L)
        )
    }

    @Test
    fun cancelClearsTheOutstandingRequest() {
        WelltoryRequestRegistry.cancel()
        val pending = WelltoryRequestRegistry.begin("acct-1", t0)
        WelltoryRequestRegistry.cancel()
        assertNull(WelltoryRequestRegistry.consume(pending.nonce, t0))
    }

    // == approval status ===========================================================================

    @Test
    fun noApprovalCredentialIsInvented() {
        // Guards against someone "fixing" the empty partner id with a made-up token. The integration
        // contract forbids inventing credentials; approval must come from Welltory.
        assertEquals(
            "the partner id must stay empty until Welltory actually approves this package",
            "", WelltoryConfig.APPROVED_PARTNER_ID
        )
    }
}
