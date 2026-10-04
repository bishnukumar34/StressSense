package com.bishnu.stresswelfarebridge.questionnaire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DASS-21 scoring, item mapping and severity bands.
 *
 * Scoring is fully implemented and verified here; the **wording** is deliberately a placeholder
 * until it is pasted from the official source, and [dassWordingIsStillAPlaceholder] pins that so
 * the release gate cannot be quietly forgotten.
 */
class Dass21ScoringTest {

    private val dass = QuestionnaireInstrument.DASS_21

    private fun answers(vararg pairs: Pair<Int, Int>): Map<String, Int> =
        pairs.associate { (n, v) -> "dass_$n" to v }

    private fun allItems(value: Int): Map<String, Int> =
        (1..21).associate { "dass_$it" to value }

    /**
     * Answers that put the stress subscale exactly on [target].
     *
     * DASS subscale scores are always EVEN (a sum doubled by 2), so only even targets are
     * reachable. The band edges 15/19/25/33 are band boundaries, not reachable values; the test
     * therefore picks a reachable score inside each band.
     */
    private fun stressAnswerFor(target: Int): Map<String, Int> {
        val a = mutableMapOf<String, Int>()
        var remaining = target / 2
        for (item in dass.subscaleItems("stress")) {
            val v = minOf(3, remaining)
            a[item.id] = v
            remaining -= v
        }
        return a
    }    @Test
    fun hasTwentyOneItemsOnAZeroToThreeScale() {
        assertEquals(21, dass.items.size)
        assertEquals(0, dass.scale.min)
        assertEquals(3, dass.scale.max)
    }

    @Test
    fun subscalesUseTheSpecifiedItemMaps() {
        assertEquals(listOf(1, 6, 8, 11, 12, 14, 18), QuestionnaireInstrument.DASS_STRESS_ITEMS)
        assertEquals(listOf(2, 4, 7, 9, 15, 19, 20), QuestionnaireInstrument.DASS_ANXIETY_ITEMS)
        assertEquals(listOf(3, 5, 10, 13, 16, 17, 21), QuestionnaireInstrument.DASS_DEPRESSION_ITEMS)
        assertEquals(7, dass.subscaleItems("stress").size)
        assertEquals(7, dass.subscaleItems("anxiety").size)
        assertEquals(7, dass.subscaleItems("depression").size)
    }

    @Test
    fun everyItemBelongsToExactlyOneSubscale() {
        val assigned = dass.items.mapNotNull { it.subscale }
        assertEquals(21, assigned.size)
        assertEquals("three distinct subscales", 3, assigned.toSet().size)
    }

    @Test
    fun allZeroScoresZero() {
        val s = QuestionnaireScorer.score(dass, allItems(0))
        assertEquals(0.0, s.subscales["stress"]!!, 1e-9)
        assertEquals(0.0, s.subscales["anxiety"]!!, 1e-9)
        assertEquals(0.0, s.subscales["depression"]!!, 1e-9)
        assertEquals("Normal", s.bands["stress"])
    }

    @Test
    fun allMaximumScoresTheDoubledMaximum() {
        val s = QuestionnaireScorer.score(dass, allItems(3))
        assertEquals(42.0, s.subscales["stress"]!!, 1e-9)
        assertEquals(42.0, s.subscales["anxiety"]!!, 1e-9)
        assertEquals(42.0, s.subscales["depression"]!!, 1e-9)
        assertEquals("Extremely severe", s.bands["stress"])
    }

    @Test
    fun subscaleSumsAreDoubled() {
        val s = QuestionnaireScorer.score(dass, answers(1 to 2, 6 to 2))
        assertEquals(8.0, s.subscales["stress"]!!, 1e-9)
    }

    @Test
    fun subscalesAreIndependent() {
        val stressOnly = dass.subscaleItems("stress").associate { it.id to 3 }
        val s = QuestionnaireScorer.score(dass, stressOnly)
        assertEquals(42.0, s.subscales["stress"]!!, 1e-9)
        // Untouched subscales are reported as absent, not as zero.
        assertNull(s.subscales["anxiety"])
        assertNull(s.subscales["depression"])
    }

    @Test
    fun stressItemsFeedOnlyTheStressSubscale() {
        val s = QuestionnaireScorer.score(dass, answers(1 to 3))
        assertEquals(6.0, s.subscales["stress"]!!, 1e-9)
        assertNull(s.subscales["anxiety"])
        assertNull(s.subscales["depression"])
    }

