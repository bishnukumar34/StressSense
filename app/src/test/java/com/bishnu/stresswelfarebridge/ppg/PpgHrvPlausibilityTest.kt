package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM tests for the **robustness** of the camera PPG → HRV chain: beat-to-beat plausibility,
 * artifact rejection, RMSSD correctness and — critically — the quality gate that decides whether a
 * number may be shown to the user at all.
 *
 * Motivation: a real device reported RMSSD 274 ms at 95 BPM while still rating the measurement
 * GOOD. These tests pin down every stage that could allow that, and they deliberately include
 * cases of *legitimate* high HRV to prove the fixes are not an arbitrary cap on RMSSD — no test in
 * this file asserts an upper bound on the HRV value itself, only on the coherence of the interval
 * series that produced it.
 */
class PpgHrvPlausibilityTest {

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Builds a peak series whose inter-beat intervals are exactly [rr]. */
    private fun peaksFrom(rr: List<Double>): List<PulsePeak> {
        val times = ArrayList<Double>(rr.size + 1)
        var t = 0.0
        for (r in rr) {
            times.add(t)
            t += r
        }
        times.add(t)
        return times.map { PulsePeak(it, 1.0, 1.0, PeakConditions(true, true, true, true)) }
    }

    /** Reference RMSSD = sqrt(mean((NN[i+1] − NN[i])²)) computed directly in milliseconds. */
    private fun rmssdOf(nn: List<Double>): Double {
        var sum = 0.0
        for (i in 1 until nn.size) {
            val d = nn[i] - nn[i - 1]
            sum += d * d
        }
        return sqrt(sum / (nn.size - 1))
    }

    /** Runs validation + metrics + quality exactly as the app does. */
    private fun assess(rr: List<Double>, amplitudeCv: Double = 0.05): Triple<IbiValidation, HrvMetrics?, QualityAssessment?> {
        val v = ArtifactRejection.validate(peaksFrom(rr))
        val m = HrvCalculator.compute(v.validNn)
        val q = m?.let { SignalQualityEvaluator.evaluate(it, v, amplitudeCv) }
        return Triple(v, m, q)
    }

    // ------------------------------------------------------------------------------------------
    // 1-2. Normal stable RR and legitimate HRV must survive intact
    // ------------------------------------------------------------------------------------------

    @Test
    fun normalStableRrIntervalsSurviveIntactAndScoreGood() {
        val rr = List(60) { 800.0 }
        val (v, m, q) = assess(rr)

        assertEquals(60, v.totalIntervals)
        assertEquals(0, v.rejectedIntervals)
        assertEquals(60, v.validNn.size)
        assertEquals(0, v.suspectPairCount)

        assertNotNull(m)
        assertEquals(0.0, m!!.rmssdMs, 1e-9)
        assertEquals(75.0, m.heartRateBpm, 0.01)

        assertNotNull(q)
        assertEquals(SignalQuality.GOOD, q!!.quality)
    }

    @Test
    fun legitimateHrvVariationIsPreservedAndStillTrusted() {
        // A physiological respiratory-modulated pattern: nothing here should be rejected.
        val pattern = listOf(800.0, 870.0, 790.0, 850.0, 820.0, 840.0, 810.0, 860.0)
        val rr = (0 until 8).flatMap { pattern }
        val (v, m, q) = assess(rr)

        assertEquals("no legitimate interval may be discarded", rr.size, v.validNn.size)
        assertEquals(0, v.rejectedIntervals)
        assertEquals(0, v.suspectPairCount)

        assertNotNull(m)
        assertEquals("RMSSD must match the interval series exactly", rmssdOf(rr), m!!.rmssdMs, 1e-9)
        assertTrue("RMSSD unexpectedly large: ${m.rmssdMs}", m.rmssdMs in 30.0..60.0)

        assertNotNull(q)
        assertEquals("legitimate HRV must not be downgraded: ${q!!.quality}", SignalQuality.GOOD, q.quality)
    }

