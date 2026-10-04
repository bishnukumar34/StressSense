package com.bishnu.stresswelfarebridge.commander

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult

/**
 * Turns the live app state into the record that is stored for a personnel account.
 *
 * Everything here is a pass-through of values that were actually measured:
 *  - the stress score/level come from [StressEngine.infer] — the existing engine, unchanged;
 *  - the zone comes from [StressZoneMapping], which reuses that engine's own cut-points;
 *  - Health Connect values (HR, HRV, resting HR, steps, sleep, exercise, SpO2, respiratory rate)
 *    are copied from [DailyHealth] exactly as read, with null kept as null;
 *  - the camera measurement is captured in its own field, never merged into the wearable values.
 */
object PersonnelRecordFactory {

    fun from(
        session: Session,
        today: DailyHealth,
        result: StressEngine.Result,
        cameraHrv: CameraHrvResult?,
        nowMs: Long
    ): PersonnelRecord = PersonnelRecord(
        accountId = session.accountId,
        fullName = session.account.fullName,
        serviceId = session.account.serviceId,
        stressScore = result.score,
        stressLevel = result.level,
        zone = StressZoneMapping.fromResult(result),
        confidence = result.confidence,
        usingRealData = result.usingRealData,
        dataQuality = result.dataQuality,
        baselineDays = result.baselineDays,
        lastUpdatedMs = nowMs,
        steps = today.steps,
        exerciseMinutes = today.exerciseMinutes,
        sleepMinutes = today.sleepMinutes,
        avgHr = today.avgHr,
        avgHrv = today.avgHrv,
        restingHr = today.restingHr,
        respiratoryRate = today.respiratoryRate,
        spo2 = today.spo2,
        cameraHrv = cameraHrv?.let {
            CameraHrvSummary(
                rmssdMs = it.rmssdMs,
                sdnnMs = it.sdnnMs,
                heartRateBpm = it.heartRateBpm,
                signalQuality = it.signalQuality.name,
                confidence = it.confidence,
                validBeatCount = it.validIbiCount,
                measuredAtMs = it.measurementEndMs
            )
        }
    )
}
