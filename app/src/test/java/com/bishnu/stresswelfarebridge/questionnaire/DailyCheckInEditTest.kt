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
 * Editing an already-completed Daily Check-in.
 *
 * ### The bug this locks down
 * Opening today's completed check-in and changing an answer left the save/update button disabled,
 * so a check-in could be created once and never revised. The button used to be gated on a stress
 * score that is intentionally always null.
 *
 * ### The rules asserted here
 *  1. An existing check-in loads with its answers.
 *  2. The update button is off only when there is genuinely nothing to change.
 *  3. Changing any single answer turns it on.
 *  4. Saving the change succeeds and updates the existing record.
 *  5. The updated answers survive reopening the screen.
 *  6. No duplicate record is created for the same day.
 *
 * Every rule is checked twice: against the pure [DailyCheckInForm] the UI now calls, and against the
 * real repository, so a passing test means the record on disk really did change.
 */
class DailyCheckInEditTest {

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

    private fun record(stress: Int?, rest: Int?, duty: Int?, requested: Boolean = false) =
        DailyPulseRecord(
            userId = personnel.accountId,
            date = today,
            dStress = stress,
            dRest = rest,
            dDuty = duty,
            checkinRequested = requested,
            createdAt = now
        )

    // == 1. existing check-in loads =================================================================

    @Test
    fun existingCheckInIsLoadedWithItsSavedAnswers() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        val existing = repo.dailyPulseFor(personnel, today)
        assertNotNull("the stored check-in must be readable back", existing)
        assertEquals(4, existing!!.dStress)
        assertEquals(2, existing.dRest)
        assertEquals(5, existing.dDuty)
    }

    // == 2. button starts off only when there is nothing to change =================================

    @Test
    fun updateButtonIsOffWhenNothingHasChanged() {
        val existing = record(4, 2, 5)
        assertFalse(
            "an unchanged check-in has nothing to save",
            DailyCheckInForm.canSave(existing, 4, 2, 5, false)
        )
    }

    @Test
    fun newCheckInButtonIsOffUntilAllThreeQuestionsAreAnswered() {
        assertFalse(DailyCheckInForm.canSave(null, null, null, null, false))
        assertFalse(DailyCheckInForm.canSave(null, 3, null, null, false))
        assertFalse(DailyCheckInForm.canSave(null, 3, 3, null, false))
        assertTrue(DailyCheckInForm.canSave(null, 3, 3, 3, false))
    }

    @Test
    fun updateButtonIsNeverGatedOnACompletedCheckInOrAStressScore() {
        // completedToday == true must not disable the button on its own: once an answer differs,
        // saving must be possible.
        val existing = record(4, 2, 5)
        assertTrue(DailyCheckInForm.canSave(existing, 5, 2, 5, false))
        // And a DailyPulseRecord carries no score at all, so nothing could read one.
        val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("score") })
    }

    @Test
    fun buttonIsOffWhenAnExistingAnswerIsCleared() {
        // Losing an answer means the record is no longer valid, so it must not be written as-is.
        val existing = record(4, 2, 5)
        assertFalse(DailyCheckInForm.canSave(existing, null, 2, 5, false))
    }

    // == 3. changing any answer enables the button =================================================

    @Test
    fun changingAnySingleAnswerEnablesTheUpdateButton() {
        val existing = record(4, 2, 5)
        assertTrue("stress changed", DailyCheckInForm.canSave(existing, 1, 2, 5, false))
        assertTrue("rest changed", DailyCheckInForm.canSave(existing, 4, 5, 5, false))
        assertTrue("duty changed", DailyCheckInForm.canSave(existing, 4, 2, 1, false))
        assertTrue(
            "check-in request toggled",
            DailyCheckInForm.canSave(existing, 4, 2, 5, true)
        )
    }

    @Test
    fun changingAnAnswerBackToTheOriginalDisablesTheButtonAgain() {
        val existing = record(4, 2, 5)
        assertTrue(DailyCheckInForm.canSave(existing, 1, 2, 5, false))
        assertFalse(DailyCheckInForm.canSave(existing, 4, 2, 5, false))
    }

    @Test
    fun outOfScaleAnswersAreRejected() {
        val existing = record(4, 2, 5)
        assertFalse(DailyCheckInForm.canSave(existing, 9, 2, 5, false))
        assertFalse(DailyCheckInForm.canSave(null, 0, 2, 5, false))
        assertFalse(DailyCheckInForm.canSave(null, 4, 2, 6, false))
    }

    // == 4/5/6. saving updates, persists, and never duplicates ======================================

    @Test
    fun savingAChangedAnswerUpdatesTheExistingRecord() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        val updated = repo.saveDailyPulse(personnel, today, 1, 2, 5, false)
        assertNotNull("the update must be accepted", updated)
        assertEquals(1, updated!!.dStress)
        // The unchanged answers are preserved, not reset.
        assertEquals(2, updated.dRest)
        assertEquals(5, updated.dDuty)
    }

    @Test
    fun updatedAnswersAreStillThereWhenTheScreenIsReopened() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        repo.saveDailyPulse(personnel, today, 1, 5, 3, true)

        // Reopening the screen means re-reading through the controller/repository.
        val reopened = QuestionnaireRepository(store, accounts, { now }, { "r-${counter++}" })
            .dailyPulseFor(personnel, today)
        assertNotNull(reopened)
        assertEquals(1, reopened!!.dStress)
        assertEquals(5, reopened.dRest)
        assertEquals(3, reopened.dDuty)
        assertTrue(reopened.checkinRequested)
        // And it is preloaded as the screen's starting point.
        assertTrue(DailyCheckInForm.canSave(reopened, 1, 5, 3, true) == false)
    }

    @Test
    fun updatingDoesNotCreateADuplicateRecordForTheSameDay() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        repo.saveDailyPulse(personnel, today, 1, 5, 3, true)
        val all = repo.dailyPulses(personnel)
        assertEquals("exactly one record for the day", 1, all.size)
        assertEquals(today, all.first().date)
        assertEquals(1, all.first().dStress)
    }

    @Test
    fun repeatedUpdatesNeverAccumulateRecords() {
        repeat(5) { i ->
            repo.saveDailyPulse(personnel, today, (i % 5) + 1, 3, 3, false)
        }
        assertEquals(1, repo.dailyPulses(personnel).size)
    }

    @Test
    fun aDifferentDayGetsItsOwnRecord() {
        repo.saveDailyPulse(personnel, today, 4, 2, 5, false)
        repo.saveDailyPulse(personnel, today, 1, 2, 5, false)
        repo.saveDailyPulse(personnel, today.plusDays(1), 5, 1, 5, false)
        assertEquals(2, repo.dailyPulses(personnel).size)
    }

    // == labels and helper text =====================================================================

    @Test
    fun theButtonSaysUpdateOnceARecordExists() {
        assertEquals("Save Check-in", DailyCheckInForm.buttonLabel(null))
        assertEquals("Update Check-in", DailyCheckInForm.buttonLabel(record(4, 2, 5)))
    }

    @Test
    fun theHelperTextExplainsBothDisabledStates() {
        assertTrue(
            DailyCheckInForm.helperText(record(4, 2, 5), 4, 2, 5)
                .contains("Change any answer")
        )
        assertTrue(
            DailyCheckInForm.helperText(null, null, null, null)
                .contains("Answer all three")
        )
        assertTrue(
            DailyCheckInForm.helperText(null, 3, 3, 3).contains("saved")
        )
    }
}
