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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The three-question walk an actual user performs, question by question.
 *
 * ### The bug this covers
 * On question 3 - the question where saving actually happens - the Update button was not reachable.
 * The screen was a non-scrolling Column containing a `weight(1f)` spacer, and question 3 adds an
 * extra checkbox row. That overflowed the viewport and pushed the button off-screen, so a person
 * who had opened "Update Today's Check-in" could change question 1 and 2 and then find no way to
 * save. The button now lives in the Scaffold bottom bar, pinned and always on screen.
 *
 * These tests walk the walker's state the way the screen does: seed from the stored record, apply
 * the answers the person picks, and ask the same [DailyCheckInForm] the button is bound to.
 */
class DailyCheckInQuestionFlowTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var session: Session
    private var counter = 0

    private val now = Instant.parse("2026-10-04T09:00:00Z")
    private val today = LocalDate.of(2026, 10, 4)

    /** The three items, in presentation order, so "question 3" means the same thing as in the UI. */
    private val questions = DailyPulse.items

    /** Shared with [Walk], a nested class, which cannot see instance members. */
    private object Items { val list = DailyPulse.items }

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
        assertTrue(r is AuthResult.Success)
        session = (r as AuthResult.Success).session
        repo = QuestionnaireRepository(store, accounts, { now }, { "d-${counter++}" })
    }

    /** The walker's three answers, seeded from [existing] exactly as the screen seeds its state. */
    private class Walk(private val existing: DailyPulseRecord?) {
        var stress: Int? = existing?.dStress
        var rest: Int? = existing?.dRest
        var duty: Int? = existing?.dDuty
        var requested: Boolean = existing?.checkinRequested ?: false

        fun answer(questionNumber: Int, value: Int): Walk {
            when (Items.list[questionNumber - 1].id) {
                DailyPulse.ITEM_STRESS -> stress = value
                DailyPulse.ITEM_REST -> rest = value
                else -> duty = value
            }
            return this
        }

        /** The exact predicate the Save / Update button is bound to. */
        fun canSave(): Boolean = DailyCheckInForm.canSave(existing, stress, rest, duty, requested)

        fun label(): String = DailyCheckInForm.buttonLabel(existing)
    }

    private fun walk() = Walk(repo.dailyPulseFor(session, today))

    /** There must be three questions, or "question 3" is meaningless. */
    @Test
    fun thereAreExactlyThreeQuestions() {
        assertEquals(3, questions.size)
        assertEquals(DailyPulse.ITEM_STRESS, questions[0].id)
        assertEquals(DailyPulse.ITEM_REST, questions[1].id)
        assertEquals(DailyPulse.ITEM_DUTY, questions[2].id)
    }

    // == new check-in ===========================================================================

    @Test
    fun aNewCheckInCanBeWalkedAndSaved() {
        val w = walk()
        assertFalse("nothing saved yet, so nothing to save", w.canSave())
        w.answer(1, 4).answer(2, 2).answer(3, 5)
        assertEquals("Save Check-in", w.label())
        assertTrue("all three answered", w.canSave())

        val saved = repo.saveDailyPulse(session, today, w.stress, w.rest, w.duty, w.requested)
        assertNotNull(saved)
        assertEquals(4, saved!!.dStress)
        assertEquals(2, saved.dRest)
        assertEquals(5, saved.dDuty)
    }

    // == existing check-in: change each question in turn ==========================================

    @Test
    fun changingQuestionOneEnablesTheFinalUpdateButton() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        assertFalse("unchanged", w.canSave())
        w.answer(1, 1)
        w.answer(2, 2)   // question 2 unchanged
        w.answer(3, 5)   // question 3 unchanged
        assertEquals("Update Check-in", w.label())
        assertTrue("a change on question 1 alone must enable it", w.canSave())
    }

    @Test
    fun changingQuestionTwoEnablesTheFinalUpdateButton() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 4)   // unchanged
        w.answer(2, 5)   // changed
        w.answer(3, 5)   // unchanged
        assertEquals("Update Check-in", w.label())
        assertTrue("a change on question 2 alone must enable it", w.canSave())
    }

    @Test
    fun changingQuestionThreeEnablesTheFinalUpdateButton() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 4)
        w.answer(2, 2)
        w.answer(3, 1)   // changed
        assertEquals("Update Check-in", w.label())
        assertTrue("a change on question 3 alone must enable it", w.canSave())
    }

    @Test
    fun changingAllThreeStillEnablesTheFinalUpdateButton() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 1).answer(2, 5).answer(3, 2)
        assertTrue(w.canSave())
    }

    @Test
    fun ifNothingChangedTheUpdateButtonMayRemainDisabled() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 4).answer(2, 2).answer(3, 5)
        assertFalse("nothing changed anywhere", w.canSave())
    }

    @Test
    fun changingAnAnswerBackToItsOriginalDisablesTheButtonAgain() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 1)
        assertTrue(w.canSave())
        w.answer(1, 4)
        assertFalse("back to the saved value", w.canSave())
    }

    // == the final save ==========================================================================

    @Test
    fun theFinalUpdateWritesAllThreeAnswers() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 1).answer(2, 5).answer(3, 2)
        assertTrue(w.canSave())

        val saved = repo.saveDailyPulse(session, today, w.stress, w.rest, w.duty, w.requested)
        assertNotNull(saved)
        assertEquals(1, saved!!.dStress)
        assertEquals(5, saved.dRest)
        assertEquals(2, saved.dDuty)
    }

    @Test
    fun theUpdatedAnswersSurviveReopeningTheScreen() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        w.answer(1, 1).answer(2, 5).answer(3, 2)
        repo.saveDailyPulse(session, today, w.stress, w.rest, w.duty, w.requested)

        // Reopening reads through a fresh repository, as a relaunch would.
        val reopened = QuestionnaireRepository(store, accounts, { now }, { "r-${counter++}" })
            .dailyPulseFor(session, today)
        assertNotNull(reopened)
        assertEquals(1, reopened!!.dStress)
        assertEquals(5, reopened.dRest)
        assertEquals(2, reopened.dDuty)

        // And the screen is seeded with them, showing Update disabled until something changes.
        val secondWalk = Walk(reopened)
        assertFalse(secondWalk.canSave())
        assertEquals("Update Check-in", secondWalk.label())
    }

    @Test
    fun updatingTwiceDoesNotCreateADuplicateRecord() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val first = walk().answer(1, 1)
        repo.saveDailyPulse(session, today, first.stress, first.rest, first.duty, first.requested)
        val second = walk().answer(2, 1)
        repo.saveDailyPulse(session, today, second.stress, second.rest, second.duty, second.requested)

        val all = repo.dailyPulses(session)
        assertEquals("still exactly one record for the day", 1, all.size)
        // The second walk re-seeds from the already-updated record, so it keeps the first change
        // (stress 1) and adds its own (rest 1). Getting 4 here would mean the screen failed to
        // reload the updated answers before the next edit.
        assertEquals(1, all.first().dStress)
        assertEquals(1, all.first().dRest)
        assertEquals(5, all.first().dDuty)
    }

    @Test
    fun togglingTheCheckinRequestAlsoEnablesUpdate() {
        repo.saveDailyPulse(session, today, 4, 2, 5, false)
        val w = walk()
        assertFalse(w.canSave())
        w.requested = true
        assertTrue("the checkbox is part of the record too", w.canSave())
        val saved = repo.saveDailyPulse(session, today, w.stress, w.rest, w.duty, w.requested)
        assertTrue(saved!!.checkinRequested)
    }

    // == the button must exist on question 3 ======================================================

    @Test
    fun theSaveButtonIsPinnedAndPresentOnEveryQuestion() {
        val src = File("src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/DailyCheckInScreen.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/DailyCheckInScreen.kt")
        val text = src.readText()

        // The button lives in the Scaffold's bottomBar, so no amount of question content can push
        // it off-screen. This is the fix for question 3.
        assertTrue(
            "the save button must be pinned in a bottomBar",
            text.contains("bottomBar = {") && text.contains("enabled = canSave")
        )
        // It must not be conditional on which question is showing.
        val buttonIndex = text.indexOf("enabled = canSave")
        val barIndex = text.lastIndexOf("bottomBar = {", buttonIndex)
        assertTrue("the button must be inside the bottomBar", barIndex in 0 until buttonIndex)
    }

    @Test
    fun theQuestionContentScrollsSoTheButtonIsNeverTrapped() {
        val src = File("src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/DailyCheckInScreen.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/DailyCheckInScreen.kt")
        val text = src.readText()
        assertTrue("long question content must be scrollable", text.contains("verticalScroll("))
        // A weighted spacer inside a scrolling column has unbounded height and would throw, so the
        // old weight(1f) must be gone.
        assertFalse(
            "weight(1f) must not remain inside the scrolling column",
            text.contains("Spacer(Modifier.weight(1f))")
        )
    }

    @Test
    fun noNumericalDailyStressScoreIsEverProduced() {
        val w = walk().answer(1, 5).answer(2, 1).answer(3, 5)
        repo.saveDailyPulse(session, today, w.stress, w.rest, w.duty, w.requested)
        val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("the record must carry no score field", fields.none { it.contains("score") })
        val raw = store.getString("qpulse_v1_${session.accountId}")!!
        assertFalse(raw.contains("score", ignoreCase = true))
    }
}
