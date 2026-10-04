package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AccountSummary
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.commander.StressZoneMapping
import com.bishnu.stresswelfarebridge.ml.PersonalBaseline
import com.bishnu.stresswelfarebridge.ml.StressBaselineStore
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressModel
import com.bishnu.stresswelfarebridge.questionnaire.DailyContext
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord
import com.bishnu.stresswelfarebridge.questionnaire.Pss10
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The synthetic demo seeding, verified end to end against the real deployed model.
 *
 * All data here is synthetic. It does not represent real individuals.
 *
 * The property under test is that **the model produces the distribution**, not that the seeder asserts
 * it. [zoneDistributionIsThreeFourThree] runs the shipped wesad-loso-logreg-v1 weights over the seeded
 * physiology and then checks what came out. If a future change to the model moved a person into a
 * different band, this test would fail, which is correct: the dashboard would be showing something
 * different too.
 *
 * [theModelStillOwnsTheFiveReadingColdStart] guards the other important boundary: seeding must reach
 * a score through genuine qualifying readings, never by lowering MIN_SAMPLES or writing a score by
 * hand.
 */
class DemoSeedingTest {

    companion object {
        private lateinit var model: StressModel

        @BeforeClass
        @JvmStatic
        fun loadDeployedModel() {
            model = StressModel.fromJson(
                org.json.JSONObject(
                    DemoSeedingTest::class.java.classLoader!!
                        .getResource("stress_model_ml.json")!!.readText()
                )
            )
            StressModel.verifySchema(model)
        }
    }

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var directory: PersonnelDirectory
    private lateinit var measurements: StressMeasurementStore
    private lateinit var baselines: StressBaselineStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var seeder: DemoDataSeeder
    private var counter = 0

