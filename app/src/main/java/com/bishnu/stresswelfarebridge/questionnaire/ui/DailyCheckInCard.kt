package com.bishnu.stresswelfarebridge.questionnaire.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The **Daily Check-in** card on the personnel Home dashboard.
 *
 * ### Why this card was renamed
 * It used to be titled "PERCEIVED STRESS / How you feel today" while actually showing the Daily
 * Check-in. That was wrong twice over:
 *
 *  1. **Wrong instrument.** Perceived Stress is PSS-10, a monthly 0-40 questionnaire shown by
 *     [Pss10Card]. This card is three daily context questions with no score at all. Labelling one
 *     as the other invited a comparison between two different things on two different scales.
 *  2. **Implied a measurement.** "Stress" on a card that produces no number reads as a reading.
 *     It does not produce one - see [com.bishnu.stresswelfarebridge.questionnaire.DailyContext].
 *
 * So the card now says exactly what it is: a daily check-in, optional, and context only.
 *
 * ### What it deliberately does not show
 * No score, no severity word derived from a score, and nothing that could be read as a stress
 * measurement. The three answers are displayed as the plain category they reduce to, or as a
 * "Completed today" state, and nothing more.
 *
 * ### Not gated on the trigger
 * This is the permanent entry point. A person can check in from the first launch and skipping is
 * always allowed; the trigger system only ever adds an *extra* private offer on top of it.
 */
@Composable
fun DailyCheckInCard(
    state: QuestionnaireUiState,
    onStartCheckIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Context only. The three daily answers are deliberately NOT reduced to a number: there is no
    // daily 0-100 stress score anywhere in this app. The only two scored things are PSS-10 (0-40,
    // shown by Pss10Card) and Physiological Stress (0-100, from the ML model).
    val completed = state.todayPulse != null

    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "DAILY CHECK-IN",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "How are you doing today?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            if (completed) {
                Text(
                    "Completed today",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your stress, rest and duty answers are saved as context. " +
                        "They are not a stress score.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStartCheckIn, Modifier.fillMaxWidth()) {
                    Text("Update Today's Check-in")
                }
            } else {
                Text(
                    "Take a moment to share how today is going.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Three quick questions about 15 seconds. It is context, not a stress score. " +
                        "Optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStartCheckIn, Modifier.fillMaxWidth()) {
                    Text("Complete Today's Check-in")
                }
            }

            Text(
                "Your answers are private and are not shown to commanders.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}
