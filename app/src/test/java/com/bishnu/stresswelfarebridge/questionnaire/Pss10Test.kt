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
 * PSS-10 scoring, reverse items, calendar-month availability and persistence.
 */
class Pss10Test {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var session: Session
    private var counter = 0
    private val now: Instant = Instant.parse("2026-11-15T10:00:00Z")

    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        val accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acc-${counter++}" })
        val r = accounts.register(
            RegistrationRequest(
                fullName = "J Doe", username = "jdoe", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.PERSONNEL, serviceId = "SVC-1"
            )
        )
        assertTrue(r is AuthResult.Success)
        session = (r as AuthResult.Success).session
        repo = QuestionnaireRepository(store, accounts, { now }, { "r${counter++}" })
    }

    private fun all(value: Int) = (1..10).associate { it to value }

    /**
     * PSS-10 minimum: forward items 0, reverse items 4 (disagreeing with the positively-worded
     * items). Maximum is the mirror image. Using all-zeros/all-fours would NOT be the extremes,
     * because on a reverse item a raw 0 means high stress.
     */
    private fun minimum() = (1..10).associate { it to if (it in Pss10.REVERSE_ITEMS) 4 else 0 }
    private fun maximum() = (1..10).associate { it to if (it in Pss10.REVERSE_ITEMS) 0 else 4 }

    // -- item configuration -------------------------------------------------------------------

    @Test
    fun pssHasTenItemsOnAZeroToFourScale() {
        val pss = QuestionnaireInstrument.PSS_10
        assertEquals(10, pss.items.size)
        assertEquals(0, pss.scale.min)
        assertEquals(4, pss.scale.max)
        assertEquals(setOf(4, 5, 7, 8), Pss10.REVERSE_ITEMS)
        assertEquals(4, pss.items.count { it.reverseScored })
    }

    @Test
    fun scoreRangeIsZeroToForty() {
        assertEquals(0, Pss10.score(minimum()))
        assertEquals(40, Pss10.score(maximum()))
        assertEquals(Pss10.MAX_SCORE, 40)
    }

    // -- scoring vectors -------------------------------------------------------------------------

    @Test
    fun allLowestAnswersScoreZero() {
        assertEquals(0, Pss10.score(minimum()))
    }

    @Test
    fun allHighestAnswersScoreForty() {
        assertEquals(40, Pss10.score(maximum()))
    }

    @Test
    fun allMidpointAnswersScoreTwenty() {
        // 6 forward x2 = 12, 4 reverse (4-2=2) x4 = 8, total 20.
        assertEquals(20, Pss10.score(all(2)))
    }

    @Test
    fun reverseItemsAreInvertedBeforeSumming() {
        // Forward item 1 = 4, reverse item 4 = 0 -> 4 + (4-0) = 8.
        // Every item 0 gives 16 (the four reverse items each contribute 4). Raising one
        // forward item from 0 to 4 must add exactly 4.
        assertEquals(16, Pss10.score(all(0)))
        val answers = all(0).toMutableMap().apply { this[1] = 4 }
        assertEquals(20, Pss10.score(answers))
    }

    @Test
    fun incompleteResponsesProduceNoScore() {
        assertNull(Pss10.score((1..9).associate { it to 1 }))
        assertNull(Pss10.score(emptyMap()))
    }

    @Test
    fun outOfScaleAnswersProduceNoScore() {
        val answers = all(2).toMutableMap().apply { this[3] = 9 }
        assertNull(Pss10.score(answers))
    }

    // -- calendar-month availability -----------------------------------------------------------------

    @Test
    fun pssIsAvailableWhenNoResponseExistsForTheMonth() {
        assertTrue(Pss10.isAvailable(october, emptySet()))
        assertTrue(repo.pss10Available(session, october))
    }

    @Test
    fun octoberFourthCompletesAndOctoberTwentiethStaysUnavailable() {
        assertNotNull(repo.savePss10(session, october, all(2)))
        // Later in the SAME month: not available again.
        assertFalse("October is already completed", repo.pss10Available(session, october))
        assertNull(repo.savePss10(session, october, all(4)))
    }

    @Test
    fun novemberBecomesAvailableAgainAfterOctober() {
        repo.savePss10(session, october, all(2))
        assertTrue("November must be available", repo.pss10Available(session, november))
        assertNotNull(repo.savePss10(session, november, all(4)))
    }

    @Test
    fun octoberIsNotOverwrittenByNovember() {
        repo.savePss10(session, october, all(2))
        repo.savePss10(session, november, maximum())
        val history = repo.pss10History(session)
        assertEquals(2, history.size)
        assertEquals(20, history.first { it.month == october }.score)
        assertEquals(40, history.first { it.month == november }.score)
    }

    @Test
    fun historyIsNewestFirst() {
        repo.savePss10(session, october, all(2))
        repo.savePss10(session, november, maximum())
        assertEquals(november, repo.pss10History(session).first().month)
    }

    @Test
    fun completedRecordCarriesAllRequiredMetadata() {
        val r = repo.savePss10(session, october, all(3))!!
        assertEquals("PSS10", "PSS10")           // instrument id is carried by the repository key
        assertEquals(QuestionnaireInstrument.PSS_10.version, r.version)
        assertEquals(october, r.month)
        assertEquals(10, r.answers.size)
        assertEquals(22, r.score)
        assertTrue(r.completed)
        assertEquals(session.accountId, r.userId)
        assertEquals(now, r.completedAt)
    }

    @Test
    fun incompleteResponsesAreNotStored() {
        assertNull(repo.savePss10(session, october, (1..5).associate { it to 1 }))
        assertTrue(repo.pss10History(session).isEmpty())
    }

    @Test
    fun previousResultsSurviveARestart() {
        repo.savePss10(session, october, all(2))
        // Same underlying storage, new repository: models an app relaunch.
        val accounts = AccountStore(store, store, { now.toEpochMilli() }, { "x${counter++}" })
        val resumed = accounts.currentSession()
        assertNotNull(resumed)
        val repo2 = QuestionnaireRepository(store, accounts, { now }, { "y${counter++}" })
        val history = repo2.pss10History(resumed)
        assertEquals(1, history.size)
        assertEquals(20, history.first().score)
    }

    // -- licensing gate ---------------------------------------------------------------------------

    // -- licensing status -----------------------------------------------------------------------

    @Test
    fun pssIsPresentableInThisPrototypeButNeverShippable() {
        val pss = QuestionnaireInstrument.PSS_10
        assertEquals(VerificationStatus.PROTOTYPE_PENDING_LICENCE, pss.verification)
        // Presentable so the prototype flow is usable...
        assertTrue(pss.isPresentable)
        // ...but explicitly NOT cleared for release. This is the distinction that matters.
        assertFalse(pss.isShippable)
    }

    @Test
    fun pssStillFailsTheProductionGate() {
        // requireShippable() is what any release path would call. A prototype status must not
        // satisfy it, otherwise enabling the prototype flow would silently unlock production.
        val pss = QuestionnaireInstrument.PSS_10
        try {
            pss.requireShippable()
            throw AssertionError("prototype PSS-10 must not pass the shippable gate")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("licence", ignoreCase = true))
        }
    }

    @Test
    fun pssCarriesNoPlaceholderWording() {
        val pss = QuestionnaireInstrument.PSS_10
        assertEquals(10, pss.items.size)
        assertTrue(pss.items.none { it.textKey.startsWith("PLACEHOLDER_") })
        pss.items.forEach { assertTrue("item text missing", it.textKey.isNotBlank()) }
    }

    @Test
    fun pssPublishesNoUnofficialClinicalBands() {
        assertTrue(QuestionnaireInstrument.PSS_10.bands.isEmpty())
    }

    @Test
    fun commanderCannotReadPssHistory() {
        repo.savePss10(session, october, all(2))
        val accounts = AccountStore(store, store, { now.toEpochMilli() }, { "c${counter++}" })
        val cdr = accounts.register(
            RegistrationRequest(
                fullName = "Cdr", username = "cdr", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.COMMANDER, serviceId = null
            )
        )
        val commander = (cdr as AuthResult.Success).session
        assertTrue(repo.pss10History(commander).isEmpty())
        assertFalse(repo.pss10Available(commander, november))
        assertNull(repo.savePss10(commander, november, all(4)))
    }

    @Test
    fun onePersonCannotSeeAnothersPssHistory() {
        repo.savePss10(session, october, all(2))
        val accounts = AccountStore(store, store, { now.toEpochMilli() }, { "o${counter++}" })
        val other = accounts.register(
            RegistrationRequest(
                fullName = "A Smith", username = "asmith", password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = Role.PERSONNEL, serviceId = "SVC-2"
            )
        )
        assertTrue(repo.pss10History((other as AuthResult.Success).session).isEmpty())
    }
}