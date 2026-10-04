package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM tests for the on-device camera PPG → HRV pipeline.
 *
 * The synthetic waveforms below are **test fixtures only**. The application never fabricates
 * heart-rate or HRV values; these signals exist purely so the detector and calculator can be
 * checked against a known ground truth in a plain JVM unit test.
 */
class PpgPipelineTest {

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private class Beat(val timeMs: Double)

    /** Ground-truth beat times produced by cycling [rrMs] until [seconds] elapses. */
    private fun beats(rrMs: List<Double>, seconds: Double): List<Beat> {
        val out = ArrayList<Beat>()
        var t = 0.0
        var i = 0
        val endMs = seconds * 1000.0
        while (t < endMs) {
            out.add(Beat(t))
            t += rrMs[i % rrMs.size]
            i++
        }
        return out
    }

    /** Analytic RMSSD of the RR pattern that generated the beats. */
    private fun trueRmssd(rrMs: List<Double>): Double {
        var sum = 0.0
        for (i in 1 until rrMs.size) {
            val d = rrMs[i] - rrMs[i - 1]
            sum += d * d
        }
        return sqrt(sum / (rrMs.size - 1))
    }

    /** One photoplethysmographic pulse: systolic peak plus a smaller dicrotic wave. */
    private fun pulse(uMs: Double): Double {
        if (uMs < 0.0) return 0.0
        val u = uMs / 1000.0
        val s1 = 0.055
        val s2 = 0.075
        val systolic = exp(-(u - 0.18) * (u - 0.18) / (2.0 * s1 * s1))
        val dicrotic = 0.35 * exp(-(u - 0.42) * (u - 0.42) / (2.0 * s2 * s2))
        return systolic + dicrotic
    }

    /**
     * Simulates the camera acquisition: ~30 fps with timing jitter, DC offset, linear sensor/torch
     * drift and a 0.15 Hz respiration baseline — i.e. exactly what the pipeline must remove.
     */
    private fun acquire(beatList: List<Beat>, seconds: Double, seed: Long = 7L): List<PpgSample> {
        val rnd = Random(seed)
        val out = ArrayList<PpgSample>()
        val endMs = seconds * 1000.0
        var t = 0.0
        while (t < endMs) {
            var v = 130.0 + 0.05 * t + 7.0 * sin(2.0 * PI * 0.15 * t / 1000.0)
            for (b in beatList) {
                val u = t - b.timeMs
                if (u < -50.0 || u > 700.0) continue
                v += pulse(u)
            }
            out.add(PpgSample(t, v, v, v))
            t += 33.3333 + (rnd.nextDouble() - 0.5) * 12.0
        }
        return out
    }

