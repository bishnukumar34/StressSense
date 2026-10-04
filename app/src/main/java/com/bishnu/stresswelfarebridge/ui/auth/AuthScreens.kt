package com.bishnu.stresswelfarebridge.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bishnu.stresswelfarebridge.auth.AuthField
import com.bishnu.stresswelfarebridge.auth.AuthIssue
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.demo.DemoAccounts

/**
 * Sign-in and registration for both roles.
 *
 * **Login does not ask for a role.** The destination screen is decided by the role stored on the
 * account, not by a toggle on the form — otherwise anyone could claim the commander dashboard by
 * tapping a different chip. The role is chosen once, at registration, and that is the only place
 * the system role can be set.
 *
 * Passwords go straight to the [com.bishnu.stresswelfarebridge.auth.AccountStore] for hashing; this
 * screen never stores, logs or echoes them, and both password fields are masked.
 */
@Composable
fun AuthScreen(
    busy: Boolean,
    formError: String?,
    fieldErrors: Map<AuthField, String>,
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (RegistrationRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var role by rememberSaveable { mutableStateOf(Role.PERSONNEL) }
    var fullName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var serviceId by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val shownError = localError ?: formError

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(82.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "S",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("StressSense", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "AI-based personnel stress & welfare monitoring",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        if (registering) {
            RoleSelector(role) { role = it }
            Spacer(Modifier.height(14.dp))
            AuthFieldInput(
                value = fullName,
                onValueChange = { fullName = it; localError = null },
                label = "Full name",
                error = fieldErrors[AuthField.FULL_NAME]
            )
            Spacer(Modifier.height(10.dp))
        }

        AuthFieldInput(
            value = username,
            onValueChange = { username = it; localError = null },
            label = "Username or email",
            error = fieldErrors[AuthField.USERNAME]
        )
        Spacer(Modifier.height(10.dp))
        AuthFieldInput(
            value = password,
            onValueChange = { password = it; localError = null },
            label = "Password",
            error = fieldErrors[AuthField.PASSWORD],
            isPassword = true
        )

        if (registering) {
            Spacer(Modifier.height(10.dp))
            AuthFieldInput(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; localError = null },
                label = "Confirm password",
                error = fieldErrors[AuthField.CONFIRM_PASSWORD],
                isPassword = true
            )
            Spacer(Modifier.height(10.dp))
            AuthFieldInput(
                value = serviceId,
                onValueChange = { serviceId = it; localError = null },
                label = if (role == Role.COMMANDER) {
                    "Commander ID (optional)"
                } else {
                    "Personnel / service ID (optional)"
                },
                error = fieldErrors[AuthField.SERVICE_ID]
            )
        }

        if (!shownError.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                shownError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (busy) return@Button
                localError = null
                if (registering) {
                    onRegister(
                        RegistrationRequest(
                            fullName = fullName,
                            username = username,
                            password = password,
                            confirmPassword = confirmPassword,
                            role = role,
                            serviceId = serviceId
                        )
                    )
                } else if (username.isBlank() || password.isBlank()) {
                    localError = "Enter your username and password."
                } else {
                    onLogin(username.trim(), password)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !busy
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(if (registering) "CREATE ACCOUNT" else "LOGIN")
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { registering = !registering; localError = null }, enabled = !busy) {
            Text(if (registering) "Already registered? Sign in" else "New here? Create an account")
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Your account keeps your own health and stress data. Passwords are stored only as a " +
                "salted hash, never in plain text.",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        // Login-only: a demo panel has no meaning on the registration form.
        if (!registering) {
            DemoAccountsPanel(
                onPick = { demoUser, demoPassword ->
                    registering = false
                    localError = null
                    username = demoUser
                    password = demoPassword
                }
            )
        }
    }
}

/**
 * Reveals the synthetic demonstration credentials.
 *
 * Collapsed by default so the normal login form is untouched, and explicitly labelled as synthetic.
 * These are published demo credentials for a research prototype and are not production secrets: a
 * reviewer needs them, but a user must not be able to mistake them for real accounts.
 *
 * Tapping an account only fills the existing username and password fields. Authentication still runs
 * through the ordinary [com.bishnu.stresswelfarebridge.auth.AccountStore.login] path, so the demo
 * flow exercises exactly the same credential check as any other login.
 */
@Composable
private fun DemoAccountsPanel(onPick: (username: String, password: String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (expanded) "Hide demo accounts" else "Show demo accounts")
            }
            if (expanded) {
                Text(
                    "Synthetic demonstration accounts. They contain fictional data and do not " +
                        "represent real people. Credentials are published for review and are not " +
                        "production secrets.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                DemoCredentialRow(
                    label = "Commander",
                    username = DemoAccounts.COMMANDER_USERNAME,
                    password = DemoAccounts.COMMANDER_PASSWORD,
                    onPick = onPick
                )
                Spacer(Modifier.height(6.dp))
                // One representative personnel account; the other nine share the same password and
                // follow the same predictable pattern, shown beneath it.
                DemoCredentialRow(
                    label = "Personnel 01",
                    username = DemoAccounts.personnelUsername(1),
                    password = DemoAccounts.PERSONNEL_PASSWORD,
                    onPick = onPick
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "personnel02${DemoAccounts.PERSONNEL_USERNAME_SUFFIX} ... " +
                        "personnel10${DemoAccounts.PERSONNEL_USERNAME_SUFFIX}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "All personnel accounts use the password above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DemoCredentialRow(
    label: String,
    username: String,
    password: String,
    onPick: (String, String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(username, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = { onPick(username, password) }) { Text("Use") }
    }
}


@Composable
private fun RoleSelector(role: Role, onRoleChange: (Role) -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Register as", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Role.entries.forEach { option ->
                    FilterChip(
                        selected = role == option,
                        onClick = { onRoleChange(option) },
                        label = { Text(if (option == Role.COMMANDER) "Commander" else "Personnel") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (role == Role.COMMANDER) {
                    "Monitors personnel across the unit; cannot publish personnel records."
                } else {
                    "Monitors your own health and stress data."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthFieldInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    isPassword: Boolean = false
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text
            ),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            }
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Turns validation issues into a field → message map for the form. */
fun authFieldErrors(issues: List<AuthIssue>): Map<AuthField, String> =
    issues.associate { it.field to it.message }

