package com.bishnu.stresswelfarebridge.questionnaire

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
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
 * The Daily Check-in save behaviour, end to end against the real repository.
 *
 * The bug these lock down: a completed check-in still showed "Not saved". The cause was that the UI
 * treated the presence of a *stress score* as proof of saving, and then separately ignored the
 * repository's result entirely and assumed success.
 *
 * The invariant being asserted throughout:
 *
 *     saved = TRUE   and   dailyStressScore = NULL
 *
 * is a completely valid state, and "saved" must be decided by the repository write alone.
 */
class DailyCheckInSaveTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var personnel: Session
    private var counter = 0

    private val now = Instant.parse("2026-10-04T09:00:00Z")
    private val today = LocalDate.of(2026, 10, 4)

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "id-${counter++}" })
        val r = accounts.register(
            RegistrationRequest(
                fullName = "John Doe", username = "jdoe", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.PERSONNEL, serviceId = "SVC-1"
            )
        )
        assertTrue("registration failed: $r", r is AuthResult.Success)
        personnel = (r as AuthResult.Success).session
        repo = QuestionnaireRepository(store, accounts, { now }, { "d-${counter++}" })
    }

    // == save success ============================================================================

    @Test
    fun allThreeAnswersSaveSuccessfully() {
        val saved = repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        assertNotNull("a fully answered check-in must save", saved)
        assertEquals(4, saved!!.dStress)
        assertEquals(2, saved.dRest)
        assertEquals(5, saved.dDuty)
        assertEquals(personnel.accountId, saved.userId)
        assertEquals(today, saved.date)
        assertFalse(saved.checkinRequested)
    }

    @Test
    fun checkinRequestedIsPersistedWhenPresent() {
        val saved = repo.saveDailyPulse(personnel, today, 3, 3, 3, true)
        assertTrue(saved!!.checkinRequested)
        assertTrue(repo.dailyPulseFor(personnel, today)!!.checkinRequested)
    }

    @Test
    fun savedStateIsIndependentOfAnyStressScore() {
        // The whole point: saved == true, and there is no score anywhere in the model.
        val saved = repo.saveDailyPulse(personnel, today, 5, 1, 5, false)
        assertNotNull(saved)
        assertTrue(repo.hasPulseFor(personnel, today))

        val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "a DailyPulseRecord must carry no stress score, found $fields",
            fields.none { it.contains("score") }
        )
    }

    @Test
    fun nothingAnsweredStoresNothing() {
        assertNull(repo.saveDailyPulse(personnel, today, null, null, null, false))
        assertFalse(repo.hasPulseFor(personnel, today))
    }

    // == persistence across a reload / restart ===================================================

    @Test
    fun checkinSurvivesARepositoryRestart() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)

        // Fresh repository and fresh account store over the same persisted storage.
        val reopenedAccounts = AccountStore(store, store, { now.toEpochMilli() }, { "id2-${counter++}" })
        val resumed = (reopenedAccounts.login("jdoe", "Passw0rd!23") as AuthResult.Success).session
        val reopened = QuestionnaireRepository(store, reopenedAccounts, { now }, { "d2-${counter++}" })

        val found = reopened.dailyPulseFor(resumed, today)
        assertNotNull("today's check-in must survive a restart", found)
        assertEquals(4, found!!.dStress)
        assertEquals(2, found.dRest)
        assertEquals(5, found.dDuty)
        assertTrue(reopened.hasPulseFor(resumed, today))
    }

    @Test
    fun readingDoesNotDropTheUserId() {
        repo.saveDailyPulse(personnel, today, 3, 3, 3, false)
        val read = repo.dailyPulses(personnel).first()
        assertEquals(personnel.accountId, read.userId)
        assertFalse("userId must not be blank after a re-read", read.userId.isBlank())
    }

    @Test
    fun reopeningTheSameDayDoesNotCreateADuplicate() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        // Opening the screen again must not write a second row.
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        repo.saveDailyPulse(personnel, today, 1, 5, 1, false)
        assertEquals("exactly one record per calendar day", 1, repo.dailyPulses(personnel).size)
        // The later edit updates that record rather than appending.
        assertEquals(1, repo.dailyPulseFor(personnel, today)!!.dStress)
    }

    @Test
    fun editingTodaysAnswersUpdatesTheExistingRecord() {
        repo.saveDailyPulse(personnel, today, 2, 3, 2, false)
        val updated = repo.saveDailyPulse(personnel, today, 5, 1, 4, true)
        assertNotNull(updated)
        assertEquals(1, repo.dailyPulses(personnel).size)
        val stored = repo.dailyPulseFor(personnel, today)!!
        assertEquals(5, stored.dStress)
        assertEquals(1, stored.dRest)
        assertEquals(4, stored.dDuty)
        assertTrue(stored.checkinRequested)
    }

    @Test
    fun aDifferentDayGetsItsOwnRecord() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        val tomorrow = today.plusDays(1)
        repo.saveDailyPulse(personnel, tomorrow, 1, 5, 1, false)
        assertEquals(2, repo.dailyPulses(personnel).size)
        assertEquals(4, repo.dailyPulseFor(personnel, today)!!.dStress)
        assertEquals(1, repo.dailyPulseFor(personnel, tomorrow)!!.dStress)
    }

    @Test
    fun todayIsTheUsersLocalCalendarDate() {
        val record = repo.saveDailyPulse(personnel, today, 3, 3, 3, false)
        assertEquals(today, record!!.date)
        // The lookup key is the calendar date, so a later time on the same day still matches.
        assertNotNull(repo.dailyPulseFor(personnel, LocalDate.of(2026, 10, 4)))
        assertNull(repo.dailyPulseFor(personnel, LocalDate.of(2026, 10, 5)))
    }

    // == no daily numerical stress score ==========================================================

    @Test
    fun theThreeAnswersProduceNoNumericalStressScore() {
        repo.saveDailyPulse(personnel, today, 5, 1, 5, false)
        val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("score") || it.contains("self") })

        // DailyPulse exposes no scoring function at all.
        val scoring = DailyPulse::class.java.declaredMethods
            .filter { it.name.contains("score", ignoreCase = true) || it.name.contains("calc", ignoreCase = true) }
        assertTrue("DailyPulse must expose no scoring method, found $scoring", scoring.isEmpty())
    }

    // == Daily Context category (display only) ==================================================

    @Test
    fun dailyContextCategoryCoversLowMediumAndHigh() {
        assertEquals(DailyContextCategory.LOW, DailyContext.category(1, 5, 1))
        assertEquals(DailyContextCategory.MEDIUM, DailyContext.category(3, 3, 3))
        assertEquals(DailyContextCategory.HIGH, DailyContext.category(5, 1, 5))
        assertEquals(DailyContextCategory.LOW, DailyContext.category(2, 4, 1))
        assertEquals(DailyContextCategory.HIGH, DailyContext.category(4, 1, 4))
    }

    @Test
    fun dailyContextIsNullUnlessAllThreeWereAnswered() {
        assertNull(DailyContext.category(null, null, null))
        assertNull(DailyContext.category(3, null, null))
        assertNull(DailyContext.category(3, 3, null))
        assertNull(DailyContext.category(null, 3, 3))
    }

    @Test
    fun dailyContextInvertsRestBecauseHigherRestedIsBetter() {
        // Same stress and duty; only rest differs. Fully rested must read as LESS load.
        assertEquals(DailyContextCategory.HIGH, DailyContext.category(4, 1, 5))
        assertEquals(DailyContextCategory.MEDIUM, DailyContext.category(4, 5, 5))
        assertTrue(
            DailyContext.category(4, 1, 5)!!.ordinal > DailyContext.category(4, 5, 5)!!.ordinal
        )
    }

    @Test
    fun dailyContextRejectsOutOfScaleAnswers() {
        assertNull(DailyContext.category(0, 3, 3))
        assertNull(DailyContext.category(6, 3, 3))
        assertNull(DailyContext.category(3, 3, 9))
    }

    @Test
    fun dailyContextIsDerivedNotPersisted() {
        val saved = repo.saveDailyPulse(personnel, today, 5, 1, 5, false)
        assertEquals(DailyContextCategory.HIGH, DailyContext.category(saved))
        // The stored record has no field for the category: it is recomputed for display.
        val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("category") || it.contains("context") })
        // And it is not part of the serialised JSON either.
        val raw = store.getString("qpulse_v1_${personnel.accountId}")!!
        assertFalse("the category must not be written to storage", raw.contains("category", ignoreCase = true))
    }

    // == privacy is unchanged ====================================================================

    @Test
    fun commanderStillCannotReadOrWriteCheckins() {
        val commanderResult = accounts.register(
            RegistrationRequest(
                fullName = "Cara Officer", username = "cdr", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.COMMANDER, serviceId = "SVC-2"
            )
        )
        val commander = (commanderResult as AuthResult.Success).session

        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        assertTrue(repo.dailyPulses(commander).isEmpty())
        assertNull(repo.dailyPulseFor(commander, today))
        assertNull(repo.saveDailyPulse(commander, today, 4, 2, 5, false))
        assertFalse(repo.hasPulseFor(commander, today))
    }
}
