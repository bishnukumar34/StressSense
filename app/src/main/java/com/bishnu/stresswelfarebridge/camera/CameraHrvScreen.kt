package com.bishnu.stresswelfarebridge.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.lifecycle.LifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.bishnu.stresswelfarebridge.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.ppg.SignalQuality
import com.bishnu.stresswelfarebridge.ppg.SignalStage
import java.util.concurrent.Executors
import kotlin.math.abs

/** Pinned analysis geometry: a fixed, known frame cadence is what makes IBI timing trustworthy. */
private const val ANALYSIS_WIDTH = 640
private const val ANALYSIS_HEIGHT = 480
private const val ANALYSIS_FPS = 30

/**
 * Full-screen on-device camera HRV measurement.
 *
 * Flow: CAMERA permission → rear camera preview with torch on → place fingertip over the lens →
 * live PPG waveform + signal-quality indicator while the window is captured → quality-gated result
 * (or an explicit "no reliable result" message carrying no numbers).
 *
 * Results are stored only in [CameraHrvRepository] with source `CAMERA_PPG`; nothing here reads or
 * writes Health Connect records, so a camera measurement can never overwrite a wearable HRV value.
 */
@Composable
fun CameraHrvScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val controller = remember { CameraHrvController() }
    val state by controller.state.collectAsState()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    DisposableEffect(Unit) { onDispose { controller.dispose() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Camera HRV",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { controller.stop(); onClose() }) { Text("Close") }
            }

            when {
                lifecycleOwner == null -> MessagePane(
                    "Camera is not available on this device.", null
                )
                !permissionGranted -> PermissionPane(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
                else -> MeasurementPane(lifecycleOwner, controller, state, onClose)
            }
        }
    }
}

@Composable
private fun PermissionPane(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Camera permission needed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The rear camera and torch act as a photoplethysmography (PPG) sensor to detect your " +
                "pulse. Each frame is reduced to one average intensity value on-device and the frame " +
                "itself is discarded — nothing is stored or transmitted.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Grant camera access") }
    }
}

@Composable
private fun MessagePane(text: String, secondary: String?) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        if (secondary != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                secondary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Binds the rear camera (preview + image analysis with the torch on) and renders the live
 * measurement controls. Unbinds and shuts the analysis executor when the pane leaves composition.
 */
@Composable
private fun MeasurementPane(
    lifecycleOwner: LifecycleOwner,
    controller: CameraHrvController,
    state: CameraHrvUiState,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Requested, not assumed: if the device cannot deliver RGBA the decoder falls
                    // back to a correct YUV → green reconstruction and says so in the log, instead
                    // of reading a luma plane as if it were an RGB channel.
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    // PPG timing quality depends on a stable, known frame cadence, so the analysis
                    // resolution is pinned rather than left to the device default.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                // The frame rate is requested through the Camera2 capture request, which is where
                // CameraX 1.4 exposes it; if the device cannot honour it the measured rate is logged
                // anyway, so the effective FPS is always a fact rather than an assumption.
                runCatching {
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(ANALYSIS_FPS, ANALYSIS_FPS)
                    )
                }.onFailure {
                    Log.d("CameraHrvPpg", "fps request rejected by Camera2 interop: ${it.message}")
                }
                val analysis = analysisBuilder.build()
                val processor = CameraPpgProcessor(controller::onSample, controller::onSources)
                controller.attachSource(processor)
                analysis.setAnalyzer(executor, processor)
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                // The torch result is awaited, not fire-and-forget: a silently failed torch is one
                // of the few ways to end up with a perfectly decodable but completely dark signal.
                camera.cameraControl.enableTorch(true).addListener({
                    val torch = runCatching { camera.cameraInfo.torchState.value }
                    val flash = runCatching { camera.cameraInfo.hasFlashUnit() }
                    Log.d(
                        "CameraHrvPpg",
                        "torch state=${torch.getOrNull()} hasFlash=${flash.getOrNull()}"
                    )
                }, ContextCompat.getMainExecutor(context))
                Log.d(
                    "CameraHrvPpg",
                    "bound analysis resolution=${analysis.resolutionInfo?.resolution} " +
                        "zoom=${camera.cameraInfo.zoomState.value}"
                )
                cameraError = null
            } catch (t: Throwable) {
                cameraError = t.message ?: "Unknown camera error"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching {
                val f = ProcessCameraProvider.getInstance(context)
                if (f.isDone) f.get().unbindAll()
            }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (cameraError == null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            MessagePane("Could not open the camera.", cameraError)
        }

        Card(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                when (state.stage) {
                    HrvStage.COMPLETE -> ResultSection(state, onAgain = { controller.start() }, onClose)
                    HrvStage.INSUFFICIENT -> InsufficientSection(state, onAgain = { controller.start() }, onClose)
                    else -> MeasurementControls(state, onStart = { controller.start() }, onStop = { controller.stop() })
                }
            }
        }
    }
}


