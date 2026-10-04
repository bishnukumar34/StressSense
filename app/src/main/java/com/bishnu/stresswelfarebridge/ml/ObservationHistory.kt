package com.bishnu.stresswelfarebridge.ml

import java.time.Duration
import java.time.Instant

/**
 * Bounded, per-account history of past observations, used to build the temporal features of Phase 7.
 *
 * The governing rule of this class is that **history is never invented**. Every statistic is
 * computed from measurements that actually happened; when the required history does not exist the
 * feature is null, and a null feature is imputed as missing by the model rather than being filled
 * with a zero or a guess. A subject's first ever measurement therefore has no `hr_delta_recent`,
 * and that is correct ” there is nothing to have differed from.
 *
 * Only qualifying observations are admitted (see [StressEstimator] for the quality gate), so a bad
 * acquisition cannot manufacture a fake trend.
 */
class ObservationHistory(
    private val maxEntries: Int = 200,
    private val retention: Duration = Duration.ofDays(30)
) {

    private data class Entry(val at: Instant, val hr: Double?, val rmssd: Double?)

    /** Oldest first. */
    private val entries = ArrayList<Entry>()

    /** Records an observation. Values are stored as measured; nulls are kept, not dropped. */
    fun add(observation: StressObservation) {
        entries.add(Entry(observation.measuredAt, observation.hrBpm, observation.rmssdMs))
        evict(observation.measuredAt)
    }

    private fun evict(now: Instant) {
        val cutoff = now.minus(retention)
        while (entries.isNotEmpty() && entries.first().at.isBefore(cutoff)) entries.removeAt(0)
        while (entries.size > maxEntries) entries.removeAt(0)
    }

    /**
     * History strictly *before* [now], most recent last. The current observation is never included
 * in its own temporal features ” that would make every delta exactly zero.
     */
    private fun prior(now: Instant): List<Entry> = entries.filter { it.at.isBefore(now) }

    fun size(): Int = entries.size

    fun clear() = entries.clear()

    // -- persistence ---------------------------------------------------------------------------------
    //
    // This history supplies the temporal features `hr_delta_recent`, `rmssd_delta_recent`,
    // `hr_slope_recent` and `rmssd_slope_recent`. If it were lost on restart, every person's temporal
    // columns would fall back to missing immediately after each app launch, and the model would score
    // on a weaker feature vector than it was validated on.
    //
    // Snapshot/restore is plain data in and out; [StressBaselineStore] owns the actual storage.

    /** Every retained observation, oldest first. */
    fun snapshot(): List<ObservationEntry> =
        entries.map { ObservationEntry(it.at.toEpochMilli(), it.hr, it.rmssd) }

    /**
     * Replaces the history with [entries], oldest first.
     *
     * No observation is invented, dropped or re-valued: the list is restored exactly as saved. The
     * existing bounds are then reapplied, relative to the newest restored entry, exactly as [add]
     * does relative to the newest incoming one. That preserves the intended 30-day window and
     * 200-entry cap rather than extending them across restarts.
     */
    fun restore(entries: List<ObservationEntry>) {
        this.entries.clear()
        val cutoff = entries.maxOfOrNull { it.atMillis }?.minus(retention.toMillis()) ?: return
        var kept = entries.filter { it.atMillis >= cutoff }.sortedBy { it.atMillis }
        if (kept.size > maxEntries) kept = kept.subList(kept.size - maxEntries, kept.size)
        kept.forEach {
            this.entries.add(Entry(Instant.ofEpochMilli(it.atMillis), it.hr, it.rmssd))
        }
    }

    // -- Phase 7 temporal statistics ----------------------------------------------------------------

    /** Most recent prior HR, or null when there is no earlier measurement. */
    fun latestHrBefore(now: Instant): Double? = prior(now).lastOrNull()?.hr

    /** Most recent prior RMSSD, or null when there is no earlier measurement. */
    fun latestRmssdBefore(now: Instant): Double? = prior(now).lastOrNull()?.rmssd

    /** Change since the previous measurement. Null when either side is missing. */
    fun hrChange(now: Instant, current: Double?): Double? {
        val prev = latestHrBefore(now) ?: return null
        val cur = current ?: return null
        return cur - prev
    }

    fun rmssdChange(now: Instant, current: Double?): Double? {
        val prev = latestRmssdBefore(now) ?: return null
        val cur = current ?: return null
        return cur - prev
    }

    /**
     * Least-squares slope over the last [window] prior measurements, in units per measurement.
     *
     * Requires at least [MIN_SLOPE_POINTS] points, otherwise null. Uses each point's own index as
     * the x-coordinate rather than the timestamp, so the slope is "change per measurement" and does
     * not silently encode how often the person happened to measure.
     */
    fun hrSlope(now: Instant, window: Int = 5): Double? = slope(now, window) { it.hr }

    fun rmssdSlope(now: Instant, window: Int = 5): Double? = slope(now, window) { it.rmssd }

    private fun slope(
        now: Instant,
        window: Int,
        pick: (Entry) -> Double?
    ): Double? {
        val points = prior(now).takeLast(window).mapNotNull { e ->
            pick(e)?.let { e.at.toEpochMilli().toDouble() to it }
        }
        if (points.size < MIN_SLOPE_POINTS) return null
        val n = points.size.toDouble()
        val meanX = points.sumOf { it.first } / n
        val meanY = points.sumOf { it.second } / n
        var num = 0.0
        var den = 0.0
        points.forEach { (x, y) ->
            val dx = x - meanX
            num += dx * (y - meanY)
            den += dx * dx
        }
        if (den <= 0.0) return null
        val perMs = num / den
        // Convert per-millisecond to per-measurement using the observed spacing between points.
        val spanMs = points.last().first - points.first().first
        val gaps = points.size - 1
        if (gaps <= 0 || spanMs <= 0.0) return null
        return perMs * (spanMs / gaps)
    }

    companion object {
        /** Two points define a line but no trend; three is the minimum for a meaningful slope. */
        const val MIN_SLOPE_POINTS = 3
    }
}
/**
 * One stored observation: when it was measured and the two values the temporal features read.
 *
 * `hr` and `rmssd` stay nullable because [ObservationHistory.add] keeps nulls rather than dropping
 * them; a restored entry must preserve that, or the history would start claiming measurements that
 * never contained a usable value.
 */
data class ObservationEntry(val atMillis: Long, val hr: Double?, val rmssd: Double?)
