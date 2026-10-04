package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for choosing which (region, band) source to trust.
 *
 * The whole premise is that "green is always best" and "the centre is always best" are both
 * assumptions that fail on a real fingertip, so the selection must be evidence-driven, must not
 * oscillate, and must not be won by a saturated patch that merely looks loud.
 */
class PpgSourceSelectorTest {

    private val centralGreen = PpgSourceKey(PpgRegion.CENTRAL, PpgBand.GREEN)
    private val centralRed = PpgSourceKey(PpgRegion.CENTRAL, PpgBand.RED)
    private val northWest = PpgSourceKey(PpgRegion.CENTRE_NW, PpgBand.GREEN)

    /** Feeds [frames] identical scores, then returns the committed choice. */
    private fun feed(
        selector: PpgSourceSelector,
        scores: Map<PpgSourceKey, Double>,
        frames: Int = 1,
        clipped: Set<PpgSourceKey> = emptySet()
    ): PpgSourceKey? {
        var last: PpgSourceKey? = null
        repeat(frames) { last = selector.update(scores, clipped) }
        return last
    }

    @Test
    fun theBestScoringSourceIsChosen() {
        val selector = PpgSourceSelector()
        val chosen = feed(
            selector,
            mapOf(centralGreen to 0.30, centralRed to 0.85, northWest to 0.10),
            frames = 12
        )
        assertEquals(centralRed, chosen)
    }

    @Test
    fun aFingerThatIsOffCentreMovesTheSelectionToItsQuadrant() {
        val selector = PpgSourceSelector()
        val chosen = feed(
            selector,
            mapOf(centralGreen to 0.20, northWest to 0.90),
            frames = 12
        )
        assertEquals(northWest, chosen)
    }

    @Test
    fun theSelectorIsNotHardcodedToGreen() {
        // Red genuinely carrying the strongest pulse must win; the algorithm is not allowed to
        // prefer green by construction.
        val selector = PpgSourceSelector()
        feed(selector, mapOf(centralGreen to 0.40, centralRed to 0.60), frames = 20)
        assertEquals(centralRed, selector.selected)
        assertEquals(centralRed, selector.ranking().first().first)
    }

    @Test
    fun aSaturatedSourceCannotWinEvenWithTheHighestRawScore() {
        val selector = PpgSourceSelector()
        val chosen = feed(
            selector,
            scores = mapOf(centralGreen to 0.10, centralRed to 0.95),
            frames = 20,
            clipped = setOf(centralRed)
        )
        // Red is reported as clipped on every frame, so it is demoted and green is kept.
        assertEquals(centralGreen, chosen)
    }

    @Test
    fun theChoiceDoesNotOscillateBetweenTwoSimilarSources() {
        val selector = PpgSourceSelector()
        feed(selector, mapOf(centralGreen to 0.60, centralRed to 0.62), frames = 10)
        val first = selector.selected
        assertNotNull(first)

        // A challenger that is only marginally better must not trigger a switch.
        repeat(5) { selector.update(mapOf(centralGreen to 0.60, centralRed to 0.64)) }
        assertEquals("hysteresis should keep the incumbent", first, selector.selected)
    }

    @Test
    fun aClearlyBetterSourceDoesTakeOver() {
        val selector = PpgSourceSelector()
        feed(selector, mapOf(centralGreen to 0.60, centralRed to 0.62), frames = 10)
        repeat(5) { selector.update(mapOf(centralGreen to 0.20, centralRed to 0.95)) }
        assertEquals(centralRed, selector.selected)
    }

    @Test
    fun recoveryFollowsTheFingerWhenTheSignalMoves() {
        val selector = PpgSourceSelector()
        // Initially the finger is centred and green is clean.
        feed(selector, mapOf(centralGreen to 0.80, northWest to 0.15), frames = 10)
        assertEquals(centralGreen, selector.selected)

        // The user slides their finger; the off-centre patch is now the one with the pulse.
        // The score is a running mean, so recovery takes a moment by design - the selector must not
        // flip on a single frame or it would oscillate.
        repeat(20) { selector.update(mapOf(centralGreen to 0.10, northWest to 0.85)) }
        assertEquals("the selector must follow the finger", northWest, selector.selected)
    }

    @Test
    fun rankingIsOrderedBestFirst() {
        val selector = PpgSourceSelector()
        feed(selector, mapOf(centralGreen to 0.2, centralRed to 0.9, northWest to 0.5), frames = 10)
        val ranking = selector.ranking()
        assertEquals(3, ranking.size)
        assertEquals(centralRed, ranking[0].first)
        assertTrue(ranking[0].second >= ranking[1].second)
        assertTrue(ranking[1].second >= ranking[2].second)
    }

    @Test
    fun resetClearsAllEvidence() {
        val selector = PpgSourceSelector()
        feed(selector, mapOf(centralGreen to 0.9, centralRed to 0.1), frames = 10)
        assertNotNull(selector.selected)

        selector.reset()
        assertNull(selector.selected)
        assertTrue(selector.ranking().isEmpty())
    }

    @Test
    fun aStaleSourceLosesItsInfluenceOverTime() {
        val selector = PpgSourceSelector()
        feed(selector, mapOf(centralGreen to 0.95, northWest to 0.10), frames = 8)
        // The strong source stops being observed; its historical advantage must decay.
        repeat(200) { selector.update(mapOf(northWest to 0.55)) }
        assertEquals(northWest, selector.selected)
    }

    @Test
    fun anEmptyScoreMapIsHandledWithoutCrashing() {
        val selector = PpgSourceSelector()
        assertNull(selector.update(emptyMap()))
        assertNull(selector.selected)
    }
}
