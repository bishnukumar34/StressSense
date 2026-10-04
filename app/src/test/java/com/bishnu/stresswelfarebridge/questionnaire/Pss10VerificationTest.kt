package com.bishnu.stresswelfarebridge.questionnaire

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.YearMonth

/**
 * Reverification of the PSS-10 instrument against the authoritative source.
 *
 * Authoritative source consulted: the Addiction Research Center, University of Wisconsin-Madison,
 * which hosts the instrument's scoring code and states the rules this implementation follows -
 * "PSS scores can be made by summing across all 10 items. Scores can range from 0 to 40... The
 * following items are reverse coded: 4, 5, 7, 8." The same page states the scale measures "the
 * past month", and it states plainly that the centre "do[es] not have copyright authority over
 * these measures and cannot grant permission for use".
 *
 * ### What is verified here and what is not
 * **Verified by assertion:** item count, item ids and their order, the reverse-scored set, the
 * 0-4 response scale, the 0-40 total, the monthly gating and the monthly history.
 *
 * **NOT verified here, and deliberately not asserted as verified:** the exact wording of the ten
 * items. Permission has not been granted, so no authorised text is present in this repository. These
 * tests therefore assert the *structural* properties above and, separately, assert that the wording
 * has not been silently edited into a fabricated or duplicated form. The wording itself must be
 * checked by a human against the authorised source before any deployment - see
 * [QuestionnaireInstrument.PSS_10].
 */