    @Test
    fun legitimateHighHrvIsNotPunishedByAnRmssdCap() {
        // ±150 ms modulation around 800 ms ⇒ RMSSD is exactly 150 ms, far above 100 ms. The system
        // must still trust it: this is the proof that no arbitrary RMSSD ceiling was introduced.
        val rr = (0 until 6).flatMap { listOf(800.0, 950.0, 800.0, 650.0) }
        val (v, m, q) = assess(rr)

        assertEquals(rr.size, v.validNn.size)
        assertEquals(0, v.rejectedIntervals)

        assertNotNull(m)
        assertEquals(150.0, m!!.rmssdMs, 1e-9)
        assertTrue("this test only means something if RMSSD exceeds 100 ms", m.rmssdMs > 100.0)

        assertNotNull(q)
        assertEquals("high-but-genuine HRV must stay GOOD, was ${q!!.quality}", SignalQuality.GOOD, q.quality)
    }

    @Test
    fun irregularButPhysiologicallyPlausibleRrIsPreserved() {
        // Slow sinus arrhythmia: every interval differs from its neighbours, but smoothly.
        val rr = (0 until 50).map { 800.0 + 120.0 * sin(2.0 * PI * it / 10.0) }
        val (v, m, q) = assess(rr)

        assertEquals("smooth physiological variation must all survive", rr.size, v.validNn.size)
        assertEquals(0, v.rejectedIntervals)
        assertEquals(0, v.suspectPairCount)

        assertNotNull(m)
        assertEquals(rmssdOf(rr), m!!.rmssdMs, 1e-9)

        assertNotNull(q)
        assertTrue("plausible irregularity must not be POOR, was ${q!!.quality}", q.quality != SignalQuality.POOR)
    }

    // ------------------------------------------------------------------------------------------
    // 3-7. Missed beats, false/double beats and compensatory pairs
    // ------------------------------------------------------------------------------------------

    @Test
    fun oneMissedBeatIsRemovedAndDoesNotInflateRmssd() {
        // A single lost peak doubles one interval to 1600 ms. It must never reach the NN series.
        val rr = List(10) { 800.0 } + listOf(1600.0) + List(10) { 800.0 }
        val (v, m, _) = assess(rr)

        assertTrue("the 1600 ms interval must be gone", v.validNn.none { it == 1600.0 })
        assertTrue(v.rejectedIntervals >= 1)
        assertEquals(rr.size, v.validNn.size + v.rejectedIntervals)

        assertNotNull(m)
        assertEquals(20, m!!.nnCount)
        assertEquals(0.0, m.rmssdMs, 1e-9)
        assertEquals(75.0, m.heartRateBpm, 0.01)
    }

    @Test
    fun oneFalseDoubleBeatIsRemoved() {
        // An extra peak 350 ms after a genuine beat splits one 800 ms interval into 350 + 450.
        val rr = List(10) { 800.0 } + listOf(350.0, 450.0) + List(10) { 800.0 }
        val (v, m, _) = assess(rr)

        assertTrue("the 350 ms interval must be gone", v.validNn.none { it == 350.0 })
        assertTrue("the 450 ms interval must be gone", v.validNn.none { it == 450.0 })
        assertEquals(rr.size, v.validNn.size + v.rejectedIntervals)

        assertNotNull(m)
        assertEquals(0.0, m!!.rmssdMs, 1e-9)
    }

    @Test
    fun longShortCompensatoryPairCannotSurviveIntoTheNnSeries() {
        // The exact sequence from the field report: 630, 1250, 640 (a missed beat between two
        // normal ones). None of the three may distort the resulting HRV.
        val rr = List(10) { 800.0 } + listOf(630.0, 1250.0, 640.0) + List(10) { 800.0 }
        val (v, m, _) = assess(rr)

        assertTrue("1250 ms must be rejected", v.validNn.none { it == 1250.0 })
        assertEquals(rr.size, v.validNn.size + v.rejectedIntervals)

        assertNotNull(m)
        assertTrue("RMSSD ${m!!.rmssdMs} must not be inflated by the pair", m.rmssdMs < 50.0)

        // Even on its own — only three intervals, no global statistics to lean on — the long one
        // must not be reported as a normal interval.
        val bare = ArtifactRejection.validate(peaksFrom(listOf(630.0, 1250.0, 640.0)))
        assertEquals(3, bare.totalIntervals)
        assertTrue(bare.validNn.none { it == 1250.0 })
        assertEquals(bare.totalIntervals, bare.validNn.size + bare.rejectedIntervals)
    }

