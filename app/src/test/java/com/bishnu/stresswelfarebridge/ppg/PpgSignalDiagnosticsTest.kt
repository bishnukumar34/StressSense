package com.bishnu.stresswelfarebridge.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Tests for the staged "is the signal OK, and if not, where does it break?" diagnostic, plus the
 * guarantee that the new detector bookkeeping cannot influence detection.
 */
class PpgSignalDiagnosticsTest {

    @Test
    fun noFramesIsReportedBeforeAnyOtherProblem() {
        // Everything else looks perfect, but nothing arrived: that is the answer.
        assertEquals(
            SignalStage.NO_FRAMES,
            PpgSignalDiagnostics.classify(
                frames = 0,
                samples = 0,
                levelMean = 0.0,
                rawStd = 5.0,
                filteredStd = 5.0,
                peaks = 30,
                validNn = 30
            )
        )
    }

    @Test
    fun darkAndSaturatedAreDistinctStagesWithDistinctAdvice() {
        assertEquals(
            SignalStage.SIGNAL_TOO_DARK,
            PpgSignalDiagnostics.classify(frames = 100, samples = 100, levelMean = 3.0)
        )
        assertEquals(
            SignalStage.SIGNAL_SATURATED,
            PpgSignalDiagnostics.classify(frames = 100, samples = 100, levelMean = 255.0)
        )
        assertTrue(SignalStage.SIGNAL_SATURATED.remedy != SignalStage.SIGNAL_TOO_DARK.remedy)
        // A level inside the usable range is not flagged for brightness at all.
        assertEquals(
            SignalStage.NO_PULSATILE_VARIATION,
            PpgSignalDiagnostics.classify(frames = 100, samples = 100, levelMean = 128.0)
        )
    }

    @Test
    fun aWellLitButFlatSignalIsReportedAsNoPulsatileVariation() {
        // This is the real-device symptom: frames arrive, brightness is fine, and the waveform is
        // flat. It must be named as an acquisition problem, not as "not enough beats".
        val report = PpgSignalDiagnostics.report(
            frames = 1200,
            samples = 1200,
            levelMean = 137.0,
            levelMin = 136.0,
            levelMax = 138.0,
            levelStd = 0.4,
            rawStd = 0.02,
            filteredStd = 0.01,
            peaks = 2,
            validNn = 0
        )
        assertEquals(SignalStage.NO_PULSATILE_VARIATION, report.stage)
        assertTrue(report.headline().contains("No pulsatile variation"))
        assertTrue(report.detail().contains("stage=NO_PULSATILE_VARIATION"))
    }

    @Test
    fun aPulsatileSignalWhoseBeatsAreAllRejectedIsReportedAsTooFewBeats() {
        assertEquals(
            SignalStage.TOO_FEW_BEATS,
            PpgSignalDiagnostics.classify(
                frames = 1200,
                samples = 1200,
                levelMean = 130.0,
                rawStd = 2.0,
                filteredStd = 1.8,
                peaks = 3,
                validNn = 1
            )
        )
    }

    @Test
    fun readyRequiresEveryStageToPass() {
        assertEquals(
            SignalStage.NO_BEATS_DETECTED,
            PpgSignalDiagnostics.classify(
                frames = 1200, samples = 1200, levelMean = 130.0,
                rawStd = 2.0, filteredStd = 1.8, peaks = 0, validNn = 0
            )
        )
        assertEquals(
            SignalStage.READY,
            PpgSignalDiagnostics.classify(
                frames = 1200, samples = 1200, levelMean = 130.0,
                rawStd = 2.0, filteredStd = 1.8, peaks = 60, validNn = 55
            )
        )
    }

    @Test
    fun theDiagnosticNeverInspectsAnHrvValue() {
        // The classifier takes only acquisition and signal statistics — there is deliberately no
        // RMSSD/SDNN parameter, so a highly variable person can never be pushed into a failure
        // stage. This is the regression guard for that design decision.
        val methods = PpgSignalDiagnostics::class.java.declaredMethods
            .filter { it.name == "classify" || it.name == "report" }
        assertTrue(methods.isNotEmpty())
        for (m in methods) {
            for (p in m.parameterTypes) {
                assertTrue(
                    "stage logic must not take an HrvMetrics parameter, found $p",
                    p != HrvMetrics::class.java
                )
            }
        }
    }

    // ------------------------------------------------------------------ detector instrumentation

