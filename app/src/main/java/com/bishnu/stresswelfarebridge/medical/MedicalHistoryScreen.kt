package com.bishnu.stresswelfarebridge.medical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Profile summary of the person's medical history.
 *
 * Deliberately a **summary**, not the record. Conditions, allergies, medications and a one-line note
 * that further history exists; the full detail stays behind "View / Edit Medical History". Putting
 * the entire private record on the main profile screen would expose far more than is needed to know
 * that something is on file.
 */
@Composable
fun MedicalHistorySummaryCard(
    history: MedicalHistory?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "MEDICAL HISTORY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))

            if (history == null || !history.hasAnything) {
                Text("No medical history added", style = MaterialTheme.typography.bodyMedium)
            } else {
                val lines = history.summaryLines()
                if (lines.isEmpty()) {
                    Text("No medical history added", style = MaterialTheme.typography.bodyMedium)
                } else {
                    lines.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (history.notes.isNotBlank()) {
                        Text(
                            "Further detail is recorded.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpen, Modifier.fillMaxWidth()) {
                Text(if (history == null) "Add Medical History" else "View / Edit Medical History")
            }
            Text(
                "Private to you. Not shared with commanders.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * The editable medical-history form.
 *
 * Yes/no checkboxes gate the free-text fields, so a person who has no history of a given kind can
 * say so explicitly instead of leaving a field ambiguously blank, and no field is mandatory.
 * State is rememberSaveable so a rotation does not discard half-entered medical information.
 */
@Composable
fun MedicalHistoryForm(
    existing: MedicalHistory?,
    userId: String,
    onSave: (MedicalHistory) -> Unit,
    onClose: () -> Unit
) {
    val base = existing ?: MedicalHistory(userId = userId)
    var hasConditions by rememberSaveable { mutableStateOf(base.hasConditions) }
    var conditions by rememberSaveable { mutableStateOf(base.conditions.joinToString(", ")) }
    var hasAllergies by rememberSaveable { mutableStateOf(base.hasAllergies) }
    var allergies by rememberSaveable { mutableStateOf(base.allergies.joinToString(", ")) }
    var medications by rememberSaveable { mutableStateOf(base.medications) }
    var hasSurgeries by rememberSaveable { mutableStateOf(base.hasSurgeries) }
    var surgeries by rememberSaveable { mutableStateOf(base.surgeries) }
    var hasHospitalizations by rememberSaveable { mutableStateOf(base.hasHospitalizations) }
    var hospitalizations by rememberSaveable { mutableStateOf(base.hospitalizations) }
    var hasInjuries by rememberSaveable { mutableStateOf(base.hasInjuries) }
    var injuries by rememberSaveable { mutableStateOf(base.injuries) }
    var ongoing by rememberSaveable { mutableStateOf(base.ongoingTreatment) }
    var notes by rememberSaveable { mutableStateOf(base.notes) }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Medical History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Only fill in what applies. Every field is optional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        YesNoField("Existing medical conditions", hasConditions, { hasConditions = it }) {
            OutlinedTextField(
                value = conditions,
                onValueChange = { conditions = it },
                label = { Text("Conditions (comma separated)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        YesNoField("Allergies", hasAllergies, { hasAllergies = it }) {
            OutlinedTextField(
                value = allergies,
                onValueChange = { allergies = it },
                label = { Text("Allergies (comma separated)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = medications,
            onValueChange = { medications = it },
            label = { Text("Current medications") },
            modifier = Modifier.fillMaxWidth()
        )

        YesNoField("Previous surgeries", hasSurgeries, { hasSurgeries = it }) {
            OutlinedTextField(
                value = surgeries,
                onValueChange = { surgeries = it },
                label = { Text("Which surgeries, and when") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        YesNoField("Previous hospitalizations", hasHospitalizations, { hasHospitalizations = it }) {
            OutlinedTextField(
                value = hospitalizations,
                onValueChange = { hospitalizations = it },
                label = { Text("Reason and approximate date") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        YesNoField("Significant injuries", hasInjuries, { hasInjuries = it }) {
            OutlinedTextField(
                value = injuries,
                onValueChange = { injuries = it },
                label = { Text("Which injuries, and when") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = ongoing,
            onValueChange = { ongoing = it },
            label = { Text("Ongoing treatment") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Other important medical information") },
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Private to you", fontWeight = FontWeight.Bold)
                Text(
                    "This information is stored only on your account and is not shared with " +
                        "commanders.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = {
                onSave(
                    MedicalHistory(
                        userId = userId,
                        hasConditions = hasConditions,
                        conditions = splitList(conditions),
                        hasAllergies = hasAllergies,
                        allergies = splitList(allergies),
                        medications = medications.trim(),
                        hasSurgeries = hasSurgeries,
                        surgeries = surgeries.trim(),
                        hasHospitalizations = hasHospitalizations,
                        hospitalizations = hospitalizations.trim(),
                        hasInjuries = hasInjuries,
                        injuries = injuries.trim(),
                        ongoingTreatment = ongoing.trim(),
                        notes = notes.trim()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Medical History") }

        OutlinedButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Close") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun YesNoField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        if (checked) {
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

private fun splitList(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
