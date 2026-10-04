package com.bishnu.stresswelfarebridge.trends

import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import com.bishnu.stresswelfarebridge.questionnaire.DailyContext
import com.bishnu.stresswelfarebridge.questionnaire.DailyContextCategory
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord
import com.bishnu.stresswelfarebridge.questionnaire.Pss10
import com.bishnu.stresswelfarebridge.questionnaire.Pss10Record
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The three-banded colour vocabulary shared by all three graphs. */
enum class StressBand(val label: String) {
    LOW("Low"),
    MODERATE("Moderate"),
    HIGH("High")
}

/**
 * One persisted physiological stress measurement, plotted at its real date and time.
 *
 * [atMillis] is the instant the measurement was taken, taken straight from
 * [StressMeasurementRecord.measuredAtMs] - never synthesised from an index or rounded to a day.
 */
data class PhysiologicalPoint(val atMillis: Long, val score: Int, val band: StressBand)

/** One completed month's PSS-10 total, on its own 0-40 scale. */
data class PerceivedPoint(val month: YearMonth, val score: Int, val band: StressBand)

/**
 * One completed Daily Check-in, reduced to its display-only category.
 *
 * [category] is [DailyContextCategory], never a number. There is deliberately no `score` field, so
 * this type cannot be made to carry a daily stress reading even by accident.
 */
data class DailyContextPoint(val date: LocalDate, val category: DailyContextCategory)
/**
 * Builds the three trend series from **real persisted records only**.
 *
 * ### The rule this file exists to enforce
 * No graph may invent a point. Every entry here comes from a record the app actually wrote, so an
 * empty history yields an empty series and the UI shows its "no data yet" message rather than a
 * plausible-looking curve. Gaps are never filled and months a person never completed are never
 * interpolated.
 *
 * ### The three scales stay separate
 * Physiological is 0-100, PSS-10 is 0-40, Daily Context is Low/Medium/High. Nothing converts one
 * into another or combines them: the three builders share no code path beyond [bandFor], which is
 * presentation colour only.
 *
 * Android-free by design, so every rule is verifiable with plain JVM unit tests.
 */
object StressTrends {

    const val PHYSIOLOGICAL_MIN = 0
    const val PHYSIOLOGICAL_MAX = 100

    const val PERCEIVED_MIN = 0
    const val PERCEIVED_MAX = 40

    /**
     * Band for a physiological score, using the app's **existing** cut-points.
     *
     * Read from [StressEngine] rather than restated, so the graph colour can never disagree with the
     * "Low"/"Moderate"/"High" level the engine itself prints on the card above it. Presentation
     * mapping only: it creates no second model and changes no score.
     */
    fun bandFor(score: Int): StressBand = when {
        score < StressEngine.LOW_SCORE_MAX -> StressBand.LOW
        score < StressEngine.MODERATE_SCORE_MAX -> StressBand.MODERATE
        else -> StressBand.HIGH
    }

    /**
     * Physiological points, chronologically, one per real measurement.
     *
     * Records the model abstained on (`stressScore == null`) are dropped rather than plotted at zero:
     * an abstention means "not measured", and plotting it as 0 would invent a healthy reading.
     */
    fun physiological(records: List<StressMeasurementRecord>): List<PhysiologicalPoint> =
        records.filter { it.stressScore != null }
            .sortedBy { it.measuredAtMs }
            .map { PhysiologicalPoint(it.measuredAtMs, it.stressScore!!, bandFor(it.stressScore!!)) }

    /**
     * PSS-10 points, one per completed month, chronologically.
     *
     * Only months that genuinely have a completed response appear. **Missing months are not
     * created** and no score is interpolated for a month a person never answered; the line simply
     * connects the months that exist.
     */
    fun perceived(records: List<Pss10Record>): List<PerceivedPoint> {
        val scores = records
            .filter { it.completed && it.score != null }
            .associate { it.month to it.score!! }
            .toSortedMap()
        val bare = scores.map { (month, score) -> PerceivedPoint(month, score, StressBand.LOW) }
        val relative = relativeBands(bare)
        return bare.map { it.copy(band = relative[it.month] ?: StressBand.LOW) }
    }