    @Test
    fun shortLongCompensatoryPairCannotSurviveIntoTheNnSeries() {
        // A premature beat: 450 ms short interval followed by a 1150 ms compensatory pause
        // (450 + 1150 = 1600 = two normal beats).
        val rr = List(10) { 800.0 } + listOf(450.0, 1150.0) + List(10) { 800.0 }
        val (v, m, _) = assess(rr)

        assertTrue("the 450 ms interval must be gone", v.validNn.none { it == 450.0 })
        assertTrue("the 1150 ms interval must be gone", v.validNn.none { it == 1150.0 })
        assertEquals(rr.size, v.validNn.size + v.rejectedIntervals)

        assertNotNull(m)
        assertEquals(0.0, m!!.rmssdMs, 1e-9)
    }

    @Test
    fun multipleArtifactsAreAllRemovedAndRmssdIsNotInflated() {
        val rr = List(5) { 800.0 } + listOf(1600.0) +
            List(5) { 800.0 } + listOf(400.0) +
            List(5) { 800.0 } + listOf(1200.0) +
            List(5) { 800.0 }
        val (v, m, _) = assess(rr)

        assertEquals(23, v.totalIntervals)
        assertTrue("expected at least 3 rejections, got ${v.rejectedIntervals}", v.rejectedIntervals >= 3)
        for (bad in listOf(400.0, 1200.0, 1600.0)) {
            assertTrue("$bad ms must be gone", v.validNn.none { it == bad })
        }
        assertEquals(rr.size, v.validNn.size + v.rejectedIntervals)

        assertNotNull(m)
        assertTrue("RMSSD ${m!!.rmssdMs} after cleaning must be small", m.rmssdMs < 50.0)
    }

    @Test
    fun aSingleIsolatedAbnormalIbiCannotProduceAHugeRmssdContribution() {
        // 60 clean beats with one surviving 950 ms interval — even if it ever slipped through
        // rejection, a single 150 ms deviation across 60 intervals contributes very little.
        val withLeak = ArrayList<Double>(List(60) { 800.0 })
        withLeak[30] = 950.0
        val leaked = HrvCalculator.compute(withLeak)
        assertNotNull(leaked)
        assertTrue("one isolated abnormal IBI gave RMSSD ${leaked!!.rmssdMs}", leaked.rmssdMs < 60.0)

        // In the real chain it is removed outright rather than being left in the series.
        val rr = ArrayList<Double>(List(60) { 800.0 })
        rr[30] = 950.0
        val v = ArtifactRejection.validate(peaksFrom(rr))
        assertTrue(v.validNn.none { it == 950.0 })
        val kept = HrvCalculator.compute(v.validNn)
        assertNotNull(kept)
        assertTrue(kept!!.rmssdMs < 10.0)
    }

    @Test
    fun compensatoryPairRuleFiresWhenGlobalStatisticsLookOrdinary() {
        // Designed so the *global* median/MAD test must pass the 950 ms interval (deviation 150 ms
        // against a median of 800 ms, ceiling 160 ms) while its own two neighbours are 700 ms — a
        // local excursion of 250 ms, i.e. 36 % above their reference. Only the neighbour-referenced
        // plausibility rule can explain that interval.
        val rr = ArrayList<Double>()
        repeat(30) { rr.add(750.0); rr.add(850.0) }
        rr.add(850.0)
        rr.add(700.0)
        rr.add(950.0)
        rr.add(700.0)

        val v = ArtifactRejection.validate(peaksFrom(rr))

        assertEquals(rr.size, v.totalIntervals)
        assertTrue("the pair rule must fire", v.suspectPairCount >= 1)
        assertTrue(v.rejectionReasons.containsKey("compensatory_pair"))
        assertTrue("950 ms must be rejected", v.validNn.none { it == 950.0 })
        // …while the surrounding genuine ±50 ms variation is untouched.
        assertEquals(rr.size - 1, v.validNn.size)
        assertEquals(rr.size, v.validNn.size + v.rejectedIntervals)
    }

