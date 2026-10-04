package com.bishnu.stresswelfarebridge.camera

import android.os.SystemClock
import android.util.Log
import com.bishnu.stresswelfarebridge.ppg.AcquisitionScore
import com.bishnu.stresswelfarebridge.ppg.ArtifactRejection
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.ppg.HrvCalculator
import com.bishnu.stresswelfarebridge.ppg.HrvMetrics
import com.bishnu.stresswelfarebridge.ppg.IbiValidation
import com.bishnu.stresswelfarebridge.ppg.NnSeriesStats
import com.bishnu.stresswelfarebridge.ppg.PpgSample
import com.bishnu.stresswelfarebridge.ppg.PpgSignalDiagnostics
import com.bishnu.stresswelfarebridge.ppg.PpgSourceKey
import com.bishnu.stresswelfarebridge.ppg.PpgSourceSelector
import com.bishnu.stresswelfarebridge.ppg.PpgAcquisition
import com.bishnu.stresswelfarebridge.ppg.PpgBand
import com.bishnu.stresswelfarebridge.ppg.PpgRegion
import com.bishnu.stresswelfarebridge.ppg.RegionRead
import com.bishnu.stresswelfarebridge.ppg.SignalAcquisitionState
import com.bishnu.stresswelfarebridge.ppg.PpgSignalProcessor
import com.bishnu.stresswelfarebridge.ppg.PulseDetector
import com.bishnu.stresswelfarebridge.ppg.PulsePeak
import com.bishnu.stresswelfarebridge.ppg.QualityAssessment
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import com.bishnu.stresswelfarebridge.ppg.SignalQualityEvaluator
import com.bishnu.stresswelfarebridge.ppg.SignalStage
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifecycle of a single camera HRV measurement session. */
enum class HrvStage {
    /** Nothing running; camera may or may not be open. */
    IDLE,
    /** Camera starting up and the torch warming; nothing is being counted yet. */
    PREPARING,

    /** Camera is live but no usable pulse has been seen yet - the user is being coached. */
    CHECKING_FINGER,
    /** Actively collecting the measurement window. */
    MEASURING,
    /** Finished with a valid, quality-gated result. */
    COMPLETE,
    /** Finished without a trustworthy result — no HRV numbers are shown. */
    INSUFFICIENT
}

/**
 * One line of the live acquisition checklist.
 *
 * The point of this is Phase-13 behaviour: instead of a screen that sits on "Quality 0 %" for the
 * whole capture, the user sees each precondition resolved or not, so a failure is diagnosable.
 */
data class AcquisitionChecklistItem(
    val label: String,
    val ok: Boolean,
    val detail: String = ""
)

/** Everything the UI needs to render one measurement session. */
data class CameraHrvUiState(
    val stage: HrvStage = HrvStage.IDLE,
    val elapsedSeconds: Int = 0,
    val progress: Float = 0f,
    val signalLocked: Boolean = false,
    val meanLevel: Double = 0.0,
    val quality: SignalQuality? = null,
    val confidence: Double = 0.0,
    val qualityReason: String = "",
    val liveHeartRateBpm: Double? = null,
    val liveRmssdMs: Double? = null,
    val nnCount: Int = 0,
    val rejectedCount: Int = 0,
    val waveform: List<Float> = emptyList(),
    val result: CameraHrvResult? = null,
    /** Which stage of the signal chain is failing, reported before any HRV number exists. */
    val signalStage: SignalStage = SignalStage.IDLE,
    /** Short, actionable explanation of [signalStage] for the screen. */
    val diagnostic: String = "",
    /** Sensor-level acquisition verdict, which is independent of HRV quality. */
    val acquisitionState: SignalAcquisitionState = SignalAcquisitionState.NO_SIGNAL,
    /** Live, staged checklist so the user can see exactly what is or is not working. */
    val checklist: List<AcquisitionChecklistItem> = emptyList(),
    /** The (region, band) the pipeline is currently trusting. */
    val sourceLabel: String = "",
    val message: String = MESSAGE_PLACE
) {
    companion object {
        const val MESSAGE_PLACE = "Place your fingertip over the rear camera."
        const val MESSAGE_HOLD = "Hold still — keep the finger covering the lens."
        const val MESSAGE_MEASURING = "Measuring — keep still and breathe normally."
    }
}

/**
 * Orchestrates the camera PPG → HRV pipeline:
 *
 *   frames → [CameraPpgProcessor] → [PpgSignalProcessor] (resample / detrend / band-pass)
 *         → [PulseDetector] (4-condition adaptive peaks) → [ArtifactRejection]
 *         → [HrvCalculator] → [SignalQualityEvaluator] → [CameraHrvResult]
 *
 * The raw buffer only ever holds scalars (constant memory), each analysis pass rebuilds its peak
 * state from scratch (deterministic, so a given capture always yields the same numbers), and no
 * value is ever synthesized — if the pipeline cannot produce a trustworthy answer the session ends
 * in [HrvStage.INSUFFICIENT] with no numbers displayed.
 */
