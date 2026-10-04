package com.bishnu.stresswelfarebridge

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max

/**
 * On-device ML inference.
 * The model consumes real Health Connect measurements when available and personalizes
 * them against a rolling baseline. The bundled coefficients are a research/prototype
 * baseline; they are not a clinical diagnostic model.
 */
object StressEngine {
    private const val MIN_BASELINE_DAYS = 3

    /**
     * Stress band cut-points, extracted as named constants (values unchanged) so that the
     * commander zone mapping can reuse the *existing* thresholds instead of restating them.
     * A score below [LOW_SCORE_MAX] is "Low", below [MODERATE_SCORE_MAX] is "Moderate",
     * otherwise "High".
     */
    const val LOW_SCORE_MAX = 35
    const val MODERATE_SCORE_MAX = 65

    // Physiologically plausible ranges used to reject obvious sensor glitches.
    private const val HR_MIN = 30.0; private const val HR_MAX = 220.0
    private const val HRV_MIN = 1.0; private const val HRV_MAX = 300.0
    private const val RHR_MIN = 30.0; private const val RHR_MAX = 150.0

    data class Baseline(
        val hr: Double, val hrv: Double, val restingHr: Double, val sleepMinutes: Double, val steps: Double,
        val daysUsed: Int = 0
    )
    data class Result(
        val score: Int?, val level: String, val confidence: Int,
        val hrContribution: Int, val hrvContribution: Int, val recoveryContribution: Int,
        val message: String, val usingRealData: Boolean,
        val dataQuality: String = "", val baselineDays: Int = 0, val lowConfidence: Boolean = false
    )
    data class Model(val weights: List<Double>, val bias: Double)

    private fun Double.plausible(min: Double, max: Double): Boolean = this in min..max

    /** Trimmed mean: drops the single highest and lowest reading when there are enough
     * samples, which makes the baseline far less sensitive to one-off sensor glitches
     * or outlier days than a plain average. */
    private fun List<Double>.trimmedMeanOr(default: Double): Double {
        if (isEmpty()) return default
        if (size < 4) return average()
        val sorted = sorted()
        return sorted.subList(1, sorted.size - 1).average()
    }

    fun loadModel(context: Context): Model = try {
        val json = context.assets.open("stress_model.json").bufferedReader().use { JSONObject(it.readText()) }
        val a = json.getJSONArray("weights")
        Model(List(a.length()) { a.getDouble(it) }, json.getDouble("bias"))
    } catch (_: Exception) {
        Model(listOf(2.6, -3.4, 1.4, -0.7, 0.25), -0.5)
    }

    fun baseline(days: List<DailyHealth>): Baseline {
        val hrDays = days.mapNotNull { it.avgHr }.filter { it.plausible(HR_MIN, HR_MAX) }
        val hrvDays = days.mapNotNull { it.avgHrv }.filter { it.plausible(HRV_MIN, HRV_MAX) }
        val rhrDays = days.mapNotNull { it.restingHr }.filter { it.plausible(RHR_MIN, RHR_MAX) }
        val sleepDays = days.map { it.sleepMinutes.toDouble() }.filter { it > 0 }
        val stepDays = days.map { it.steps.toDouble() }.filter { it > 0 }
        // Days usable for a real personal baseline need both HR and HRV present and plausible.
        val realDays = days.count {
            val hr = it.avgHr; val hrv = it.avgHrv
            hr != null && hrv != null && hr.plausible(HR_MIN, HR_MAX) && hrv.plausible(HRV_MIN, HRV_MAX)
        }
        return Baseline(
            hrDays.trimmedMeanOr(72.0),
            hrvDays.trimmedMeanOr(45.0),
            rhrDays.trimmedMeanOr(62.0),
            sleepDays.trimmedMeanOr(420.0),
            stepDays.trimmedMeanOr(6000.0),
            realDays
        )
    }

