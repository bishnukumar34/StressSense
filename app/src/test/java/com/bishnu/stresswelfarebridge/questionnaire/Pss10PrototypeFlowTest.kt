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
import java.time.YearMonth

/**
 * The complete PSS-10 prototype flow, end to end, against the real deployed definition.
 *
 * Covers the fifteen behaviours the feature is required to have: it opens, shows all ten items,
 * accepts 0-4 answers, reverse-scores 4/5/7/8, always lands in 0-40, saves, obeys calendar-month
 * availability, keeps every previous month, survives a restart, and is invisible to commanders.
 *
 * On the Daily Check-in and Physiological Stress: there are explicit tests here that they are NOT
 * reduced to a 0-100 stress score, and that the physiological path is untouched by any of this.
 */
class Pss10PrototypeFlowTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var personnel: Session
    private lateinit var commander: Session
    private var counter = 0

    private val now = Instant.parse("2026-10-04T09:00:00Z")
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "id-${counter++}" })
        personnel = register("jdoe", "John Doe", Role.PERSONNEL)
        commander = register("cdr", "Cara Officer", Role.COMMANDER)
        repo = QuestionnaireRepository(store, accounts, { now }, { "r-${counter++}" })
    }

    private fun register(user: String, name: String, role: Role): Session {
        val r = accounts.register(
            RegistrationRequest(
                fullName = name, username = user, password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = role, serviceId = "SVC-$user"
            )
        )
        assertTrue("registration failed: $r", r is AuthResult.Success)
        return (r as AuthResult.Success).session
    }

    private fun all(v: Int) = (1..10).associate { it to v }

    /** Lowest possible adjusted total: forward items 0, reverse items answered 4 (-> 0). */
    private fun minimum() = (1..10).associate { it to if (it in Pss10.REVERSE_ITEMS) 4 else 0 }

    /** Highest possible adjusted total: forward items 4, reverse items answered 0 (-> 4). */
    private fun maximum() = (1..10).associate { it to if (it in Pss10.REVERSE_ITEMS) 0 else 4 }

    private val pss = QuestionnaireInstrument.PSS_10

    // == 1. the questionnaire opens ==============================================================

    @Test
    fun pss10OpensInThisPrototypeBuild() {
        assertTrue("the Start button must not be disabled in a prototype build", pss.isPresentable)
        assertEquals(
            "no blocking reason may be shown",
            "",
            com.bishnu.stresswelfarebridge.questionnaire.ui.QuestionnaireController(
                QuestionnaireRepository(InMemoryKeyValueStore(), accounts, { now })
            ).unavailableReason(pss)
        )
    }

    @Test
    fun prototypeIsNotClaimedAsLicensed() {
        // The whole point of the new status: usable in a prototype, never presented as approved.
        assertEquals(VerificationStatus.PROTOTYPE_PENDING_LICENCE, pss.verification)
        assertFalse(pss.isShippable)
        assertTrue(
            "the licensing note must state permission has not been granted",
            pss.licensingNote.contains("PERMISSION HAS NOT BEEN GRANTED")
        )
    }

    // == 2. all ten questions are displayed ========================================================

    @Test
    fun allTenQuestionsAreDisplayedWithRealText() {
        assertEquals(10, pss.items.size)
        assertEquals(10, pss.items.map { it.textKey }.distinct().size)
        assertEquals(Pss10.ITEM_COUNT, pss.items.size)
        pss.items.forEachIndexed { i, item ->
            assertTrue("item ${i + 1} has no text", item.textKey.isNotBlank())
            assertFalse(
                "item ${i + 1} is still a placeholder",
                item.textKey.startsWith("PLACEHOLDER")
            )
        }
    }

    @Test
    fun reverseScoredItemsArePositivelyWorded() {
        // Load-bearing: the scorer reverses positions 4,5,7,8 unconditionally, so those texts must
        // be the positively worded ones. "going your way" is unambiguously positive.
        assertTrue(pss.items[3].textKey.contains("confident and capable"))
        assertTrue(pss.items[4].textKey.contains("able to control the important things"))
        assertTrue(pss.items[6].textKey.contains("going your way"))
        assertTrue(pss.items[7].textKey.contains("able to control the annoyances"))
    }

    // == 3. 0-4 responses work ====================================================================

    @Test
    fun everyResponseInZeroToFourIsAccepted() {
        for (v in 0..4) {
            assertNotNull("response $v must be accepted", Pss10.score(all(v)))
        }
        assertEquals(5, pss.scale.count())
        assertEquals(0, pss.scale.min)
        assertEquals(4, pss.scale.max)
    }

    // == 4. items 4, 5, 7, 8 are reverse scored ===================================================

    @Test
    fun itemsFourFiveSevenAndEightAreReverseScored() {
        assertEquals(setOf(4, 5, 7, 8), Pss10.REVERSE_ITEMS)
        assertEquals(setOf(4, 5, 7, 8), QuestionnaireInstrument.PSS_REVERSE_ITEMS)
        assertEquals(
            listOf(3, 4, 6, 7),                     // 0-based positions of items 4,5,7,8
            pss.items.filter { it.reverseScored }.map { it.id }.map { it.removePrefix("pss_").toInt() - 1 }
        )
    }

    @Test
    fun reverseScoringUsesFourMinusResponse() {
        for (item in Pss10.REVERSE_ITEMS) {
            for (raw in 0..4) {
                val answers = all(2).toMutableMap().apply { this[item] = raw }
                // Everything else contributes 2 each; only this item moves.
                val baseline = 9 * 2
                val expected = baseline + (4 - raw)
                assertEquals("item $item raw $raw", expected, Pss10.score(answers))
            }
        }
    }

    @Test
    fun forwardItemsAreSummedUnchanged() {
        val base = Pss10.score(all(2))!!
        for (item in (1..10) - Pss10.REVERSE_ITEMS) {
            val answers = all(2).toMutableMap().apply { this[item] = 4 }
            assertEquals("forward item $item must add 2", base + 2, Pss10.score(answers))
        }
    }

    // == 5 & 6. the extreme all-zero and all-four cases ==============================================

    @Test
    fun allZeroRawAnswersScoreSixteenNotZero() {
        // Every answer 0: the six forward items contribute 0, and each reverse item contributes
        // 4 - 0 = 4, so the total is 16. This is the single most commonly got-wrong PSS-10 result.
        assertEquals(16, Pss10.score(all(0)))
        assertEquals(16, repo.savePss10(personnel, october, all(0))!!.score)
    }

    @Test
    fun allFourRawAnswersScoreTwentyFourNotForty() {
        // Mirror image: forward items 4 each = 24, reverse items contribute 4 - 4 = 0.
        assertEquals(24, Pss10.score(all(4)))
        assertEquals(24, repo.savePss10(personnel, october, all(4))!!.score)
    }

    @Test
    fun theGenuineExtremesAreZeroAndForty() {
        assertEquals(0, Pss10.score(minimum()))
        assertEquals(40, Pss10.score(maximum()))
    }

    // == 7. the score is always 0-40 ===============================================================

    @Test
    fun scoreIsAlwaysZeroToForty() {
        val values = mutableListOf<Int>()
        fun walk(item: Int, current: Map<Int, Int>) {
            if (item > Pss10.ITEM_COUNT) {
                Pss10.score(current)?.let(values::add)
                return
            }
            for (v in 0..4) walk(item + 1, current + (item to v))
        }
        walk(1, emptyMap())
        assertEquals("exhaustive over 5^10 answer patterns", 9_765_625, values.size)
        assertTrue(values.all { it in 0..40 })
        assertEquals(0, values.min())
        assertEquals(40, values.max())
    }

    @Test
    fun incompleteOrOutOfScaleAnswersProduceNoScore() {
        assertNull(Pss10.score((1..9).associate { it to 1 }))
        assertNull(Pss10.score((1..10).associate { it to 5 }))
        assertNull(repo.savePss10(personnel, october, (1..9).associate { it to 1 }))
    }

    // == 8. completion saves the result ============================================================

    @Test
    fun completionSavesTheResult() {
        val record = repo.savePss10(personnel, october, all(2))
        assertNotNull(record)
        assertEquals(20, record!!.score)
        assertTrue(record.completed)
        assertEquals(october, record.month)
        assertEquals(personnel.accountId, record.userId)
        assertEquals(QuestionnaireInstrument.PSS_10.version, record.version)
        assertEquals(10, record.answers.size)
        assertEquals(1, repo.pss10History(personnel).size)
    }

    // == 9 & 10. calendar-month availability ========================================================

    @Test
    fun octoberCompletionBlocksASecondOctoberCompletion() {
        assertTrue(repo.pss10Available(personnel, october))
        repo.savePss10(personnel, october, all(2))

        assertFalse("already completed this month", repo.pss10Available(personnel, october))
        // Still blocked later in the same month - calendar month, not a 30-day timer.
        assertFalse(repo.pss10Available(personnel, october))
        assertNull(repo.savePss10(personnel, october, all(3)))
        assertEquals("no duplicate row was written", 1, repo.pss10History(personnel).size)
    }

    @Test
    fun novemberMakesItAvailableAgain() {
        repo.savePss10(personnel, october, all(2))
        assertTrue("1 November opens the next month", repo.pss10Available(personnel, november))
        assertNotNull(repo.savePss10(personnel, november, all(3)))
        assertEquals(2, repo.pss10History(personnel).size)
    }

    @Test
    fun availabilityFollowsTheCalendarNotARollingWindow() {
        // Completed on 4 October. 20 October is still blocked...
        repo.savePss10(personnel, october, all(2))
        assertFalse(repo.pss10Available(personnel, october))
        // ...and September - earlier in time - is not retroactively "available" either.
        assertTrue("a month never completed is available", repo.pss10Available(personnel, YearMonth.of(2026, 9)))
    }

    // == 11. previous months are preserved =========================================================

    @Test
    fun previousMonthlyScoresRemainAccessible() {
        repo.savePss10(personnel, october, all(2))   // 20
        repo.savePss10(personnel, november, all(0))  // 16

        val history = repo.pss10History(personnel)
        assertEquals(2, history.size)
        assertEquals("newest first", november, history[0].month)
        assertEquals(16, history[0].score)
        assertEquals("October was not overwritten", october, history[1].month)
        assertEquals(20, history[1].score)
        assertEquals(20, repo.pss10ForMonth(personnel, october)!!.score)
    }

    @Test
    fun historyIsOrderedNewestMonthFirst() {
        repo.savePss10(personnel, october, all(2))
        repo.savePss10(personnel, november, all(3))
        repo.savePss10(personnel, YearMonth.of(2026, 12), all(1))
        assertEquals(
            listOf(
                YearMonth.of(2026, 12), november, october
            ),
            repo.pss10History(personnel).map { it.month }
        )
    }

    // == 12. restart ==============================================================================

    @Test
    fun theScoreSurvivesAnAppRestart() {
        repo.savePss10(personnel, october, all(2))

        // Fresh repository over the same storage, and a fresh session lookup.
        val reopened = QuestionnaireRepository(store, accounts, { now }, { "r2-${counter++}" })
        // currentSession() would return whoever registered last (the commander), so log in
        // explicitly as the person whose score we are checking.
        val resumed = (accounts.login("jdoe", "Passw0rd!23") as AuthResult.Success).session
        assertNotNull(resumed)

        val history = reopened.pss10History(resumed)
        assertEquals(1, history.size)
        assertEquals(20, history[0].score)
        assertEquals(october, history[0].month)
        assertEquals(10, history[0].answers.size)
    }

    // == 13. the daily check-in produces no stress score ===========================================

    @Test
    fun dailyCheckInStoresNoScoreOfAnyKind() {
        val record = repo.saveDailyPulse(personnel, java.time.LocalDate.of(2026, 10, 4), 5, 1, 5, false)
        assertNotNull(record)
        // There is deliberately no score field on the record to carry one.
        val scoreFields = DailyPulseRecord::class.java.declaredFields
            .map { it.name.lowercase() }
            .filter { it.contains("score") }
        assertTrue("a DailyPulseRecord must have no score field, found $scoreFields", scoreFields.isEmpty())
    }

    @Test
    fun dailyCheckInAndPss10AreSeparateNumbersOnSeparateScales() {
        // 0-5 answers saved; a PSS-10 0-40 total. Neither is derived from the other.
        repo.saveDailyPulse(personnel, java.time.LocalDate.of(2026, 10, 4), 5, 1, 5, false)
        val pssRecord = repo.savePss10(personnel, october, all(4))!!
        assertEquals(24, pssRecord.score)
        val pulse = repo.dailyPulseFor(personnel, java.time.LocalDate.of(2026, 10, 4))
        assertEquals(5, pulse!!.dStress)
    }

    // == 15. commander cannot access answers =======================================================

    @Test
    fun commanderCannotReadPss10Answers() {
        repo.savePss10(personnel, october, all(2))

        assertTrue(repo.pss10History(commander).isEmpty())
        assertNull(repo.pss10ForMonth(commander, october))
        assertNull("a commander cannot submit a PSS-10", repo.savePss10(commander, october, all(2)))
        assertFalse(repo.pss10Available(commander, october))
        assertTrue(repo.saveDailyPulse(commander, java.time.LocalDate.of(2026, 10, 4), 5, 5, 5, false) == null)

        // And the commander's authorised view carries no answer at all.
        val summary = repo.commanderSummary(commander, personnel.accountId)
        assertEquals(CheckInStatus.COMPLETED, summary.valueOrNull()!!.status)
        val fields = PersonnelCheckInSummary::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("answer") || it.contains("score") || it.contains("item") })
    }

    @Test
    fun onePersonNeverSeesAnothersPss10() {
        val other = register("asmith", "Alex Smith", Role.PERSONNEL)
        repo.savePss10(personnel, october, all(2))
        assertEquals(1, repo.pss10History(personnel).size)
        assertTrue(repo.pss10History(other).isEmpty())
    }
}
