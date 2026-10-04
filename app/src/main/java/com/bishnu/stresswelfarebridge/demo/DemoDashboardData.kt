package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.commander.StressZoneMapping
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Presents an already-persisted demo measurement to the dashboard.
 *
 * ## Why this exists
 *
 * [DemoDataSeeder] writes each demo person's physiological readings into the ordinary
 * [com.bishnu.stresswelfarebridge.ml.StressMeasurementStore], keyed by that account's own id. But the
 * Home and Stress screens render from Health Connect's `DailyHealth`, so after a fresh install - where
 * Health Connect holds nothing and the demo person has no wearable - the dashboard showed "-" and
 * "AWAITING DATA" even though the seeded records were sitting in the store, correctly and in isolation.
 *
 * This class is the missing link: it converts the **existing persisted record** into the two shapes the
 * dashboard already renders (`DailyHealth` for the metrics, `StressEngine.Result` for the stress card).
 * It adds no data of its own.
 *
 * ## What it deliberately does not do
 *
 *  - It does **not** recompute a score. [resultFrom] publishes `record.stressScore`, which the deployed
 *    model produced when the record was seeded. No second formula is involved.
 *  - It does **not** invent a resting heart rate or any other field the record does not hold; absent
 *    values stay null.
 *  - It does **not** affect real accounts. [MainActivity] only consults it for demo usernames, so an
 *    ordinary user's Health Connect behaviour is untouched.
 *
 * The band comes from [StressZoneMapping], the same existing thresholds the Commander uses, so the
 * dashboard and the Commander can never disagree about the same number.
 */
object DemoDashboardData {

    /** True when [username] is one of the synthetic demo personnel accounts. */
    fun isDemoPersonnel(username: String?): Boolean {
        val u = username ?: return false
        // Compared against the real usernames rather than re-parsed, so this can never drift from the
        // accounts the seeder actually creates - and an unknown look-alike such as
        // "personnel4@stresssense.demo" or "personnel99@..." is correctly rejected.
        return DemoAccounts.personnelIndices.any { DemoAccounts.personnelUsername(it) == u }
    }

    /** The newest scored record for this person, or null when none has been seeded. */
    fun latestScored(records: List<StressMeasurementRecord>): StressMeasurementRecord? =
        records.filter { it.stressScore != null }.maxByOrNull { it.measuredAtMs }

    /**
     * The persisted reading rendered as a [DailyHealth] for the metric cards.
     *
     * Only carries the values the record actually holds: heart rate and RMSSD. Nothing is imputed, so
     * the card shows a real number or a dash exactly as it does for any other user.
     */
    fun dailyHealthFrom(record: StressMeasurementRecord, zone: ZoneId = ZoneId.systemDefault()): DailyHealth {
        val date = Instant.ofEpochMilli(record.measuredAtMs).atZone(zone).toLocalDate()
        val health = DailyHealth(date)
        health.avgHr = record.heartRateBpm
        health.avgHrv = record.rmssdMs
        record.heartRateBpm?.let { health.heartRates.add(it) }
        record.rmssdMs?.let { health.hrvValues.add(it) }
        return health
    }

    /**
     * The persisted reading rendered as the [StressEngine.Result] the dashboard displays.
     *
     * The score is the model's own output, carried straight from storage. When no score was ever
     * produced the honest "Awaiting data" state is returned rather than a substitute number.
     */
    fun resultFrom(record: StressMeasurementRecord): StressEngine.Result {
        val score = record.stressScore
            ?: return StressEngine.Result(
                score = null,
                level = "Awaiting data",
                confidence = 0,
                hrContribution = 0,
                hrvContribution = 0,
                recoveryContribution = 0,
                message = "No stress score has been calculated for this measurement yet.",
                usingRealData = false,
                dataQuality = "Synthetic demo measurement with no model score",
                baselineDays = 0
            )
        return StressEngine.Result(
            score = score,
            level = levelTextFor(score),
            // The model's own confidence, persisted with the record.
            confidence = record.confidence,
            hrContribution = 0,
            hrvContribution = 0,
            recoveryContribution = 0,
            message = "Synthetic demo measurement scored on-device by ${record.modelVersion}.",
            // True because the score genuinely came from the on-device deployed model; the *inputs* are
            // synthetic, which dataQuality states plainly rather than hiding.
            usingRealData = true,
            dataQuality = "Synthetic demo data scored by ${record.modelVersion}",
            baselineDays = 0,
            lowConfidence = false
        )
    }

    /**
     * The dashboard's own wording for a score, derived from the **existing** band thresholds.
     *
     * [StressZoneMapping.fromScore] uses `StressEngine.LOW_SCORE_MAX` / `MODERATE_SCORE_MAX`, the same
     * cut-points `StressEngine.infer` applies, so the Home card and the Commander band are guaranteed to
     * agree. There is no second threshold set here.
     */
    fun levelTextFor(score: Int): String = when (StressZoneMapping.fromScore(score)) {
        StressZone.RED -> "High"
        StressZone.YELLOW -> "Moderate"
        StressZone.GREEN -> "Low"
    }
}