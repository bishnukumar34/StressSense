package com.bishnu.stresswelfarebridge.welltory

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Receives the Welltory measurement result and hands it to the rest of the app.
 *
 * ## Lifecycle requirement from Welltory's documentation
 *
 * The documentation is explicit that a partner must expect the result in **`onNewIntent` or
 * `onCreate`** - "in case Android kills the application's activity". Both are handled here through
 * the single private [handle] function, so the two paths cannot drift:
 *
 *  - **A. StressSense stayed alive.** Welltory returns to the existing task and, because this
 *    activity is `singleTask`, the system delivers `onNewIntent`.
 *  - **B. Android recreated the task.** Welltory starts a fresh instance and the result arrives in
 *    `onCreate` from `getIntent()`.
 *
 * Assuming only `onNewIntent` would silently drop every result that arrives after process death.
 *
 * ## Security
 *
 * The activity is `exported="true"` because Welltory launches it cross-process, which also means any
 * app on the device can reach it. Nothing here trusts the Intent: the result is only accepted when it
 * echoes the single-use nonce of a request this app issued (see [WelltoryRequestRegistry.consume])
 * and the account id that request was made for.
 *
 * ## Why it does no processing itself
 *
 * This activity stays free of ML, persistence and UI-state concerns. It validates, parks the outcome
 * in [WelltoryResults], and finishes immediately. The Compose layer observes that state and runs the
 * model with the signed-in session, so ownership is always decided by the live session rather than by
 * whatever Intent happened to arrive.
 */
class WelltoryCallbackActivity : Activity() {

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Case B: the process was recreated and the result is the Activity's launch Intent.
        handle(intent)
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Case A: the app was still alive. Note the system may reuse the old Intent for getIntent(),
        // so the new one is set explicitly before being read.
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) {
            finishQuietly()
            return
        }
        val nowMs = System.currentTimeMillis()

        // Consuming first is what makes a duplicate delivery harmless: the second call finds no
        // pending request and is ignored, so the same result can never be recorded twice.
        val echoedNonce = intent.extras?.getString(WelltoryConfig.NONCE_PARAM)
        val pending = WelltoryRequestRegistry.consume(echoedNonce, nowMs)
        if (pending == null) {
            Log.i(TAG, "Ignoring a Welltory callback with no matching outstanding request")
            WelltoryResults.report(WelltoryResults.State.Ignored("This result was not expected."))
            finishQuietly()
            return
        }

        val outcome = try {
            WelltoryCallbackParser.parse(
                extras = IntentExtras(intent),
                expectedNonce = pending.nonce,
                expectedAccountId = pending.accountId,
                receivedAtMs = nowMs
            )
        } catch (e: Exception) {
            // A malformed Intent must never crash the app; it is reported as a refusal instead.
            Log.w(TAG, "Could not parse the Welltory result", e)
            WelltoryCallbackParser.Outcome.Invalid(
                "The Welltory result could not be read, so it was ignored."
            )
        }

        WelltoryResults.report(
            when (outcome) {
                is WelltoryCallbackParser.Outcome.Accepted -> WelltoryResults.State.Received(
                    measurement = outcome.parsed.measurement,
                    summary = outcome.parsed.summary
                )

                is WelltoryCallbackParser.Outcome.Invalid ->
                    WelltoryResults.State.Rejected(outcome.reason)
            }
        )
        finishQuietly()
    }

    /** Closes immediately; the measurement happened in Welltory's own UI, so there is nothing to show. */
    private fun finishQuietly() {
        if (!isFinishing) finish()
    }

    companion object {
        private const val TAG = "WelltoryCallback"
    }
}