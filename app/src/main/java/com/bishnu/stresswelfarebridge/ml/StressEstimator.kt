package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import java.time.Instant

/**
 * Orchestrates the whole on-device path: features → model → calibration → confidence/abstention →
 * smoothing → score.
 *
 * ### The abstention contract (Phase 14)
 * This class never emits a number it cannot stand behind. If the measurement is too poor, the
 * beat-to-beat series is untrustworthy, or the model is being asked to run on almost no data, it
 * returns a null score with a human-readable [StressPrediction.reason]. The UI then shows
 * "no reliable stress estimate yet" instead of a fabricated 50.
 *
 * The gates are properties of the *measurement*, never of the person's stress level. There is no
 * gate anywhere that rejects a high RMSSD, a high HR, or any healthy-looking value — only gates on
 * quality, quantity and completeness. That distinction matters: a gate that rejected "HR too high"
 * would be a hidden clinical judgement, and it would be wrong.
 *
 * ### Activity is context, not stress (Phase 15)
 * Physical load is a model input, not a pre-filter. A person whose HR is high because they just ran
 * is passed to the model *with* their activity context so the model can discount it, rather than
 * being silently excluded.
 */
class StressEstimator(
    private val model: StressModel,
    private val baseline: PersonalBaseline,
    private val history: ObservationHistory,
    private val clock: () -> Instant = { Instant.now() },
    /**
     * Invoked immediately after any observation that passed the gates has been committed to the
     * personal baseline and history.
     *
     * Exists so [StressBaselineStore] can write the updated state at the moment a reading genuinely
     * qualified, rather than on app close - a kill or crash must not lose a reading the person made.
     * It is a notification only: it cannot influence the score, which has already been computed from
     * the same features either way. Defaults to doing nothing, so every existing caller and test is
     * unaffected.
     */
    private val onCommitted: () -> Unit = {}
) {

    private val extractor = FeatureExtractor(baseline, history)

    /** Running score history for temporal smoothing (Phase 23). */
    private val scoreHistory = ArrayList<Int>()
    private val smoothed = ArrayList<Int>()

    /**
     * Evaluates one observation.
     *
     * @param quality acquisition quality of the underlying measurement. POOR always abstains.
     * @param commit when true, a *passing* observation updates the personal baseline and history.
     *   A failing observation is never committed, so a bad trace cannot poison the baseline.
     */
    fun evaluate(observation: StressObservation, quality: SignalQuality): StressPrediction {
        val vector = extractor.extract(observation)

        val gate = firstFailingGate(observation, vector, quality)
        if (gate != null) {
            if (gate.allowsCommit) {
                extractor.commit(observation)
                onCommitted()
            }
            return StressPrediction(
                score = null, zone = StressZone.LOW, rawLogit = null, probability = null,
                confidence = gate.confidence, reason = gate.reason,
                contributions = model.contributions(vector)
            )
        }

        val raw = model.rawScore(vector)
        val probability = model.calibratedProbability(vector)
        val score = smooth(model.displayScore(vector))
        val confidence = confidenceFor(vector, observation, probability)

        extractor.commit(observation)
        onCommitted()

        val present = FeatureSchema.NAMES.count { name ->
            val i = vector.indexOf(name); i >= 0 && !vector.isMissing(i)
        }
        return StressPrediction(
            score = score,
            zone = StressZoneMapping.fromScore(score),
            rawLogit = raw,
            probability = probability,
            confidence = confidence,
            contributions = model.contributions(vector),
            reason = "Estimated from $present of ${FeatureSchema.NAMES.size} features."
        )
    }

    /** A gate that refused. [allowsCommit] records whether history should still absorb the sample. */
    private data class Gate(val reason: String, val confidence: Int, val allowsCommit: Boolean)

    private fun firstFailingGate(
        o: StressObservation,
        v: FeatureVector,
        quality: SignalQuality
    ): Gate? {
        if (quality == SignalQuality.POOR) {
            return Gate(
                "Camera PPG signal quality is too poor for a stress estimate. Retake the measurement.",
                0, allowsCommit = false
            )
        }
        // Some heart data is the minimum the model was validated on.
        if (o.hrBpm == null && o.rmssdMs == null) {
            return Gate("No reliable heart-rate or HRV reading available.", 0, allowsCommit = false)
        }
        val validRatio = o.validNnRatio
        if (validRatio != null && validRatio < MIN_VALID_NN_RATIO) {
            return Gate(
                "Too many beat intervals were discarded as artefacts (%.0f%% survived). Retake."
                    .format(validRatio * 100),
                10, allowsCommit = false
            )
        }
        val completeness = v.completeness()
        if (completeness < MIN_COMPLETENESS) {
            return Gate(
                "Not enough measured data for a stress estimate (%.0f%% of features present)."
                    .format(completeness * 100),
                15, allowsCommit = false
            )
        }
        return null
    }

    /**
     * Confidence reflects how much trustworthy evidence stands behind this number.
     *
     * It combines acquisition quality, feature completeness and how mature the personal baseline
     * is. A high score with a thin baseline is still low confidence, which is the honest answer.
     */
    private fun confidenceFor(
        v: FeatureVector,
        o: StressObservation,
        probability: Double
    ): Int {
        val quality = (o.ppgQuality ?: o.confidence ?: 0.0).coerceIn(0.0, 1.0)
        val completeness = v.completeness()
        val baselineMature = if (baseline.isEstablished("hr_bpm")) 1.0 else 0.4
        // A probability near 0 or 1 is an easier call than one near the 0.5 threshold.
        val decisiveness = kotlin.math.abs(probability - 0.5) * 2.0
        val c = 100.0 * (
            0.35 * quality + 0.35 * completeness + 0.15 * baselineMature + 0.15 * decisiveness
            )
        return c.toInt().coerceIn(0, 100)
    }

    /**
     * Exponential smoothing over recent display scores, so one noisy measurement does not swing the
     * displayed band. Applies only to *display*; raw and calibrated outputs are untouched.
     */
    private fun smooth(score: Int): Int {
        scoreHistory.add(score)
        scoreHistory.takeLast(SMOOTHING_WINDOW)
        val previous = smoothed.lastOrNull()
        val next = if (previous == null) score else {
            (SMOOTHING_ALPHA * score + (1.0 - SMOOTHING_ALPHA) * previous)
                .toInt().coerceIn(0, 100)
        }
        smoothed.add(next)
        return next
    }

    /** Test/preview hook: clear smoothing window, history and baseline. */
    fun reset() {
        scoreHistory.clear()
        smoothed.clear()
        history.clear()
        baseline.clear()
    }

    /**
     * How many qualifying readings currently back this person's personal baseline.
     *
     * The deployed model spends most of its discriminative power on the personal-deviation features
     * (`hr_z`, `rmssd_z`, ...). Those are genuinely unknown until the person has been measured
     * several times, so this count is what separates "the model has context" from "the model is
     * guessing from imputed columns".
     */
    fun baselineSampleCount(): Int = baseline.sampleCount("hr_bpm")

    /** Further qualifying readings needed before the personal baseline is established. */
    fun readingsUntilBaselineReady(): Int =
        (PersonalBaseline.MIN_SAMPLES - baselineSampleCount()).coerceAtLeast(0)

    /**
     * Records a qualifying measurement into the personal baseline **without** reporting a score.
     *
     * This exists so that "not enough history yet" can be a *transient* state rather than a
     * deadlock. If an early camera reading were simply dropped, the baseline would never grow and
     * the person could never obtain a score at all. A reading that clears the quality gates is
     * therefore still learned from, while [evaluate] withholds the number it cannot yet stand
     * behind.
     *
     * @return true when the reading was admitted to the baseline.
     */
    fun warmUp(observation: StressObservation, quality: SignalQuality): Boolean {
        val vector = extractor.extract(observation)
        val gate = firstFailingGate(observation, vector, quality)
        if (gate != null && !gate.allowsCommit) return false
        extractor.commit(observation)
        onCommitted()
        return true
    }

    companion object {
        /** Below this surviving-interval fraction the beat series is too corrupted to model on. */
        const val MIN_VALID_NN_RATIO = 0.5

        /** Fraction of schema features that must actually be measured. */
        const val MIN_COMPLETENESS = 0.45

        /** Number of recent scores that influence the displayed value. */
        const val SMOOTHING_WINDOW = 3

        /** Weight given to the newest score. */
        const val SMOOTHING_ALPHA = 0.6
    }
}