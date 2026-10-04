package com.bishnu.stresswelfarebridge.welltory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-process handoff of a Welltory callback from [WelltoryCallbackActivity] to the Compose layer.
 *
 * The callback Activity is created and destroyed within a single callback, so it cannot own UI state.
 * It publishes here instead, and the Home screen observes. This mirrors how `CameraHrvRepository`
 * already carries the native camera result, so the two measurement sources behave identically.
 *
 * [clear] is called on sign-out and on tab changes so a stale result is never re-processed for a
 * different person.
 */
object WelltoryResults {

    /** Where a Welltory attempt currently stands. */
    sealed class State {
        /** Nothing has happened yet - the default, showing the two measurement buttons. */
        object Idle : State()

        /** Welltory is being opened; the person is about to leave the app. */
        data class Launching(val message: String) : State()

        /**
         * Waiting for Welltory to return. Not an error: the person is mid-measurement in Welltory's
         * own camera UI, so StressSense must not show any stress number yet.
         */
        data class InProgress(val message: String) : State()

        /** Welltory returned a validated result; the model has not yet been run on it. */
        data class Received(
            val measurement: WelltoryMeasurement,
            val summary: String
        ) : State()

        /** A real measurement was saved but the model has not issued a score yet. */
        data class SavedWithoutScore(val reason: String) : State()

        /** The model produced a real StressSense score. */
        data class Scored(val score: Int, val zone: String, val confidence: Int) : State()

        /** Welltory is not installed; the user is offered the Play Store link. */
        data class NotInstalled(val reason: String) : State()

        /** The measurement was cancelled or Welltory reported a failure. */
        data class Cancelled(val reason: String) : State()

        /** The callback arrived but was refused. Nothing was recorded. */
        data class Rejected(val reason: String) : State()

        /** A result arrived that was not expected. Ignored, nothing recorded. */
        data class Ignored(val reason: String) : State()

        /** The callback never arrived within the expected window. */
        data class TimedOut(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** Observed by the Home screen to render the current state. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Publishes a new state. */
    fun report(state: State) {
        _state.value = state
    }

    /** Returns to [State.Idle], e.g. after the person dismisses a message. */
    fun clear() {
        _state.value = State.Idle
    }
}