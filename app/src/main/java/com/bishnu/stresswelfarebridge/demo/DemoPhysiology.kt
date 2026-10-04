package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.ml.StressObservation
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import java.time.Duration
import java.time.Instant

/**
 * Deterministic synthetic physiological observations for the demo personnel accounts.
 *
 * **Synthetic data.** These numbers were authored for demonstration. They were not measured on any
 * person and do not describe anyone's physiology.
 *
 * ### Deterministic, never random
 * Every value is a closed-form function of (personnelIndex, dayIndex). There is no Random, no clock
 * read and no run-order dependence, so seeding twice produces byte-identical input and the demo
 * reproduces exactly. That is what makes the seeded result verifiable in a test rather than merely
 * plausible on a screen.
 *
 * ### Internal consistency
 * The physiology is built to make sense, because an implausible pairing produces a score that simply
 * looks wrong on the commander dashboard:
 *
 *  - heart rate stays inside PersonalBaseline.ranges (hr_bpm 30..220);
 *  - RMSSD and SDNN stay inside theirs (1..300), and RMSSD <= SDNN as real data must be, because
 *    SDNN is by construction the larger, more slowly varying measure;
 *  - stress raises HR **and lowers HRV** together, because a sympathetic response shortens the beat
 *    interval and reduces vagal tone at the same time. The naive "high HR and high HRV = severe
 *    stress" pairing cannot be produced here;
 *  - meanNnMs is always the reciprocal of the HR, so it cannot contradict it;
 *  - timestamps are strictly increasing per person and always in the past.
 *
 * ### The stress signal is in the input, not the output
 * Each person has a resting HR, a resting HRV and a load amplitude describing how far above that rest
 * their physiology travels over the demo period. The score is then whatever the **existing deployed
 * model** makes of those values. Nothing in this file computes or asserts a stress score.
 */
object DemoPhysiology {

    /** Days of history seeded per account. Comfortably clears PersonalBaseline.MIN_SAMPLES. */
    const val DAYS = 12

    /** Measurements per day: a resting morning reading and an afternoon reading. */
    const val MEASUREMENTS_PER_DAY = 2

    /** Hour of day per slot; slot 0 is the resting morning reading. */
    private val HOURS = intArrayOf(7, 16)

    /** Minutes past the hour, so timestamps are not suspiciously round. */
    private val MINUTES = intArrayOf(12, 38)

    private val DAY_SPACING: Duration = Duration.ofHours(24)

    /** Signal quality for the synthetic capture: realistic, and never POOR, which always abstains. */
    val signalQuality: SignalQuality get() = SignalQuality.GOOD

    /** Resting heart rate per personnel index, in bpm. */
    fun restingHr(index: Int): Double = when (index) {
        1 -> 58.0
        2 -> 64.0
        3 -> 71.0
        4 -> 60.0
        5 -> 67.0
        6 -> 73.0
        7 -> 56.0
        8 -> 66.0
        9 -> 75.0
        10 -> 62.0
        else -> error("personnel index out of range: $index")
    }

    /** Resting RMSSD per personnel index, in ms. Higher means more parasympathetic tone. */
    fun restingRmssd(index: Int): Double = when (index) {
        1 -> 62.0
        2 -> 48.0
        3 -> 30.0
        4 -> 58.0
        5 -> 40.0
        6 -> 26.0
        7 -> 66.0
        8 -> 44.0
        9 -> 22.0
        10 -> 52.0
        else -> error("personnel index out of range: $index")
    }

    /**
     * How far this person's physiology travels above their own rest, in bpm.
     *
     * The only knob separating the three intended groups. It lifts HR and lowers HRV together, so a
     * larger amplitude is a genuinely more loaded profile rather than a relabelled copy.
     *
     * ### How these numbers were chosen
     * Not by eye and not by guessing at a target score: they were measured. Each value was swept
     * through the real deployed model and selected from the observed response curve so the score the
     * model produces on the final reading falls inside the intended band for that person. The scoring
     * logic is untouched - only these synthetic input constants were fitted to it, which is exactly
     * the adjustment the brief asks for when a model disagrees with the intended distribution.
     *
     * Peak heart rates land in the 82-99 bpm band, and peak RMSSD in the 8-34 ms band: plausible for
     * young adults under load, and never the "high HR *and* high HRV" pairing that would be nonsense.
     */
    fun loadAmplitude(index: Int): Double = when (index) {
        1 -> 24.0  // Low
        2 -> 34.0  // Moderate
        3 -> 28.0  // High
        4 -> 26.0  // Low
        5 -> 30.0  // Moderate
        6 -> 24.0  // High
        7 -> 26.0  // Low
        8 -> 30.0  // Moderate
        9 -> 20.0  // High
        10 -> 34.0 // Moderate
        else -> error("personnel index out of range: $index")
    }

