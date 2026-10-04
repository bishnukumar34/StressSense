package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Tests for the signal-acquisition analysis that runs **before** peak detection.
 *
 * The point of this stage is that "the camera is not seeing a usable pulse" must be distinguishable
 * from "the camera is seeing a pulse we failed to detect". These tests build realistic fingertip PPG
 * traces — a tiny AC component on a large DC level, as real ones are — and assert the classifier
 * separates them correctly.
 */
class PpgAcquisitionTest {

    private val fs = 50.0

    /**
     * A realistic fingertip PPG: ~140 intensity counts of DC with a small pulsatile AC component, a
     * dicrotic notch, mild respiratory baseline wander and a little sensor noise.
     */
    private fun fingertipPpg(
        seconds: Double = 12.0,
        dc: Double = 140.0,
        ac: Double = 1.2,
        bpm: Double = 70.0,
        noise: Double = 0.05,
        drift: Double = 0.4,
        seed: Long = 7L
    ): DoubleArray {
        val n = (seconds * fs).toInt()
        val rnd = java.util.Random(seed)
        val period = 60.0 / bpm
        return DoubleArray(n) { i ->
            val t = i / fs
            val u = t % period
            val pulse = kotlin.math.exp(-(u - 0.15) * (u - 0.15) / (2 * 0.055 * 0.055))
            val dicrotic = 0.4 * kotlin.math.exp(-(u - 0.42) * (u - 0.42) / (2 * 0.075 * 0.075))
            dc + ac * (pulse + dicrotic) + drift * sin(2 * Math.PI * 0.2 * t) + noise * rnd.nextGaussian()
        }
    }

    /** Detrend + band-pass, the same preparation the pipeline applies before scoring. */
    private fun filtered(raw: DoubleArray): DoubleArray {
        val times = DoubleArray(raw.size) { it / fs * 1000.0 }
        val resampled = PpgSignalProcessor.resample(times, raw)!!
        return PpgSignalProcessor.bandPass(
            PpgSignalProcessor.detrend(resampled.signal), resampled.fsHz
        )
    }

    // ------------------------------------------------------------------ classification

    @Test
    fun aStrongCleanFingertipPulseIsUsable() {
        val raw = fingertipPpg(ac = 1.5, noise = 0.03)
        val score = PpgAcquisition.score(raw, filtered(raw), fs)
        assertEquals(SignalAcquisitionState.USABLE_SIGNAL, score.state)
        assertTrue("AC amplitude was ${score.acAmplitude}", score.acAmplitude > 0.5)
        assertTrue("SNR was ${score.snr}", score.snr > 2.0)
        assertTrue("periodicity was ${score.periodicity}", score.periodicity > 0.4)
        assertTrue("AC/DC was ${score.acToDc}", score.acToDc in 0.001..0.5)
    }

    @Test
    fun aWeakButRealPulseIsSeparatedFromNoSignal() {
        // A thin, quiet finger: small AC, but unmistakably periodic.
        val raw = fingertipPpg(ac = 0.35, noise = 0.04)
        val score = PpgAcquisition.score(raw, filtered(raw), fs)
        assertTrue("a thin finger must not be dismissed", score.acAmplitude > PpgAcquisition.MIN_AC_AMPLITUDE)
        assertTrue(
            "expected at least WEAK_SIGNAL, got ${score.state}",
            score.state != SignalAcquisitionState.NO_SIGNAL
        )
    }

    @Test
    fun aCompletelyFlatSignalIsNoSignal() {
        val raw = DoubleArray(600) { 137.0 }
        val score = PpgAcquisition.score(raw, filtered(raw), fs)
        assertEquals(SignalAcquisitionState.NO_SIGNAL, score.state)
        assertEquals(0.0, score.acAmplitude, 1e-9)
        assertEquals(0.0, score.periodicity, 1e-9)
    }

    @Test
    fun pureNoiseIsNeverUsable() {
        val rnd = java.util.Random(99)
        val raw = DoubleArray(600) { 130.0 + rnd.nextGaussian() * 3.0 }
        assertEquals(SignalAcquisitionState.WEAK_SIGNAL, PpgAcquisition.score(raw, filtered(raw), fs).state)
    }

    @Test
    fun lowLightNoiseIsNotMistakenForAPulse() {
        // Very dark, very noisy — the "torch is off" case.
        val rnd = java.util.Random(3)
        val raw = DoubleArray(600) { 6.0 + rnd.nextGaussian() * 1.2 }
        assertEquals(SignalAcquisitionState.NO_SIGNAL, PpgAcquisition.score(raw, filtered(raw), fs).state)
    }

    @Test
    fun strongIlluminationStillProducesAUsableSignal() {
        // Near the top of the range but not clipped: a brightly lit, thin finger.
        val raw = fingertipPpg(dc = 235.0, ac = 1.1, noise = 0.04)
        val score = PpgAcquisition.score(raw, filtered(raw), fs)
        assertEquals(SignalAcquisitionState.USABLE_SIGNAL, score.state)
        assertEquals(0.0, score.saturationFraction, 1e-9)
    }

    @Test
    fun aSaturatedBlownOutCaptureIsNeverUsableEvenThoughItVaries() {
        // The classic torch failure mode: pressed hard, the green channel pins at 255 and the pulse
        // disappears into the ceiling. It must be NO_SIGNAL, not a usable pulse.
        val raw = fingertipPpg(dc = 254.6, ac = 1.2, noise = 0.05)
        val score = PpgAcquisition.score(raw, filtered(raw), fs)
        assertEquals(SignalAcquisitionState.NO_SIGNAL, score.state)
        assertTrue("saturation fraction was ${score.saturationFraction}", score.saturationFraction > 0.5)
    }