    /** Magnitude of the DFT bin at [f] — used to check how much of each component survived. */
    private fun dftMagnitude(x: DoubleArray, fs: Double, f: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            val w = -2.0 * PI * f * i / fs
            re += x[i] * cos(w)
            im += x[i] * sin(w)
        }
        return 2.0 * sqrt(re * re + im * im) / x.size
    }

    // ------------------------------------------------------------------------------------------
    // PpgSignalProcessor
    // ------------------------------------------------------------------------------------------

    @Test
    fun resampleProducesUniformGridAndPreservesLinearSignal() {
        val n = 91
        val times = DoubleArray(n) { i -> i * 33.0 + 5.0 * sin(i.toDouble()) }
        val values = DoubleArray(n) { i -> times[i] / 1000.0 }

        val rs = PpgSignalProcessor.resample(times, values, outputHz = 50.0)
        assertNotNull(rs)
        val out = rs!!
        assertEquals(50.0, out.fsHz, 1e-12)

        for (i in out.tMs.indices) {
            assertEquals("grid step at $i", i * 20.0, out.tMs[i], 1e-9)
            // Linear interpolation of a linear signal must be exact.
            assertEquals("value at $i", out.tMs[i] / 1000.0, out.signal[i], 1e-6)
        }
    }

    @Test
    fun resampleRejectsInsufficientData() {
        assertNull(PpgSignalProcessor.resample(DoubleArray(3), DoubleArray(3)))
        // Span shorter than one second cannot support the 0.7 Hz lower cut-off.
        assertNull(
            PpgSignalProcessor.resample(
                DoubleArray(10) { it * 50.0 }, DoubleArray(10) { it.toDouble() }
            )
        )
    }

    @Test
    fun detrendRemovesLinearBaselineExactly() {
        val x = DoubleArray(200) { i -> 5.0 + 3.0 * i }
        val y = PpgSignalProcessor.detrend(x)
        for (v in y) assertEquals(0.0, v, 1e-8)
    }

    @Test
    fun bandPassRemovesBaselineDriftAndKeepsPulseBand() {
        val fs = PpgSignalProcessor.ANALYSIS_HZ
        val n = 1000
        val driftHz = 0.10
        val pulseHz = 1.50
        val x = DoubleArray(n) { i ->
            sin(2.0 * PI * driftHz * i / fs) + sin(2.0 * PI * pulseHz * i / fs)
        }

        val y = PpgSignalProcessor.bandPass(PpgSignalProcessor.detrend(x), fs)

        val driftBefore = dftMagnitude(x, fs, driftHz)
        val driftAfter = dftMagnitude(y, fs, driftHz)
        val pulseAfter = dftMagnitude(y, fs, pulseHz)

        assertTrue(
            "0.10 Hz drift must be strongly attenuated (before=$driftBefore after=$driftAfter)",
            driftAfter < 0.05 * driftBefore
        )
        assertTrue(
            "1.50 Hz pulse component must survive (got $pulseAfter)",
            pulseAfter > 0.75 && pulseAfter < 1.25
        )
    }

    // ------------------------------------------------------------------------------------------
    // HrvCalculator / ArtifactRejection / SignalQualityEvaluator
    // ------------------------------------------------------------------------------------------

    @Test
    fun hrvCalculatorRefusesTooFewIntervals() {
        assertNull(HrvCalculator.rmssd(List(19) { 800.0 }))
        assertNull(HrvCalculator.sdnn(List(19) { 800.0 }))
        assertNull(HrvCalculator.compute(List(19) { 800.0 }))
        assertNull(HrvCalculator.compute(emptyList()))
    }

    @Test
    fun hrvCalculatorMatchesAnalyticValues() {
        // Alternating 1000/1100 ms NNs ⇒ RMSSD is exactly 100 ms.
        val nn = (0 until 21).map { if (it % 2 == 0) 1000.0 else 1100.0 }
        val m = HrvCalculator.compute(nn)
        assertNotNull(m)
        assertEquals(21, m!!.nnCount)
        assertEquals(100.0, m.rmssdMs, 1e-6)
        // Sample standard deviation (N−1 denominator) ≈ 51.18 ms; a population denominator would
        // give ≈ 49.94 ms, so the 0.1 ms tolerance also pins down the convention.
        assertEquals(51.18, m.sdnnMs, 0.1)
        assertEquals(60000.0 / m.meanIbiMs, m.heartRateBpm, 1e-9)
    }

    @Test
    fun artifactRejectionDropsImpossibleAndOutlierIntervals() {
        // Mostly 1000 ms NNs with one 250 ms (physiologically impossible), one 3000 ms (impossible)
        // and one 1600 ms (possible but a clear outlier against the 1000 ms core).
        val rr = listOf(
            1000.0, 1000.0, 1000.0, 250.0, 1000.0, 1000.0, 1600.0,
            1000.0, 1000.0, 1000.0, 3000.0
        )
        val times = ArrayList<Double>()
        var t = 0.0
        for (r in rr) {
            times.add(t)
            t += r
        }
        repeat(25) {
            times.add(t)
            t += 1000.0
        }
        times.add(t)

        val peaks = times.map {
            PulsePeak(it, 1.0, 1.0, PeakConditions(true, true, true, true))
        }
        val v = ArtifactRejection.validate(peaks)

        assertEquals(times.size - 1, v.totalIntervals)
        assertEquals(1, v.rejectionReasons["too_short"] ?: -1)
        assertEquals(1, v.rejectionReasons["too_long"] ?: -1)
        assertTrue("expected the 1600 ms outlier to be rejected", (v.rejectionReasons["outlier"] ?: 0) >= 1)
        assertEquals(v.totalIntervals, v.validNn.size + v.rejectedIntervals)
        for (nn in v.validNn) assertTrue("surviving NN $nn is not ~1000 ms", nn in 950.0..1050.0)
        assertTrue("only ${v.validNn.size} NN survived", v.validNn.size >= 30)
    }

    @Test
    fun artifactRejectionHandlesDegenerateInput() {
        val empty = ArtifactRejection.validate(emptyList())
        assertTrue(empty.validNn.isEmpty())
        assertEquals(0, empty.totalIntervals)
        assertEquals(0, empty.rejectedIntervals)
        assertEquals(0.0, empty.validPercent, 1e-9)
    }

    @Test
    fun qualityEvaluatorBandsAreOrdered() {
        val goodValidation = IbiValidation(
            validNn = List(60) { 850.0 }, totalIntervals = 62,
            rejectedIntervals = 2, rejectionReasons = emptyMap()
        )
        val good = SignalQualityEvaluator.evaluate(
            HrvMetrics(40.0, 60.0, 857.0, 70.0, 60), goodValidation, 0.05
        )
        assertEquals(SignalQuality.GOOD, good.quality)
        assertTrue("confidence ${good.confidence} out of range", good.confidence in 0.0..1.0)

        val badValidation = IbiValidation(
            validNn = List(21) { 400.0 }, totalIntervals = 60,
            rejectedIntervals = 39, rejectionReasons = emptyMap()
        )
        val bad = SignalQualityEvaluator.evaluate(
            HrvMetrics(90.0, 120.0, 400.0, 150.0, 21), badValidation, 1.0
        )
        assertEquals(SignalQuality.POOR, bad.quality)
        assertTrue(bad.confidence < 0.55)
        assertTrue(bad.reason.isNotBlank())
    }

    // ------------------------------------------------------------------------------------------
    // End-to-end: synthetic capture → known ground truth
    // ------------------------------------------------------------------------------------------

    private class Analysis(
        val metrics: HrvMetrics?,
        val validation: IbiValidation,
        val peaks: List<PulsePeak>
    )

    /** Runs every production stage exactly as the app does and keeps the intermediates. */
    private fun analyseCapture(samples: List<PpgSample>): Analysis? {
        if (samples.size < 4) return null
        val t0 = samples[0].timeMs
        val times = DoubleArray(samples.size) { samples[it].timeMs - t0 }
        val values = DoubleArray(samples.size) { samples[it].green }
        val rs = PpgSignalProcessor.resample(times, values) ?: return null
        val filtered = PpgSignalProcessor.bandPass(
            PpgSignalProcessor.detrend(rs.signal), rs.fsHz
        )
        val peaks = PulseDetector.detectPeaks(filtered, 1000.0 / rs.fsHz, PulseDetector.State())
        val validation = ArtifactRejection.validate(peaks)
        return Analysis(HrvCalculator.compute(validation.validNn), validation, peaks)
    }

    @Test
    fun steadyHeartRateIsRecoveredFromSyntheticCapture() {
        val rr = 60000.0 / 72.0 // 833.33 ms ⇒ 72 BPM
        val analysis = analyseCapture(acquire(beats(listOf(rr), 45.0), 45.0))
        assertNotNull("pipeline produced nothing", analysis)
        val m = analysis!!.metrics
        assertNotNull("HRV metrics missing", m)

        assertEquals(72.0, m!!.heartRateBpm, 4.0)
        assertTrue("NN count ${m.nnCount} is too low", m.nnCount >= 40)
        assertTrue("steady HR RMSSD should be small, got ${m.rmssdMs}", m.rmssdMs < 40.0)
        assertTrue("steady HR SDNN should be small, got ${m.sdnnMs}", m.sdnnMs < 40.0)
    }

    @Test
    fun heartRateVariabilityIsRecoveredFromSyntheticCapture() {
        val rr = listOf(800.0, 870.0, 790.0, 850.0, 820.0, 840.0, 810.0, 860.0)
        val expectedRmssd = trueRmssd(rr)

        val analysis = analyseCapture(acquire(beats(rr, 45.0), 45.0))
        assertNotNull(analysis)
        val m = analysis!!.metrics
        assertNotNull(m)

        assertEquals(60000.0 / rr.average(), m!!.heartRateBpm, 6.0)
        assertTrue("NN count ${m.nnCount} is too low", m.nnCount >= 40)
        assertTrue(
            "RMSSD measured ${m.rmssdMs} ms, ground truth $expectedRmssd ms",
            kotlin.math.abs(m.rmssdMs - expectedRmssd) <= 35.0
        )
    }

    @Test
    fun syntheticCapturePassesTheQualityGate() {
        val rr = listOf(800.0, 870.0, 790.0, 850.0, 820.0, 840.0, 810.0, 860.0)
        val analysis = analyseCapture(acquire(beats(rr, 45.0), 45.0))
        assertNotNull(analysis)
        val a = analysis!!
        assertNotNull(a.metrics)

        val assessment = SignalQualityEvaluator.evaluate(
            a.metrics!!, a.validation, PulseDetector.amplitudeVariation(a.peaks)
        )
        assertTrue(
            "clean synthetic capture scored ${assessment.quality} (${assessment.confidence})",
            assessment.quality != SignalQuality.POOR
        )
        assertTrue(assessment.confidence >= 0.55)
    }

    @Test
    fun noDataProducesNoResultInsteadOfAFabricatedValue() {
        // Short/empty inputs must yield null — never a made-up HR or HRV number.
        assertNull(analyseCapture(emptyList()))
        assertNull(analyseCapture(listOf(PpgSample(0.0, 120.0, 120.0, 120.0))))
        assertNull(HrvCalculator.compute(emptyList()))
        assertNull(HrvCalculator.compute(listOf(800.0, 900.0, 700.0)))
    }
}