    // ------------------------------------------------------------------------------------------
    // 5-6. RMSSD mathematics
    // ------------------------------------------------------------------------------------------

    @Test
    fun rmssdIsComputedFromSuccessiveNnMillisecondsNotFromBpm() {
        val nn = (0 until 21).map { if (it % 2 == 0) 1000.0 else 1100.0 }
        val expected = rmssdOf(nn)
        assertEquals(100.0, expected, 1e-9)
        assertEquals(expected, HrvCalculator.rmssd(nn)!!, 1e-9)

        // The BPM-domain equivalent is a completely different number, so this assertion proves
        // which formula the production code uses.
        val bpm = nn.map { 60000.0 / it }
        val bpmRmssd = rmssdOf(bpm)
        assertTrue(
            "BPM-domain RMSSD ($bpmRmssd) must not equal the NN-domain RMSSD ($expected)",
            abs(expected - bpmRmssd) > 1.0
        )
    }

    @Test
    fun rejectedIntervalsAreCompletelyExcludedFromTheRmssdSequence() {
        val rr = List(10) { 800.0 } + listOf(1600.0) + List(10) { 800.0 }
        val v = ArtifactRejection.validate(peaksFrom(rr))
        val m = HrvCalculator.compute(v.validNn)
        assertNotNull(m)

        // Exactly the validated sequence — nothing else contributes.
        assertEquals(rmssdOf(v.validNn), m!!.rmssdMs, 1e-9)
        // …and including the rejected interval would have produced a wildly different answer.
        assertTrue(abs(m.rmssdMs - rmssdOf(rr)) > 1.0)
        assertTrue(rmssdOf(rr) > 200.0)
        assertEquals(v.totalIntervals, v.validNn.size + v.rejectedIntervals)
        for (nn in v.validNn) assertTrue("interval $nn is not an original 800 ms beat", nn == 800.0)
    }

    @Test
    fun insufficientValidIntervalsProduceNoResult() {
        // Under [HrvCalculator.MIN_NN] the calculator must return null — never a guess.
        assertNull(HrvCalculator.compute(List(19) { 800.0 }))
        assertNull(HrvCalculator.rmssd(List(19) { 800.0 }))

        val shortCapture = List(15) { 800.0 }
        val v = ArtifactRejection.validate(peaksFrom(shortCapture))
        assertEquals(15, v.validNn.size)
        assertNull(HrvCalculator.compute(v.validNn))

        // Degenerate inputs likewise.
        assertEquals(0, ArtifactRejection.validate(emptyList()).totalIntervals)
        assertEquals(0, ArtifactRejection.validate(peaksFrom(emptyList())).totalIntervals)
    }

    // ------------------------------------------------------------------------------------------
    // 8-9. The quality gate itself
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFieldFailureIsNeverLabelledGood() {
        // Real-device observation: intervals alternating 500/764 ms. Mean = 632 ms ⇒ 95 BPM and
        // RMSSD = 264 ms, i.e. a *plausible* heart rate with a nonsense variability figure. This is
        // the exact shape that previously reached the UI as GOOD.
        val nn = List(40) { i -> if (i % 2 == 0) 500.0 else 764.0 }
        val v = IbiValidation(
            validNn = nn, totalIntervals = 40, rejectedIntervals = 0, rejectionReasons = emptyMap()
        )
        val m = HrvCalculator.compute(nn)
        assertNotNull(m)
        assertEquals(94.94, m!!.heartRateBpm, 0.01)
        assertTrue("expected a hugely inflated RMSSD, got ${m.rmssdMs}", m.rmssdMs > 200.0)

        val assessment = SignalQualityEvaluator.evaluate(m, v, 0.05)
        assertTrue(
            "a 43 % beat-to-beat excursion series must not be GOOD (was ${assessment.quality})",
            assessment.quality != SignalQuality.GOOD
        )
        assertEquals(SignalQuality.POOR, assessment.quality)
    }