/** Plain-language word for the current stage, shown instead of an icon glyph. */
private fun stageWord(stage: HrvStage): String = when (stage) {
    HrvStage.PREPARING -> "Starting camera"
    HrvStage.CHECKING_FINGER -> "Checking finger"
    HrvStage.MEASURING -> "Measuring"
    HrvStage.COMPLETE -> "Complete"
    HrvStage.INSUFFICIENT -> "Not enough signal"
    HrvStage.IDLE -> "Idle"
}

/**
 * Live acquisition checklist: camera, flash, signal, heartbeats, NN intervals.
 *
 * This replaces the old "Quality 0 % / NN 0 / Waiting for signal" screen, which told the user
 * nothing about *why* nothing was happening. Each row resolves independently so a failure is
 * immediately attributable to the camera, the torch, the finger position, or the analysis.
 */
@Composable
private fun AcquisitionChecklist(items: List<AcquisitionChecklistItem>) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(96.dp)
                    )
                    Icon(
                        painter = painterResource(if (item.ok) R.drawable.ic_check_circle else R.drawable.ic_error),
                        contentDescription = null,
                        tint = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
/** Live controls: status, progress, waveform, quality and the start/stop buttons. */
@Composable
private fun MeasurementControls(
    state: CameraHrvUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Text(state.message, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${state.elapsedSeconds}s", style = MaterialTheme.typography.labelSmall)
        Text(
            if (state.signalLocked) "Signal locked" else stageWord(state.stage),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.signalLocked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    Waveform(state.waveform, Modifier.fillMaxWidth().height(84.dp))
    // Live acquisition checklist: every precondition shown resolved or not, so a failure is
    // diagnosable instead of sitting on "Quality 0 %" for the whole capture.
    if (state.checklist.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        AcquisitionChecklist(state.checklist)
    }
    // Stage verdict: names the first broken link of the chain (frames → samples → pulsatile
    // variation → beats → NN) instead of leaving the user with a bare "waiting for signal".
    if (state.diagnostic.isNotBlank() && state.stage != HrvStage.IDLE) {
        Spacer(Modifier.height(6.dp))
        Text(
            state.diagnostic,
            style = MaterialTheme.typography.labelMedium,
            color = when (state.signalStage) {
                SignalStage.READY -> MaterialTheme.colorScheme.primary
                SignalStage.NO_FRAMES, SignalStage.SIGNAL_TOO_DARK, SignalStage.SIGNAL_SATURATED,
                SignalStage.NO_PULSATILE_VARIATION, SignalStage.NO_BEATS_DETECTED ->
                    MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        QualityBadge(state.quality, state.confidence)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                state.liveHeartRateBpm?.let { "%.0f BPM".format(it) } ?: "— BPM",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "NN ${state.nnCount} • rejected ${state.rejectedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = if (state.stage == HrvStage.IDLE) onStart else onStop,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (state.stage == HrvStage.IDLE) "Start measurement" else "Stop")
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Rest the tip of your index finger gently over the rear camera and torch. " +
            "Keep still and breathe normally for the whole window.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun QualityBadge(quality: SignalQuality?, confidence: Double) {
    val label: String
    val color = when (quality) {
        SignalQuality.GOOD -> { label = "GOOD"; MaterialTheme.colorScheme.primary }
        SignalQuality.FAIR -> { label = "FAIR"; MaterialTheme.colorScheme.tertiary }
        SignalQuality.POOR -> { label = "POOR"; MaterialTheme.colorScheme.error }
        null -> { label = "—"; MaterialTheme.colorScheme.onSurfaceVariant }
    }
    Text(
        "Quality $label • ${(confidence * 100).toInt()}%",
        color = color,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium
    )
}

/** Quality-gated result. Numbers are only rendered when a validated result object exists. */
@Composable
private fun ResultSection(state: CameraHrvUiState, onAgain: () -> Unit, onClose: () -> Unit) {
    val result = state.result
    Text(
        "Measurement complete",
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    if (result == null) {
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
    } else {
        QualityBanner(result.signalQuality)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("RMSSD", "%.0f ms".format(result.rmssdMs))
            Metric("SDNN", "%.0f ms".format(result.sdnnMs))
            Metric("Heart rate", "%.0f BPM".format(result.heartRateBpm))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Duration", "%.0f s".format(result.durationSeconds))
            Metric("Valid beats", "%d / %d".format(result.validIbiCount, result.totalIbiCount))
            Metric("Confidence", "%d%%".format((result.confidence * 100).toInt()))
        }
        Spacer(Modifier.height(10.dp))
        Text(state.qualityReason, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "%d of %d beat intervals kept (%.0f%%), %d discarded as artefacts.".format(
                result.validIbiCount,
                result.totalIbiCount,
                result.validPercent,
                result.rejectedIbiCount
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Source: ${result.source} — stored separately; Health Connect wearable HRV is not modified.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Not a medical diagnosis — camera PPG is not clinically validated and does not " +
                "diagnose stress or any condition.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Measure again") }
        Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Done") }
    }
}

/**
 * Explains what the quality band actually means, so "GOOD" is only ever read as *"the signal and
 * the beat intervals passed the plausibility checks"* and FAIR carries an explicit caution. POOR
 * never reaches the result path — the controller routes it to the no-numbers failure screen.
 */
@Composable
private fun QualityBanner(quality: SignalQuality) {
    val title: String
    val body: String
    val container: Color
    val content: Color
    when (quality) {
        SignalQuality.GOOD -> {
            title = "Quality GOOD"
            body = "The signal and the beat-to-beat intervals passed the plausibility checks, so " +
                "this HRV value is consistent with the detected rhythm."
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        SignalQuality.FAIR -> {
            title = "Quality FAIR"
            body = "Usable with caution — some beat intervals were discarded as artefacts. " +
                "Treat this HRV value as indicative and repeat the measurement if it matters."
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
        SignalQuality.POOR -> {
            title = "Quality POOR"
            body = "Not trustworthy — no HRV value would be reported for this quality."
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
    }
    Surface(color = container, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                color = content
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = content)
        }
    }
}

/** Explicit failure path — shows why, and deliberately reports no HRV numbers. */
@Composable
private fun InsufficientSection(state: CameraHrvUiState, onAgain: () -> Unit, onClose: () -> Unit) {
    Text(
        "No reliable result",
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(6.dp))
    Text(state.qualityReason, style = MaterialTheme.typography.bodyMedium)
    if (state.diagnostic.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Signal check: ${state.diagnostic}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "No HRV values are reported or stored for this attempt.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Try again") }
        Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Done") }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Live band-passed PPG waveform (downsampled to a fixed number of points). */
@Composable
private fun Waveform(values: List<Float>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Canvas(modifier) {
        val midY = size.height / 2f
        drawLine(grid, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
        if (values.size < 2) return@Canvas
        val peak = values.maxOf { abs(it) }.coerceAtLeast(1e-4f)
        val stepX = size.width / (values.size - 1)
        val amplitude = midY * 0.9f
        val path = Path()
        for (i in values.indices) {
            val x = i * stepX
            val y = midY - (values[i] / peak) * amplitude
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}
