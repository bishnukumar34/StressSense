package com.bishnu.stresswelfarebridge.welltory

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.ml.StressEstimator
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore

/**
 * Drives the Welltory flow from the Compose layer.
 *
 * Responsibilities kept deliberately small: begin/launch a request, observe the callback state that
 * [WelltoryCallbackActivity] publishes, run the real model on a validated result, persist it, and
 * report the outcome so the UI and Commander can update.
 *
 * It performs no scoring of its own and holds no health state - the estimator, baseline and history
 * come from the caller, so the model behaves exactly as it does for the native camera.
 */
class WelltoryController(
    private val estimatorFactory: () -> StressEstimator,
    private val store: StressMeasurementStore,
    private val modelVersion: String,
    private val launcher: (WelltoryRequestRegistry.PendingRequest) -> AndroidWelltoryLauncher.LaunchOutcome,
    private val installLauncher: () -> AndroidWelltoryLauncher.LaunchOutcome,
    private val healthProvider: () -> DailyHealth?,
    private val onScored: (WelltoryBridge.Result.Scored) -> Unit = {},
    private val onSavedWithoutScore: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** Record ids already handled in this process, making callback handling idempotent. */
    private val processed = mutableSetOf<String>()

    /**
     * Starts a measurement for [session] and reports the immediate outcome.
     *
     * The nonce and owning account are registered *before* launching, so a callback that arrives
     * while StressSense is still alive is matched against a real request.
     */
    fun start(session: Session?) {
        val s = session
        if (s == null) {
            WelltoryResults.report(
                WelltoryResults.State.Rejected("Sign in before taking a measurement.")
            )
            return
        }
        if (s.isCommander) {
            // Matches StressMeasurementStore, which refuses to persist for a commander.
            WelltoryResults.report(
                WelltoryResults.State.Rejected(
                    "Commander accounts cannot record physiological measurements."
                )
            )
            return
        }
        val pending = WelltoryRequestRegistry.begin(s.accountId, clock())
        when (val outcome = launcher(pending)) {
            is AndroidWelltoryLauncher.LaunchOutcome.Started ->
                WelltoryResults.report(
                    WelltoryResults.State.InProgress(
                        if (outcome.usedInstallFallback) {
                            "Opening Welltory. If it is not installed, the Play Store will open."
                        } else {
                            "Welltory is opening."
                        }
                    )
                )

            is AndroidWelltoryLauncher.LaunchOutcome.Failed ->
                WelltoryResults.report(WelltoryResults.State.NotInstalled(outcome.reason))
        }
    }

    /** Opens the Welltory install page from the "not installed" prompt. */
    fun openInstallPage() {
        when (installLauncher()) {
            is AndroidWelltoryLauncher.LaunchOutcome.Started ->
                WelltoryResults.report(
                    WelltoryResults.State.Launching("Opening the Play Store to install Welltory.")
                )

            is AndroidWelltoryLauncher.LaunchOutcome.Failed ->
                WelltoryResults.report(
                    WelltoryResults.State.NotInstalled(
                        "The Play Store could not be opened on this device."
                    )
                )
        }
    }

    /**
     * Processes a validated callback: runs the deployed model, persists, and reports.
     *
     * Called by the UI layer whenever [WelltoryResults.state] reports a received measurement. The
     * [processed] guard means a recomposition - or a callback that somehow arrives twice - cannot
     * score and persist the same measurement a second time.
     */
    fun onReceived(measurement: WelltoryMeasurement, session: Session?) {
        val s = session
        if (s == null) {
            WelltoryResults.report(
                WelltoryResults.State.Rejected(
                    "You are signed out, so this measurement was not recorded."
                )
            )
            return
        }
        if (!processed.add(measurement.recordId)) return

        val health = healthProvider()
        // Welltory's callback carries no heart rate, so a real one is taken from the wearable when
        // available - and only then. Never invented, never defaulted.
        val withHr = WelltoryBridge.withRealHeartRate(measurement, health)

        val result = WelltoryBridge.process(
            session = s,
            measurement = withHr,
            estimator = estimatorFactory(),
            store = store,
            health = health,
            modelVersion = modelVersion,
            alreadyProcessedIds = store.history(s).map { it.id }.toSet()
        )
        when (result) {
            is WelltoryBridge.Result.Scored -> {
                onScored(result)
                WelltoryResults.report(
                    WelltoryResults.State.Scored(
                        score = result.score,
                        zone = result.zone,
                        confidence = result.confidence
                    )
                )
            }

            // The measurement is real and has been saved; only the number is missing.
            is WelltoryBridge.Result.NotScoredYet -> {
                onSavedWithoutScore(result.reason)
                WelltoryResults.report(WelltoryResults.State.SavedWithoutScore(result.reason))
            }

            is WelltoryBridge.Result.Refused ->
                WelltoryResults.report(WelltoryResults.State.Rejected(result.reason))
        }
    }

    /** Clears any in-progress state, e.g. on sign-out or account switch. */
    fun reset() {
        WelltoryRequestRegistry.cancel()
        WelltoryResults.clear()
        processed.clear()
    }
}