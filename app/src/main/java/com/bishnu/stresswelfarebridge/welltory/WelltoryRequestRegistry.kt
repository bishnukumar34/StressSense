package com.bishnu.stresswelfarebridge.welltory

import java.security.SecureRandom

/**
 * Tracks the single outstanding Welltory measurement request.
 *
 * ## Why this exists
 *
 * Two correctness problems are solved here at once:
 *
 *  1. **Provenance.** A callback is only accepted when it echoes the nonce of a request this app
 *     actually issued. Without that, any app on the device could send this exported Activity an
 *     Intent and write an arbitrary "Welltory measurement" into a signed-in user's history.
 *  2. **Account binding.** The account that started the measurement is remembered, so a result is
 *     attributed to the person who actually took it even if the session changes while Welltory is in
 *     the foreground.
 *
 * Only one request may be in flight at a time; starting a new one replaces the previous, which also
 * invalidates any stale callback from an abandoned attempt.
 *
 * The nonce is 128 bits from [SecureRandom] and is consumed on use, so it cannot be guessed and
 * cannot be replayed.
 */
object WelltoryRequestRegistry {

    private val random = SecureRandom()

    /** Immutable description of an outstanding request. */
    data class PendingRequest(
        val nonce: String,
        val accountId: String,
        val issuedAtMs: Long
    )

    @Volatile
    private var pending: PendingRequest? = null

    /** Requests older than this are considered abandoned and no longer accepted. */
    private const val REQUEST_TTL_MS = 10 * 60 * 1000L

    /** A fresh 128-bit nonce, hex encoded. */
    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    /** Registers a new outstanding request for [accountId], replacing any previous one. */
    @Synchronized
    fun begin(accountId: String, nowMs: Long): PendingRequest {
        val request = PendingRequest(newNonce(), accountId, nowMs)
        pending = request
        return request
    }

    /** The outstanding request, or null when none exists or the previous one has expired. */
    @Synchronized
    fun current(nowMs: Long): PendingRequest? {
        val p = pending ?: return null
        if (nowMs - p.issuedAtMs > REQUEST_TTL_MS) {
            pending = null
            return null
        }
        return p
    }

    /**
     * Consumes the outstanding request, returning it only if [nonce] matches.
     *
     * Called when a callback arrives. A mismatch clears the request, so a forged or stale Intent
     * cannot be retried, and leaves nothing behind to match a later attempt.
     */
    @Synchronized
    fun consume(nonce: String?, nowMs: Long): PendingRequest? {
        val p = current(nowMs)
        pending = null
        if (p == null) return null
        if (nonce.isNullOrBlank() || nonce != p.nonce) return null
        return p
    }

    /** Drops any outstanding request, e.g. on sign-out. */
    @Synchronized
    fun cancel() {
        pending = null
    }
}