package com.bishnu.stresswelfarebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StepAggregationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata") // UTC+05:30, exercises half-hour offsets

    @Test
    fun dayHas24ContiguousHourlyBucketsCoveringTheLocalDay() {
        val anchor = LocalDate.of(2026, 10, 5)
        val specs = stepBucketSpecs(StepPeriod.DAY, anchor, zone)
        assertEquals(24, specs.size)
        assertEquals(anchor.atStartOfDay(zone).toInstant(), specs.first().start)
        assertEquals(anchor.plusDays(1).atStartOfDay(zone).toInstant(), specs.last().end)
        for (i in 1 until specs.size) assertEquals(specs[i - 1].end, specs[i].start)
    }

    @Test
    fun weekStartsOnMondayWith7IndependentDays() {
        val anchor = LocalDate.of(2026, 10, 7) // Wednesday
        val specs = stepBucketSpecs(StepPeriod.WEEK, anchor, zone)
        assertEquals(7, specs.size)
        assertEquals(LocalDate.of(2026, 10, 5).atStartOfDay(zone).toInstant(), specs.first().start)
        assertEquals(LocalDate.of(2026, 10, 12).atStartOfDay(zone).toInstant(), specs.last().end)
    }

    @Test
    fun monthLengthsAreCorrectFor28_29_30_31() {
        assertEquals(28, stepBucketSpecs(StepPeriod.MONTH, LocalDate.of(2026, 2, 1), zone).size)
        assertEquals(29, stepBucketSpecs(StepPeriod.MONTH, LocalDate.of(2024, 2, 1), zone).size) // leap year
        assertEquals(30, stepBucketSpecs(StepPeriod.MONTH, LocalDate.of(2026, 4, 1), zone).size)
        assertEquals(31, stepBucketSpecs(StepPeriod.MONTH, LocalDate.of(2026, 1, 1), zone).size)
    }

    @Test
    fun yearHas12MonthlyBucketsTilingTheYear() {
        val specs = stepBucketSpecs(StepPeriod.YEAR, LocalDate.of(2026, 6, 15), zone)
        assertEquals(12, specs.size)
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant(), specs.first().start)
        assertEquals(LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant(), specs.last().end)
    }

    @Test
    fun boundariesUseLocalTimezoneNotUtc() {
        val specs = stepBucketSpecs(StepPeriod.MONTH, LocalDate.of(2026, 10, 1), zone)
        // 2026-10-01 00:00 IST == 2026-09-30 18:30 UTC
        assertEquals(Instant.parse("2026-09-30T18:30:00Z"), specs.first().start)
    }

    @Test
    fun seriesTotalsAndAverageComeFromTheSameDataset() {
        val anchor = LocalDate.of(2026, 10, 7)
        val specs = stepBucketSpecs(StepPeriod.WEEK, anchor, zone)
        val counts = listOf(1000L, 2000L, 3000L, 4000L, 5000L, 0L, 0L)
        val series = assembleStepSeries(StepPeriod.WEEK, anchor, 10_000L, zone, LocalDateTime.of(2026, 10, 7, 12, 0), specs, counts)
        assertEquals(15_000L, series.total)
        assertEquals(15_000.0 / 7, series.averagePerBucket, 0.001)
        assertEquals(5_000L, series.bestCount)
        assertTrue(series.hasData)
        assertEquals(7, series.points.size)
    }

    @Test
    fun emptyDataProducesNoDataSeries() {
        val anchor = LocalDate.of(2026, 10, 7)
        val specs = stepBucketSpecs(StepPeriod.DAY, anchor, zone)
        val series = assembleStepSeries(StepPeriod.DAY, anchor, 10_000L, zone, LocalDateTime.of(2026, 10, 7, 9, 0), specs, List(24) { 0L })
        assertFalse(series.hasData)
        assertEquals(0L, series.total)
        assertNull(series.bestLabel)
        assertEquals(0f, series.goalProgress, 0.0001f)
    }

    @Test
    fun overlappingOrMultipleSourceRecordsAreCountedOnce() {
        // Health Connect has already de-duplicated; simulate a day that received several
        // contributions for the same hour which must appear as one aggregated value.
        val anchor = LocalDate.of(2026, 10, 5)
        val specs = stepBucketSpecs(StepPeriod.DAY, anchor, zone)
        val counts = MutableList(24) { 0L }
        counts[9] = 1200L // merged total for 09:00 (e.g. watch + phone overlapping)
        val series = assembleStepSeries(StepPeriod.DAY, anchor, 10_000L, zone, LocalDateTime.of(2026, 10, 5, 23, 0), specs, counts)
        assertEquals(1200L, series.total)
        assertEquals("9a", series.bestLabel)
    }

    @Test
    fun highlightIndexFollowsToday() {
        val anchor = LocalDate.of(2026, 10, 7)
        val specs = stepBucketSpecs(StepPeriod.DAY, anchor, zone)
        val sameDay = assembleStepSeries(StepPeriod.DAY, anchor, 10_000L, zone, LocalDateTime.of(2026, 10, 7, 14, 0), specs, List(24) { 1L })
        assertEquals(14, sameDay.highlightIndex)
        val otherDay = assembleStepSeries(StepPeriod.DAY, anchor, 10_000L, zone, LocalDateTime.of(2026, 10, 8, 14, 0), specs, List(24) { 1L })
        assertEquals(-1, otherDay.highlightIndex)
    }

    @Test
    fun currentPeriodDetectionAndNavigation() {
        val today = LocalDate.of(2026, 10, 7)
        assertTrue(isCurrentPeriod(StepPeriod.DAY, today, today))
        assertTrue(isCurrentPeriod(StepPeriod.WEEK, LocalDate.of(2026, 10, 5), today))
        assertTrue(isCurrentPeriod(StepPeriod.MONTH, LocalDate.of(2026, 10, 31), today))
        assertFalse(isCurrentPeriod(StepPeriod.YEAR, LocalDate.of(2025, 1, 1), today))
        assertEquals(LocalDate.of(2026, 9, 7), shiftAnchor(StepPeriod.MONTH, today, -1))
        assertEquals(LocalDate.of(2027, 10, 7), shiftAnchor(StepPeriod.YEAR, today, 1))
        assertEquals(LocalDate.of(2026, 10, 8), shiftAnchor(StepPeriod.DAY, today, 1))
    }
}
