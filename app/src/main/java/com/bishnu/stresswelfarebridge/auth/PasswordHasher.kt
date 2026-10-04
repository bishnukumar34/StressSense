package com.bishnu.stresswelfarebridge.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing for local accounts.
 *
 * The project had no credential handling at all before this, so this is the single place passwords
 * are turned into stored material. The plaintext password never leaves the caller's stack: it is
 * hashed immediately and only the salt + derived key are kept.
 *
 * PBKDF2-HMAC-SHA256 with a per-account random salt (API 26+ supports this algorithm, which matches
 * the project's minSdk of 26), compared in constant time so verification cannot be timed.
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val RADIX = 16

    private val random = SecureRandom()

    /** Fresh 16-byte random salt, hex encoded. */
    fun newSalt(): String {
        val bytes = ByteArray(SALT_LENGTH_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /** Derives the stored hash for [password] using [salt]. */
    fun hash(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.hexToBytes(), ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded.toHex()
    }

    /** Salt + hash pair to persist for a new account. */
    fun hashWithNewSalt(password: String): Pair<String, String> {
        val salt = newSalt()
        return salt to hash(password, salt)
    }

    /**
     * Verifies a password against a stored salt + hash in constant time.
     *
     * Returns false (never throws) for malformed stored material, so a corrupted record denies
     * access instead of crashing the login path.
     */
    fun verify(password: String, salt: String, expectedHash: String): Boolean {
        val actual = runCatching { hash(password, salt) }.getOrNull() ?: return false
        return constantTimeEquals(actual, expectedHash)
    }

    /** Length-independent, content-constant-time comparison. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(x, y)
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = trim()
        require(clean.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], RADIX)
            val lo = Character.digit(clean[i * 2 + 1], RADIX)
            require(hi >= 0 && lo >= 0) { "invalid hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** Opaque, random session token. Not derived from any credential. */
object SessionTokens {
    fun new(): String = PasswordHasher.newSalt() + PasswordHasher.newSalt()
}