class CameraHrvController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _state = MutableStateFlow(CameraHrvUiState())
    val state: StateFlow<CameraHrvUiState> = _state.asStateFlow()

    private val bufferLock = Any()
    private val samples = ArrayList<PpgSample>()

    /**
     * One short rolling series per candidate (region, band) source.
     *
     * Keeping a handful of candidates rather than a single central-green average is what allows the
     * controller to follow a finger that is slightly off-centre, or to fall back to another colour
     * band when the torch has driven green into saturation. Memory is bounded by [MAX_WINDOW_MS]
     * per source and by the shortlist in [SHORTLIST]; at 30 fps this is a few hundred kilobytes.
     */
    private val sourceBuffers = LinkedHashMap<PpgSourceKey, ArrayList<PpgSample>>()
    private val selector = PpgSourceSelector()

    /** Acquisition verdict for the currently selected source; drives the staged user flow. */
    @Volatile
    private var acquisition: AcquisitionScore? = null

    /** Acquisition verdict per candidate source for the most recent evaluation. */
    @Volatile
    private var acquisitionBySource: Map<PpgSourceKey, AcquisitionScore> = emptyMap()

    /** Sources whose most recent frame was saturated or crushed. */
    @Volatile
    private var clippedSources: Set<PpgSourceKey> = emptySet()

    @Volatile
    private var chosen: PpgSourceKey? = null

    private var running = false
    private var startElapsedMs = 0L
    private var wallStartMs = 0L
    private var ticker: Job? = null
    private var lastDebugMs = 0L

    /** Elapsed-realtime at which a usable signal was first seen; 0 until then. */
    private var captureStartedAtMs = 0L

    /** The first-N raw/filtered dump is emitted once per session, never per frame. */
    private var headDumped = false

    /**
     * The frame analyzer feeding this controller. Held only to read its acquisition diagnostics
     * (runtime layout, measured FPS, decoded frame count) and to reset them between sessions; the
     * samples themselves still arrive one at a time through [onSample].
     */
    @Volatile
    private var source: CameraPpgProcessor? = null

    /** Called once by the screen when the analysis use case is built. */
    fun attachSource(processor: CameraPpgProcessor) {
        source = processor
    }

    fun start() {
        synchronized(bufferLock) {
            samples.clear()
            running = true
            startElapsedMs = SystemClock.elapsedRealtime()
            wallStartMs = System.currentTimeMillis()
        }
        source?.resetDiagnostics()
        lastDebugMs = 0L
        headDumped = false
        captureStartedAtMs = 0L
        selector.reset()
        synchronized(bufferLock) { sourceBuffers.clear() }
        chosen = null
        acquisition = null
        acquisitionBySource = emptyMap()
        _state.value = CameraHrvUiState(
            stage = HrvStage.PREPARING,
            message = CameraHrvUiState.MESSAGE_PLACE
        )
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (!running) break
                tick()
            }
        }
    }

    /** Called by [CameraPpgProcessor] once per usable frame (legacy single-scalar path). */
    fun onSample(sample: PpgSample) {
        synchronized(bufferLock) {
            if (!running) return
            samples.add(sample)
            if (samples.size > 2) {
                val newest = samples[samples.size - 1].timeMs
                while (samples.size > 2 && newest - samples[0].timeMs > MAX_WINDOW_MS) {
                    samples.removeAt(0)
                }
            }
        }
    }

    /**
     * Called by [CameraPpgProcessor] once per frame with every decoded (region, band) candidate.
     *
     * Only the shortlist is retained, which bounds both memory and the per-tick scoring cost. The
     * analyzer thread is the only writer, and every buffer is trimmed to [MAX_WINDOW_MS], so a long
     * session cannot grow without limit.
     */
    fun onSources(timeMs: Double, read: RegionRead) {
        synchronized(bufferLock) {
            if (!running) return
            val clipped = HashSet<PpgSourceKey>()
            for (patch in read.samples) {
                val key = PpgSourceKey(patch.region, patch.band)
                if (key !in SHORTLIST) continue
                if (patch.clipped) clipped.add(key)
                val buffer = sourceBuffers.getOrPut(key) { ArrayList() }
                buffer.add(PpgSample(timeMs, patch.mean, patch.mean, patch.mean))
                while (buffer.size > 2 && timeMs - buffer[0].timeMs > MAX_WINDOW_MS) {
                    buffer.removeAt(0)
                }
            }
            clippedSources = clipped
        }
    }

    /** Current candidate source and its acquisition verdict, for the UI and the log. */
    fun currentSource(): PpgSourceKey? = chosen

    fun currentAcquisition(): AcquisitionScore? = acquisition

    /** User-initiated early stop. */
    fun stop() = finish()

    fun dispose() {
        synchronized(bufferLock) { running = false }
        ticker?.cancel()
        ticker = null
    }

    // ---------------------------------------------------------------------------------------
    // Analysis
    // ---------------------------------------------------------------------------------------

    /**
     * Evaluates every shortlisted candidate and re-picks the best one.
     *
     * This is the "automatic recovery" loop: because the shortlist spans several regions and bands,
     * a finger that slides, or a torch setting that saturates the primary band, is recovered from
     * automatically within a second or two — without the user being told to start over.
     *
     * @return the acquisition verdict of the selected source, or null when nothing is available yet.
     */
    private fun refreshSelection(): AcquisitionScore? {
        val snapshot: Map<PpgSourceKey, List<PpgSample>>
        val clipped: Set<PpgSourceKey>
        synchronized(bufferLock) {
            snapshot = sourceBuffers.mapValues { (_, v) -> v.toList() }
            clipped = clippedSources
        }
        if (snapshot.isEmpty()) {
            chosen = null
            acquisition = null
            acquisitionBySource = emptyMap()
            return null
        }

        val scores = LinkedHashMap<PpgSourceKey, AcquisitionScore>()
        for ((key, series) in snapshot) {
            val verdict = scoreSource(series) ?: continue
            scores[key] = verdict
        }
        acquisitionBySource = scores
        if (scores.isEmpty()) {
            chosen = null
            acquisition = null
            return null
        }
        chosen = selector.update(
            scores = scores.mapValues { it.value.score },
            clipped = clipped
        )
        acquisition = chosen?.let(scores::get)
        return acquisition
    }

    /**
     * Runs the acquisition analysis over a short trailing window of one source.
     *
     * Only the last [ACQUISITION_WINDOW_MS] are used, because the question being answered is "what
     * is the signal doing right now", and a short window reacts within a second when the finger moves
     * — a full-length window would keep averaging in the bad setup the user has just corrected.
     */
    private fun scoreSource(series: List<PpgSample>): AcquisitionScore? {
        if (series.size < MIN_SAMPLES) return null
        val tEnd = series[series.size - 1].timeMs
        val tStart = tEnd - ACQUISITION_WINDOW_MS
        val window = series.filter { it.timeMs >= tStart }
        if (window.size < MIN_SAMPLES) return null

        val times = DoubleArray(window.size) { window[it].timeMs - window[0].timeMs }
        val values = DoubleArray(window.size) { window[it].green }
        val resampled = PpgSignalProcessor.resample(times, values) ?: return null
        val detrended = PpgSignalProcessor.detrend(resampled.signal)
        val filtered = PpgSignalProcessor.bandPass(detrended, resampled.fsHz)
        return PpgAcquisition.score(resampled.signal, filtered, resampled.fsHz)
    }

    /** The series the full HRV pipeline should run on: the selected source, else the legacy one. */
    private fun activeBuffer(): List<PpgSample> {
        val key = chosen
        if (key != null) {
            synchronized(bufferLock) {
                sourceBuffers[key]?.let { if (it.size >= MIN_SAMPLES) return it.toList() }
            }
        }
        synchronized(bufferLock) { return samples.toList() }
    }

    /** min / max / mean / std of one analysis stage, in the units of that stage. */
    private data class SeriesStats(
        val min: Double,
        val max: Double,
        val mean: Double,
        val std: Double
    ) {
        override fun toString(): String =
            "min=${fmt(min, 3)} max=${fmt(max, 3)} mean=${fmt(mean, 3)} std=${fmt(std, 3)}"
    }
    private data class Snapshot(
        val lock: Boolean,
        val meanLevel: Double,
        val peaks: List<PulsePeak>,
        val validation: IbiValidation,
        val nnStats: NnSeriesStats,
        val metrics: HrvMetrics?,
        val quality: QualityAssessment?,
        val waveform: List<Float>,
        val spanMs: Double,
        /** Median gap between consecutive camera frames — the acquisition timing diagnostic. */
        val medianFrameIntervalMs: Double,
        /** Frame timestamps that were not strictly increasing and had to be dropped. */
        val droppedTimestamps: Int,
        /** Raw (pre-filter) camera samples as they came out of the frame decoder. */
        val rawStats: SeriesStats,
        /** After DC removal + detrending: the pulsatile content before band-pass filtering. */
        val detrendedStats: SeriesStats,
        /** After the 0.7–3.5 Hz band-pass, i.e. exactly what the peak detector sees. */
        val filteredStats: SeriesStats,
        /** Stage-by-stage verdict; identifies the first failing link of the chain. */
        val report: PpgSignalDiagnostics.Report,
        /** Runtime camera layout (format, planes, strides, channel) as decoded from the device. */
        val layout: String,
        val fps: Double,
        val sampleCount: Int,
        val resampledCount: Int,
        val fsHz: Double,
        /** First [HEAD_DUMP_SAMPLES] filtered values, for the one-shot waveform head dump. */
        val filteredHead: List<Double>,
        /** Per-condition peak-detector counters explaining the accepted/rejected split. */
        val detector: String
    )

    private fun analyse(buffer: List<PpgSample>): Snapshot? {
        if (buffer.size < MIN_SAMPLES) return null
        val t0 = buffer[0].timeMs
        val times = DoubleArray(buffer.size) { buffer[it].timeMs - t0 }
        val values = DoubleArray(buffer.size) { buffer[it].green }
        val spanMs = if (buffer.size < 2) 0.0 else buffer[buffer.size - 1].timeMs - t0

        val resampled = PpgSignalProcessor.resample(times, values) ?: return null
        val detrended = PpgSignalProcessor.detrend(resampled.signal)
        val filtered = PpgSignalProcessor.bandPass(detrended, resampled.fsHz)

        val dtMs = 1000.0 / resampled.fsHz
        val detectorState = PulseDetector.State().apply { collectDiagnostics = DEBUG_LOG }
        val peaks = PulseDetector.detectPeaks(filtered, dtMs, detectorState)
        val validation = ArtifactRejection.validate(peaks)
        val metrics = HrvCalculator.compute(validation.validNn)
        val ampCv = PulseDetector.amplitudeVariation(peaks)
        val quality = metrics?.let { SignalQualityEvaluator.evaluate(it, validation, ampCv) }

        // Finger-contact lock: enough beats detected and raw level neither clipped nor dark.
        val recentMean = values.takeLast(RECENT_FRAMES).average()
        val lock = peaks.size >= MIN_LOCK_BEATS && recentMean in LEVEL_LOW..LEVEL_HIGH

        // Statistics per stage, so a failure can be attributed to acquisition, to filtering or to
        // detection instead of being reported as "no result".
        val rawStats = statsOf(values)
        val detrendedStats = statsOf(detrended)
        val filteredStats = statsOf(filtered)
        val report = PpgSignalDiagnostics.report(
            frames = (source?.frameCount ?: buffer.size.toLong()).toInt(),
            samples = buffer.size,
            levelMean = recentMean,
            levelMin = rawStats.min,
            levelMax = rawStats.max,
            levelStd = rawStats.std,
            // The detrended series is the camera's own pulsatile content with the linear trend
            // removed, i.e. the honest "is there a pulse at all" measure before any filtering.
            rawStd = detrendedStats.std,
            filteredStd = filteredStats.std,
            peaks = peaks.size,
            validNn = validation.validNn.size
        )

        return Snapshot(
            lock = lock,
            meanLevel = recentMean,
            peaks = peaks,
            validation = validation,
            nnStats = ArtifactRejection.stats(validation),
            metrics = metrics,
            quality = quality,
            waveform = downsample(filtered, WAVE_POINTS),
            spanMs = spanMs,
            medianFrameIntervalMs = medianInterval(times),
            droppedTimestamps = resampled.droppedTimestamps,
            rawStats = rawStats,
            detrendedStats = detrendedStats,
            filteredStats = filteredStats,
            report = report,
            layout = source?.lastFrame?.layout() ?: "no frame decoded",
            fps = source?.fps ?: 0.0,
            sampleCount = buffer.size,
            resampledCount = resampled.size,
            fsHz = resampled.fsHz,
            filteredHead = filtered.take(HEAD_DUMP_SAMPLES).toList(),
            detector = detectorState.diagnostics.summary()
        )
    }

    /** min / max / mean / std of a series, in the units of that series. */
    private fun statsOf(x: DoubleArray): SeriesStats {
        if (x.isEmpty()) return SeriesStats(0.0, 0.0, 0.0, 0.0)
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        var sum = 0.0
        var sumSq = 0.0
        for (v in x) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sum += v
            sumSq += v * v
        }
        val mean = sum / x.size
        val variance = (sumSq / x.size) - mean * mean
        return SeriesStats(minV, maxV, mean, if (variance > 0.0) sqrt(variance) else 0.0)
    }

    /** Median positive gap between consecutive acquisition timestamps (frame-timing diagnostic). */
    private fun medianInterval(timesMs: DoubleArray): Double {
        if (timesMs.size < 2) return 0.0
        val gaps = ArrayList<Double>(timesMs.size - 1)
        for (i in 1 until timesMs.size) {
            val g = timesMs[i] - timesMs[i - 1]
            if (g > 0.0) gaps.add(g)
        }
        return if (gaps.isEmpty()) 0.0 else ArtifactRejection.medianOf(gaps)
    }

    private fun downsample(x: DoubleArray, points: Int): List<Float> {
        if (x.isEmpty()) return emptyList()
        if (x.size <= points) return x.map { it.toFloat() }
        val out = ArrayList<Float>(points)
        val bucket = x.size.toDouble() / points
        for (i in 0 until points) {
            val s = (i * bucket).toInt()
            val e = (((i + 1) * bucket).toInt()).coerceAtMost(x.size)
            var sum = 0.0
            var count = 0
            for (k in s until e) { sum += x[k]; count++ }
            out.add(if (count > 0) (sum / count).toFloat() else 0f)
        }
        return out
    }

    private fun tick() {
        val buffer: List<PpgSample>
        synchronized(bufferLock) {
            if (!running) return
            buffer = ArrayList(samples)
        }
        val elapsedSec = ((SystemClock.elapsedRealtime() - startElapsedMs) / 1000L).toInt()

        // Re-evaluate the candidate sources on every tick *before* deciding what to do, so recovery
        // from a bad finger position happens continuously rather than only at session start.
        val currentAcq = refreshSelection()
        val working = if (chosen != null) activeBuffer() else buffer
        val snap = analyse(working)

        if (snap == null) {
            // Nothing analysable yet, but the acquisition side can still be judged: this is where
            // "no frames at all" is distinguished from "frames, but too few to analyse".
            val src = source
            val early = PpgSignalDiagnostics.report(
                frames = (src?.frameCount ?: 0L).toInt(),
                samples = buffer.size,
                levelMean = src?.meanLevel ?: 0.0,
                levelMin = src?.lastFrame?.min ?: 0.0,
                levelMax = src?.lastFrame?.max ?: 0.0,
                levelStd = src?.lastFrame?.std ?: 0.0
            )
            _state.value = CameraHrvUiState(
                stage = if (elapsedSec < 2) HrvStage.PREPARING else HrvStage.CHECKING_FINGER,
                elapsedSeconds = elapsedSec,
                progress = progressOf(elapsedSec),
                signalLocked = false,
                message = CameraHrvUiState.MESSAGE_PLACE,
                signalStage = early.stage,
                diagnostic = early.headline()
            )
            if (DEBUG_LOG && SystemClock.elapsedRealtime() - lastDebugMs >= DEBUG_EVERY_MS) {
                lastDebugMs = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "t=${elapsedSec}s ${early.detail()} layout=${src?.lastFrame?.layout() ?: "none"} " +
                        "fps=${fmt(src?.fps ?: 0.0)}"
                )
            }
            if (elapsedSec >= MAX_SECONDS) finish()
            return
        }

        // The measurement only becomes official once the *sensor* is demonstrably delivering a
        // usable pulse. Before that we keep analysing and coaching — we never publish, and we never
        // count the preparation time against the user.
        val usable = currentAcq != null &&
            currentAcq.state == SignalAcquisitionState.USABLE_SIGNAL
        if (usable && captureStartedAtMs == 0L) captureStartedAtMs = SystemClock.elapsedRealtime()
        val captureSec = if (captureStartedAtMs == 0L) 0 else {
            ((SystemClock.elapsedRealtime() - captureStartedAtMs) / 1000L).toInt()
        }

        val ready = usable &&
            snap.metrics != null && snap.quality != null &&
            snap.validation.validNn.size >= HrvCalculator.MIN_NN &&
            snap.spanMs >= MIN_SPAN_MS

        // Adaptive window: complete as soon as the target window is met with enough validated beats,
        // but never count preparation or a bad-setup period towards it.
        if (captureSec >= TARGET_SECONDS && ready) { finish(); return }
        if (elapsedSec >= MAX_SECONDS) { finish(); return }

        val message = when {
            !usable -> coachingFor(currentAcq, source)
            ready -> CameraHrvUiState.MESSAGE_MEASURING
            else -> CameraHrvUiState.MESSAGE_HOLD
        }
        val stage = when {
            elapsedSec < 2 -> HrvStage.PREPARING
            !usable -> HrvStage.CHECKING_FINGER
            else -> HrvStage.MEASURING
        }
        val checklist = buildChecklist(source, snap, currentAcq)

        _state.value = CameraHrvUiState(
            stage = stage,
            elapsedSeconds = elapsedSec,
            progress = progressOf(captureSec),
            signalLocked = usable,
            meanLevel = snap.meanLevel,
            quality = snap.quality?.quality,
            confidence = snap.quality?.confidence ?: 0.0,
            qualityReason = snap.quality?.reason ?: "",
            liveHeartRateBpm = snap.metrics?.heartRateBpm,
            liveRmssdMs = snap.metrics?.rmssdMs,
            nnCount = snap.validation.validNn.size,
            rejectedCount = snap.validation.rejectedIntervals,
            waveform = snap.waveform,
            signalStage = snap.report.stage,
            diagnostic = snap.report.headline(),
            acquisitionState = currentAcq?.state ?: SignalAcquisitionState.NO_SIGNAL,
            checklist = checklist,
            sourceLabel = chosen?.toString() ?: "",
            message = message
        )

        logTick(elapsedSec, snap)
    }

    /**
     * Turns the acquisition verdict into one actionable instruction.
     *
     * These are the failure modes a real torch-lit fingertip actually produces, and each one maps to
     * a different physical action, so the user is never told "try again" without knowing what to change.
     */
    private fun coachingFor(
        acquisition: AcquisitionScore?,
        processor: CameraPpgProcessor?
    ): String {
        val level = processor?.meanLevel ?: 0.0
        return when {
            processor == null || (processor.frameCount == 0L) ->
                "Starting the camera — keep the rear camera and flash covered."

            level <= PpgAcquisition.SATURATION_LOW ->
                "Almost no light reaches the lens. Check the torch is on and your finger covers it."

            acquisition?.saturationFraction != null &&
                acquisition.saturationFraction > PpgAcquisition.MAX_SATURATION_FRACTION ->
                "The image is blown out to white. Ease the pressure until the level drops below 250."

            acquisition == null || acquisition.state == SignalAcquisitionState.NO_SIGNAL ->
                "Cover both the camera and the flash completely with your fingertip."

            else ->
                "Signal is weak. Keep still, use light steady pressure, or try another finger position."
        }
    }

    /** The live checklist: every precondition resolved or not, in plain language. */
    private fun buildChecklist(
        processor: CameraPpgProcessor?,
        snap: Snapshot?,
        acquisition: AcquisitionScore?
    ): List<AcquisitionChecklistItem> {
        val frames = processor?.frameCount ?: 0L
        val torchOn = frames > 0L && (processor?.meanLevel ?: 0.0) > PpgAcquisition.SATURATION_LOW
        val usable = acquisition?.state == SignalAcquisitionState.USABLE_SIGNAL
        return listOf(
            AcquisitionChecklistItem("Camera", frames > 0L, if (frames > 0L) "$frames frames" else "no frames"),
            AcquisitionChecklistItem("Flash / light", torchOn, if (torchOn) "lit" else "no light detected"),
            AcquisitionChecklistItem(
                "Signal",
                usable,
                chosen?.toString() ?: "searching regions"
            ),
            AcquisitionChecklistItem(
                "Heartbeats",
                (snap?.peaks?.size ?: 0) > 0,
                "${snap?.peaks?.size ?: 0} detected"
            ),
            AcquisitionChecklistItem(
                "NN intervals",
                (snap?.validation?.validNn?.size ?: 0) >= HrvCalculator.MIN_NN,
                "${snap?.validation?.validNn?.size ?: 0} valid"
            )
        )
    }

    /**
     * Throttled acquisition-and-signal trace. Emits *timings, scalars and layout* only — no frame
     * data, nothing written to storage — and at most once every [DEBUG_EVERY_MS] so logcat stays
     * readable. This is the record that answers "is the camera even delivering a pulse?".
     */
    private fun logTick(elapsedSec: Int, snap: Snapshot) {
        if (!DEBUG_LOG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugMs < DEBUG_EVERY_MS) return
        lastDebugMs = now

        val src = source
        Log.d(
            TAG,
            "t=${elapsedSec}s frames=${src?.frameCount ?: 0} undecodable=${src?.undecodableFrames ?: 0} " +
                "fps=${fmt(snap.fps)} frameDt=${fmt(snap.medianFrameIntervalMs)}ms dropTs=${snap.droppedTimestamps}"
        )
        Log.d(TAG, "layout=${snap.layout}")
        Log.d(
            TAG,
            "raw ${snap.rawStats} samples=${snap.sampleCount} → resampled=${snap.resampledCount}@${fmt(snap.fsHz, 1)}Hz"
        )
        Log.d(TAG, "detrended ${snap.detrendedStats}  filtered ${snap.filteredStats}")
        Log.d(TAG, "detector ${snap.detector}")

        // Acquisition evidence: the numbers that explain *why* beats were or were not found, which
        // is the difference between "your finger is in the wrong place" and "your HRV is unusual".
        acquisition?.let { a ->
            Log.d(
                TAG,
                "acq state=${a.state} ac=${fmt(a.acAmplitude, 3)}counts " +
                    "acToDc=${fmt(a.acToDc * 100, 2)}% snr=${fmt(a.snr, 2)} " +
                    "periodicity=${fmt(a.periodicity, 2)} sat=${fmt(a.saturationFraction * 100, 1)}% " +
                    "score=${fmt(a.score, 3)}"
            )
        }
        Log.d(
            TAG,
            "source chosen=$chosen candidates=" +
                acquisitionBySource.entries.joinToString(",") { "${it.key}=${fmt(it.value.score, 2)}" } +
                " clipping=$clippedSources"
        )
        Log.d(
            TAG,
            "${snap.report.detail()} lock=${snap.lock} q=${snap.quality?.quality} " +
                "conf=${fmt(snap.quality?.confidence, 2)} reject=${snap.validation.rejectionReasons}"
        )

        if (!headDumped && snap.sampleCount >= HEAD_DUMP_SAMPLES) {
            headDumped = true
            val headRaw = src?.headSamples ?: emptyList()
            Log.d(TAG, "headRaw(n=${headRaw.size})=" + headRaw.joinToString(",") { fmt(it, 2) })
            Log.d(TAG, "headFiltered=" + snap.filteredHead.joinToString(",") { fmt(it, 3) })
        }
    }

    /**
     * Development trace of the detected beat train: peak timestamps, the raw IBI sequence, the
     * validated NN sequence, the rejection bookkeeping and the final metrics.
     *
     * Only timings and scalars are logged — the raw camera frame was already discarded when its
     * mean intensity was extracted, and nothing here is persisted beyond logcat. This is the
     * evidence needed to see *which* interval produced a suspicious RMSSD.
     */
    private fun logCapture(
        elapsedSec: Int,
        snap: Snapshot?,
        validation: IbiValidation?,
        metrics: HrvMetrics?,
        quality: QualityAssessment?
    ) {
        if (!DEBUG_LOG) return
        if (snap == null) {
            Log.d(TAG, "capture t=${elapsedSec}s: no analysis snapshot (too little signal)")
            return
        }
        Log.d(
            TAG,
            "capture t=${elapsedSec}s span=${fmt(snap.spanMs / 1000.0)}s " +
                "frameDt=${fmt(snap.medianFrameIntervalMs)}ms droppedTs=${snap.droppedTimestamps} " +
                "level=${fmt(snap.meanLevel)}"
        )
        Log.d(TAG, "capture layout=${snap.layout} frames=${source?.frameCount ?: 0} fps=${fmt(snap.fps)}")
        Log.d(
            TAG,
            "capture raw ${snap.rawStats} detrended ${snap.detrendedStats} filtered ${snap.filteredStats}"
        )
        Log.d(TAG, "capture detector ${snap.detector}")
        Log.d(TAG, "capture ${snap.report.detail()}")
        Log.d(
            TAG,
            "beats(${snap.peaks.size}) t_s=" +
                snap.peaks.joinToString(",") { fmt(it.timeMs / 1000.0, 2) }
        )
        val rawIbi = ArrayList<Double>(snap.peaks.size)
        for (i in 1 until snap.peaks.size) rawIbi.add(snap.peaks[i].timeMs - snap.peaks[i - 1].timeMs)
        Log.d(TAG, "rawIbi(${rawIbi.size})_ms=" + rawIbi.joinToString(",") { fmt(it, 0) })
        Log.d(
            TAG,
            "validNN(${validation?.validNn?.size ?: 0})_ms=" +
                (validation?.validNn ?: emptyList<Double>()).joinToString(",") { fmt(it, 0) }
        )
        Log.d(
            TAG,
            "reject=${validation?.rejectionReasons ?: emptyMap<String, Int>()} " +
                "suspectPairs=${validation?.suspectPairCount ?: 0} stats=${snap.nnStats}"
        )
        Log.d(
            TAG,
            "metrics=$metrics quality=${quality?.quality} " +
                "conf=${fmt(quality?.confidence, 3)} reason=${quality?.reason ?: "-"}"
        )
    }

    /** Locale-stable number formatting for the development log. */
    private fun progressOf(elapsedSec: Int): Float =
        (elapsedSec.toFloat() / TARGET_SECONDS).coerceIn(0f, 1f)

    private fun finish() {
        val buffer: List<PpgSample>
        synchronized(bufferLock) {
            if (!running) return
            running = false
            buffer = ArrayList(samples)
        }
        ticker?.cancel()
        ticker = null

        val wallEnd = System.currentTimeMillis()
        val elapsedSec = ((SystemClock.elapsedRealtime() - startElapsedMs) / 1000L).toInt()
        val snap = analyse(buffer)
        val metrics = snap?.metrics
        val quality = snap?.quality
        val validation = snap?.validation
        logCapture(elapsedSec, snap, validation, metrics, quality)

        val usable = snap != null && metrics != null && quality != null && validation != null &&
            snap.spanMs >= MIN_SPAN_MS &&
            validation.validNn.size >= HrvCalculator.MIN_NN &&
            quality.quality != SignalQuality.POOR

        if (!usable) {
            _state.value = CameraHrvUiState(
                stage = HrvStage.INSUFFICIENT,
                elapsedSeconds = elapsedSec,
                progress = 1f,
                signalLocked = snap?.lock ?: false,
                meanLevel = snap?.meanLevel ?: 0.0,
                quality = quality?.quality,
                confidence = quality?.confidence ?: 0.0,
                qualityReason = quality?.reason ?: "Not enough clean signal was captured.",
                nnCount = validation?.validNn?.size ?: 0,
                rejectedCount = validation?.rejectedIntervals ?: 0,
                waveform = snap?.waveform ?: emptyList(),
                // The failing stage is stated explicitly, so "no result" always comes with the
                // reason the signal chain broke rather than a generic "try again".
                signalStage = snap?.report?.stage ?: SignalStage.TOO_FEW_BEATS,
                diagnostic = snap?.report?.headline()
                    ?: SignalStage.TOO_FEW_BEATS.remedy,
                message = "No reliable result — check finger coverage and try again."
            )
            return
        }

        val result = CameraHrvResult(
            rmssdMs = metrics.rmssdMs,
            sdnnMs = metrics.sdnnMs,
            meanIbiMs = metrics.meanIbiMs,
            heartRateBpm = metrics.heartRateBpm,
            measurementStartMs = wallStartMs,
            measurementEndMs = wallEnd,
            durationSeconds = snap.spanMs / 1000.0,
            validIbiCount = validation.validNn.size,
            rejectedIbiCount = validation.rejectedIntervals,
            totalIbiCount = validation.totalIntervals,
            validPercent = validation.validPercent,
            signalQuality = quality.quality,
            confidence = quality.confidence,
            source = CameraHrvResult.SOURCE_CAMERA_PPG,
            sdsdMs = metrics.sdsdMs,
            pnn50Pct = metrics.pnn50Pct
        )
        CameraHrvRepository.publish(result)

        _state.value = CameraHrvUiState(
            stage = HrvStage.COMPLETE,
            elapsedSeconds = elapsedSec,
            progress = 1f,
            signalLocked = true,
            meanLevel = snap.meanLevel,
            quality = quality.quality,
            confidence = quality.confidence,
            qualityReason = quality.reason,
            liveHeartRateBpm = result.heartRateBpm,
            liveRmssdMs = result.rmssdMs,
            nnCount = result.validIbiCount,
            rejectedCount = result.rejectedIbiCount,
            waveform = snap.waveform,
            result = result,
            message = "Measurement complete."
        )
    }

    private companion object {
        const val TICK_MS = 500L

        /**
         * Development trace of the acquisition and signal chain (see [logTick] and [logCapture]).
         * Emits *timings, layout and scalars only* — raw frames are discarded inside
         * [CameraPpgProcessor.analyze] and nothing here is written to storage, so flipping this off
         * only silences logcat. It never changes a measurement outcome.
         */
        const val DEBUG_LOG = true

        /** Minimum interval between acquisition traces, so logcat stays readable. */
        const val DEBUG_EVERY_MS = 3_000L

        /** Number of leading raw/filtered values dumped once per session. */
        const val HEAD_DUMP_SAMPLES = 20

        const val TAG = "CameraHrvPpg"

        /** Trailing window used for the acquisition verdict, in ms. Short so recovery is quick. */
        const val ACQUISITION_WINDOW_MS = 4_000.0

        /**
         * Candidate sources kept and scored. The central patch across all four colour bands
         * covers exposure/skin-tone differences; the four quadrants in green cover an off-centre
         * finger. Eight sources keeps the per-tick scoring cost bounded.
         */
        val SHORTLIST: Set<PpgSourceKey> = buildSet {
            PpgBand.ordered.forEach { add(PpgSourceKey(PpgRegion.CENTRAL, it)) }
            add(PpgSourceKey(PpgRegion.CENTRE_NW, PpgBand.GREEN))
            add(PpgSourceKey(PpgRegion.CENTRE_NE, PpgBand.GREEN))
            add(PpgSourceKey(PpgRegion.CENTRE_SW, PpgBand.GREEN))
            add(PpgSourceKey(PpgRegion.CENTRE_SE, PpgBand.GREEN))
        }
        /** Target capture length; the session auto-completes once enough beats are validated. */
        const val TARGET_SECONDS = 40
        /** Hard cap on a single session. */
        const val MAX_SECONDS = 60
        /** Bounded buffer window. */
        const val MAX_WINDOW_MS = 65_000.0
        const val MIN_SAMPLES = 30
        const val MIN_LOCK_BEATS = 3
        /** Minimum usable signal span before any HRV number is published. */
        const val MIN_SPAN_MS = 20_000.0
        const val RECENT_FRAMES = 20
        const val LEVEL_LOW = 20.0
        const val LEVEL_HIGH = 248.0
        const val WAVE_POINTS = 180
    }
}

/**
 * Locale-stable number formatting for the development log. File-level so the nested [Snapshot]
 * types can use it too.
 */
private fun fmt(value: Double?, decimals: Int = 1): String =
    if (value == null || value.isNaN()) "--"
    else String.format(Locale.US, "%.${decimals}f", value)