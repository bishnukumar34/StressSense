package com.bishnu.stresswelfarebridge.questionnaire.ui

import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulse
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireInstrument
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireScorer
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireTriggerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The Daily Check-in after its purpose changed: it is **context only** and must not produce a
 * perceived-stress score.
 */
class DailyCheckInContextTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var controller: QuestionnaireController
    private lateinit var personnel: Session
    private lateinit var commander: Session
    private var counter = 0

    private val today: LocalDate = LocalDate.parse("2026-03-01")
    private val now: Instant = Instant.parse("2026-03-01T12:00:00Z")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "a${counter++}" })
        personnel = register("jdoe", Role.PERSONNEL)
        commander = register("cdr", Role.COMMANDER)
        repo = QuestionnaireRepository(store, accounts, { now }, { "r${counter++}" })
        controller = QuestionnaireController(
            repository = repo, engine = QuestionnaireTriggerEngine(),
            today = { today }, now = { now }
        )
    }

    private fun register(username: String, role: Role): Session {
        val r = accounts.register(
            RegistrationRequest(
                fullName = username.replaceFirstChar { it.uppercase() },
                username = username, password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = role,
                serviceId = if (role == Role.PERSONNEL) "SVC-$username" else null
            )
        )
        assertTrue(r is AuthResult.Success)
        return (r as AuthResult.Success).session
    }

    private fun physiological(score: Int, real: Boolean = true) = StressEngine.Result(
        score = score, level = "High", confidence = 80, hrContribution = 0, hrvContribution = 0,
        recoveryContribution = 0, message = "m", usingRealData = real
    )

    // -- questions can be answered and persist -------------------------------------------------

    @Test
    fun allThreeQuestionsCanBeAnswered() {
        assertNotNull(controller.saveDailyPulse(personnel, 4, 2, 5, false))
        val r = repo.dailyPulseFor(personnel, today)!!
        assertEquals(4, r.dStress)
        assertEquals(2, r.dRest)
        assertEquals(5, r.dDuty)
    }

    @Test
    fun answersPersistAcrossRestart() {
        controller.saveDailyPulse(personnel, 3, 4, 2, true)
        val reopened = QuestionnaireRepository(store, accounts, { now }, { "z${counter++}" })
        val r = reopened.dailyPulseFor(personnel, today)
        assertNotNull(r)
        assertEquals(3, r!!.dStress)
        assertTrue(r.checkinRequested)
    }

    @Test
    fun partialAnswersAreStoredAsGivenWithoutSubstitution() {
        controller.saveDailyPulse(personnel, 4, null, null, false)
        val r = repo.dailyPulseFor(personnel, today)!!
        assertEquals(4, r.dStress)
        assertNull("an unanswered question must stay null, not become 0", r.dRest)
        assertNull(r.dDuty)
    }

    @Test
    fun nothingAnsweredStoresNothing() {
        assertNull(controller.saveDailyPulse(personnel, null, null, null, false))
        assertTrue(repo.dailyPulses(personnel).isEmpty())
    }

    @Test
    fun checkInRequestPersists() {
        controller.saveDailyPulse(personnel, 3, 3, 3, true)
        assertTrue(repo.dailyPulseFor(personnel, today)!!.checkinRequested)
    }

    @Test
    fun oneRecordPerDayAndRetakeReplaces() {
        controller.saveDailyPulse(personnel, 5, 5, 5, false)
        controller.saveDailyPulse(personnel, 1, 1, 1, false)
        val all = repo.dailyPulses(personnel)
        assertEquals(1, all.size)
        assertEquals(1, all.first().dStress)
    }

    // -- NO daily 0-100 stress score is produced ----------------------------------------------------

    @Test
    fun dailyRecordHasNoScoreFieldAtAll() {
        DailyPulseRecord::class.java.declaredFields.forEach {
            assertFalse(
                "the Daily Check-in must not carry a score field: ${it.name}",
                it.name.contains("score", ignoreCase = true) ||
                    it.name.contains("selfDaily", ignoreCase = true)
            )
        }
    }

    @Test
    fun dailyCheckInDoesNotProduceAPerceivedScore() {
        controller.saveDailyPulse(personnel, 5, 1, 5, false)
        val s = controller.build(personnel, physiological(40))
        assertNull("the Daily Check-in must not set a perceived-stress score", s.perceivedScore)
        assertNull(s.insight.perceived.score)
    }

    @Test
    fun perceivedScoreComesOnlyFromPss10() {
        controller.saveDailyPulse(personnel, 5, 1, 5, false)
        controller.savePss10(personnel, java.time.YearMonth.of(2026, 3), (1..10).associate { it to 3 })
        val s = controller.build(personnel, physiological(40))
        assertNotNull(s.perceivedScore)
        assertEquals(22, s.perceivedScore)
    }

    @Test
    fun theOldSelfDailyFormulaIsGone() {
        // The scorer no longer exposes a 0-100 daily score.
        val methods = QuestionnaireScorer::class.java.methods.map { it.name }
        assertFalse(methods.contains("scoreDailyPulse"))
    }

    // -- daily answers remain available as context -------------------------------------------------

    @Test
    fun dailyAnswersRemainAvailableAsContextualData() {
        controller.saveDailyPulse(personnel, 4, 2, 3, false)
        val s = controller.build(personnel, physiological(30))
        assertNotNull("today's raw answers stay available for paired analysis", s.todayPulse)
        assertEquals(4, s.todayPulse!!.dStress)
        assertTrue(s.hasAnyHistory)
    }

    // -- physiological path unaffected ---------------------------------------------------------------

    @Test
    fun physiologicalPassThroughIsUnchangedForEveryScore() {
        for (score in listOf(0, 25, 50, 64, 65, 80, 100)) {
            assertEquals(score, controller.build(personnel, physiological(score)).insight.physiological.score)
        }
    }

    @Test
    fun missingPhysiologicalDataNeverBecomesZero() {
        assertNull(controller.build(personnel, physiological(0, real = false)).insight.physiological.score)
    }

    @Test
    fun missingQuestionnaireDataNeverBecomesZero() {
        val s = controller.build(personnel, physiological(70))
        assertNull(s.perceivedScore)
        assertNull(s.insight.perceived.score)
    }

    @Test
    fun dailyCheckInDoesNotAlterThePhysiologicalScore() {
        val before = controller.build(personnel, physiological(72)).insight.physiological.score
        controller.saveDailyPulse(personnel, 5, 1, 5, false)
        assertEquals(before, controller.build(personnel, physiological(72)).insight.physiological.score)
    }

    // -- privacy ---------------------------------------------------------------------------------------

    @Test
    fun commanderSeesNoQuestionnaireData() {
        controller.saveDailyPulse(personnel, 5, 1, 5, true)
        val s = controller.build(commander, physiological(70))
        assertNull(s.perceivedScore)
        assertTrue(s.history.isEmpty())
        assertTrue(s.pss10Records.isEmpty())
    }

    @Test
    fun dassStillCannotBeStarted() {
        assertFalse(controller.canStart(QuestionnaireInstrument.DASS_21))
        assertTrue(controller.canStart(QuestionnaireInstrument.byId(DailyPulse.INSTRUMENT_ID)!!))
    }

    @Test
    fun normRemainsAvailableForComparingDailyAnswers() {
        assertEquals(0.0, QuestionnaireScorer.norm(1), 1e-12)
        assertEquals(1.0, QuestionnaireScorer.norm(5), 1e-12)
    }
}
