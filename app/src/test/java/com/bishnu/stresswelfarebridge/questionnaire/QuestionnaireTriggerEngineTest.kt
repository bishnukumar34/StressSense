package com.bishnu.stresswelfarebridge.questionnaire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Trigger and episode logic.
 *
 * These are the most important tests in the feature. The failure mode being guarded against is
 * showing a person a psychological stress questionnaire because of a single elevated sensor day, so
 * most cases below assert that nothing *doesn't* happen.
 */
class QuestionnaireTriggerEngineTest {

    private val engine = QuestionnaireTriggerEngine()
    private val now: Instant = Instant.parse("2026-03-01T12:00:00Z")
    private val day0: LocalDate = LocalDate.parse("2026-02-01")

    /** One usable day: good quality, no exercise, awake, not on night duty. */
    private fun day(offset: Int, score: Int) = DailySensorDay(
        date = day0.plusDays(offset.toLong()),
        stressSensor = score,
        quality = 0.9
    )

    private fun episode(
        openedDaysAgo: Int = 1,
        level: Int = 70,
        offerMade: Boolean = false
    ) = StressEpisode(
        id = "ep1",
        userId = "u1",
        openedAt = now.minus(Duration.ofDays(openedDaysAgo.toLong())),
        closedAt = null,
        lastTriggerLevel = level,
        offerMade = offerMade
    )

    // -- the headline requirement: persistence, not a single spike --------------------------------

    @Test
    fun sixtyFiveThenFiftyThenSixtyDoesNotTrigger() {
        val d = engine.evaluate(listOf(day(0, 65), day(1, 50), day(2, 60)), null, null, now)
        assertFalse("a single elevated day must not trigger", d.shouldOffer)
        assertEquals(EpisodeState.NORMAL, d.state)
    }

    @Test
    fun sustainedHighSequenceTriggers() {
        val d = engine.evaluate(listOf(day(0, 68), day(1, 70), day(2, 66)), null, null, now)
        assertTrue(d.shouldOffer)
        assertEquals(EpisodeState.NORMAL, d.state)
    }

    @Test
    fun singleHighDayAloneNeverTriggers() {
        assertFalse(engine.evaluate(listOf(day(0, 65)), null, null, now).shouldOffer)
    }

    @Test
    fun threeHighDaysWithinFiveDaysTriggersEvenIfTheMeanIsLow() {
        // Clustered elevated days must trigger on their own, independent of the window mean.
        val d = engine.evaluate(
            listOf(day(0, 66), day(1, 50), day(2, 66), day(3, 50), day(4, 66)), null, null, now
        )
        assertTrue(d.shouldOffer)
    }

    @Test
    fun exactlyTwoHighDaysDoNotTrigger() {
        val d = engine.evaluate(
            listOf(day(0, 70), day(1, 50), day(2, 50), day(3, 70)), null, null, now
        )
        assertFalse(d.shouldOffer)
    }

    // -- context gating -----------------------------------------------------------------------------

    @Test
    fun lowQualityDaysAreExcluded() {
        val bad = day(2, 80).copy(quality = 0.1)
        val d = engine.evaluate(listOf(day(0, 68), day(1, 70), bad), null, null, now)
        assertFalse("a poor acquisition must not drive a questionnaire", d.shouldOffer)
        assertEquals(1, d.excludedDays)
    }

    @Test
    fun exerciseDaysAreExcluded() {
        val exercised = day(2, 80).copy(activityFlag = true)
        val d = engine.evaluate(listOf(day(0, 68), day(1, 70), exercised), null, null, now)
        assertFalse("exercise explains a rise; it is not psychological strain", d.shouldOffer)
        assertEquals(1, d.excludedDays)
    }

    @Test
    fun sleepingHourMeasurementsAreExcluded() {
        val asleep = day(2, 80).copy(duringSleep = true)
        val d = engine.evaluate(listOf(day(0, 68), day(1, 70), asleep), null, null, now)
        assertFalse(d.shouldOffer)
        assertEquals(1, d.excludedDays)
    }
@Test
    fun unknownQualityIsNotTreatedAsBad() {
        val unknown = day(2, 80).copy(quality = null)
        val d = engine.evaluate(listOf(day(0, 68), day(1, 70), unknown), null, null, now)
        assertTrue("absent quality is not evidence of a bad measurement", d.shouldOffer)
    }

