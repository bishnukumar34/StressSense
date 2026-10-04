package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.Access
import com.bishnu.stresswelfarebridge.commander.CommanderOverview
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.commander.StressZoneMapping
import com.bishnu.stresswelfarebridge.ml.StressBaselineStore
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressModel
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Locks in the exact demo scores verified on the Commander stress-group screens.
 *
 * Every number below is the **output of the deployed `wesad-loso-logreg-v1` model** over the
 * synthetic physiology in [DemoPhysiology]. Nothing here writes, defaults or hard-codes a score: the
 * seeder persists whatever the model returns, and these tests read that back. That is why the
 * expected values are asserted against the real pipeline rather than injected anywhere.
 *
 * If a future change to the physiology, the model or the seeding order moved a person, these tests
 * would fail - which is the point. They are a regression lock on the published demo, not a
 * specification the UI reads from.
 */
class DemoScoreLockTest {

    companion object {
        private lateinit var model: StressModel

        @BeforeClass
        @JvmStatic
        fun loadDeployedModel() {
            model = StressModel.fromJson(
                org.json.JSONObject(
                    DemoScoreLockTest::class.java.classLoader!!
                        .getResource("stress_model_ml.json")!!.readText()
                )
            )
            StressModel.verifySchema(model)
        }
    }

