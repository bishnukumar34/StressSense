package com.bishnu.stresswelfarebridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import android.util.Log
import kotlin.reflect.KClass
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Single source of truth for Health Connect reads. Cumulative activity is read with AggregateRequest
 * so Health Connect can de-duplicate overlapping sources according to the user's priorities.
 *
 * Only record types whose read permission has actually been granted are queried. Reading a record
 * type without its permission throws a SecurityException, which would otherwise discard every
 * metric (including HR/HRV) the moment a single optional permission is denied. */
class HealthRepository(
    private val client: HealthConnectClient,
    private val grantedPermissions: Set<String>
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun canRead(recordType: KClass<out Record>): Boolean =
        HealthPermission.getReadPermission(recordType) in grantedPermissions

    suspend fun readSevenDays(): List<DailyHealth> {
        val today = LocalDate.now(zone)
        val days = (6L downTo 0L).map { today.minusDays(it) }
        val byDay = days.associateWith { DailyHealth(date = it) }.toMutableMap()
        val start = days.first().atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()

        readAll<HeartRateRecord>(HeartRateRecord::class, start, end).forEach { record ->
            record.samples.forEach { s -> byDay[s.time.atZone(zone).toLocalDate()]?.heartRates?.add(s.beatsPerMinute.toDouble()) }
        }
        readAll<HeartRateVariabilityRmssdRecord>(HeartRateVariabilityRmssdRecord::class, start, end).forEach { r ->
            byDay[r.time.atZone(zone).toLocalDate()]?.hrvValues?.add(r.heartRateVariabilityMillis)
        }
        readAll<RestingHeartRateRecord>(RestingHeartRateRecord::class, start, end).forEach { r ->
            byDay[r.time.atZone(zone).toLocalDate()]?.restingHeartRates?.add(r.beatsPerMinute.toDouble())
        }
        readAll<RespiratoryRateRecord>(RespiratoryRateRecord::class, start, end).forEach { r ->
            byDay[r.time.atZone(zone).toLocalDate()]?.respiratoryRates?.add(r.rate)
        }
        readAll<OxygenSaturationRecord>(OxygenSaturationRecord::class, start, end).forEach { r ->
            byDay[r.time.atZone(zone).toLocalDate()]?.oxygenValues?.add(r.percentage.value)
        }
        readAll<SleepSessionRecord>(SleepSessionRecord::class, start.minus(Duration.ofDays(1)), end).forEach { r ->
            // Assign sleep to the date on which the sleep session ends, matching common fitness-app summaries.
            val day = r.endTime.atZone(zone).toLocalDate()
            val minutes = Duration.between(r.startTime, r.endTime).toMinutes()
            if (minutes > 0) byDay[day]?.sleepMinutes = (byDay[day]?.sleepMinutes ?: 0L) + minutes
        }
        readAll<ExerciseSessionRecord>(ExerciseSessionRecord::class, start, end).forEach { r ->
            val day = r.endTime.atZone(zone).toLocalDate()
            val minutes = Duration.between(r.startTime, r.endTime).toMinutes()
            if (minutes > 0) byDay[day]?.exerciseMinutes = (byDay[day]?.exerciseMinutes ?: 0L) + minutes
        }

        for (day in days) {
            val ds = day.atStartOfDay(zone).toInstant()
            val de = day.plusDays(1).atStartOfDay(zone).toInstant()
            val range = TimeRangeFilter.between(ds, de)
            // Only request aggregate metrics we are actually allowed to read, otherwise the whole
            // aggregate call fails and no activity data is returned at all.
            val metrics = buildSet {
                if (canRead(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
                if (canRead(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
                if (canRead(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                if (canRead(FloorsClimbedRecord::class)) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            }
            if (metrics.isNotEmpty()) {
                val response = client.aggregate(
                    AggregateRequest(metrics = metrics, timeRangeFilter = range)
                )
                byDay[day]?.apply {
                    steps = response[StepsRecord.COUNT_TOTAL] ?: steps
                    distanceMeters = response[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: distanceMeters
                    calories = response[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: calories
                    floors = response[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.toDouble() ?: floors
                }
            }
        }

        // Latest body measurements in the same recent window.
        val heights = readAll<HeightRecord>(HeightRecord::class, start, end)
        val weights = readAll<WeightRecord>(WeightRecord::class, start, end)
        val latestHeight = heights.maxByOrNull { it.time }
        val latestWeight = weights.maxByOrNull { it.time }
        val heightCm = latestHeight?.height?.inMeters?.times(100.0)
        val weightKg = latestWeight?.weight?.inKilograms
        return days.map { day -> byDay.getValue(day).finalize(heightCm, weightKg) }
    }

    /**
     * Reads real step totals for a full DAY/WEEK/MONTH/YEAR selection.
     *
     * One Health Connect [AggregateRequest] is issued per calendar bucket (hour/day/month). Using
     * aggregation (rather than summing raw records ourselves) lets Health Connect de-duplicate
     * overlapping records and multiple data sources, so nothing is double counted. Buckets with no
     * records simply resolve to 0 — no placeholder data is ever invented.
     */
    suspend fun readStepSeries(period: StepPeriod, anchor: LocalDate, goal: Long): StepSeries {
        val specs = stepBucketSpecs(period, anchor, zone)
        val counts = specs.map { aggregateSteps(it.start, it.end) }
        val series = assembleStepSeries(period, anchor, goal, zone, LocalDateTime.now(zone), specs, counts)
        Log.d(
            "StressSenseSteps",
            "period=$period anchor=$anchor range=${series.rangeStart}..${series.rangeEnd} " +
                "buckets=${series.points.size} total=${series.total} best=${series.bestLabel}:${series.bestCount}"
        )
        return series
    }

    /** De-duplicated step total for an exact [start, end) instant range, or 0 when not permitted. */
    private suspend fun aggregateSteps(start: Instant, end: Instant): Long {
        if (!canRead(StepsRecord::class)) return 0L
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return response[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    private suspend inline fun <reified T : Record> readAll(
        recordType: kotlin.reflect.KClass<T>, start: Instant, end: Instant
    ): List<T> {
        if (!canRead(recordType)) return emptyList()
        val result = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token
                )
            )
            result += response.records
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        return result
    }
}

data class DailyHealth(
    val date: LocalDate,
    val heartRates: MutableList<Double> = mutableListOf(),
    val hrvValues: MutableList<Double> = mutableListOf(),
    val restingHeartRates: MutableList<Double> = mutableListOf(),
    val respiratoryRates: MutableList<Double> = mutableListOf(),
    val oxygenValues: MutableList<Double> = mutableListOf(),
    var sleepMinutes: Long = 0L,
    var exerciseMinutes: Long = 0L,
    var steps: Long = 0L,
    var distanceMeters: Double = 0.0,
    var calories: Double = 0.0,
    var floors: Double = 0.0,
    var heightCm: Double? = null,
    var weightKg: Double? = null,
    var avgHr: Double? = null,
    var avgHrv: Double? = null,
    var restingHr: Double? = null,
    var respiratoryRate: Double? = null,
    var spo2: Double? = null
) {
    fun finalize(latestHeightCm: Double?, latestWeightKg: Double?): DailyHealth {
        avgHr = heartRates.averageOrNull()
        avgHrv = hrvValues.averageOrNull()
        restingHr = restingHeartRates.averageOrNull()
        respiratoryRate = respiratoryRates.averageOrNull()
        spo2 = oxygenValues.averageOrNull()
        heightCm = latestHeightCm
        weightKg = latestWeightKg
        return this
    }
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
