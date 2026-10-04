package com.bishnu.stresswelfarebridge.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.ml.CameraStressBridge
import com.bishnu.stresswelfarebridge.ml.StressMeasurementRecord
import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import com.bishnu.stresswelfarebridge.welltory.WelltoryResults

/**
 * Home-screen entry point for HRV measurement, offering both real camera paths.
 *
 * Displays only the most recent camera-derived result (source `CAMERA_PPG`) - never a Health
 * Connect value - so the two sources stay visibly distinct.
 *
 * Two genuinely distinct sources, kept visibly separate:
 *
 *  - **"Measure HRV with camera"** - the native on-device CameraX PPG pipeline ([CameraHrvScreen]).
 *    Unchanged and still the default.
 *  - **"Measure HRV with Welltory"** - launches the official Welltory integration, which performs
 *    its own camera measurement and returns HRV to StressSense. This is a separate third-party
 *    acquisition path, **not** part of StressSense's own ML model, and the copy says so.
 *
 * A native failure never silently reroutes to Welltory and vice versa; each button does exactly what
 * it says. While a Welltory attempt is underway the card shows that attempt's real state instead of a
 * stress number, because no score exists until the callback returns and the model has run.
 *
 * @param welltoryState the current Welltory attempt, or null when idle.
 * @param welltoryRecord the most recent persisted Welltory-derived record, so the card can show the
 *   real RMSSD/SDNN and score from that source even after an app restart.
 */
@Composable
fun CameraHrvCard(
    result: CameraHrvResult?,
    stress: CameraStressBridge.CameraStressOutcome?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    welltoryState: WelltoryResults.State? = null,
    welltoryRecord: StressMeasurementRecord? = null,
    welltoryBusy: Boolean = false,
    onMeasureWithWelltory: () -> Unit = {},
    onOpenWelltoryInstall: () -> Unit = {},
    onDismissWelltory: () -> Unit = {}
) {
    Card(
        modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "CAMERA HRV",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                result?.let { "RMSSD %.0f ms • %.0f BPM".format(it.rmssdMs, it.heartRateBpm) }
                    ?: "Measure your pulse with the rear camera",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (result != null) {
                    "Quality %s • %.0f s capture • camera PPG".format(
                        result.signalQuality, result.durationSeconds
                    )
                } else {
                    "Torch on, rest a finger on the lens, stay still for ~40 seconds."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result != null && stress?.score != null) {
                Spacer(Modifier.height(10.dp))
                Text("PHYSIOLOGICAL STRESS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${stress.score} / 100",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Zone: ${stress.zone} - source: camera PPG",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (result != null && stress != null) {
                // No score was issued. Say why, rather than leaving the card silently blank or,
                // worse, implying a stress level that was never actually measured.
                Spacer(Modifier.height(10.dp))
                Text(
                    "PHYSIOLOGICAL STRESS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Not enough history yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stress.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(if (result == null) "Measure HRV with camera" else "Measure again")
            }
            Spacer(Modifier.height(14.dp))
            WelltorySection(
                state = welltoryState,
                record = welltoryRecord,
                busy = welltoryBusy,
                onMeasure = onMeasureWithWelltory,
                onInstall = onOpenWelltoryInstall,
                onDismiss = onDismissWelltory
            )
        }
    }
}

/**
 * The Welltory half of the card: a real, separate measurement option plus whatever state the current
 * attempt is genuinely in.
 *
 * Every state below maps to something that actually happened. In particular there is no branch that
 * renders a stress number the model did not produce: `Scored` shows the real score, and every other
 * state shows words instead.
 */
@Composable
private fun WelltorySection(
    state: WelltoryResults.State?,
    record: StressMeasurementRecord?,
    busy: Boolean,
    onMeasure: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Column {
        OutlinedButton(
            onClick = onMeasure,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Measure HRV with Welltory")
        }
        Text(
            "Uses Welltory's camera measurement and returns HRV data to StressSense.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Progress / result messaging for the current attempt.
        when (state) {
            is WelltoryResults.State.Launching ->
                StatusLine(state.message, isError = false)

            is WelltoryResults.State.InProgress ->
                StatusLine(
                    "Complete the measurement in Welltory, then return here.",
                    isError = false
                )

            is WelltoryResults.State.NotInstalled -> {
                StatusLine(state.reason, isError = true)
                TextButton(onClick = onInstall) { Text("Get Welltory from Play Store") }
            }

            is WelltoryResults.State.Cancelled ->
                StatusLine(state.reason, isError = false)

            is WelltoryResults.State.Rejected ->
                StatusLine(state.reason, isError = true)

            is WelltoryResults.State.Ignored ->
                StatusLine(state.reason, isError = true)

            is WelltoryResults.State.TimedOut ->
                StatusLine(state.reason, isError = true)

            is WelltoryResults.State.Received ->
                StatusLine("Validating the Welltory measurement...", isError = false)

            is WelltoryResults.State.SavedWithoutScore ->
                StatusLine(
                    "Measurement saved. ${state.reason}",
                    isError = false
                )

            is WelltoryResults.State.Scored -> {
                // The only place a Welltory-attempt stress number is shown, and it is the deployed
                // model's output - never Welltory's proprietary stress score.
                Spacer(Modifier.height(6.dp))
                Text(
                    "PHYSIOLOGICAL STRESS (STRESSENSE)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${state.score} / 100",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${state.zone} - scored by StressSense from Welltory HRV",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Idle, or nothing pending: show the last persisted Welltory measurement so the card is
            // still useful after an app restart.
            else -> if (record != null) {
                Text(
                    "Latest Welltory: RMSSD %.0f ms - SDNN %.0f ms".format(
                        record.rmssdMs ?: Double.NaN, record.sdnnMs ?: Double.NaN
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (record.hasScore) {
                    Text(
                        "StressSense stress ${record.stressScore}/100 (${record.stressZone})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Dismiss control for terminal states, so a stale message does not linger on the card.
        if (state != null && state !is WelltoryResults.State.Idle && state !is WelltoryResults.State.Scored) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** One line of status text, coloured by whether it reports a problem. */
@Composable
private fun StatusLine(message: String, isError: Boolean) {
    Spacer(Modifier.height(6.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
