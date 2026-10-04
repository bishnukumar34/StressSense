package com.bishnu.stresswelfarebridge.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord
import com.bishnu.stresswelfarebridge.questionnaire.InsightLevel
import com.bishnu.stresswelfarebridge.questionnaire.VerificationStatus

/**
 * The combined view: "how you feel" beside "how your body is responding".
 *
 * Both readings are shown as they actually are. Nothing here averages them, and nothing invents a
 * value: when a dimension has no data the card says so rather than showing a zero, because a zero
 * would read as "definitely fine" when it actually means "we do not know".
 *
 * The interpretation text comes from [com.bishnu.stresswelfarebridge.questionnaire.StressInsightBuilder];
 * this screen only renders it. Keeping the wording in one tested place is what stops the two
 * dimensions quietly collapsing into a single verdict.
 */
@Composable
fun StressInsightScreen(
    state: QuestionnaireUiState,
    onTakeCheckIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val insight = state.insight

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Stress Insight", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Two different readings of the same period. They can disagree.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            DimensionCard(
                heading = "HOW YOU FEEL",
                title = "Perceived Stress",
                score = insight.perceived.score,
                source = insight.perceived.sourceLabel,
                missingText = "Not completed today"
            )
        }

        item {
            DimensionCard(
                heading = "YOUR BODY",
                title = "Physiological Stress Load",
                score = insight.physiological.score,
                source = insight.physiological.sourceLabel,
                missingText = "Awaiting valid data"
            )
        }

        val caveat = state.physiologicalCaveat
        if (caveat != null) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Physiological data is limited, so the body reading may be less reliable.",
                            fontWeight = FontWeight.Bold
                        )
                        Text(caveat, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("What this may mean", fontWeight = FontWeight.Bold)
                    Text(insight.interpretation, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { PerceivedTrendCard(state.history) }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Wellness insight, not a medical diagnosis.", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Button(onClick = onTakeCheckIn, Modifier.fillMaxWidth()) {
                Text(if (state.perceivedScore == null) "Daily Check-in" else "Update Today's Check-in")
            }
        }
    }
}

/**
 * One dimension's card. [score] is null when there is no reading, which is shown as "Not available"
 * rather than 0/100.
 */
@Composable
private fun DimensionCard(
    heading: String,
    title: String,
    score: Int?,
    source: String?,
    missingText: String
) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                heading,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (score != null) "$score / 100" else missingText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                source ?: "No data yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Seven-day perceived-stress series.
 *
 * Kept as a separate card from the physiological charts on purpose: overlaying two different
 * constructs on one axis invites exactly the false comparison this feature exists to avoid.
 */
@Composable
fun PerceivedTrendCard(history: List<DailyPulseRecord>) {
    val recent = history.takeLast(7)
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Perceived Stress - last 7 check-ins", fontWeight = FontWeight.Bold)
            if (recent.isEmpty()) {
                Text(
                    "Not enough data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (r in recent) {
                    Text(
                        "${r.date}: " + listOfNotNull(
                            r.dStress?.let { "stress $it/5" },
                            r.dRest?.let { "rest $it/5" },
                            r.dDuty?.let { "duty $it/5" }
                        ).joinToString(", ").ifEmpty { "not answered" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * The private card shown when the trigger engine says a questionnaire may be offered.
 *
 * The wording deliberately does not mention a score, a threshold or "mental stress". It says a
 * questionnaire is available, which is the whole truth of what the trigger did.
 */
@Composable
fun TriggeredCheckInCard(
    onTake: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Stress Check-in Available", fontWeight = FontWeight.Bold)
            Text(
                "Your recent stress trend has been elevated. A short questionnaire is available " +
                    "to help you check in.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "This is private to you.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onTake, Modifier.fillMaxWidth()) { Text("Take Check-in") }
            OutlinedButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}

/**
 * Development-safe state for an instrument that exists but is not cleared to be shown.
 *
 * DASS-21 is [VerificationStatus.PENDING_WORDING]: the scoring works and is unit-tested, but the
 * item wording still has to come from the official source. Rather than render placeholder questions
 * to a real person, this explains the state and stops. It is visually distinct from a completed
 * questionnaire so nobody can mistake "not available yet" for "you answered and scored zero".
 */
@Composable
fun InstrumentUnavailableCard(
    title: String,
    reason: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Not yet available - this is different from a completed questionnaire.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