    /** The verified demo scores, as displayed by the Commander stress-group screens. */
    private val expected = mapOf(
        1 to 17, 2 to 53, 3 to 78, 4 to 23, 5 to 56,
        6 to 79, 7 to 17, 8 to 46, 9 to 79, 10 to 44
    )

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
        seeder.seed(repo)
    }

    private fun sessionFor(index: Int): Session {
        val a = accounts.accountByUsername(DemoAccounts.personnelUsername(index))!!
        return Session("t-" + a.id, a.toPublicSummary())
    }

    private fun commanderSession(): Session {
        val a = accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!
        return Session("c-" + a.id, a.toPublicSummary())
    }

    private fun overview(): CommanderOverview {
        val access = directory.commanderOverview(commanderSession())
        assertTrue("the commander must be allowed to read the overview", access is Access.Allowed)
        return (access as Access.Allowed<CommanderOverview>).value
    }

    /** Service ids of a Commander group, sorted; null ids are dropped since demo accounts all have one. */
    private fun idsIn(zone: StressZone): List<String> =
        overview().zones[zone]!!.mapNotNull { it.serviceId }.sorted()

    private fun scoreOf(index: Int): Int? = measurements.latest(sessionFor(index))?.stressScore
    // == 1. Personnel 03's seeded StressSense score is 78 ==========================================

    @Test
    fun personnelThreeSeedsExactlySeventyEight() {
        // Compared as Int? against Int? so the assertion also fails if no score was persisted.
        val actual: Int? = scoreOf(3)
        assertEquals("Personnel 03's StressSense score is 78", 78, actual)
    }

    @Test
    fun personnelThreeScoreComesFromTheDeployedModel() {
        val record = measurements.latest(sessionFor(3))!!
        assertEquals(
            "the score must be produced by the deployed logistic-regression model",
            "wesad-loso-logreg-v1", record.modelVersion
        )
        assertNotNull("a real score must be persisted", record.stressScore)
    }

    // == 2. classified HIGH through the existing StressZoneMapping ================================

    @Test
    fun personnelThreeIsClassifiedHighByTheExistingMapping() {
        val score = scoreOf(3)!!
        // The Commander band is derived by the app's own thresholds - there is no personnel-specific
        // rule. 78 sits above MODERATE_SCORE_MAX, so it lands in RED.
        assertEquals(StressZone.RED, StressZoneMapping.fromScore(score))

        // The persisted zone string comes from the model's own StressZone, which is a different enum
        // (ml.HIGH) from the Commander band (RED). What matters is that the two agree on "high".
        val persisted = measurements.latest(sessionFor(3))!!.stressZone
        assertEquals(
            "the persisted zone must agree with the model that produced the score",
            com.bishnu.stresswelfarebridge.ml.StressZone.HIGH.name, persisted
        )
    }

    // == 3. the Commander displays 78 for Personnel 03 =============================================

    @Test
    fun theCommanderDisplaysSeventyEightForPersonnelThree() {
        val high = overview().zones[StressZone.RED]!!.toList()
        val p3 = high.single { it.accountId == sessionFor(3).accountId }
        val shown: Int? = p3.stressScore
        assertEquals("the Commander's published score is 78", 78, shown)
        assertEquals("Personnel 03 is in the HIGH group", StressZone.RED, p3.zone)
    }

    @Test
    fun theHighGroupIsPersonnelThreeSixAndNine() {
        assertEquals(
            "HIGH contains exactly Personnel 03, 06 and 09",
            listOf("DEMO-P03", "DEMO-P06", "DEMO-P09"),
            idsIn(StressZone.RED)
        )
    }

    // == 4. Personnel 03's own Stress Level uses the same persisted score ==========================

    @Test
    fun personnelThreeOwnViewUsesTheSamePersistedScore() {
        // What the person sees is read from the very record the Commander reads.
        val own = measurements.latest(sessionFor(3))!!
        val shownToCommander = overview().zones[StressZone.RED]!!
            .single { it.accountId == sessionFor(3).accountId }
        assertEquals(
            "there must be exactly one score, shared by owner and commander",
            own.stressScore, shownToCommander.stressScore
        )
    }

    // == 5. Trends uses the same persisted score =================================================

    @Test
    fun personnelThreeTrendsUseTheSamePersistedScore() {
        val history = measurements.history(sessionFor(3))
        assertTrue("there must be persisted records to plot", history.isNotEmpty())
        // The graph plots the persisted record, so its newest point is that same 78 - there is no
        // separate graph-only value anywhere.
        assertEquals("the newest trend point is the same 78", 78, history.first().stressScore)
    }

    // == 6. no UI hard-code maps Personnel 03 to 78 ==============================================

    @Test
    fun noProductionCodeHardcodesSeventyEightForPersonnelThree() {
        val files = listOf(
            "src/main/java/com/bishnu/stresswelfarebridge/ui/commander/CommanderScreens.kt",
            "src/main/java/com/bishnu/stresswelfarebridge/ui/commander/CommanderController.kt",
            "src/main/java/com/bishnu/stresswelfarebridge/commander/PersonnelDirectory.kt",
            "src/main/java/com/bishnu/stresswelfarebridge/commander/PersonnelRecord.kt",
            "src/main/java/com/bishnu/stresswelfarebridge/commander/PersonnelRecordFactory.kt",
            "src/main/java/com/bishnu/stresswelfarebridge/commander/StressZone.kt",
            "app/src/main/java/com/bishnu/stresswelfarebridge/ui/commander/CommanderScreens.kt",
            "app/src/main/java/com/bishnu/stresswelfarebridge/ui/commander/CommanderController.kt",
            "app/src/main/java/com/bishnu/stresswelfarebridge/commander/PersonnelDirectory.kt"
        )
        val present = files.map { File(it) }.filter { it.exists() }
        assertTrue("expected to inspect the commander sources", present.isNotEmpty())
        for (f in present) {
            assertFalse(
                "${f.name} must not contain a hard-coded 78 for a specific person",
                Regex("""(Personnel\s*0?3|accountId|serviceId)[^\n]{0,40}\b78\b""").containsMatchIn(f.readText())
            )
        }
    }

    @Test
    fun theSeederNeverWritesAScoreItself() {
        val file = File("src/main/java/com/bishnu/stresswelfarebridge/demo/DemoDataSeeder.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/bishnu/stresswelfarebridge/demo/DemoDataSeeder.kt")
        val body = file.readText()
        assertFalse(
            "the seeder must not assign a literal score",
            Regex("""stressScore\s*=\s*\d""").containsMatchIn(body)
        )
        assertTrue(
            "the seeder must run the measurement through the real bridge",
            body.contains("CameraStressBridge")
        )
    }

    // == 7. the other nine scores are unchanged ==================================================

    @Test
    fun everyOtherDemoScoreIsUnchanged() {
        expected.forEach { (index, score) ->
            assertEquals(
                "Personnel ${"%02d".format(index)} must remain $score",
            score as Int?, scoreOf(index)
            )
        }
    }

    // == 8. the 3/4/3 distribution is unchanged ==================================================

    @Test
    fun theThreeFourThreeDistributionIsUnchanged() {
        val ov = overview()
        assertEquals(3, ov.count(StressZone.GREEN))
        assertEquals(4, ov.count(StressZone.YELLOW))
        assertEquals(3, ov.count(StressZone.RED))
        assertEquals(10, ov.totalPersonnel)
    }

    @Test
    fun theLowAndModerateGroupsKeepTheirMembers() {
        val ov = overview()
        assertEquals(
            listOf("DEMO-P01", "DEMO-P04", "DEMO-P07"),
            idsIn(StressZone.GREEN)
        )
        assertEquals(
            listOf("DEMO-P02", "DEMO-P05", "DEMO-P08", "DEMO-P10"),
            idsIn(StressZone.YELLOW)
        )
    }

    // == determinism ===========================================================================

    @Test
    fun theScoresAreReproducibleAcrossSeeds() {
        // The physiology is a closed-form function, so a fresh seed reproduces Personnel 03's 78
        // exactly - the value is not an artefact of run order.
        store = InMemoryKeyValueStore()
        counter = 0
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acct-${counter++}" })
        directory = PersonnelDirectory(store, accounts)
        measurements = StressMeasurementStore(store)
        baselines = StressBaselineStore(store)
        repo = QuestionnaireRepository(store, accounts, { now }, { "q${counter++}" })
        seeder = DemoDataSeeder(accounts, directory, measurements, baselines, model, { now }, zone)
        seeder.seed(repo)
        val again: Int? = scoreOf(3)
        assertEquals(78, again)
    }
}