    @Test
    fun severityBandsMapExactlyAsSpecified() {
        // Only even scores are reachable, because the subscale sum is always doubled.
        val cases = mapOf(
            0 to "Normal", 14 to "Normal",
            16 to "Mild", 18 to "Mild",
            20 to "Moderate", 24 to "Moderate",
            26 to "Severe", 32 to "Severe",
            34 to "Extremely severe", 42 to "Extremely severe"
        )
        for ((target, band) in cases) {
            val s = QuestionnaireScorer.score(dass, stressAnswerFor(target))
            val actual = s.subscales["stress"]!!.toInt()
            assertEquals("score $actual", target, actual)
            assertEquals("band for $actual", band, s.bands["stress"])
        }
    }

    @Test
    fun bandBoundariesAreInclusiveAndContiguous() {
        val bands = dass.bands["stress"]!!
        assertEquals("Normal", bands.first().name)
        assertEquals(0, bands.first().min)
        assertEquals(14, bands.first().maxInclusive)
        assertEquals(15, bands[1].min)
        assertNull("top band must be open-ended", bands.last().maxInclusive)
        for (v in 0..42) assertEquals(1, bands.count { it.contains(v) })
    }

    @Test
    fun noAnswersProducesNoScore() {
        val s = QuestionnaireScorer.score(dass, emptyMap())
        assertNull(s.primary)
        assertEquals(0, s.answeredCount)
    }

    @Test
    fun outOfScaleAnswersAreIgnoredRatherThanScored() {
        val s = QuestionnaireScorer.score(dass, answers(1 to 4, 6 to 3))
        assertEquals(6.0, s.subscales["stress"]!!, 1e-9)
        assertEquals(1, s.answeredCount)
    }

    @Test
    fun unknownItemIdsAreIgnored() {
        assertNull(QuestionnaireScorer.score(dass, mapOf("dass_99" to 3)).primary)
    }

    @Test
    fun dassIsNotShippableUntilWordingIsVerified() {
        assertEquals(VerificationStatus.PENDING_WORDING, dass.verification)
        assertFalse(dass.isShippable)
        assertTrue(QuestionnaireInstrument.shippable().none { it.id == dass.id })
    }

    @Test
    fun dassWordingIsStillAPlaceholder() {
        assertEquals(
            "DASS wording must be replaced from https://www.psy.unsw.edu.au/dass/ before release",
            21, dass.items.count { it.textKey.startsWith("PLACEHOLDER_") }
        )
    }

    @Test
    fun dassRecordsItsProvenanceAndTerms() {
        assertTrue(dass.licensingNote.contains("public domain"))
        assertTrue(dass.licensingNote.contains("Lovibond"))
        assertTrue(dass.isPsychologicalInstrument)
    }

    @Test
    fun pssIsPrototypeOnlyAndDeclaresReverseItems() {
        val pss = QuestionnaireInstrument.PSS_10
        assertEquals(VerificationStatus.PROTOTYPE_PENDING_LICENCE, pss.verification)
        assertFalse(pss.isShippable)
        assertEquals(setOf(4, 5, 7, 8), QuestionnaireInstrument.PSS_REVERSE_ITEMS)
        assertEquals(4, pss.items.count { it.reverseScored })
        assertTrue(pss.bands.isEmpty())
    }

    @Test
    fun pssReverseScoringIsAppliedByTheScorer() {
        val pss = QuestionnaireInstrument.PSS_10
        // All answers 4: 6 forward items x4 = 24, 4 reverse items (4-4=0) => 24.
        assertEquals(24.0, QuestionnaireScorer.score(pss, (1..10).associate { "pss_$it" to 4 })
            .primary!!, 1e-9)
        // All answers 0: 6 forward x0 = 0, 4 reverse items (4-0=4) => 16.
        assertEquals(16.0, QuestionnaireScorer.score(pss, (1..10).associate { "pss_$it" to 0 })
            .primary!!, 1e-9)
    }

    @Test
    fun tier3IsDisabledByDefaultAndGated() {
        assertFalse(QuestionnaireFeatureFlags.tier3Screening)
        assertFalse(SafetyProtocolGate.APPROVED_CLINICAL_PROTOCOL_IN_PLACE)
        assertFalse(SafetyProtocolGate.tier3Allowed)
        assertTrue(SafetyProtocolGate.configuredContacts().isEmpty())
        assertFalse(SafetyProtocolGate.contactsConfigured())
    }
}