    private val now = Instant.parse("2026-03-01T09:00:00Z")
    private val zone = ZoneId.of("UTC")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acct-${counter++}" })
        directory = PersonnelDirectory(store, accounts)
        measurements = StressMeasurementStore(store)
        baselines = StressBaselineStore(store)
        repo = QuestionnaireRepository(store, accounts, { now }, { "q${counter++}" })
        seeder = DemoDataSeeder(accounts, directory, measurements, baselines, model, { now }, zone)
    }

    private fun sessionFor(index: Int): Session {
        val account = accounts.accountByUsername(DemoAccounts.personnelUsername(index))!!
        return Session("t-" + account.id, account.toPublicSummary())
    }

    private fun commanderSession(): Session {
        val account = accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!
        return Session("c-" + account.id, account.toPublicSummary())
    }

    private fun summaryFor(accountId: String, fullName: String, serviceId: String?): AccountSummary =
        AccountSummary(accountId, fullName, serviceId ?: accountId, Role.PERSONNEL, serviceId)

    private fun seeded() = seeder.seed(repo)

    // == A. account creation =====================================================================

    @Test
    fun seedingCreatesExactlyOneCommanderAndTenPersonnel() {
        seeded()
        val all = accounts.allAccounts()
        assertEquals(11, all.size)
        assertEquals(1, all.count { it.role == Role.COMMANDER })
        assertEquals(10, all.count { it.role == Role.PERSONNEL })
    }

    @Test
    fun everyPersonnelUsernameFollowsThePublishedPattern() {
        seeded()
        DemoAccounts.personnelIndices.forEach { i ->
            val account = accounts.accountByUsername(DemoAccounts.personnelUsername(i))
            assertNotNull("missing personnel account $i", account)
            assertEquals(Role.PERSONNEL, account!!.role)
            assertEquals(DemoAccounts.personnelDisplayName(i), account.fullName)
        }
        assertNotNull(accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME))
    }

    // == B. login and roles ======================================================================

    @Test
    fun commanderCredentialsLogInToTheCommanderRole() {
        seeded()
        val result = accounts.login(DemoAccounts.COMMANDER_USERNAME, DemoAccounts.COMMANDER_PASSWORD)
        assertTrue("commander login failed: $result", result is AuthResult.Success)
        assertTrue((result as AuthResult.Success).session.isCommander)
    }

    @Test
    fun everyPersonnelCredentialLogsInToThePersonnelRole() {
        seeded()
        DemoAccounts.personnelIndices.forEach { i ->
            val result = accounts.login(
                DemoAccounts.personnelUsername(i), DemoAccounts.PERSONNEL_PASSWORD
            )
            assertTrue("personnel $i login failed: $result", result is AuthResult.Success)
            assertFalse("personnel $i must not be a commander", (result as AuthResult.Success).session.isCommander)
        }
    }

    @Test
    fun aWrongPasswordIsStillRejectedForADemoAccount() {
        seeded()
        val result = accounts.login(DemoAccounts.COMMANDER_USERNAME, "WrongPassword123")
        assertFalse("demo accounts must not bypass the credential check", result is AuthResult.Success)
    }

    // == E. the distribution the model produced ==================================================

    @Test
    fun zoneDistributionIsThreeFourThree() {
        val result = seeded()
        assertEquals("Low", 3, result.low)
        assertEquals("Moderate", 4, result.moderate)
        assertEquals("High", 3, result.high)
    }

    @Test
    fun everyIntendedGroupIsMatchedByTheModel() {
        val result = seeded()
        assertEquals(
            "the deployed model disagreed with the seeded intent; adjust the synthetic inputs, " +
                "never override the model",
            emptyList<String>(),
            DemoDataSeeder.verifyZoneDistribution(result)
        )
    }

    @Test
    fun theIntendedMembershipIsTheOneSpecified() {
        assertEquals(
            listOf(1, 4, 7),
            DemoAccounts.personnelIndices.filter {
                DemoAccounts.intendedGroup(it) == DemoAccounts.IntendedGroup.LOW
            }
        )
        assertEquals(
            listOf(2, 5, 8, 10),
            DemoAccounts.personnelIndices.filter {
                DemoAccounts.intendedGroup(it) == DemoAccounts.IntendedGroup.MODERATE
            }
        )
        assertEquals(
            listOf(3, 6, 9),
            DemoAccounts.personnelIndices.filter {
                DemoAccounts.intendedGroup(it) == DemoAccounts.IntendedGroup.HIGH
            }
        )
    }
    // == F. grouping is derived from persisted data, never declared ==============================

    @Test
    fun theCommanderOverviewIsDerivedFromPersistedScores() {
        seeded()
        val overview = directory.commanderOverview(commanderSession())
        assertTrue("commander overview denied", overview.isAllowed)
        val value = overview.valueOrNull()!!
        assertEquals(10, value.totalPersonnel)
        assertEquals(3, value.count(StressZone.GREEN))
        assertEquals(4, value.count(StressZone.YELLOW))
        assertEquals(3, value.count(StressZone.RED))
        assertTrue("every demo person has a real score", value.unclassified.isEmpty())
    }

    @Test
    fun eachPublishedZoneFollowsThatPersonsLatestPersistedScore() {
        seeded()
        val overview = directory.commanderOverview(commanderSession()).valueOrNull()!!
        StressZone.ordered.forEach { z ->
            overview.zones[z].orEmpty().forEach { record ->
                val summary = summaryFor(record.accountId, record.fullName, record.serviceId)
                val latest = measurements.history(Session("x-" + record.accountId, summary))
                    .maxByOrNull { it.measuredAtMs }
                assertNotNull("a published person must have a persisted measurement", latest)
                assertEquals(
                    "the published score must be the latest persisted one",
                    latest!!.stressScore, record.stressScore
                )
                assertEquals(
                    "zone must follow the score via StressZoneMapping, never a name lookup",
                    StressZoneMapping.fromScore(record.stressScore!!), record.zone
                )
                assertEquals(z, record.zone)
            }
        }
    }

    @Test
    fun republishingAPersonWithANewScoreMovesThemToAnotherGroup() {
        // The proof that grouping is dynamic rather than declared. The app republishes a person''s
        // record whenever a new measurement lands; doing the same here must regroup them. Nothing in
        // this test refers to which band the person was originally seeded into.
        seeded()
        val before = directory.commanderOverview(commanderSession()).valueOrNull()!!
        val victim = before.zones[StressZone.GREEN]!!.first()
        val session = Session("v-" + victim.accountId, summaryFor(victim.accountId, victim.fullName, victim.serviceId))
        val republished = victim.copy(
            stressScore = 88,
            zone = StressZoneMapping.fromScore(88),
            stressLevel = "High",
            lastUpdatedMs = now.toEpochMilli() + 1000
        )
        assertTrue(directory.publishOwnRecord(session, republished).isAllowed)

        val after = directory.commanderOverview(commanderSession()).valueOrNull()!!
        assertEquals("one fewer in the green band", 2, after.count(StressZone.GREEN))
        assertEquals("one more in the red band", 4, after.count(StressZone.RED))
        assertEquals(10, after.totalPersonnel)
    }

    @Test
    fun personnelCannotUseTheCommanderOverview() {
        seeded()
        assertFalse(directory.commanderOverview(sessionFor(1)).isAllowed)
        assertFalse(directory.zoneMembers(sessionFor(1), StressZone.RED).isAllowed)
    }

    // == D. demo physiological data ==============================================================

    @Test
    fun everyPersonnelHasPersistedPhysiologicalMeasurements() {
        seeded()
        DemoAccounts.personnelIndices.forEach { i ->
            val history = measurements.history(sessionFor(i))
            assertTrue("personnel $i has no measurements", history.isNotEmpty())
            assertTrue("personnel $i should have a full history", history.size >= 12)
            history.forEach { r ->
                assertNotNull("a score is required for the graph", r.stressScore)
                assertEquals("score must be 0..100", true, r.stressScore!! in 0..100)
                assertNotNull(r.heartRateBpm)
                assertNotNull(r.rmssdMs)
                assertTrue("heart rate must be plausible", r.heartRateBpm!! in 30.0..220.0)
                assertTrue("RMSSD must be plausible", r.rmssdMs!! in 1.0..300.0)
                assertTrue("SDNN must be at least RMSSD", r.sdnnMs!! >= r.rmssdMs!!)
                assertTrue("timestamps must be in the past", r.measuredAtMs <= now.toEpochMilli())
                assertEquals("the deployed model must be recorded", model.modelVersion, r.modelVersion)
            }
        }
    }

    @Test
    fun seededTimestampsAreUniqueAndIncreasing() {
        seeded()
        DemoAccounts.personnelIndices.forEach { i ->
            val times = measurements.history(sessionFor(i)).map { it.measuredAtMs }.sorted()
            assertEquals("duplicate timestamps in one series", times.distinct().size, times.size)
        }
    }

    @Test
    fun noColdStartAbstentionIsEverWrittenAsZero() {
        seeded()
        DemoAccounts.personnelIndices.forEach { i ->
            measurements.history(sessionFor(i)).forEach { r ->
                assertTrue(
                    "an abstention must be absent, never 0/100",
                    r.stressScore == null || r.stressScore > 0
                )
            }
        }
    }

    @Test
    fun seedingIsIdempotentAndNeverDuplicatesRecords() {
        val first = seeded()
        assertEquals(10, first.personnelSeeded)
        val counts = DemoAccounts.personnelIndices.associateWith { measurements.history(sessionFor(it)).size }

        // Simulate several more launches.
        repeat(3) { seeded() }

        DemoAccounts.personnelIndices.forEach { i ->
            assertEquals(
                "personnel $i grew on a repeat seed",
                counts.getValue(i), measurements.history(sessionFor(i)).size
            )
        }
        assertEquals("a repeat seed must create no accounts", 11, accounts.allAccounts().size)
        assertEquals("a repeat seed must seed nobody again", 0, seeded().personnelSeeded)
    }

    @Test
    fun theModelStillOwnsTheFiveReadingColdStart() {
        assertEquals("MIN_SAMPLES must not be lowered", 5, PersonalBaseline.MIN_SAMPLES)
        // This test drives the estimator by hand, so seed first: it needs the demo accounts for the
        // final assertion that a seeded person really does hold a real persisted score.
        seeded()


        // A brand new account, not seeded, is at zero and can produce no score.
        val fresh = com.bishnu.stresswelfarebridge.auth.RegistrationRequest(
            "Fresh Person", "fresh@nowhere.demo", "Passw0rd!23", "Passw0rd!23",
            Role.PERSONNEL, "FRESH"
        )
        val freshSession = (accounts.register(fresh) as AuthResult.Success).session
        val freshBaseline = PersonalBaseline { now }
        val freshHistory = com.bishnu.stresswelfarebridge.ml.ObservationHistory()
        val est = com.bishnu.stresswelfarebridge.ml.CameraStressBridge.estimator(
            model, freshBaseline, freshHistory
        )
        val start = now.minusSeconds(11L * 24L * 3600L)
        var scoredAt = -1
        DemoPhysiology.seriesFor(1, start).forEachIndexed { i, obs ->
            val outcome = com.bishnu.stresswelfarebridge.ml.CameraStressBridge.evaluateObservation(
                est, obs, DemoPhysiology.signalQuality
            )
            if (outcome.score != null && scoredAt < 0) scoredAt = i
            if (i < 5) {
                assertNull("no score may exist before the fifth qualifying reading", outcome.score)
            }
        }
        assertEquals("the score must first appear on the fifth reading", 5, scoredAt)
        assertTrue("every qualifying reading should have been learned", freshBaseline.sampleCount("hr_bpm") >= 5)
        assertTrue(freshBaseline.isEstablished("hr_bpm"))
        assertNotNull("a seeded person must therefore have a real score", measurements.history(sessionFor(1)).first().stressScore)
        assertTrue(freshSession.account.username == "fresh@nowhere.demo")
    }
}