    // -- cooldown ----------------------------------------------------------------------------------

    @Test
    fun cooldownSuppressesASecondTrigger() {
        val recent = now.minus(Duration.ofDays(3))
        val d = engine.evaluate(listOf(day(0, 68), day(1, 70), day(2, 66)), null, recent, now)
        assertFalse(d.shouldOffer)
        assertTrue(d.reason.contains("cooldown"))
    }

    @Test
    fun cooldownExpiryAllowsANewTrigger() {
        val old = now.minus(Duration.ofDays(20))
        assertTrue(engine.evaluate(listOf(day(0, 68), day(1, 70), day(2, 66)), null, old, now).shouldOffer)
    }

    @Test
    fun fourteenDayCooldownIsTheDefault() {
        assertEquals(14, TriggerPolicy.COOLDOWN_DAYS)
        assertEquals(14, TriggerPolicy.DEFAULT.cooldownDays)
    }

    // -- episode open / close -------------------------------------------------------------------------

    @Test
    fun episodeRemainsOpenWhileAnyDayIsStillElevated() {
        // 60 is above the 55 exit threshold, so the episode must stay open.
        val d = engine.evaluate(
            listOf(day(0, 60), day(1, 52), day(2, 54)), episode(level = 70), null, now
        )
        assertEquals(EpisodeState.EPISODE_OPEN, d.state)
        assertFalse(d.shouldOffer)
    }

    @Test
    fun episodeClosesWhenAllThreeDaysSettleBelowFiftyFive() {
        val d = engine.evaluate(
            listOf(day(0, 52), day(1, 54), day(2, 50)), episode(level = 70), null, now
        )
        assertEquals(EpisodeState.NORMAL, d.state)
        assertFalse(d.shouldOffer)
        assertTrue(d.reason.contains("episode can close"))
    }

    @Test
    fun episodeDoesNotCloseOnASingleGoodDay() {
        val d = engine.evaluate(
            listOf(day(0, 60), day(1, 60), day(2, 40)), episode(level = 70), null, now
        )
        assertEquals(EpisodeState.EPISODE_OPEN, d.state)
    }

    // -- one offer per episode ------------------------------------------------------------------------

    @Test
    fun anEpisodeOffersAtMostOnce() {
        val ep = episode(level = 70, offerMade = true)
        // A big rise, but the offer was already made for this episode.
        val d = engine.evaluate(listOf(day(0, 90), day(1, 92), day(2, 95)), ep, null, now)
        assertFalse(d.shouldOffer)
        assertTrue(d.reason.contains("since the last offer"))
    }

    // -- re-escalation (sharp rise) -----------------------------------------------------------------

    @Test
    fun reEscalationRequiresASharpRiseOverThePreviousLevel() {
        // Opened at 70, so re-escalation needs mean >= 85.
        val d = engine.evaluate(
            listOf(day(0, 90), day(1, 90), day(2, 90)),
            episode(openedDaysAgo = 20, level = 70, offerMade = false), null, now
        )
        assertTrue("mean 90 >= 70 + 15", d.shouldOffer)
        assertTrue(d.reason.contains("Re-escalation"))
    }

    @Test
    fun modestRiseDoesNotReEscalate() {
        // Opened at 70; mean 80 is a real rise but not a 15-point one.
        val d = engine.evaluate(
            listOf(day(0, 80), day(1, 80), day(2, 80)),
            episode(openedDaysAgo = 20, level = 70, offerMade = false), null, now
        )
        assertFalse(d.shouldOffer)
    }

    @Test
    fun reEscalationStillRequiresTheCooldown() {
        val d = engine.evaluate(
            listOf(day(0, 95), day(1, 95), day(2, 95)),
            episode(openedDaysAgo = 2, level = 70, offerMade = false), null, now
        )
        assertFalse(d.shouldOffer)
        assertTrue(d.reason.contains("since the last offer"))
    }

    // -- configuration ---------------------------------------------------------------------------------

