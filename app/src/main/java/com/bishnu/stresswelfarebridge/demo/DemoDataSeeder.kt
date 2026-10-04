package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AccountSummary
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.PersonnelRecord
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.commander.StressZoneMapping
import com.bishnu.stresswelfarebridge.ml.CameraStressBridge
import com.bishnu.stresswelfarebridge.ml.FeatureSchema
import com.bishnu.stresswelfarebridge.ml.ObservationHistory
import com.bishnu.stresswelfarebridge.ml.PersonalBaseline
import com.bishnu.stresswelfarebridge.ml.StressBaselineStore
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressModel
import com.bishnu.stresswelfarebridge.questionnaire.Pss10
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Seeds the 11 synthetic demo accounts and their demonstration data.
 *
 * **All data written here is synthetic.** It does not represent real individuals and is never written
 * into Health Connect. No Google account, wearable or camera result is involved anywhere.
 *
 * ### The central rule: the model computes the score, not this class
 * This seeder never writes a stress score. For each person it builds synthetic physiological
 * observations, feeds them through the **existing deployed model** via [CameraStressBridge.estimator]
 * and [CameraStressBridge.evaluate] - the same path a real camera capture takes - and persists whatever
 * score that model returns as a [StressMeasurementRecord]. The physiological ML model, its weights and
 * its scoring are untouched.
 *
 * That is what makes the demonstration honest: the commander's zone distribution is an *output* of the
 * shipped pipeline, not a lookup table. If the model changed, the dashboard would change with it.
 *
 * ### Grouping is derived, never declared
 * The published zone comes from [StressZoneMapping] applied to the score the model produced for the
 * **latest** persisted measurement. There is no `if (personnel == "Personnel 03") HIGH` anywhere.
 * [DemoAccounts.intendedGroup] only shapes the synthetic *input* physiology and is never consulted
 * when displaying a zone. [verifyZoneDistribution] reports a mismatch so the inputs can be adjusted -
 * never so the model can be overridden.
 *
 * ### Idempotent
 * [seed] is safe on every launch: accounts are found by username before being created, and a person
 * whose measurement history already exists is left untouched, so re-running cannot duplicate records.
 *
 * ### Isolation
 * Every write goes through the existing repositories keyed by the session's account id, exactly as a
 * real user would produce it. The existing authorisation checks - not this class - are what keep one
 * person out of another's data.
 */
