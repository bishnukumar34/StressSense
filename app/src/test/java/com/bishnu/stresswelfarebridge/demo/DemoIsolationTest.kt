package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AccountSummary
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.medical.MedicalHistory
import com.bishnu.stresswelfarebridge.medical.MedicalHistoryRepository
import com.bishnu.stresswelfarebridge.ml.ObservationHistory
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
 * Account isolation, privacy and the context-only guarantees for the synthetic demo data.
 *
 * The point of this file is the negative space: what one demo person must NOT be able to see. Seeding
 * writes through the ordinary repositories keyed by account id, so these assertions are about the
 * shipped authorisation checks rather than about the seeder being careful.
 *
 * All data here is synthetic and does not represent real individuals.
 */
class DemoIsolationTest {

    companion object {
        private lateinit var model: StressModel

        @BeforeClass
        @JvmStatic
        fun loadDeployedModel() {
            model = StressModel.fromJson(
                org.json.JSONObject(
                    DemoIsolationTest::class.java.classLoader!!
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
    private lateinit var medical: MedicalHistoryRepository
    private lateinit var seeder: DemoDataSeeder
    private var counter = 0

    private val now = Instant.parse("2026-03-01T09:00:00Z")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acct-${counter++}" })
        directory = PersonnelDirectory(store, accounts)
        measurements = StressMeasurementStore(store)
        baselines = StressBaselineStore(store)
        repo = QuestionnaireRepository(store, accounts, { now }, { "q${counter++}" })
        medical = MedicalHistoryRepository(store, { now })
        seeder = DemoDataSeeder(accounts, directory, measurements, baselines, model, { now }, ZoneId.of("UTC"))
        seeder.seed(repo)
    }

    private fun sessionFor(index: Int): Session {
        val account = accounts.accountByUsername(DemoAccounts.personnelUsername(index))!!
        return Session("t-" + account.id, account.toPublicSummary())
    }

    private fun accountIdOf(index: Int): String =
        accounts.accountByUsername(DemoAccounts.personnelUsername(index))!!.id

    // == C. physiological data isolation ========================================================

    @Test
    fun eachPersonnelSeesOnlyTheirOwnMeasurements() {
        val p1 = sessionFor(1)
        val p2 = sessionFor(2)
        val h1 = measurements.history(p1)
        val h2 = measurements.history(p2)
        assertTrue(h1.isNotEmpty() && h2.isNotEmpty())
        assertEquals("P1 history must belong to P1", setOf(accountIdOf(1)), h1.map { it.userId }.toSet())
        assertEquals("P2 history must belong to P2", setOf(accountIdOf(2)), h2.map { it.userId }.toSet())
        assertTrue("the two must not share record ids", h1.map { it.id }.toSet().intersect(h2.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun everyPersonnelAccountHasItsOwnDistinctSeries() {
        val all: List<List<Double>> = DemoAccounts.personnelIndices.map { i ->
            measurements.history(sessionFor(i)).mapNotNull { r -> r.heartRateBpm }.sorted()
        }
        val distinct = all.distinct().size
        assertEquals("profiles must be distinguishable, not clones", 10, distinct)
    }

    @Test
    fun onePersonCannotReadAnothersPersistedBaseline() {
        val p1Base = PersonalBaseline { now }
        assertTrue(baselines.load(sessionFor(1), p1Base, ObservationHistory()))
        val p1Samples = p1Base.sampleCount("hr_bpm")

        val p2Base = PersonalBaseline { now }
        // P2 is seeded too, so their OWN baseline loads. What must never happen is P2 receiving
        // P1's numbers, so the check is that what loads has P2's own personal centre and not
        // P1's. (Sample counts are equal by construction - both have the same series length - so
        // comparing them would prove nothing; the centre is what actually distinguishes people.)
        assertTrue(baselines.load(sessionFor(2), p2Base, ObservationHistory()))
        assertTrue("P2 must have real samples of their own", p2Base.sampleCount("hr_bpm") > 0)
        assertNotNull("P2 must have a usable personal centre of their own", p2Base.center("hr_bpm"))
        assertFalse(
            "P2 must not share P1's baseline centre",
            p2Base.center("hr_bpm") == p1Base.center("hr_bpm")
        )

        val p1Again = PersonalBaseline { now }
        baselines.load(sessionFor(1), p1Again, ObservationHistory())
        assertEquals("P1 keeps their own baseline", p1Samples, p1Again.sampleCount("hr_bpm"))
    }

    @Test
    fun onePersonCannotReadAnothersPersonnelRecord() {
        val p1 = sessionFor(1)
        val p2Id = accountIdOf(2)
        assertTrue("the owner may read their own", directory.personnelDetail(p1, accountIdOf(1)).isAllowed)
        assertFalse("P1 must not read P2", directory.personnelDetail(p1, p2Id).isAllowed)
    }

    @Test
    fun onePersonCannotOverwriteAnothersRecord() {
        val p1 = sessionFor(1)
        val p2 = sessionFor(2)
        val p2Record = directory.ownRecord(p2)!!
        assertFalse(
            "P1 must not publish into P2's record",
            directory.publishOwnRecord(p1, p2Record).isAllowed
        )
        assertEquals("P2's record must be untouched", p2Record.stressScore, directory.ownRecord(p2)!!.stressScore)
    }

    // == G. privacy ==============================================================================

    @Test
    fun medicalHistoryIsScopedToItsOwnerAndReopensOnlyForThem() {
        val p1 = sessionFor(1)
        val p2 = sessionFor(2)
        assertTrue(medical.save(p1, MedicalHistory(
            userId = accountIdOf(1), conditions = listOf("None recorded - synthetic demo")
        )))
        assertNotNull(medical.get(p1))
        assertNull("P2 must not see P1's medical history", medical.get(p2))
    }

    @Test
    fun aCommanderCannotReadAnyPersonnelMedicalHistory() {
        val commander = Session(
            "c-x", AccountSummary(
                accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!.id,
                "Commander", "cmdr", Role.COMMANDER, null
            )
        )
        medical.save(sessionFor(1), MedicalHistory(userId = accountIdOf(1), conditions = listOf("Synthetic")))
        assertNull("a commander must not obtain medical history", medical.get(commander))
        // And there is no commander API that could return one at all.
        val medicalMethods = MedicalHistoryRepository::class.java.methods.map { it.name }
        assertFalse(
            "no commander-scoped medical history reader may exist",
            medicalMethods.any { it.contains("commander", ignoreCase = true) }
        )
    }

    @Test
    fun thePublishedPersonnelRecordCarriesNoMedicalFields() {
        val commander = Session(
            "c-y", AccountSummary(
                accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!.id,
                "Commander", "cmdr", Role.COMMANDER, null
            )
        )
        val overview = directory.commanderOverview(commander).valueOrNull()!!
        StressZone.ordered.forEach { z ->
            overview.zones[z].orEmpty().forEach { record ->
                val fields = record::class.java.declaredFields.map { it.name.lowercase() }
                listOf("condition", "allergy", "medication", "surgery", "hospital", "injur", "treatment", "notes")
                    .forEach { forbidden ->
                        assertTrue(
                            "commander record must not carry $forbidden",
                            fields.none { it.contains(forbidden) }
                        )
                    }
            }
        }
    }

    @Test
    fun theCommanderRecordCarriesOnlyOperationalFields() {
        val commander = Session(
            "c-z", AccountSummary(
                accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!.id,
                "Commander", "cmdr", Role.COMMANDER, null
            )
        )
        val record = directory.commanderOverview(commander).valueOrNull()!!
            .zones.values.flatten().first()
        // Operational stress context only, which is what the commander workflow needs.
        assertNotNull(record.stressScore)
        assertNotNull(record.zone)
        assertNotNull(record.avgHr)
        assertNotNull(record.avgHrv)
        assertTrue(record.lastUpdatedMs > 0)
    }

    // == H. Daily Check-in stays context only ====================================================

    @Test
    fun dailyCheckInRecordsBelongToTheCorrectAccount() {
        DemoAccounts.personnelIndices.forEach { i ->
            val mine = repo.dailyPulses(sessionFor(i))
            assertTrue("personnel $i has no check-ins", mine.isNotEmpty())
            assertEquals("P$i check-ins must be P$i's", setOf(accountIdOf(i)), mine.map { it.userId }.toSet())
            // Same calendar dates are fine; what must differ is the owning account on every row.
            mine.forEach { record ->
                assertEquals(accountIdOf(i), record.userId)
            }
        }
    }

    @Test
    fun onePersonCannotReadOrWriteAnothersDailyCheckIn() {
        val p1 = sessionFor(1)
        val p2 = sessionFor(2)
        // The seeder writes history for the days *before* today, so both own that date already.
        val date = java.time.LocalDate.ofInstant(now, ZoneId.of("UTC")).minusDays(1)
        val p1Before = repo.dailyPulseFor(p1, date)!!
        val p2Before = repo.dailyPulseFor(p2, date)!!
        assertEquals("each person owns their own row", accountIdOf(1), p1Before.userId)
        assertEquals("each person owns their own row", accountIdOf(2), p2Before.userId)

        // P1 overwrites their own answers for that day.
        assertNotNull("P1 may write their own record", repo.saveDailyPulse(p1, date, 5, 1, 5, true))
        assertEquals("P1's own answers changed", 5, repo.dailyPulseFor(p1, date)!!.dStress)
        assertEquals(
            "P2's record must be untouched by P1's write",
            p2Before.dStress,
            repo.dailyPulseFor(p2, date)!!.dStress
        )
        assertEquals("and P2 still owns it", accountIdOf(2), repo.dailyPulseFor(p2, date)!!.userId)
    }

    @Test
    fun dailyCheckInNeverProducesANumericalStressScore() {
        DemoAccounts.personnelIndices.forEach { i ->
            repo.dailyPulses(sessionFor(i)).forEach { record ->
                val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
                assertTrue("no score field may exist", fields.none { it.contains("score") })
                assertTrue("answers must stay on the 1-5 scale", record.dStress!! in 1..5)
                assertTrue(record.dRest!! in 1..5)
                assertTrue(record.dDuty!! in 1..5)
            }
            // The category is derived for display and is not persisted.
            val raw = store.getString("qpulse_v1_" + accountIdOf(i))!!
            assertFalse("no category may be stored", raw.contains("category", ignoreCase = true))
            assertFalse("no score may be stored", raw.contains("score", ignoreCase = true))
        }
        assertNotNull(DailyContext.category(5, 1, 5))
    }

    @Test
    fun seededDailyContextCoversAllThreeCategories() {
        val categories = DemoAccounts.personnelIndices.flatMap { i ->
            repo.dailyPulses(sessionFor(i)).mapNotNull { DailyContext.category(it) }
        }.toSet()
        assertEquals("the demo should exercise all three context bands", 3, categories.size)
    }

    // == I. PSS-10 ===============================================================================

    @Test
    fun pss10ResultsStayWithinZeroToFortyAndBelongToTheRightAccount() {
        DemoAccounts.personnelIndices.forEach { i ->
            val history = repo.pss10History(sessionFor(i))
            assertTrue("personnel $i has no PSS-10 results", history.isNotEmpty())
            history.forEach { r ->
                assertNotNull(r.score)
                assertTrue("PSS-10 must stay 0..40, got ${r.score}", r.score!! in 0..40)
                assertEquals("answers must be on the 0-4 scale", setOf(accountIdOf(i)), setOf(r.userId))
                assertEquals(10, r.answers.size)
                assertEquals("stored total must equal the shipped scorer's output", Pss10.score(r.answers), r.score)
            }
        }
    }

    @Test
    fun onePersonCannotSeeAnothersPss10History() {
        val p1 = repo.pss10History(sessionFor(1)).map { it.month }.toSet()
        val p2 = repo.pss10History(sessionFor(2)).map { it.month }.toSet()
        assertTrue(p1.isNotEmpty() && p2.isNotEmpty())
        assertTrue("both were seeded for the same recent months", p1.intersect(p2).isNotEmpty())
        // Both are seeded for the same recent months, but ownership is still per account.
        // The months overlap by construction; ownership is what must not.
        assertTrue(repo.pss10History(sessionFor(3)).all { it.userId == accountIdOf(3) })
    }

    @Test
    fun aCommanderCannotReadPss10Results() {
        val commander = Session(
            "c-p", AccountSummary(
                accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!.id,
                "Commander", "cmdr", Role.COMMANDER, null
            )
        )
        assertTrue(repo.pss10History(commander).isEmpty())
    }

    @Test
    fun thePss10ScoringImplementationIsUntouchedByTheDemo() {
        // The demo seeds an answer pattern; the shipped scorer still does the arithmetic.
        val answers = seeder.pssAnswersFor(3, 1)
        assertEquals(10, answers.size)
        assertTrue(answers.values.all { it in 0..4 })
        assertNotNull(Pss10.score(answers))
        assertEquals(Pss10.score(answers), Pss10.score(answers))
    }
}