    @Test
    fun gradualExposureDriftDoesNotHideThePulse() {
        // Auto-exposure hunting is exactly the nuisance detrend + band-pass are there to remove.
        val rnd = java.util.Random(11)
        val base = fingertipPpg(ac = 1.3, noise = 0.03)
        val raw = DoubleArray(base.size) { i ->
            base[i] + 25.0 * sin(2 * Math.PI * 0.05 * i / fs) + 0.4 * rnd.nextGaussian()
        }
        assertEquals(SignalAcquisitionState.USABLE_SIGNAL, PpgAcquisition.score(raw, filtered(raw), fs).state)
    }

    @Test
    fun clippingDominatesAcAmplitudeSoItCannotWinOnStrength() {
        // A clipped window has a large peak-to-peak value but carries no information: the combined
        // score must prefer a quiet clean signal over a loud clipped one.
        val clipped = PpgAcquisition.combinedScoreForTest(
            snr = 5.0, periodicity = 0.9, ac = 50.0, saturation = 0.9, acToDc = 0.3
        )
        val clean = PpgAcquisition.combinedScoreForTest(
            snr = 1.6, periodicity = 0.45, ac = 0.6, saturation = 0.0, acToDc = 0.004
        )
        assertTrue("clipped=$clipped must not beat clean=$clean", clean > clipped)
    }

    // ------------------------------------------------------------------ helper maths

    @Test
    fun saturationFractionCountsBothEndsOfTheRange() {
        assertEquals(0.0, PpgAcquisition.saturationFraction(doubleArrayOf(128.0, 200.0)), 1e-9)
        assertEquals(0.5, PpgAcquisition.saturationFraction(doubleArrayOf(255.0, 128.0)), 1e-9)
        assertEquals(1.0, PpgAcquisition.saturationFraction(doubleArrayOf(0.0, 252.0)), 1e-9)
    }

    @Test
    fun periodicityTracksHeartRateAcrossThePlausibleRange() {
        for (bpm in listOf(48.0, 60.0, 75.0, 100.0, 140.0)) {
            val raw = fingertipPpg(seconds = 20.0, bpm = bpm, ac = 1.2, noise = 0.03, seed = 13)
            val score = PpgAcquisition.score(raw, filtered(raw), fs)
            assertTrue("periodicity too low at $bpm bpm: ${score.periodicity}", score.periodicity > 0.25)
        }
    }

    @Test
    fun medianAbsoluteDeviationIgnoresOutliers() {
        val clean = DoubleArray(200) { 100.0 + (it % 5) * 0.1 }
        val withGlare = clean.copyOf()
        withGlare[7] = 255.0
        withGlare[88] = 0.0
        // A mean/sigma rule would be dragged away by the two corrupt samples; the MAD must not be.
        assertTrue(PpgAcquisition.medianAbsoluteDeviation(clean) < 0.2)
        assertTrue(PpgAcquisition.medianAbsoluteDeviation(withGlare) < 0.3)
    }

    @Test
    fun shortOrDegenerateInputIsNeverUsable() {
        assertEquals(
            SignalAcquisitionState.NO_SIGNAL,
            PpgAcquisition.score(DoubleArray(2) { 100.0 }, DoubleArray(2) { 1.0 }, fs).state
        )
        assertEquals(
            SignalAcquisitionState.NO_SIGNAL,
            PpgAcquisition.score(DoubleArray(600), DoubleArray(600), 0.0).state
        )
    }

    @Test
    fun aFrameDroppedMidCaptureDoesNotDestroyTheSignal() {
        val full = fingertipPpg(seconds = 12.0, ac = 1.3, noise = 0.03)
        // ~0.4 s of dropped frames appear as repeated samples: no information, but no failure either.
        val gapped = DoubleArray(full.size) { i -> if (i in 300..319) full[299] else full[i] }
        assertEquals(SignalAcquisitionState.USABLE_SIGNAL, PpgAcquisition.score(gapped, filtered(gapped), fs).state)
    }

    @Test
    fun recoveryIsDetectedOnceABadPatchIsReplaced() {
        val rnd = java.util.Random(5)
        // A genuinely clipped patch: pressed hard against the lens, the channel pins at the ceiling.
        val bad = DoubleArray(600) { 254.9 + rnd.nextGaussian() * 0.15 }
        val badScore = PpgAcquisition.score(bad, filtered(bad), fs)
        assertEquals(SignalAcquisitionState.NO_SIGNAL, badScore.state)

        // The user eases off or moves the finger; a clean patch now sits behind the same lens.
        val good = fingertipPpg(seconds = 12.0, dc = 120.0, ac = 1.4, noise = 0.03)
        val goodScore = PpgAcquisition.score(good, filtered(good), fs)
        assertEquals(SignalAcquisitionState.USABLE_SIGNAL, goodScore.state)
        assertTrue(goodScore.score > badScore.score)
    }

    @Test
    fun anAbsentHeartbeatIsNotSmoothedIntoExistence() {
        // Sinusoidal "breathing" only: strong low-frequency motion, no cardiac component.
        val raw = DoubleArray(600) { i ->
            val t = i / fs
            130.0 + 6.0 * sin(2 * Math.PI * 0.25 * t) + 0.15 * sin(2 * Math.PI * 3.0 * t)
        }
        val score = PpgAcquisition.score(raw, filtered(raw), fs)
        assertTrue(
            "a 0.25 Hz wobble must not be reported as a usable pulse " +
                "(state=${score.state}, periodicity=${score.periodicity})",
            score.state != SignalAcquisitionState.USABLE_SIGNAL
        )
    }
}

