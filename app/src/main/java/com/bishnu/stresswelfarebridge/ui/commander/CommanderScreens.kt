@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bishnu.stresswelfarebridge.ui.commander

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.Access
import com.bishnu.stresswelfarebridge.commander.PersonnelRecord
import com.bishnu.stresswelfarebridge.commander.StressZone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Commander shell: zone dashboard → zone roster → personnel detail.
 *
 * Navigation is a small explicit stack so the system back gesture behaves like a back button, and
 * so the role decision cannot be sidestepped: the shell is only entered with a commander session,
 * and every read is re-authorised by
 * [com.bishnu.stresswelfarebridge.commander.PersonnelDirectory] when it happens.
 */
@Composable
fun CommanderAppShell(
    session: Session,
    controller: CommanderController,
    onLogout: () -> Unit
) {
    var route by remember { mutableStateOf<CommanderRoute>(CommanderRoute.Zones) }
    var reloadKey by remember { mutableStateOf(0) }
    var state by remember(session, reloadKey) { mutableStateOf(CommanderUiState(loading = true)) }

    // Data is (re)loaded whenever the dashboard is entered or refresh is requested.
    LaunchedEffect(session, reloadKey) {
        state = CommanderUiState(loading = true)
        state = controller.load(session)
    }

    BackHandler(enabled = route != CommanderRoute.Zones) { route = CommanderRoute.Zones }

    when (val current = route) {
        is CommanderRoute.Zones -> ZoneDashboard(
            username = session.account.fullName,
            state = state,
            onZoneClick = { route = CommanderRoute.ZoneList(it) },
            onRefresh = { reloadKey++ },
            onLogout = onLogout
        )

        is CommanderRoute.ZoneList -> ZoneRosterScreen(
            zone = current.zone,
            members = state.members(current.zone),
            onBack = { route = CommanderRoute.Zones },
            onPersonnelClick = { route = CommanderRoute.PersonnelDetail(it) }
        )

        is CommanderRoute.PersonnelDetail -> PersonnelDetailScreen(
            access = controller.personnelDetail(session, current.accountId),
            onBack = { route = CommanderRoute.Zones }
        )
    }
}

/** The three monitoring zones with live counts and explicit loading / empty / error states. */
@Composable
private fun ZoneDashboard(
    username: String,
    state: CommanderUiState,
    onZoneClick: (StressZone) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("StressSense", fontWeight = FontWeight.Bold)
                        Text(
                            "COMMANDER",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Unit stress overview",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Welcome, $username",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
                }
            }

            if (state.loading) item { LoadingBlock("Loading personnel data...") }
            state.error?.let { message -> item { ErrorBlock(message, onRefresh) } }
            if (!state.loading && state.error == null && !state.hasData) {
                item { EmptyBlock("No personnel data available.") }
            }
            if (state.hasData) {
                // Headline counts, read from the same grouped data the zone cards below are built
                // from. Nothing here is derived from an account name or a hardcoded roster: if a
                // person's latest persisted score changes, these numbers move with it.
                item { OverviewSummary(state) }
                items(StressZone.ordered, key = { it.name }) { zone ->
                    ZoneCard(zone, state.count(zone)) { onZoneClick(zone) }
                }
                // Synthetic data notice, stated once for the whole screen rather than on every card.
                item { SyntheticDemoNotice() }
            }

            item { LastUpdatedLine(state.refreshedAtMs) }
            item { DisclaimerCard() }
            item {
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("LOG OUT") }
            }
        }
    }
}

@Composable
private fun ZoneCard(zone: StressZone, count: Int, onClick: () -> Unit) {
    val container = when (zone) {
        StressZone.GREEN -> MaterialTheme.colorScheme.primaryContainer
        StressZone.YELLOW -> MaterialTheme.colorScheme.tertiaryContainer
        StressZone.RED -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (zone) {
        StressZone.GREEN -> MaterialTheme.colorScheme.onPrimaryContainer
        StressZone.YELLOW -> MaterialTheme.colorScheme.onTertiaryContainer
        StressZone.RED -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(zone.emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(zone.title, fontWeight = FontWeight.Bold, color = onContainer)
                Text(zone.description, style = MaterialTheme.typography.bodySmall, color = onContainer)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
                Text("personnel", style = MaterialTheme.typography.labelSmall, color = onContainer)
            }
        }
    }
}


