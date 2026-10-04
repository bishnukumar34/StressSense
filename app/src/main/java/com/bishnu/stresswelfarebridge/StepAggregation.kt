package com.bishnu.stresswelfarebridge

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Pure, side-effect free step aggregation helpers.
 *
 * These functions contain NO Android or Health Connect dependencies so they can be verified with
 * plain JVM unit tests. [HealthRepository.readStepSeries] supplies the per-bucket step counts that
 * come from real Health Connect aggregation; everything here just slices the calendar correctly
 * (local timezone, start/end of day/week/month/year) and shapes the data for the UI.
 */

enum class StepPeriod { DAY, WEEK, MONTH, YEAR }

/** One bar in the graph: a human label plus the real step count for that bucket. */
data class StepPoint(val label: String, val count: Long)

/** A calendar bucket with its exact local-time boundaries (used to query Health Connect). */
data class StepBucketSpec(val label: String, val start: Instant, val end: Instant)

/** Fully resolved data for one period/date selection. Total and graph share [points] so they can
 *  never disagree. */
data class StepSeries(
    val period: StepPeriod,
    val anchor: LocalDate,
    val title: String,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val points: List<StepPoint>,
    val total: Long,
    val goal: Long,
    val bestLabel: String?,
    val bestCount: Long,
    val bucketNoun: String,
    val highlightIndex: Int
) {
    val hasData: Boolean get() = total > 0L
    val averagePerBucket: Double get() = if (points.isEmpty()) 0.0 else total.toDouble() / points.size
    val goalProgress: Float get() = if (goal <= 0L) 0f else (total.toDouble() / goal.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

private val WEEKDAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
private val DAY_TITLE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM")
private val MONTH_TITLE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val MONTH_SHORT_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

/** Monday of the ISO week that contains [date]. */
fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Compact hour label used on the DAY graph: 0 -> "12a", 13 -> "1p". */
fun stepHourLabel(hour: Int): String = when {
    hour == 0 -> "12a"
    hour in 1..11 -> "${hour}a"
    hour == 12 -> "12p"
    else -> "${hour - 12}p"
}

private fun startOfDay(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

/**
 * Builds the list of calendar buckets for a period. Every boundary is computed from the user's
 * local timezone, so there are no UTC/local off-by-one errors.
 *
 *  - DAY   -> 24 hourly buckets covering the local day
 *  - WEEK  -> 7 buckets, Monday..Sunday (ISO week)
 *  - MONTH -> 28/29/30/31 buckets, one per day
 *  - YEAR  -> 12 buckets, one per month
 */
fun stepBucketSpecs(period: StepPeriod, anchor: LocalDate, zone: ZoneId): List<StepBucketSpec> = when (period) {
    StepPeriod.DAY -> {
        val dayStart = anchor.atStartOfDay()
        (0 until 24).map { h ->
            StepBucketSpec(
                stepHourLabel(h),
                dayStart.plusHours(h.toLong()).atZone(zone).toInstant(),
                dayStart.plusHours(h + 1L).atZone(zone).toInstant()
            )
        }
    }
    StepPeriod.WEEK -> {
        val monday = mondayOf(anchor)
        (0 until 7).map { i ->
            val day = monday.plusDays(i.toLong())
            StepBucketSpec(day.format(WEEKDAY_LABEL), startOfDay(day, zone), startOfDay(day.plusDays(1), zone))
        }
    }
    StepPeriod.MONTH -> {
        val ym = YearMonth.from(anchor)
        (1..ym.lengthOfMonth()).map { d ->
            val day = ym.atDay(d)
            StepBucketSpec(d.toString(), startOfDay(day, zone), startOfDay(day.plusDays(1), zone))
        }
    }
    StepPeriod.YEAR -> {
        val year = anchor.year
        (1..12).map { m ->
            val ym = YearMonth.of(year, m)
            StepBucketSpec(
                ym.format(MONTH_SHORT_LABEL),
                startOfDay(ym.atDay(1), zone),
                startOfDay(ym.plusMonths(1).atDay(1), zone)
            )
        }
    }
}

/** Shifts [anchor] by [delta] periods (e.g. delta = -1 -> previous week). */
fun shiftAnchor(period: StepPeriod, anchor: LocalDate, delta: Long): LocalDate = when (period) {
    StepPeriod.DAY -> anchor.plusDays(delta)
    StepPeriod.WEEK -> anchor.plusWeeks(delta)
    StepPeriod.MONTH -> anchor.plusMonths(delta)
    StepPeriod.YEAR -> anchor.plusYears(delta)
}

/** True when the period containing [anchor] is the one that contains [today]. */
fun isCurrentPeriod(period: StepPeriod, anchor: LocalDate, today: LocalDate): Boolean = when (period) {
    StepPeriod.DAY -> anchor == today
    StepPeriod.WEEK -> mondayOf(anchor) == mondayOf(today)
    StepPeriod.MONTH -> YearMonth.from(anchor) == YearMonth.from(today)
    StepPeriod.YEAR -> anchor.year == today.year
}

/**
 * Assembles the final [StepSeries] from the bucket definitions and their (already de-duplicated)
 * step counts. [now] is injected so the "highlighted" bar is deterministic and testable.
 */
fun assembleStepSeries(
    period: StepPeriod,
    anchor: LocalDate,
    goal: Long,
    zone: ZoneId,
    now: LocalDateTime,
    specs: List<StepBucketSpec>,
    counts: List<Long>
): StepSeries {
    require(specs.size == counts.size) { "specs (${specs.size}) and counts (${counts.size}) must align" }
    val points = specs.mapIndexed { i, spec -> StepPoint(spec.label, counts[i].coerceAtLeast(0L)) }
    val total = points.sumOf { it.count }
    val best = points.maxByOrNull { it.count }
    val bucketNoun = when (period) {
        StepPeriod.DAY -> "hour"
        StepPeriod.WEEK, StepPeriod.MONTH -> "day"
        StepPeriod.YEAR -> "month"
    }
    val title = when (period) {
        StepPeriod.DAY -> if (anchor == now.toLocalDate()) "Today" else anchor.format(DAY_TITLE_LABEL)
        StepPeriod.WEEK -> "Week of " + mondayOf(anchor).format(DAY_TITLE_LABEL)
        StepPeriod.MONTH -> YearMonth.from(anchor).format(MONTH_TITLE_LABEL)
        StepPeriod.YEAR -> anchor.year.toString()
    }
    val today = now.toLocalDate()
    val highlightIndex = when (period) {
        StepPeriod.DAY -> if (anchor == today) now.hour else -1
        StepPeriod.WEEK -> {
            val idx = ChronoUnit.DAYS.between(mondayOf(today), today).toInt()
            if (isCurrentPeriod(StepPeriod.WEEK, anchor, today) && idx in 0..6) idx else -1
        }
        StepPeriod.MONTH -> if (YearMonth.from(anchor) == YearMonth.from(today)) now.dayOfMonth - 1 else -1
        StepPeriod.YEAR -> if (anchor.year == now.year) now.monthValue - 1 else -1
    }
    val rangeStart = specs.firstOrNull()?.start ?: anchor.atStartOfDay(zone).toInstant()
    val rangeEnd = specs.lastOrNull()?.end ?: anchor.plusDays(1).atStartOfDay(zone).toInstant()
    return StepSeries(
        period = period,
        anchor = anchor,
        title = title,
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        points = points,
        total = total,
        goal = goal,
        bestLabel = best?.takeIf { it.count > 0 }?.label,
        bestCount = best?.count ?: 0L,
        bucketNoun = bucketNoun,
        highlightIndex = highlightIndex
    )
}
