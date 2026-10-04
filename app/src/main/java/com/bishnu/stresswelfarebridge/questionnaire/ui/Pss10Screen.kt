package com.bishnu.stresswelfarebridge.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.questionnaire.Pss10
import com.bishnu.stresswelfarebridge.questionnaire.Pss10Record
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireInstrument

/**
 * The PSS-10 card, result and history.
 *
 * The card is always visible. What changes is its *state*:
 *  - never completed -> "Recommended once a month" + Start
 *  - completed this month -> "Completed this month" + history
 *  - wording pending -> the pending/licensing explanation instead of questions
 *
 * Because PSS-10 is copyrighted and permission is not cleared, the questionnaire itself cannot be
 * shown. Everything around it - monthly availability, the result screen, the history list and the
 * comparison wording - is implemented and works the moment the wording is cleared.
 */
@Composable
fun Pss10Card(
    latest: Pss10Record?,
    available: Boolean,
    prototypePendingLicence: Boolean = true,
    historyCount: Int,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "HOW STRESSED YOU FEEL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Perceived Stress", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    latest != null -> "${latest.score} / ${Pss10.MAX_SCORE}"
                    else -> "Not completed yet"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Based on your answers about how you've been feeling over the past month.",
                style = MaterialTheme.typography.bodySmall
            )

            if (prototypePendingLicence) {
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Prototype questionnaire", fontWeight = FontWeight.Bold)
                        Text(
                            "Formal licensing and verification are required before production " +
                                "deployment. Permission has not been granted; the wording here " +
                                "is prototype text, not cleared for real use.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStart,
                enabled = available,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (available) "Start PSS-10" else "Completed this month") }

            if (historyCount > 0) {
                TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("View PSS-10 History ($historyCount)")
                }
            }
        }
    }
}

/**
 * Result screen shown after a completed PSS-10.
 *
 * Deliberately makes **no** severity claim. PSS-10 has no officially endorsed clinical cut-offs, so
 * presenting unofficial thresholds as clinical categories would misrepresent the instrument. The
 * comparison shown is against the person's own previous results, which is what the instrument can
 * legitimately support.
 */
@Composable
fun Pss10ResultScreen(record: Pss10Record, previous: List<Pss10Record>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Perceived Stress", style = MaterialTheme.typography.titleMedium)
            Text(
                "${record.score} / ${Pss10.MAX_SCORE}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Recorded ${record.month}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (previous.isEmpty()) "First recorded PSS-10 result"
                        else "Compared with your previous PSS-10 results",
                        fontWeight = FontWeight.Bold
                    )
                    if (previous.isEmpty()) {
                        Text(
                            "A baseline has been recorded. Future results will be compared " +
                                "against this one.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        val last = previous.first()
                        val delta = (record.score ?: 0) - (last.score ?: 0)
                        Text(
                            "Previous (${last.month}): ${last.score} / ${Pss10.MAX_SCORE}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            when {
                                delta > 0 -> "Higher than your previous result by $delta points."
                                delta < 0 -> "Lower than your previous result by ${-delta} points."
                                else -> "Unchanged from your previous result."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "This is a self-report score, not a diagnosis. The PSS-10 has no official " +
                            "clinical cut-offs, so no severity category is assigned here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Month-by-month history, newest first. */
@Composable
fun Pss10HistoryScreen(history: List<Pss10Record>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("PSS-10 History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (history.isEmpty()) {
            item { Text("No PSS-10 results recorded yet.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(history.size) { i ->
            val r = history[i]
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(r.month.toString(), fontWeight = FontWeight.Bold)
                    Text("${r.score ?: "-"} / ${Pss10.MAX_SCORE}")
                }
            }
        }
    }
}
/**
 * The PSS-10 questionnaire itself: all ten items, each answered 0-4.
 *
 * ### Why this screen exists in a prototype build
 * PSS-10 is a copyrighted instrument and permission has **not** been granted. The wording is
 * therefore labelled as prototype text both here and in [QuestionnaireInstrument.PSS_ITEM_TEXTS],
 * and must be swapped for the authorised wording before production deployment. The scoring,
 * monthly gating and persistence are unchanged and correct either way.
 *
 * ### Design decisions
 *  - **All ten items are shown at once.** PSS-10 is a single instrument scored as a sum, so a
 *    one-question-at-a-time wizard invites someone to lose track of where they are in a scale they
 *    cannot see. A checklist with visible progress makes "10 items, 0-4 each" self-evident.
 *  - **Submit is disabled until every item is answered.** A PSS-10 summed over 6 of 10 items is not
 *    a PSS-10 score, so partial submissions are refused rather than quietly averaged.
 *  - **The score is never shown here.** Only the result screen shows a 0-40 total.
 */
@Composable
fun Pss10FormScreen(
    answers: Map<Int, Int>,
    onAnswer: (item: Int, value: Int) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val definition = QuestionnaireInstrument.PSS_10
    val answered = answers.size
    val complete = answered >= Pss10.ITEM_COUNT

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("PSS-10", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "In the last month, how often have you felt the following?",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "0 = Never",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "4 = Very often",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Text(
                "$answered of ${Pss10.ITEM_COUNT} answered",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(definition.items.size) { i ->
            val item = definition.items[i]
            val number = i + 1
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "$number. ${item.textKey}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (definition.scale.min..definition.scale.max).forEach { value ->
                            val selected = answers[number] == value
                            FilterChip(
                                selected = selected,
                                onClick = { onAnswer(number, value) },
                                label = { Text("$value") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onSubmit,
                enabled = complete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (complete) "Submit" else "Answer all ${Pss10.ITEM_COUNT} items to submit")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Prototype questionnaire. Formal licensing and verification are required before " +
                    "production deployment; permission has not been granted. Your answers stay " +
                    "private and are not shown to commanders.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}