/** The personnel currently filed under one zone. */
@Composable
private fun ZoneRosterScreen(
    zone: StressZone,
    members: List<PersonnelRecord>,
    onBack: () -> Unit,
    onPersonnelClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${zone.emoji} ${zone.title}") },
                navigationIcon = { BackButton(onBack) }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (members.isEmpty()) {
                item { EmptyBlock("No personnel currently in this stress zone.") }
            } else {
                items(members, key = { it.accountId }) { record ->
                    PersonnelRow(record = record, onClick = { onPersonnelClick(record.accountId) })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * One personnel row: identifying information only.
 *
 * Raw health values (HR, HRV, SpO2, sleep…) are deliberately kept out of the roster — the list is
 * for triage, and measurements belong on the detail screen.
 */
@Composable
private fun PersonnelRow(record: PersonnelRecord, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(record.fullName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "ID: ${record.serviceId ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Stress: ${record.zone?.description ?: record.stressLevel}" +
                    (record.stressScore?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Last updated: ${formatTimestamp(record.lastUpdatedMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/** Detailed stress / health profile for one personnel member. */
@Composable
private fun PersonnelDetailScreen(access: Access<PersonnelRecord>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Personnel profile") }, navigationIcon = { BackButton(onBack) })
        }
    ) { padding ->
        when (access) {
            is Access.Denied -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp)
            ) {
                item { ErrorBlock(access.reason, null) }
            }

            is Access.Allowed -> {
                val record = access.value
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SectionCard("PERSONAL INFORMATION") {
                            DetailLine("Name", record.fullName)
                            DetailLine("Personnel ID", record.serviceId ?: "Not provided")
                            DetailLine("Role", "Personnel")
                        }
                    }
                    item {
                        SectionCard("CURRENT STRESS") {
                            DetailLine("Stress score", record.stressScore?.toString() ?: "No score yet")
                            DetailLine("Stress zone", record.zone?.title ?: record.stressLevel)
                            DetailLine("Last updated", formatTimestamp(record.lastUpdatedMs))
                            DetailLine("Confidence", "${record.confidence}%")
                            DetailLine("Real wearable data used", if (record.usingRealData) "Yes" else "No")
                            DetailLine("Baseline days", record.baselineDays.toString())
                            if (record.dataQuality.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    record.dataQuality,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item {
                        SectionCard("HEART DATA (Health Connect)") {
                            DetailLine("Average heart rate", record.avgHr?.let { "%.0f BPM".format(it) } ?: NOT_RECORDED)
                            DetailLine("Resting heart rate", record.restingHr?.let { "%.0f BPM".format(it) } ?: NOT_RECORDED)
                            DetailLine("HRV (RMSSD)", record.avgHrv?.let { "%.1f ms".format(it) } ?: NOT_RECORDED)
                        }
                    }
                    item {
                        SectionCard("ACTIVITY") {
                            DetailLine("Steps", if (record.steps > 0) record.steps.toString() else NOT_RECORDED)
                            DetailLine(
                                "Active minutes",
                                if (record.exerciseMinutes > 0) formatMinutes(record.exerciseMinutes) else NOT_RECORDED
                            )
                        }
                    }
                    item {
                        SectionCard("SLEEP & OTHER") {
                            DetailLine(
                                "Sleep duration",
                                if (record.sleepMinutes > 0) formatMinutes(record.sleepMinutes) else NOT_RECORDED
                            )
                            DetailLine("SpO₂", record.spo2?.let { "%.1f %%".format(it) } ?: NOT_RECORDED)
                            DetailLine(
                                "Respiratory rate",
                                record.respiratoryRate?.let { "%.1f /min".format(it) } ?: NOT_RECORDED
                            )
                        }
                    }
                    item { CameraHrvSection(record) }
                    item { DisclaimerCard() }
                }
            }
        }
    }
}


/**
 * Camera PPG HRV, shown in its own section with its own source label.
 *
 * It is deliberately never merged into the Health Connect heart data above: the two come from
 * different sensors with different validity, and camera PPG is not clinically validated.
 */
@Composable
private fun CameraHrvSection(record: PersonnelRecord) {
    SectionCard("CAMERA HRV (on-device PPG)") {
        val camera = record.cameraHrv
        if (camera == null) {
            DetailLine("Camera HRV", NOT_RECORDED)
        } else {
            DetailLine("RMSSD", "%.0f ms".format(camera.rmssdMs))
            DetailLine("SDNN", "%.0f ms".format(camera.sdnnMs))
            DetailLine("Heart rate", "%.0f BPM".format(camera.heartRateBpm))
            DetailLine("Signal quality", camera.signalQuality)
            DetailLine("Valid beat intervals", camera.validBeatCount.toString())
            DetailLine("Measured", formatTimestamp(camera.measuredAtMs))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Source: on-device camera PPG (${record.cameraHrv?.source ?: "CAMERA_PPG"}). Kept separate " +
                "from Health Connect HRV and never combined with it. Camera PPG is not clinically " +
                "validated and no raw camera data is stored or shared.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -- small shared building blocks ------------------------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LoadingBlock(message: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.height(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyBlock(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Text(
            message,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Text("←", style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun LastUpdatedLine(updatedAtMs: Long?) {
    Text(
        if (updatedAtMs == null || updatedAtMs <= 0L) {
            "No personnel records published yet."
        } else {
            "Personnel data as of ${formatTimestamp(updatedAtMs)}"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DisclaimerCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Text(
            "Stress zones are this application's own research stress estimate (StressEngine), not a " +
                "medical diagnosis. Records shown here belong to the personnel accounts listed and are " +
                "read from this device's store.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatMinutes(minutes: Long): String = "${minutes / 60}h ${minutes % 60}m"

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM, h:mm a")

private const val NOT_RECORDED = "Not recorded"


/**
 * Headline summary for the commander: how many personnel, and how many in each band.
 *
 * Every number is read from [CommanderUiState.overview], which is the same grouping the zone cards
 * below it are built from. There is no account-name lookup and no static roster anywhere in this file:
 * change a person's latest persisted stress score and both the band they appear in and these counts
 * move accordingly.
 */
@Composable
private fun OverviewSummary(state: CommanderUiState) {
    val overview = state.overview
    if (overview == null) return
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Personnel Stress Overview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${overview.totalPersonnel} Personnel",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            StressZone.ordered.forEach { zone ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${zone.emoji}  ${zone.title}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.count(zone)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Anyone with no classifiable result is counted separately and never folded into "low".
            if (overview.unclassified.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${overview.unclassified.size} awaiting data",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One clear statement that this unit's data is synthetic.
 *
 * Stated once here rather than repeated on every card, which keeps the roster readable while still
 * being unambiguous that none of it describes a real person.
 */
@Composable
private fun SyntheticDemoNotice() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Demo Mode", fontWeight = FontWeight.Bold)
            Text(
                "Synthetic demonstration data. It does not represent real individuals and was not " +
                    "collected from Health Connect or any wearable. Each score shown here was produced " +
                    "by the on-device stress model from synthetic physiological inputs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}