class Pss10VerificationTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var session: com.bishnu.stresswelfarebridge.auth.Session
    private var counter = 0

    private val now: Instant = Instant.parse("2026-11-15T10:00:00Z")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acc-${counter++}" })
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

    private fun pss() = QuestionnaireInstrument.PSS_10
    private fun all(v: Int) = (1..10).associate { it to v }

    // == 1. exactly ten items ====================================================================

    @Test
    fun thereAreExactlyTenItems() {
        assertEquals(10, pss().items.size)
        assertEquals(10, Pss10.ITEM_COUNT)
        assertEquals(10, QuestionnaireInstrument.PSS_ITEM_TEXTS.size)
    }

    // == 2. item ids and order ====================================================================

    @Test
    fun itemIdsRunPss1ToPss10InOrder() {
        assertEquals((1..10).map { "pss_$it" }, pss().items.map { it.id })
    }

    @Test
    fun eachItemTextMapsToItsOwnScoringItemInOrder() {
        // Item n in presentation order must carry PSS_ITEM_TEXTS[n-1] and be scored as item n.
        pss().items.forEachIndexed { index, item ->
            assertEquals(
                "item ${index + 1} must use the text at position ${index + 1}",
                QuestionnaireInstrument.PSS_ITEM_TEXTS[index],
                item.textKey
            )
        }
    }

    @Test
    fun theFormRendersItemsInOrderAndStoresAnswersByItemNumber() {
        // The UI must map the displayed position to the same item number the scorer reads.
        val src = File("src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/Pss10Screen.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/bishnu/stresswelfarebridge/questionnaire/ui/Pss10Screen.kt")
        val text = src.readText()
        assertTrue("items are rendered by index", text.contains("items(definition.items.size)"))
        assertTrue("the displayed number is the item number", text.contains("val number = i + 1"))
        assertTrue("answers are written against that number", text.contains("answers[number] == value"))
    }

    // == wording integrity (not authority) =========================================================

    @Test
    fun noItemTextIsBlankDuplicatedOrAPlaceholder() {
        val texts = QuestionnaireInstrument.PSS_ITEM_TEXTS
        texts.forEachIndexed { index, text ->
            assertTrue("item ${index + 1} must have text", text.isNotBlank())
            assertFalse("item ${index + 1} must not be a placeholder", text.contains("PLACEHOLDER"))
        }
        assertEquals(
            "no item may be a duplicate of another",
            texts.size,
            texts.distinct().size
        )
    }

    @Test
    fun everyItemAsksAboutTheLastMonth() {
        QuestionnaireInstrument.PSS_ITEM_TEXTS.forEachIndexed { index, text ->
            assertTrue(
                "item ${index + 1} must reference the last month",
                text.contains("In the last month")
            )
            assertTrue(
                "item ${index + 1} must ask how often",
                text.contains("how often")
            )
        }
    }

    @Test
    fun reverseScoredItemsAreThePositivelyWordedOnes() {
        // The load-bearing property: positions 4, 5, 7 and 8 are the positively-worded items, and the
        // scorer reverses exactly those. If the text were ever reordered without updating the
        // reverse set, the total would still land in 0-40 while measuring the wrong thing.
        //
        // Matching is done on the clause after "you felt", and "unable to control" is explicitly
        // excluded: forward item 3 reads "unable to control the important things in your life",
        // which contains the letters of reverse item 5's "able to control" while meaning the
        // opposite. Matching on the raw substring would misclassify item 3 as positively worded.
        fun positivelyWorded(text: String): Boolean {
            val body = text.substringAfter("you felt", "")
            val canControl = body.contains("able to control") && !body.contains("unable to control")
            return body.contains("confident") || canControl || body.contains("going your way")
        }

        listOf(4, 5, 7, 8).forEach { n ->
            assertTrue(
                "reverse item $n should read positively: " + QuestionnaireInstrument.PSS_ITEM_TEXTS[n - 1],
                positivelyWorded(QuestionnaireInstrument.PSS_ITEM_TEXTS[n - 1])
            )
        }
        listOf(1, 2, 3, 6, 9, 10).forEach { n ->
            assertFalse(
                "forward item $n should not read positively: " + QuestionnaireInstrument.PSS_ITEM_TEXTS[n - 1],
                positivelyWorded(QuestionnaireInstrument.PSS_ITEM_TEXTS[n - 1])
            )
        }
    }

    // == 3. response options are 0..4 =============================================================

    @Test
    fun theResponseScaleIsZeroToFour() {
        assertEquals(0, pss().scale.min)
        assertEquals(4, pss().scale.max)
        assertEquals(0, Pss10.SCALE_MIN)
        assertEquals(4, Pss10.SCALE_MAX)
        assertEquals(5, pss().scale.count())
        assertEquals("Never", pss().scale.lowLabel)
        assertEquals("Very often", pss().scale.highLabel)
    }

    @Test
    fun everyResponseValueOnTheScaleIsAcceptedByTheScorer() {
        for (v in 0..4) {
            assertNotNull("value $v must be scorable", Pss10.score(all(v)))
        }
    }

    // == 5/6/7. reverse scoring, summation, 0-40 ===================================================

    @Test
    fun reverseItemsAreFourFiveSevenAndEight() {
        assertEquals(setOf(4, 5, 7, 8), Pss10.REVERSE_ITEMS)
        assertEquals(setOf(4, 5, 7, 8), QuestionnaireInstrument.PSS_REVERSE_ITEMS)
        assertEquals(
            "exactly the four items flagged reverseScored",
            listOf(4, 5, 7, 8),
            pss().items.filter { it.reverseScored }.map { it.id.removePrefix("pss_").toInt() }
        )
    }

    @Test
    fun aReverseItemIsInvertedRatherThanAddedDirectly() {
        // Forward items all 0; reverse item 4 answered 0 means high stress, so it contributes 4.
        val answers = all(0).toMutableMap()
        assertEquals("four reverse items x 4", 16, Pss10.score(answers))
        // Raising reverse item 4 from 0 to 4 must LOWER the total by 4.
        answers[4] = 4
        assertEquals(12, Pss10.score(answers))
    }

    @Test
    fun theTotalIsTheSumOfTheTenScoredItems() {
        var expected = 0
        val answers = (1..10).associate { n ->
            val raw = n % 5
            expected += if (n in Pss10.REVERSE_ITEMS) 4 - raw else raw
            n to raw
        }
        assertEquals(expected, Pss10.score(answers))
    }

    @Test
    fun theScoreRangeIsZeroToForty() {
        val minimum = (1..10).associate { n -> n to if (n in Pss10.REVERSE_ITEMS) 4 else 0 }
        val maximum = (1..10).associate { n -> n to if (n in Pss10.REVERSE_ITEMS) 0 else 4 }
        assertEquals(0, Pss10.score(minimum))
        assertEquals(40, Pss10.score(maximum))
        assertEquals(40, Pss10.MAX_SCORE)
        // Any legal answer set must land inside the range.
        val randomish = listOf(
            (1..10).associate { it to (it % 5) },
            (1..10).associate { it to ((it * 3) % 5) },
            (1..10).associate { it to 0 },
            (1..10).associate { it to 4 }
        )
        randomish.forEach { answers ->
            val score = Pss10.score(answers)!!
            assertTrue("score $score out of range", score in 0..40)
        }
    }

    @Test
    fun anIncompleteResponseScoresNothingRatherThanAPartialTotal() {
        assertNull(Pss10.score((1..9).associate { it to 1 }))
        assertNull(Pss10.score(emptyMap()))
        assertNull(Pss10.score(all(2).toMutableMap().apply { remove(7) }))
    }

    @Test
    fun anOutOfScaleResponseScoresNothing() {
        assertNull(Pss10.score(all(2).toMutableMap().apply { this[3] = 5 }))
        assertNull(Pss10.score(all(2).toMutableMap().apply { this[3] = -1 }))
    }

    // == 8/9/10. monthly behaviour ================================================================

    @Test
    fun pss10IsAMonthlyInstrumentOnly() {
        assertEquals(
            "PSS-10 must only ever trigger monthly",
            setOf(TriggerType.MONTHLY),
            pss().triggerTypes
        )
        assertFalse(pss().triggerTypes.contains(TriggerType.DAILY))
    }

    @Test
    fun oneCompletionPerCalendarMonthAndHistoryIntact() {
        val may = YearMonth.of(2026, 5)
        val june = YearMonth.of(2026, 6)
        val july = YearMonth.of(2026, 7)

        // The extremes are NOT all-zeros and all-fours: on a reverse item a raw 4 means low stress,
        // so the maximum answers every forward item 4 and every reverse item 0.
        val minAnswers = (1..10).associate { n -> n to if (n in Pss10.REVERSE_ITEMS) 4 else 0 }
        val maxAnswers = (1..10).associate { n -> n to if (n in Pss10.REVERSE_ITEMS) 0 else 4 }

        assertNotNull(repo.savePss10(session, may, all(2)))
        assertFalse("May is now completed", Pss10.isAvailable(may, setOf(may)))
        assertTrue("June is a new month", Pss10.isAvailable(june, setOf(may)))
        assertNotNull(repo.savePss10(session, june, maxAnswers))
        assertNotNull(repo.savePss10(session, july, minAnswers))

        val history = repo.pss10History(session)
        assertEquals("every completed month is kept", 3, history.size)
        assertEquals(20, history.first { it.month == may }.score)
        assertEquals(40, history.first { it.month == june }.score)
        assertEquals(0, history.first { it.month == july }.score)
    }

    @Test
    fun completingPss10CreatesNoDailyStressScore() {
        repo.savePss10(session, YearMonth.of(2026, 5), all(2))
        repo.savePss10(session, YearMonth.of(2026, 6), all(4))
        // Nothing daily was written: no pulse record exists at all.
        assertTrue(repo.dailyPulses(session).isEmpty())
        assertFalse(repo.hasPulseFor(session, java.time.LocalDate.of(2026, 5, 15)))
        // And the Daily Check-in model still carries no score field to receive one.
        val fields = DailyPulseRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("score") })
    }

    @Test
    fun theMonthlyHistorySurvivesAnAppRelaunch() {
        repo.savePss10(session, YearMonth.of(2026, 5), all(2))
        val resumed = AccountStore(store, store, { now.toEpochMilli() }, { "z${counter++}" })
            .currentSession()
        assertNotNull(resumed)
        val afterRelaunch = QuestionnaireRepository(store, accounts, { now }, { "y${counter++}" })
            .pss10History(resumed)
        assertEquals(1, afterRelaunch.size)
        assertEquals(20, afterRelaunch.first().score)
        assertEquals(YearMonth.of(2026, 5), afterRelaunch.first().month)
    }

    // == licensing status, reported honestly ======================================================

    @Test
    fun theInstrumentIsStillNotClearedForProduction() {
        assertEquals(VerificationStatus.PROTOTYPE_PENDING_LICENCE, pss().verification)
        assertFalse(pss().isShippable)
        assertTrue(pss().licensingNote.contains("PERMISSION HAS NOT BEEN GRANTED"))
    }

    @Test
    fun noUnofficialClinicalBandsArePublished() {
        // The instrument authors publish no cut-offs, so the graph colour is relative to the user's
        // own history and no severity band may be presented as official.
        assertTrue(pss().bands.isEmpty())
        assertTrue(pss().subscales.keys.contains("perceived_stress"))
    }
}