    @Test
    fun thresholdsMatchTheSpecification() {
        assertEquals(65, TriggerPolicy.ENTER_THRESHOLD)
        assertEquals(55, TriggerPolicy.EXIT_THRESHOLD)
        assertEquals(3, TriggerPolicy.WINDOW_DAYS)
        assertEquals(14, TriggerPolicy.COOLDOWN_DAYS)
        assertEquals(15, TriggerPolicy.SHARP_RISE)
        assertEquals(TriggerPolicy.ENTER_THRESHOLD, TriggerPolicy.DEFAULT.enterThreshold)
        assertEquals(TriggerPolicy.EXIT_THRESHOLD, TriggerPolicy.DEFAULT.exitThreshold)
    }

    @Test
    fun policyIsConfigurable() {
        val strict = QuestionnaireTriggerEngine(TriggerPolicy(enterThreshold = 50, exitThreshold = 40))
        assertTrue(
            "a stricter policy should trigger here",
            strict.evaluate(listOf(day(0, 55), day(1, 55), day(2, 55)), null, null, now).shouldOffer
        )
    }

    @Test
    fun triggerLevelIsTheWindowMean() {
        assertEquals(68, engine.triggerLevel(listOf(day(0, 68), day(1, 70), day(2, 66))))
    }

    @Test
    fun sleepingHoursAreDetected() {
        val nightTime = day0.atTime(3, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        assertTrue(engine.isSleepingTime(nightTime))
    }

    @Test
    fun emptyHistoryDoesNotTrigger() {
        assertFalse(engine.evaluate(emptyList(), null, null, now).shouldOffer)
    }
    // -- regression: cooldown is measured from the OFFER, not from the episode opening ---------------
    // These pin the review finding in task 18: previously the cooldown ran from `openedAt`, so an
    // episode that had been open for weeks could re-offer immediately, and the `offerMade` guard
    // made re-escalation unreachable dead code.

    private fun episodeWithOffer(
        openedDaysAgo: Int,
        offeredDaysAgo: Int?,
        level: Int,
        offerMade: Boolean
    ) = StressEpisode(
        id = "ep2",
        userId = "u1",
        openedAt = now.minus(Duration.ofDays(openedDaysAgo.toLong())),
        closedAt = null,
        lastTriggerLevel = level,
        offerMade = offerMade,
        lastOfferedAt = offeredDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) }
    )

    @Test
    fun longOpenEpisodeWithRecentOfferDoesNotReOffer() {
        // Opened 40 days ago but offered only 2 days ago: the cooldown must still apply.
        val ep = episodeWithOffer(openedDaysAgo = 40, offeredDaysAgo = 2, level = 70, offerMade = true)
        val d = engine.evaluate(listOf(day(0, 95), day(1, 95), day(2, 95)), ep, null, now)
        assertFalse("must not re-offer within the cooldown", d.shouldOffer)
        assertTrue(d.reason.contains("since the last offer"))
    }

    @Test
    fun reEscalationIsPossibleAfterCooldownEvenWhenAlreadyOffered() {
        // The episode was offered before; a genuinely higher level after the cooldown may re-offer.
        val ep = episodeWithOffer(openedDaysAgo = 60, offeredDaysAgo = 20, level = 70, offerMade = true)
        val d = engine.evaluate(listOf(day(0, 95), day(1, 95), day(2, 95)), ep, null, now)
        assertTrue("a sharp rise after the cooldown may re-offer", d.shouldOffer)
        assertTrue(d.reason.contains("Re-escalation"))
    }

    @Test
    fun reEscalationNeedsAFreshSharpRiseFromTheLastOfferLevel() {
        // Level rose, but not by the required margin over the level of the LAST offer.
        val ep = episodeWithOffer(openedDaysAgo = 60, offeredDaysAgo = 20, level = 90, offerMade = true)
        val d = engine.evaluate(listOf(day(0, 95), day(1, 95), day(2, 95)), ep, null, now)
        assertFalse(d.shouldOffer)
    }

    @Test
    fun episodeNeverOpenedUsesOpenedAtForCooldown() {
        // No offer recorded yet: the episode's own opening is the only reference available.
        val ep = episodeWithOffer(openedDaysAgo = 3, offeredDaysAgo = null, level = 70, offerMade = false)
        val d = engine.evaluate(listOf(day(0, 95), day(1, 95), day(2, 95)), ep, null, now)
        assertFalse(d.shouldOffer)
    }
}
