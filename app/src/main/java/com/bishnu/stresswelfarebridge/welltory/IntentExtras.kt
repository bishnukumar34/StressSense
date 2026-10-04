package com.bishnu.stresswelfarebridge.welltory

import android.content.Intent

/**
 * Bridges `Intent` extras onto the parser's Android-free [WelltoryCallbackParser.Extras].
 *
 * Isolating this keeps [WelltoryCallbackActivity] free of type-coercion logic and, more importantly,
 * keeps the parsing rules testable on the JVM.
 *
 * ## Why the reads are so defensive
 *
 * The callback Activity is `exported="true"`, so its Intent is effectively untrusted input. Two
 * concrete hazards are handled here:
 *
 *  - **Wrong type.** A raw `Bundle.get` can hold a String, Int or a nested Bundle where a Float is
 *    documented, and `getFloatExtra` throws `ClassCastException` on those. Reading the raw object and
 *    coercing by type turns any such Intent into a clean rejection instead of a crash.
 *  - **Non-finite values.** NaN and infinity are filtered out here rather than being handed to the
 *    validator, so a non-finite extra can never reach the feature vector or storage.
 */
class IntentExtras(private val intent: Intent) : WelltoryCallbackParser.Extras {

    private val extras get() = intent.extras

    override fun has(key: String): Boolean = extras?.containsKey(key) == true

    /**
     * Reads a numeric extra as a `Double`, or null when it is absent, non-numeric or non-finite.
     *
     * The documented extras are Floats; the remaining branches exist purely so a malformed Intent
     * from any other app degrades into a clean rejection instead of an exception.
     */
    override fun optDouble(key: String): Double? {
        val e = extras ?: return null
        if (!e.containsKey(key)) return null

        // getFloatExtra throws ClassCastException when the stored value is not a Float, which an
        // untrusted Intent can arrange. Reading the raw object first is therefore the safe order:
        // the documented Float case still works, and anything else degrades to a clean null.
        val raw = runCatching { e.get(key) }.getOrNull() ?: return null
        val value = when (raw) {
            is Float -> raw.toDouble()
            is Double -> raw
            is Int -> raw.toDouble()
            is Long -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: return null
            else -> return null
        }
        return value.takeIf { it.isFinite() }
    }

    override fun optString(key: String): String? {
        val e = extras ?: return null
        if (!e.containsKey(key)) return null
        // Custom parameters come back as Strings; tolerate other scalar types defensively.
        return runCatching { e.getString(key) }.getOrNull()
            ?: e.get(key)?.toString()
    }
}