    fun infer(day: DailyHealth, baseline: Baseline, model: Model): Result {
        val hrOk = day.avgHr?.plausible(HR_MIN, HR_MAX) == true
        val hrvOk = day.avgHrv?.plausible(HRV_MIN, HRV_MAX) == true
        val rhrOk = day.restingHr?.plausible(RHR_MIN, RHR_MAX) == true
        val hasReal = hrOk && hrvOk && rhrOk
        if (!hasReal) return Result(
            null, "Awaiting data", 0, 0, 0, recoveryScore(day),
            "Health Connect needs real HR and HRV data before the AI stress estimate can run.",
            false, dataQuality = "No usable HR/HRV reading today", baselineDays = baseline.daysUsed
        )

        val hr = day.avgHr!!
        val hrv = day.avgHrv!!
        val rhr = day.restingHr!!
        val sleep = if (day.sleepMinutes > 0) day.sleepMinutes.toDouble() else baseline.sleepMinutes
        val steps = if (day.steps > 0) day.steps.toDouble() else baseline.steps
        val features = listOf(
            (hr - baseline.hr) / max(5.0, baseline.hr * .07),
            (hrv - baseline.hrv) / max(8.0, baseline.hrv * .20),
            (rhr - baseline.restingHr) / max(4.0, baseline.restingHr * .06),
            (sleep - baseline.sleepMinutes) / 90.0,
            (steps - baseline.steps) / max(1500.0, baseline.steps * .25)
        ).map { it.coerceIn(-3.0, 3.0) }
        val z = model.bias + model.weights.zip(features).sumOf { (w, x) -> w * x }
        val p = (1.0 / (1.0 + exp(-z))).coerceIn(0.0, 1.0)
        val score = (p * 100.0).toInt()
        val level = when {
            score < LOW_SCORE_MAX -> "Low"
            score < MODERATE_SCORE_MAX -> "Moderate"
            else -> "High"
        }
        // Confidence now reflects genuine data completeness instead of a fixed guess:
        //  - how many days actually contributed to the personal baseline (more days = more
        //    reliable "normal" to compare against)
        //  - how many HRV samples were captured today (more samples = less noisy reading)
        val baselineDaysScore = (baseline.daysUsed.coerceAtMost(MIN_BASELINE_DAYS) * 100) / MIN_BASELINE_DAYS
        val sampleScore = when {
            day.hrvValues.size >= 3 -> 100
            day.hrvValues.size == 2 -> 75
            day.hrvValues.size == 1 -> 55
            else -> 40
        }
        val confidence = ((baselineDaysScore * 0.6) + (sampleScore * 0.4)).toInt().coerceIn(0, 100)
        val lowConfidence = baseline.daysUsed < MIN_BASELINE_DAYS

        val hrContribution = (50 + features[0] * 20).coerceIn(0.0, 100.0).toInt()
        val hrvContribution = (50 - features[1] * 20).coerceIn(0.0, 100.0).toInt()
        val baseMsg = when (level) {
            "Low" -> "Current HR/HRV is close to the personal baseline."
            "Moderate" -> "Current physiology differs from the recent personal baseline."
            else -> "Current physiology shows a stronger stress-like deviation from the personal baseline."
        }
        val dataQuality = if (lowConfidence)
            "Baseline built from only ${baseline.daysUsed} day(s) of real data (recommended: $MIN_BASELINE_DAYS+). Treat this score as provisional."
        else
            "Baseline built from ${baseline.daysUsed} day(s) of real HR/HRV data."
        val msg = if (lowConfidence) "$baseMsg Early-baseline estimate: accuracy improves as more days of data are collected." else baseMsg

        return Result(
            score, level, confidence, hrContribution, hrvContribution, recoveryScore(day), msg, true,
            dataQuality = dataQuality, baselineDays = baseline.daysUsed, lowConfidence = lowConfidence
        )
    }

    fun recoveryScore(day: DailyHealth): Int {
        val sleep = (day.sleepMinutes / 480.0 * 100.0).coerceIn(0.0, 100.0)
        val activity = (day.steps / 8000.0 * 100.0).coerceIn(0.0, 100.0)
        return (sleep * .7 + activity * .3).toInt()
    }
}