    /**
     * Colour guidance for PSS-10, relative to **this person's own** recorded range.
     *
     * PSS-10 has no officially endorsed clinical cut-offs, so presenting these colours as severity
     * bands would be a false clinical claim. Each score is instead split into thirds of the range the
     * person has actually produced, purely so the graph is readable at a glance. The score itself
     * remains the authoritative value and the UI states this next to the graph.
     */
    fun relativeBands(points: List<PerceivedPoint>): Map<YearMonth, StressBand> {
        if (points.isEmpty()) return emptyMap()
        val lo = points.minOf { it.score }.toDouble()
        val hi = points.maxOf { it.score }.toDouble()
        if (hi <= lo) return points.associate { it.month to StressBand.LOW }
        val mid = lo + (hi - lo) / 2.0
        return points.associate { p ->
            val v = p.score.toDouble()
            p.month to when {
                v <= lo -> StressBand.LOW
                v <= mid -> StressBand.MODERATE
                else -> StressBand.HIGH
            }
        }
    }

    /**
     * Daily Context points, one per completed check-in, chronologically.
     *
     * A check-in missing any of its three answers yields no category, so it is skipped. Dates a
     * person never checked in on are never invented, and [DailyPulseRecord] is never given a score.
     */
    fun dailyContext(records: List<DailyPulseRecord>): List<DailyContextPoint> =
        records.mapNotNull { r -> DailyContext.category(r)?.let { DailyContextPoint(r.date, it) } }
            .distinctBy { it.date }
            .sortedBy { it.date }

    /**
     * Where a category sits on the graph, 0 (bottom) to 1 (top).
     *
     * A **drawing position, not a measurement**. The Daily Context y-axis is labelled with the words
     * High / Medium / Low and never with a number, so nothing is ever converted into a fake score.
     */
    fun plotFraction(category: DailyContextCategory): Float = when (category) {
        DailyContextCategory.LOW -> 0f
        DailyContextCategory.MEDIUM -> 0.5f
        DailyContextCategory.HIGH -> 1f
    }

    // -- axis labels ---------------------------------------------------------------------------------

    /** "Oct 5" - the Daily Context x-axis label. */
    fun contextDateLabel(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))

    /** "Oct" - the PSS-10 x-axis label. */
    fun perceivedMonthLabel(month: YearMonth): String =
        month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** "October 2026" - the PSS-10 tooltip label. */
    fun perceivedMonthLongLabel(month: YearMonth): String =
        month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.year

    /** "Oct 5, 10:30 AM" - the physiological x-axis and tooltip label, from the real instant. */
    fun measuredAtLabel(
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = Instant.ofEpochMilli(atMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH))

    /** Where a 0-100 score sits on the plot, clamped so nothing can escape the box. */
    fun physiologicalFraction(score: Int): Float =
        ((score - PHYSIOLOGICAL_MIN).toFloat() / (PHYSIOLOGICAL_MAX - PHYSIOLOGICAL_MIN))
            .coerceIn(0f, 1f)

    /** Where a 0-40 score sits on the plot. Never rescaled onto 0-100. */
    fun perceivedFraction(score: Int): Float =
        ((score - PERCEIVED_MIN).toFloat() / (PERCEIVED_MAX - PERCEIVED_MIN)).coerceIn(0f, 1f)

    /** Sanity guard used by the graphs: PSS-10 must never be presented outside 0-40. */
    fun isValidPerceivedScore(score: Int): Boolean = score in PERCEIVED_MIN..PERCEIVED_MAX

    /** Sanity guard used by the graphs: physiological scores come from the engine on 0-100. */
    fun isValidPhysiologicalScore(score: Int): Boolean = score in PHYSIOLOGICAL_MIN..PHYSIOLOGICAL_MAX

    /** The maximum the PSS-10 module itself declares, so the axis and the scorer cannot drift. */
    val perceivedMaxScore: Int get() = Pss10.MAX_SCORE
}