    /** A clean pulse train with a dicrotic wave, as a fingertip recording would look. */
    private fun syntheticPulse(rrMs: Double = 800.0, fsHz: Double = 50.0, beats: Int = 40): DoubleArray {
        val dtMs = 1000.0 / fsHz
        val n = ((beats * rrMs) / dtMs).toInt()
        return DoubleArray(n) { i ->
            val t = i * dtMs
            var v = 0.0
            var k = 0
            while (k * rrMs <= t) {
                val u = t - k * rrMs
                v += exp(-(u - 180.0) * (u - 180.0) / (2.0 * 55.0 * 55.0))
                v += 0.45 * exp(-(u - 420.0) * (u - 420.0) / (2.0 * 75.0 * 75.0))
                k++
            }
            v
        }
    }

    @Test
    fun enablingDetectorDiagnosticsCannotChangeTheDetectedBeats() {
        val signal = syntheticPulse()
        val dtMs = 20.0

        val plain = PulseDetector.detectPeaks(signal, dtMs, PulseDetector.State())

        val traced = PulseDetector.State().apply { collectDiagnostics = true }
        val instrumented = PulseDetector.detectPeaks(signal, dtMs, traced)

        // Identical beat trains: the counters are written beside the decision, never into it.
        assertEquals(plain.size, instrumented.size)
        assertTrue(plain.isNotEmpty())
        for (i in plain.indices) {
            assertEquals(plain[i].timeMs, instrumented[i].timeMs, 1e-9)
        }

        val d = traced.diagnostics
        assertEquals(plain.size, d.accepted)
        assertTrue("candidates should be observed, got ${d.candidates}", d.candidates >= d.accepted)
        assertTrue("amplitude extent should be recorded, got ${d.peakToPeak}", d.peakToPeak > 0.0)
        assertTrue(d.summary().contains("accepted=${plain.size}"))
    }

    @Test
    fun aFlatSignalProducesNoAcceptedBeatsAndSaysSo() {
        val flat = DoubleArray(2000) { 0.0 }
        val state = PulseDetector.State().apply { collectDiagnostics = true }
        val peaks = PulseDetector.detectPeaks(flat, 20.0, state)
        assertEquals(0, peaks.size)
        assertEquals(0, state.diagnostics.accepted)
        // A flat trace has no swing at all, the numeric signature of "no pulse in the data".
        assertEquals(0.0, state.diagnostics.peakToPeak, 1e-12)
    }

    @Test
    fun theReportedStageSurvivesACompletePipelineRunOnAFlatSignal() {
        // End-to-end: flat "camera" samples must be attributed to the acquisition stage, never to
        // beat detection, and must not produce a fabricated HRV value.
        val samples = (0 until 1200).map { PpgSample(it * 20.0, 137.0, 137.0, 137.0) }
        val times = DoubleArray(samples.size) { samples[it].timeMs }
        val values = DoubleArray(samples.size) { samples[it].green }
        val resampled = PpgSignalProcessor.resample(times, values)
        assertNotNull(resampled)
        val detrended = PpgSignalProcessor.detrend(resampled!!.signal)
        val filtered = PpgSignalProcessor.bandPass(detrended, resampled.fsHz)
        val peaks = PulseDetector.detectPeaks(filtered, 1000.0 / resampled.fsHz, PulseDetector.State())
        val validation = ArtifactRejection.validate(peaks)
        assertEquals(0, peaks.size)
        assertEquals(0, validation.validNn.size)
        // No NN intervals means no metrics at all — never an invented number.
        assertTrue(HrvCalculator.compute(validation.validNn) == null)

        assertEquals(
            SignalStage.NO_PULSATILE_VARIATION,
            PpgSignalDiagnostics.classify(
                frames = samples.size,
                samples = samples.size,
                levelMean = 137.0,
                rawStd = stdOf(detrended),
                filteredStd = stdOf(filtered)
            )
        )
    }

    private fun stdOf(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var sum = 0.0
        for (v in x) sum += v
        val mean = sum / x.size
        var sq = 0.0
        for (v in x) sq += (v - mean) * (v - mean)
        return if (sq > 0.0) kotlin.math.sqrt(sq / x.size) else 0.0
    }

    @Test
    fun levelStatisticsAreReportedEvenWhenNothingElseIsKnown() {
        // Fewer than MIN_FRAMES_FOR_STAGE samples, so the buffer is still filling; the brightness
        // statistics are still reported even though the stage verdict is "too few frames".
        val report = PpgSignalDiagnostics.report(
            frames = 4,
            samples = 4,
            levelMean = 91.5,
            levelMin = 90.0,
            levelMax = 93.0,
            levelStd = 0.8
        )
        assertEquals(SignalStage.TOO_FEW_FRAMES, report.stage)
        assertTrue(report.detail().contains("mean=91.5"))
        assertTrue(report.detail().contains("stage=TOO_FEW_FRAMES"))
        assertTrue(report.levelMax > report.levelMin)
    }
}