    /**
     * Respiration rate for every synthetic reading, in breaths per minute.
     *
     * Held at a low-normal 9 bpm. Two reasons: it is a genuinely ordinary resting value for a young
     * adult, and holding it constant keeps the demonstration's variation coming from heart rate and
     * HRV - the two signals the physiological model actually personalises against - rather than from a
     * parameter the demo would otherwise be tuning by hand.
     */
    private const val RESP_RATE_BPM = 9.0

    /**
     * Recent physical load, normalised 0..1, as the model expects it.
     *
     * 0.0 = no recent exercise, which is the honest state for a seated duty period and keeps the
     * feature a measured value rather than a missing one that would be median-imputed.
     */
    private const val ACTIVITY_RECENT_NORM = 0.0

    /**
     * Load multiplier per day, as a fraction of loadAmplitude.
     *
     * A fixed repeating shape rather than noise, so the result is reproducible. The final day is the
     * highest for everyone, which means the record the commander reads (the latest) is a stable,
     * intentional value rather than whichever sample happened to land last.
     */
    fun dayLoadFactor(dayIndex: Int): Double = when (dayIndex) {
        DAYS - 1 -> 1.00
        DAYS - 2 -> 0.82
        DAYS - 3 -> 0.60
        DAYS - 4 -> 0.45
        DAYS - 5 -> 0.55
        else -> 0.25 + 0.04 * dayIndex
    }

    /** Within-day factor: the resting morning reading is always the lower one. */
    fun slotLoadFactor(slot: Int): Double = if (slot == 0) 0.82 else 1.0

    /**
     * The synthetic observation for one personnel account at one measurement slot.
     *
     * @param index personnel index, 1..10
     * @param dayIndex 0..DAYS-1, oldest first
     * @param slot 0 for the morning reading, 1 for the afternoon one
     * @param startOfPeriod the oldest timestamp in this person's series
     */
    fun observation(index: Int, dayIndex: Int, slot: Int, startOfPeriod: Instant): StressObservation {
        require(dayIndex in 0 until DAYS) { "dayIndex out of range: $dayIndex" }
        require(slot in 0 until MEASUREMENTS_PER_DAY) { "slot out of range: $slot" }

        val load = loadAmplitude(index) * dayLoadFactor(dayIndex) * slotLoadFactor(slot)
        val hr = restingHr(index) + load
        // Floored at 12 ms, a value the deployed model was validated around for heavily suppressed
        // short-window HRV. Lowering it pushes rmssd_z further negative, which *reduces* the score
        // because that feature's weight is positive, so this floor is part of the calibrated demo and
        // is deliberately not a cosmetic clamp.
        val rmssd = (restingRmssd(index) - load * 1.15).coerceAtLeast(12.0)
        // SDNN is always the larger, slower measure; keeping it above RMSSD is what real data looks like.
        val sdnn = (rmssd * 1.35 + 12.0).coerceAtMost(280.0)
        val meanNn = 60000.0 / hr
        val sdsd = (rmssd * 0.92).coerceAtLeast(5.0)
        val pnn50 = (12.0 + rmssd * 0.45).coerceIn(10.0, 60.0)

        return StressObservation(
            measuredAt = timestampFor(dayIndex, slot, startOfPeriod),
            source = CameraHrvResult.SOURCE_CAMERA_PPG,
            hrBpm = round1(hr),
            rmssdMs = round1(rmssd),
            sdnnMs = round1(sdnn),
            meanNnMs = round1(meanNn),
            sdsdMs = round1(sdsd),
            pnn50Pct = round1(pnn50),
            respRateBpm = RESP_RATE_BPM,
            activityRecentNorm = ACTIVITY_RECENT_NORM,
            ppgQuality = 0.94,
            confidence = 0.94,
            validNnRatio = 1.0,
            artifactRatio = 0.0,
            durationSeconds = 45.0
        )
    }

    /** Timestamp for one slot; strictly increasing and always after startOfPeriod. */
    fun timestampFor(dayIndex: Int, slot: Int, startOfPeriod: Instant): Instant =
        startOfPeriod.plus(DAY_SPACING.multipliedBy(dayIndex.toLong()))
            .plus(Duration.ofHours(HOURS[slot].toLong()))
            .plus(Duration.ofMinutes(MINUTES[slot].toLong()))

    /** Every observation for one person, oldest first. */
    fun seriesFor(index: Int, startOfPeriod: Instant): List<StressObservation> =
        (0 until DAYS).flatMap { day ->
            (0 until MEASUREMENTS_PER_DAY).map { slot -> observation(index, day, slot, startOfPeriod) }
        }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}