class DemoDataSeeder(
    private val accounts: AccountStore,
    private val directory: PersonnelDirectory,
    private val measurements: StressMeasurementStore,
    private val baselines: StressBaselineStore,
    private val model: StressModel?,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /** Days of Daily Check-in history seeded per person. */
    val contextDays: Int = DemoPhysiology.DAYS

    /** Months of PSS-10 history seeded per person. */
    val pssMonths: Int = 4

    /** Outcome of a [seed] run, so a host can log what happened rather than guess. */
    data class Result(
        val personnelCreated: Int = 0,
        val personnelSeeded: Int = 0,
        val personnelSkipped: Int = 0,
        /** Zone actually produced by the model for each personnel index, 1-based. */
        val zones: Map<Int, StressZone?> = emptyMap()
    ) {
        val low: Int get() = zones.values.count { it == StressZone.GREEN }
        val moderate: Int get() = zones.values.count { it == StressZone.YELLOW }
        val high: Int get() = zones.values.count { it == StressZone.RED }
    }

    /**
     * Ensures the demo accounts exist and their demonstration data is present.
     *
     * Safe and idempotent: call it on every launch. Accounts already present are reused by username,
     * and a person whose measurement history already exists is left untouched.
     *
     * @param questionnaires optional repository used to seed Daily Check-in and PSS-10 context. When
     *   null only the physiological data is written.
     */
    fun seed(questionnaires: QuestionnaireRepository? = null): Result {
        ensureAccount(
            DemoAccounts.COMMANDER_USERNAME, DemoAccounts.COMMANDER_PASSWORD,
            "Commander", Role.COMMANDER, null
        )

        var created = 0
        var seeded = 0
        var skipped = 0
        val zones = LinkedHashMap<Int, StressZone?>()

        DemoAccounts.personnelIndices.forEach { index ->
            val username = DemoAccounts.personnelUsername(index)
            val alreadyExisted = accounts.accountByUsername(username) != null
            val account = ensureAccount(
                username, DemoAccounts.PERSONNEL_PASSWORD,
                DemoAccounts.personnelDisplayName(index), Role.PERSONNEL,
                DemoAccounts.personnelServiceId(index)
            )
            if (account == null) {
                skipped++
                return@forEach
            }
            if (!alreadyExisted) created++

            val session = sessionFor(account.toPublicSummary())
            val existing = measurements.history(session)
            if (existing.isNotEmpty()) {
                // Already seeded: adopt what the model produced previously rather than re-running it.
                skipped++
                zones[index] = zoneFor(existing.firstOrNull()?.stressScore)
                return@forEach
            }

            val latest = seedPhysiology(session, index)
            if (latest != null) {
                seeded++
                zones[index] = zoneFor(latest.stressScore)
                questionnaires?.let {
                    seedDailyCheckIns(it, session, index)
                    seedPss10(it, session, index)
                }
            } else {
                skipped++
            }
        }
        return Result(created, seeded, skipped, zones)
    }

    /**
     * Runs one person's synthetic observations through the existing model and persists the results.
     *
     * Returns the latest record the model produced, which becomes the published commander record. Null
     * only when the model asset is unavailable, in which case nothing is written rather than a
     * placeholder score.
     */
    private fun seedPhysiology(session: Session, index: Int): StressMeasurementRecord? {
        val deployed = model ?: return null

        // A fresh baseline and history, written through the same persistence path the app uses, so the
        // cold-start requirement is genuinely satisfied by qualifying readings rather than bypassed.
        // MIN_SAMPLES is untouched.
        val baseline = PersonalBaseline { clock() }
        val history = ObservationHistory()
        val estimator = CameraStressBridge.estimator(deployed, baseline, history) {
            baselines.save(session, baseline, history)
        }

        val end = clock()
        val start = end.minusSeconds(DemoPhysiology.DAYS.toLong() * 24L * 3600L + 12L * 3600L)
        var latest: StressMeasurementRecord? = null

        for (observation in DemoPhysiology.seriesFor(index, start)) {
            val outcome = CameraStressBridge.evaluateObservation(
                estimator, observation, DemoPhysiology.signalQuality
            )
            // A cold-start reading has no score. It is deliberately NOT written as 0/100: the graph and
            // the commander view both ignore an absent score, which is the honest representation.
            val score = outcome.score ?: continue
            val record = StressMeasurementRecord(
                id = "demo-${index.toString().padStart(2, '0')}-${observation.measuredAt.toEpochMilli()}",
                userId = session.accountId,
                measuredAtMs = observation.measuredAt.toEpochMilli(),
                source = observation.source,
                heartRateBpm = observation.hrBpm,
                rmssdMs = observation.rmssdMs,
                sdnnMs = observation.sdnnMs,
                signalQuality = DemoPhysiology.signalQuality.name,
                stressScore = score,
                stressZone = outcome.zone.name,
                confidence = outcome.confidence,
                modelVersion = deployed.modelVersion,
                featureSchemaVersion = FeatureSchema.FEATURE_SCHEMA_VERSION
            )
            measurements.save(session, record)
            latest = record
        }
        baselines.save(session, baseline, history)
        latest?.let { publishCommanderRecord(session, it) }
        return latest
    }

    /**
     * Publishes the person into the commander's view.
     *
     * The zone is derived here from the persisted score using the existing [StressZoneMapping], which
     * reuses the engine's own cut-points. Nothing in this method knows the person's intended group.
     */
    private fun publishCommanderRecord(session: Session, record: StressMeasurementRecord) {
        val summary = session.account
        val published = PersonnelRecord(
            accountId = session.accountId,
            fullName = summary.fullName,
            serviceId = summary.serviceId,
            stressScore = record.stressScore,
            stressLevel = levelText(record.stressScore),
            zone = zoneFor(record.stressScore),
            confidence = record.confidence,
            usingRealData = true,
            dataQuality = "Synthetic demonstration data scored by the deployed " +
                record.modelVersion + " model.",
            baselineDays = PersonalBaseline.MIN_SAMPLES,
            lastUpdatedMs = record.measuredAtMs,
            steps = 0,
            exerciseMinutes = 0,
            sleepMinutes = 0,
            avgHr = record.heartRateBpm,
            avgHrv = record.rmssdMs,
            restingHr = null,
            respiratoryRate = null,
            spo2 = null,
            cameraHrv = null
        )
        directory.publishOwnRecord(session, published)
    }

    /** Zone from a score using the existing thresholds. Null for an absent score, never "low". */
    private fun zoneFor(score: Int?): StressZone? = score?.let { StressZoneMapping.fromScore(it) }

    private fun levelText(score: Int?): String = when (score) {
        null -> "Awaiting data"
        else -> when (StressZoneMapping.fromScore(score)) {
            StressZone.GREEN -> "Low"
            StressZone.YELLOW -> "Moderate"
            else -> "High"
        }
    }

    // -- context and questionnaire data ----------------------------------------------------------
    //
    // Everything below is seeded through the ordinary repositories, under the person's own session,
    // so it is stored, read back and privacy-checked exactly like data a real user would produce.
    //
    // Daily Check-in answers are **context only**. They are stored as three 1-5 answers and are never
    // reduced to a number, never fed to the physiological model, and never mixed with a stress score.

    /**
     * Seeds the last [days] days of Daily Check-in records for one person.
     *
     * The answers track the person's intended group so the three constructs stay tellable apart: a Low
     * person rests well and reports light duty, a High person does the opposite. They remain three
     * plain integers in storage either way.
     */
    fun seedDailyCheckIns(repository: QuestionnaireRepository, session: Session, index: Int, days: Int = DemoPhysiology.DAYS) {
        val today = LocalDate.ofInstant(clock(), zone)
        for (back in 1..days) {
            val date = today.minusDays(back.toLong())
            // saveDailyPulse upserts per date, so re-running is harmless; skipping avoids a needless
            // rewrite on a second launch.
            if (repository.dailyPulseFor(session, date) != null) continue
            val answers = dailyAnswersFor(index, back)
            repository.saveDailyPulse(session, date, answers.first, answers.second, answers.third, false)
        }
    }

    /**
     * The three Daily Check-in answers for one person, [daysBack] days before today.
     *
     * Order matches `DailyPulse.items`: stressed, rested, duty load. All inside 1-5.
     */
    fun dailyAnswersFor(index: Int, daysBack: Int): Triple<Int, Int, Int> {
        val group = DemoAccounts.intendedGroup(index)
        val drift = daysBack % 3
        return when (group) {
            DemoAccounts.IntendedGroup.LOW -> Triple(1 + drift % 2, 5 - drift % 2, 1 + drift % 2)
            DemoAccounts.IntendedGroup.MODERATE -> Triple(3, 3 + drift % 2, 3)
            DemoAccounts.IntendedGroup.HIGH -> Triple(5 - drift % 2, 1 + drift % 2, 5)
        }
    }

    /**
     * Seeds the last [months] completed months of synthetic PSS-10 results.
     *
     * These are totals produced by the **existing** [Pss10.score] from a fixed synthetic answer
     * pattern. No question wording is invented or altered: the scoring implementation is untouched and
     * the answer map is the ordinary 1..10 to 0..4 shape the repository already accepts. Totals stay
     * on the existing 0-40 scale.
     */
    fun seedPss10(repository: QuestionnaireRepository, session: Session, index: Int, months: Int = 4) {
        val current = YearMonth.from(LocalDate.ofInstant(clock(), zone))
        for (back in 1..months) {
            val month = current.minusMonths(back.toLong())
            // Available means not yet completed, so the synthetic result is genuinely new.
            if (repository.pss10Available(session, month)) {
                repository.savePss10(session, month, pssAnswersFor(index, back))
            }
        }
    }

    /**
     * Synthetic PSS-10 answer pattern for one person and month offset, on the real 0-4 scale.
     *
     * A plain 1..10 to 0..4 map, exactly what the repository persists and what [Pss10.score] consumes,
     * so the stored total is computed by the shipped scorer rather than asserted here.
     */
    fun pssAnswersFor(index: Int, monthsBack: Int): Map<Int, Int> {
        val base = when (DemoAccounts.intendedGroup(index)) {
            DemoAccounts.IntendedGroup.LOW -> 1
            DemoAccounts.IntendedGroup.MODERATE -> 2
            DemoAccounts.IntendedGroup.HIGH -> 3
        }
        val shift = (index + monthsBack) % 2
        return (1..Pss10.ITEM_COUNT).associate { item ->
            // Reverse items are positively worded, so a lower raw value means more stress there.
            val raw = if (item in Pss10.REVERSE_ITEMS) 4 - base - shift else base + shift
            item to raw.coerceIn(Pss10.SCALE_MIN, Pss10.SCALE_MAX)
        }
    }

    /**
     * Returns the demo account, creating it only if it does not already exist.
     *
     * Lookup is by username, so the account id is stable across launches and a second [seed] reuses the
     * original rather than creating a duplicate. Creation goes through the ordinary
     * [AccountStore.register], so demo identities are hashed and authorised exactly like real ones.
     */
    private fun ensureAccount(
        username: String,
        password: String,
        displayName: String,
        role: Role,
        serviceId: String?
    ): com.bishnu.stresswelfarebridge.auth.Account? {
        accounts.accountByUsername(username)?.let { return it }
        val result = accounts.register(
            RegistrationRequest(
                fullName = displayName,
                username = username,
                password = password,
                confirmPassword = password,
                role = role,
                serviceId = serviceId
            )
        )
        val id = (result as? AuthResult.Success)?.session?.accountId ?: return null
        return accounts.accountById(id)
    }

    /**
     * A session for a demo account, used only to authorise the seeder's own writes.
     *
     * The token is a fixed, obviously synthetic string. This deliberately does not call
     * [AccountStore.login], which would overwrite whoever is currently signed in.
     */
    private fun sessionFor(summary: AccountSummary): Session = Session("demo-seeder-" + summary.id, summary)

    companion object {
        /**
         * Checks the distribution the model actually produced against the intended 3 / 4 / 3.
         *
         * A **verification aid for tests and development** only. It reports a mismatch so the synthetic
         * input physiology can be adjusted and the model re-run. It is never consulted when a zone is
         * displayed - that always comes from the persisted score via [StressZoneMapping].
         */
        fun verifyZoneDistribution(result: Result): List<String> {
            val problems = mutableListOf<String>()
            if (result.low != 3) problems += "expected 3 Low, model produced " + result.low
            if (result.moderate != 4) problems += "expected 4 Moderate, model produced " + result.moderate
            if (result.high != 3) problems += "expected 3 High, model produced " + result.high
            DemoAccounts.personnelIndices.forEach { index ->
                val expected = when (DemoAccounts.intendedGroup(index)) {
                    DemoAccounts.IntendedGroup.LOW -> StressZone.GREEN
                    DemoAccounts.IntendedGroup.MODERATE -> StressZone.YELLOW
                    DemoAccounts.IntendedGroup.HIGH -> StressZone.RED
                }
                val actual = result.zones[index]
                if (actual != expected) {
                    problems += "Personnel %02d intended ".format(index) +
                        DemoAccounts.intendedGroup(index) + " but the model produced " +
                        (actual?.title ?: "no score")
                }
            }
            return problems
        }
    }
}