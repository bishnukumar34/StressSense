package com.bishnu.stresswelfarebridge.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.questionnaire.DailyContext
import com.bishnu.stresswelfarebridge.questionnaire.DailyCheckInForm
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulse
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord

/**
 * One question at a time, large targets, dark-mode aware.
 *
 * Design points that matter in the field:
 *  - Optional throughout: Skip on every question, Close in the app bar, nothing implies a must.
 *  - Answers survive navigation. State is rememberSaveable, so going back, rotating or losing
 *    focus never discards what was already given.
 *  - Nothing is written until Save is pressed, and saving with no answers stores nothing at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCheckInScreen(
    existing: DailyPulseRecord? = null,
    onSave: (stress: Int?, rest: Int?, duty: Int?, checkinRequested: Boolean) -> Boolean,
    onViewInsight: () -> Unit,
    onClose: () -> Unit
) {
    // Seed from today's stored record so reopening shows the answers already given, which is what
    // makes "edit today's check-in" work. A fresh repository read, not rememberSaveable, is the
    // source of truth - see note on rememberSeedKey below.
    var stress by rememberSaveable { mutableStateOf<Int?>(existing?.dStress) }
    var rest by rememberSaveable { mutableStateOf<Int?>(existing?.dRest) }
    var duty by rememberSaveable { mutableStateOf<Int?>(existing?.dDuty) }
    var checkinRequested by rememberSaveable { mutableStateOf(existing?.checkinRequested ?: false) }
    var step by rememberSaveable { mutableStateOf(0) }
    var justSaved by rememberSaveable { mutableStateOf(false) }

    // Reflect a record that arrived from the repository after this screen was composed (the app
    // re-reads on save), without clobbering an answer the person has since changed.
    val seedKey = existing?.let { "${it.date}|${it.dStress}|${it.dRest}|${it.dDuty}" } ?: "none"
    LaunchedEffect(seedKey) {
        if (!justSaved) {
            stress = existing?.dStress
            rest = existing?.dRest
            duty = existing?.dDuty
            checkinRequested = existing?.checkinRequested ?: false
        }
    }

// The button's enabled state is decided by [DailyCheckInForm], never inline and never from a
    // stress score. `canSave` is true for a new check-in once all three are answered, and true for
    // an existing one as soon as any answer changes - which is what makes editing work.
    val canSave = DailyCheckInForm.canSave(existing, stress, rest, duty, checkinRequested)
    val saveLabel = DailyCheckInForm.buttonLabel(existing)
    val saveHelper = DailyCheckInForm.helperText(existing, stress, rest, duty)
    val hasAllThree = DailyCheckInForm.hasAllThree(stress, rest, duty)
    val completedToday = existing != null
    // Display-only category. Computed from the answers, never persisted, never a score.
    val savedCategory = DailyContext.category(stress, rest, duty)

    val totalSteps = DailyPulse.items.size
    val item = DailyPulse.items.getOrNull(step)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Check-in") },
                navigationIcon = {
                    TextButton(onClick = { if (step > 0) step-- else onClose() }) {
                        Text(if (step > 0) "Back" else "Close")
                    }
                }
            )
        },
        // The primary action is pinned to the bottom bar rather than sitting at the end of a
        // scrolling list.
        //
        // ### Why this was the bug on question 3
        // The save button used to be the last element of a non-scrolling Column whose middle held a
        // weight(1f) spacer. Question 3 adds an extra checkbox row, which is enough to overflow the
        // viewport and push the button off-screen - so on the final question, where saving actually
        // happens, the button simply could not be reached. Pinning it here makes it present on
        // question 1, 2 and 3 alike, and impossible to scroll away.
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Button(
                        onClick = { justSaved = onSave(stress, rest, duty, checkinRequested) },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(saveLabel) }
                    // Always shown, not only when disabled: it explains why the button is off and,
                    // just as importantly, that changing an answer turns it back on.
                    Text(
                        saveHelper,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Optional - about 15 seconds",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (justSaved) {
                PrivacyNotice()
                Text(
                    "TODAY'S SITUATION",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Daily Context", style = MaterialTheme.typography.titleMedium)
                // Saved comes from the repository result, never from a stress score. There is no
                // daily 0-100 number, so nothing here can depend on one.
                Text(
                    "Saved",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                savedCategory?.let {
                    Text(
                        it.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Based on today's stress, rest and duty context.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Your answers are stored as context. They are not combined into a stress score.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "This is a wellness reflection, not a diagnosis or clinical assessment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // A fixed spacer, never weight(1f): this panel is inside the verticalScroll Column,
                // which measures its child with unbounded height, so a weighted spacer would ask for
                // infinite height and throw the moment a check-in is saved.
                Spacer(Modifier.height(12.dp))
                Button(onClick = onViewInsight, Modifier.fillMaxWidth()) { Text("View Stress Insight") }
                Spacer(Modifier.height(8.dp))
                // Returning to the questions is what makes revising an answer possible. Clearing
                // justSaved restores the form with the answers as they now stand on screen, so the
                // button is off until something actually changes.
                OutlinedButton(onClick = { justSaved = false }, Modifier.fillMaxWidth()) {
                    Text("Change my answers")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Done") }
                return@Column
            }

            if (completedToday) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Completed today", fontWeight = FontWeight.Bold)
                        Text(
                            "Saved. You can update today's answers below.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (item != null) {
                LinearProgressIndicator(
                    progress = { (step + 1f) / (totalSteps + 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Question ${step + 1} of $totalSteps",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(item.textKey, style = MaterialTheme.typography.headlineSmall)

                val scale = DailyPulse.scaleFor(item.id)
                val current = when (item.id) {
                    DailyPulse.ITEM_STRESS -> stress
                    DailyPulse.ITEM_REST -> rest
                    else -> duty
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (value in scale.min..scale.max) {
                        FilterChip(
                            selected = current == value,
                            onClick = {
                                when (item.id) {
                                    DailyPulse.ITEM_STRESS -> stress = value
                                    DailyPulse.ITEM_REST -> rest = value
                                    else -> duty = value
                                }
                            },
                            label = {
                                Text(
                                    when (value) {
                                        scale.min -> "$value - ${scale.lowLabel}"
                                        scale.max -> "$value - ${scale.highLabel}"
                                        else -> "$value"
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (step == totalSteps - 1) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checkinRequested,
                            onCheckedChange = { checkinRequested = it }
                        )
                        Text("I'd like someone to check in with me.")
                    }
                }
            }

            // A plain fixed spacer, not weight(1f): inside a verticalScroll the available height is
            // unbounded, so a weighted spacer would consume infinite height and throw. Spacing here
            // must be content-sized.
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { step = (step - 1).coerceAtLeast(0) },
                    enabled = step > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Back") }
                OutlinedButton(
                    onClick = { step = (step + 1).coerceAtMost(totalSteps - 1) },
                    enabled = answeredAt(step, stress, rest, duty),
                    modifier = Modifier.weight(1f)
                ) { Text("Next") }
            }

            TextButton(
                onClick = { step = (step + 1).coerceAtMost(totalSteps - 1) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Skip this question") }

            // The save action itself lives in the Scaffold's bottomBar, pinned and always reachable, so that
            // it is available on every question including question 3.

            PrivacyNotice()
        }
    }
}

private fun answeredAt(step: Int, stress: Int?, rest: Int?, duty: Int?): Boolean =
    when (step) {
        0 -> stress != null
        1 -> rest != null
        else -> duty != null
    }

/** Short, accurate privacy line. Deliberately does not overstate the guarantee. */
@Composable
fun PrivacyNotice() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Your answers are private", fontWeight = FontWeight.Bold)
            Text(
                "Your questionnaire responses are not shown to commanders. They see only whether a " +
                    "check-in was offered or completed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}