    @Test
    fun goodIsRefusedWhenTooManyIntervalsHadToBeDiscarded() {
        // Perfectly regular surviving series, but 65 % of the detected intervals were thrown away:
        // the beat train was never trustworthy, whatever the surviving numbers look like.
        val v = IbiValidation(
            validNn = List(21) { 400.0 },
            totalIntervals = 60,
            rejectedIntervals = 39,
            rejectionReasons = emptyMap()
        )
        val m = HrvCalculator.compute(v.validNn)
        assertNotNull(m)
        val assessment = SignalQualityEvaluator.evaluate(m!!, v, 1.0)
        assertEquals(SignalQuality.POOR, assessment.quality)
        assertTrue("confidence ${assessment.confidence} should be below 0.55", assessment.confidence < 0.55)
    }

    @Test
    fun nnSeriesStatisticsExposeAnIncoherentBeatTrain() {
        val coherent = ArtifactRejection.stats(
            IbiValidation(List(40) { 800.0 }, 40, 0, emptyMap())
        )
        assertTrue(SignalQualityEvaluator.nnTrust(coherent) >= 0.95)
        assertEquals(0.0, coherent.largeDiffRatio, 1e-12)
        assertEquals(1.0, coherent.hrConsistency, 1e-12)

        val incoherent = ArtifactRejection.stats(
            IbiValidation(
                List(40) { i -> if (i % 2 == 0) 500.0 else 764.0 }, 40, 0, emptyMap()
            )
        )
        assertEquals(1.0, incoherent.largeDiffRatio, 1e-12)
        assertEquals(0.0, incoherent.hrConsistency, 1e-12)
        assertTrue("ibiCv ${incoherent.ibiCv} should be large", incoherent.ibiCv > 0.15)
        assertTrue(
            "nnTrust ${SignalQualityEvaluator.nnTrust(incoherent)} should be low",
            SignalQualityEvaluator.nnTrust(incoherent) < 0.50
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2 (detector). A strong dicrotic wave must not be counted as a second beat
    // ------------------------------------------------------------------------------------------

    @Test
    fun strongDicroticWaveIsNotCountedAsASecondBeat() {
        val rrMs = 800.0
        val fsHz = 50.0
        val dtMs = 1000.0 / fsHz
        val beatCount = 50
        val samples = ((beatCount * rrMs) / dtMs).toInt()

        // Systolic peak plus a dicrotic wave 420 ms later at 60 % height — much stronger than a
        // real dicrotic notch, which is what makes naive local-maximum detection fail.
        val signal = DoubleArray(samples) { i ->
            val t = i * dtMs
            var v = 0.0
            var k = 0
            while (k * rrMs <= t) {
                val u = t - k * rrMs
                v += exp(-(u - 180.0) * (u - 180.0) / (2.0 * 55.0 * 55.0))
                v += 0.60 * exp(-(u - 420.0) * (u - 420.0) / (2.0 * 75.0 * 75.0))
                k++
            }
            v
        }

        val peaks = PulseDetector.detectPeaks(signal, dtMs, PulseDetector.State())
        assertTrue(
            "expected roughly $beatCount beats, detected ${peaks.size}",
            peaks.size in (beatCount - 6)..(beatCount + 6)
        )
        val intervals = peaks.zipWithNext { a, b -> b.timeMs - a.timeMs }
        val bad = intervals.filter { it !in 640.0..960.0 }
        assertTrue(
            "bad intervals $bad from peaks ${peaks.map { it.timeMs }}",
            bad.isEmpty()
        )
    }
}

