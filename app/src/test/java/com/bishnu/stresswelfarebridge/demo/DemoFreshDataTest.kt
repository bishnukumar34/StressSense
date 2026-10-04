package com.bishnu.stresswelfarebridge.demo

import com.bishnu.stresswelfarebridge.DailyHealth
import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.Access
import com.bishnu.stresswelfarebridge.commander.CommanderOverview
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.StressZone
import com.bishnu.stresswelfarebridge.ml.StressBaselineStore
import com.bishnu.stresswelfarebridge.ml.StressMeasurementStore
import com.bishnu.stresswelfarebridge.ml.StressModel
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Reproduces the reported failure: after a fresh install, a demo Personnel dashboard showed
 * "-" / "AWAITING DATA" even though the seeder had persisted their data.
 *
 * These tests drive the **same** fallback the UI now uses - [DemoDashboardData] over the existing
 * [StressMeasurementStore] - so they fail if the dashboard ever stops reading the seeded records
 * again, and they prove the isolation the store already provides.
 */
class DemoFreshDataTest {

    companion object {
        private lateinit var model: StressModel
        private val now: Instant = Instant.parse("2026-03-01T09:00:00Z")
        private val zone: ZoneId = ZoneId.of("UTC")

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            model = StressModel.fromJson(
                org.json.JSONObject(
                    DemoFreshDataTest::class.java.classLoader!!
                        .getResource("stress_model_ml.json")!!.readText()
                )
            )
            StressModel.verifySchema(model)
        }
    }

    /** A freshly created world: empty storage, exactly like `pm clear`. */
    private class World {
        val store = InMemoryKeyValueStore()
        var counter = 0
        val accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acct-${counter++}" })
        val directory = PersonnelDirectory(store, accounts)
        val measurements = StressMeasurementStore(store)
        val baselines = StressBaselineStore(store)
        lateinit var repo: QuestionnaireRepository

        fun seed() {
            repo = QuestionnaireRepository(store, accounts, { now }, { "q${counter++}" })
            DemoDataSeeder(accounts, directory, measurements, baselines, model, { now }, zone).seed(repo)
        }

        fun session(index: Int): Session {
            val a = accounts.accountByUsername(DemoAccounts.personnelUsername(index))!!
            return Session("t-" + a.id, a.toPublicSummary())
        }

        fun commander(): Session {
            val a = accounts.accountByUsername(DemoAccounts.COMMANDER_USERNAME)!!
            return Session("c-" + a.id, a.toPublicSummary())
        }

        /**
         * What the dashboard shows for this person, mirroring MainActivity's fallback.
         *
         * A demo person has no Health Connect data, so `StressEngine.infer` returns its awaiting state
         * (`usingRealData == false`) and the dashboard must fall back to the persisted demo record.
         * The engine needs an Android Context to load its asset, so "Health Connect is empty" is
         * expressed directly as "no real data available" - which is exactly the condition the real
         * screen hits after a fresh install.
         */
        fun dashboardFor(index: Int): Pair<DailyHealth?, StressEngine.Result?> {
            val s = session(index)
            val record = DemoDashboardData.latestScored(measurements.history(s))
            val healthConnectHasRealData = false
            val useDemo = record != null && !healthConnectHasRealData
            return if (useDemo) {
                DemoDashboardData.dailyHealthFrom(record!!, zone) to DemoDashboardData.resultFrom(record)
            } else {
                null to null
            }
        }
    }

    private fun freshSeeded() = World().apply { seed() }

    // == A =======================================================================================

    @Test
    fun freshInitialisationCreatesAllElevenAccounts() {
        val w = freshSeeded()
        val all = w.accounts.allAccounts()
        assertEquals(11, all.size)
        assertEquals(1, all.count { it.role == Role.COMMANDER })
        assertEquals(10, all.count { it.role == Role.PERSONNEL })
    }

    // == B =======================================================================================

    @Test
    fun freshInitialisationCreatesPhysiologicalRecordsForEveryone() {
        val w = freshSeeded()
        (1..10).forEach { i ->
            val rec = w.measurements.latest(w.session(i))
            assertNotNull("personnel $i must have a record", rec)
            assertNotNull("personnel $i must have a score", rec!!.stressScore)
            assertNotNull("personnel $i must have HR", rec.heartRateBpm)
            assertNotNull("personnel $i must have RMSSD", rec.rmssdMs)
        }
    }
    // == C ======================================================================================

    @Test
    fun personnelFourCanLogInAndRetrieveItsOwnRecord() {
        val w = freshSeeded()
        // A real login, not a fabricated session.
        val login = w.accounts.login(
            DemoAccounts.personnelUsername(4), DemoAccounts.PERSONNEL_PASSWORD
        )
        assertTrue("personnel 04 must be able to log in", login is AuthResult.Success)
        val s = (login as AuthResult.Success).session
        assertEquals("DEMO-P04", s.account.serviceId)
        val rec = w.measurements.latest(s)
        assertNotNull("personnel 04 must have its own record", rec)
        assertEquals(s.accountId, rec!!.userId)
    }

    // == D ======================================================================================

    @Test
    fun personnelFourDashboardShowsHrRmssdAndStressInsteadOfDashes() {
        val w = freshSeeded()
        val (day, result) = w.dashboardFor(4)
        assertNotNull("the dashboard must show a day rather than nothing", day)
        assertNotNull("HR must be shown, not the '-' placeholder", day!!.avgHr)
        assertNotNull("RMSSD must be shown", day.avgHrv)
        assertTrue("HR must be a real reading", day.avgHr!! in 30.0..220.0)
        assertTrue("RMSSD must be a real reading", day.avgHrv!! in 1.0..300.0)

        assertNotNull("the stress score must be shown, not AWAITING DATA", result!!.score)
        assertFalse("the result must not be the awaiting state", result.level == "Awaiting data")
        assertEquals("personnel 04's score is 23", 23, result.score)
    }

    // == E, F, G ===============================================================================

    @Test
    fun personnelThreeRetrievesSeventyEight() {
        assertEquals(78, freshSeeded().dashboardFor(3).second!!.score)
    }

    @Test
    fun personnelOneRetrievesSeventeen() {
        assertEquals(17, freshSeeded().dashboardFor(1).second!!.score)
    }

    @Test
    fun personnelTenRetrievesFortyFour() {
        assertEquals(44, freshSeeded().dashboardFor(10).second!!.score)
    }

    @Test
    fun everyDemoPersonGetsTheirIntendedScoreOnTheDashboard() {
        val w = freshSeeded()
        val expected = mapOf(
            1 to 17, 2 to 53, 3 to 78, 4 to 23, 5 to 56,
            6 to 79, 7 to 17, 8 to 46, 9 to 79, 10 to 44
        )
        expected.forEach { (index, score) ->
            assertEquals(
                "personnel ${"%02d".format(index)} must show $score",
                score, w.dashboardFor(index).second!!.score
            )
        }
    }

    // == H ======================================================================================

    @Test
    fun personnelFourCannotRetrievePersonnelThreesRecord() {
        val w = freshSeeded()
        val p4 = w.measurements.latest(w.session(4))!!
        val p3 = w.measurements.latest(w.session(3))!!
        assertNotEquals("the two accounts must not share a record", p3.userId, p4.userId)
        assertEquals(23, p4.stressScore)
        assertEquals(78, p3.stressScore)
        // Each session only ever sees its own history.
        assertTrue(
            "personnel 04's history must contain only their own records",
            w.measurements.history(w.session(4)).all { it.userId == w.session(4).accountId }
        )
    }
    // == I ======================================================================================

    @Test
    fun theCommanderSeesTheIntendedRecords() {
        val w = freshSeeded()
        val access = w.directory.commanderOverview(w.commander())
        assertTrue(access is Access.Allowed)
        val ov = (access as Access.Allowed<CommanderOverview>).value
        assertEquals(3, ov.count(StressZone.GREEN))
        assertEquals(4, ov.count(StressZone.YELLOW))
        assertEquals(3, ov.count(StressZone.RED))
        assertEquals(10, ov.totalPersonnel)

        val byZone = ov.zones[StressZone.RED]!!.associate { it.serviceId to it.stressScore }
        assertEquals(mapOf("DEMO-P03" to 78, "DEMO-P06" to 79, "DEMO-P09" to 79), byZone)
    }

    @Test
    fun theCommanderCannotReadAPersonsMeasurementsDirectly() {
        // The commander surface exposes published summaries, never raw physiological histories.
        val w = freshSeeded()
        assertEquals(
            "a commander session must never read measurement history",
            emptyList<Any>(), w.measurements.history(w.commander())
        )
    }

    // == J ======================================================================================

    @Test
    fun runningTheSeederTwiceDoesNotDuplicateOrCorrupt() {
        val w = World()
        w.seed()
        val countsFirst = (1..10).map { w.measurements.history(w.session(it)).size }
        val scoresFirst = (1..10).map { w.measurements.latest(w.session(it))?.stressScore }

        // Seed again, exactly as a second app launch would.
        w.seed()
        val countsSecond = (1..10).map { w.measurements.history(w.session(it)).size }
        val scoresSecond = (1..10).map { w.measurements.latest(w.session(it))?.stressScore }

        assertEquals("record counts must not change", countsFirst, countsSecond)
        assertEquals("scores must not change", scoresFirst, scoresSecond)
        assertEquals("still exactly 11 accounts", 11, w.accounts.allAccounts().size)
        assertTrue("personnel 03 must still be 78", scoresSecond[2] == 78)
    }

    // == K ======================================================================================

    @Test
    fun clearingAndRecreatingStorageSeedsIdentically() {
        // Simulates `pm clear` followed by a fresh launch: a brand new empty world.
        val first = freshSeeded()
        val second = freshSeeded()
        (1..10).forEach { i ->
            assertEquals(
                "personnel ${"%02d".format(i)} must seed identically after a clear",
                first.measurements.latest(first.session(i))?.stressScore,
                second.measurements.latest(second.session(i))?.stressScore
            )
        }
        assertEquals(11, second.accounts.allAccounts().size)
    }

    // == fallback scoping ======================================================================

    @Test
    fun onlyDemoPersonnelUsernamesAreTreatedAsDemo() {
        assertTrue(DemoDashboardData.isDemoPersonnel("personnel04@stresssense.demo"))
        assertTrue(DemoDashboardData.isDemoPersonnel("personnel01@stresssense.demo"))
        assertTrue(DemoDashboardData.isDemoPersonnel("personnel10@stresssense.demo"))
        // A normal account must never reach the demo path.
        assertFalse(DemoDashboardData.isDemoPersonnel("jane@example.com"))
        assertFalse(DemoDashboardData.isDemoPersonnel("commander@stresssense.demo"))
        assertFalse(DemoDashboardData.isDemoPersonnel("personnel99@stresssense.demo"))
        assertFalse(DemoDashboardData.isDemoPersonnel("personnel4@stresssense.demo"))
        assertFalse(DemoDashboardData.isDemoPersonnel(null))
    }

    @Test
    fun theDashboardBandAgreesWithTheCommanderBand() {
        val w = freshSeeded()
        (1..10).forEach { i ->
            val shown = w.dashboardFor(i).second!!
            val band = com.bishnu.stresswelfarebridge.commander.StressZoneMapping
                .fromScore(shown.score!!)
            assertEquals(
                "personnel ${"%02d".format(i)} wording must match the shared band",
                when (band) {
                    StressZone.RED -> "High"
                    StressZone.YELLOW -> "Moderate"
                    StressZone.GREEN -> "Low"
                },
                shown.level
            )
        }
    }

    @Test
    fun aRecordWithNoScoreFallsBackToHonestAwaitingData() {
        val w = freshSeeded()
        val unscored = com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord(
            id = "x", userId = w.session(4).accountId, measuredAtMs = now.toEpochMilli(),
            source = "CAMERA_PPG", heartRateBpm = 70.0, rmssdMs = 30.0, sdnnMs = 40.0,
            signalQuality = null, stressScore = null, stressZone = null, confidence = 0,
            modelVersion = "v1", featureSchemaVersion = 1
        )
        val r = DemoDashboardData.resultFrom(unscored)
        assertNull("no score may be invented", r.score)
        assertEquals("Awaiting data", r.level)
        assertFalse(r.usingRealData)
    }
}