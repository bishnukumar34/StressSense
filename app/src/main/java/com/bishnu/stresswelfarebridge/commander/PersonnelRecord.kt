package com.bishnu.stresswelfarebridge.commander

/**
 * A personnel member's stored monitoring record, owned by exactly one account.
 *
 * Every value here originates from a real measurement — Health Connect records read by
 * [com.bishnu.stresswelfarebridge.HealthRepository] and scored by
 * [com.bishnu.stresswelfarebridge.StressEngine] — or it is null. No field is ever defaulted to a
 * healthy-looking number.
 */
data class PersonnelRecord(
    val accountId: String,
    val fullName: String,
    val serviceId: String?,
    /** Stress score from the engine, or null when the engine has not produced one yet. */
    val stressScore: Int?,
    /** The engine's own level text ("Low", "Moderate", "High", "Awaiting data"). */
    val stressLevel: String,
    /** Monitoring band, or null when the person is not yet classifiable. */
    val zone: StressZone?,
    val confidence: Int,
    val usingRealData: Boolean,
    val dataQuality: String,
    val baselineDays: Int,
    val lastUpdatedMs: Long,
    val steps: Long,
    val exerciseMinutes: Long,
    val sleepMinutes: Long,
    val avgHr: Double?,
    val avgHrv: Double?,
    val restingHr: Double?,
    val respiratoryRate: Double?,
    val spo2: Double?,
    /**
     * Camera PPG measurement, kept in its own field and labelled with its own source. It is never
     * merged into the Health Connect HR/HRV values above.
     */
    val cameraHrv: CameraHrvSummary? = null
) {
    val hasClassifiableStress: Boolean get() = zone != null

    /** Identifier shown in lists: the service id when the account has one, else the username. */
    fun identifierFor(username: String): String = serviceId?.takeIf { it.isNotBlank() } ?: username
}

/**
 * The camera-derived HRV measurement attached to a record.
 *
 * [source] is always the camera-PPG source so the commander view can never present it as a wearable
 * Health Connect reading.
 */
data class CameraHrvSummary(
    val rmssdMs: Double,
    val sdnnMs: Double,
    val heartRateBpm: Double,
    val signalQuality: String,
    val confidence: Double,
    val validBeatCount: Int,
    val measuredAtMs: Long,
    val source: String = com.bishnu.stresswelfarebridge.ppg.CameraHrvResult.SOURCE_CAMERA_PPG
)

/** Result of an access-controlled read or write. Denial is a first-class outcome, not an exception. */
sealed class Access<out T> {
    data class Allowed<T>(val value: T) : Access<T>()
    data class Denied(val reason: String) : Access<Nothing>()

    fun valueOrNull(): T? = (this as? Allowed)?.value
    fun deniedReason(): String? = (this as? Denied)?.reason
    val isAllowed: Boolean get() = this is Allowed
}

/** The commander's aggregated view, built entirely from records that actually exist. */
data class CommanderOverview(
    val zones: Map<StressZone, List<PersonnelRecord>>,
    /** Personnel who exist but have no classifiable stress result yet. Never shown as healthy. */
    val unclassified: List<PersonnelRecord>,
    val lastUpdatedMs: Long?
) {
    fun count(zone: StressZone): Int = zones[zone].orEmpty().size
    val totalPersonnel: Int get() = zones.values.sumOf { it.size } + unclassified.size
}
