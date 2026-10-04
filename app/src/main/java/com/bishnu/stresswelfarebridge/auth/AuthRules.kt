package com.bishnu.stresswelfarebridge.auth

/** Which form field an issue belongs to, so the UI can attach the message to the right input. */
enum class AuthField { FULL_NAME, USERNAME, PASSWORD, CONFIRM_PASSWORD, SERVICE_ID, FORM }

/** A validation problem, safe to display verbatim. Never contains the submitted value. */
data class AuthIssue(val field: AuthField, val message: String)

/** What the registration form submits. Only the fields the project actually needs are collected. */
data class RegistrationRequest(
    val fullName: String,
    val username: String,
    val password: String,
    val confirmPassword: String,
    val role: Role,
    val serviceId: String?
)

/**
 * Field-level validation, kept pure and Android-free so every rule is unit-testable.
 *
 * Nothing here decides whether an account *exists* — duplicate prevention lives in [AccountStore] —
 * and nothing here ever echoes a password back to the caller.
 */
object AuthRules {

    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_LENGTH = 128
    const val MIN_NAME_LENGTH = 2
    const val MAX_NAME_LENGTH = 60
    const val MAX_USERNAME_LENGTH = 80
    const val MAX_SERVICE_ID_LENGTH = 32

    /** Usernames are compared case-insensitively so "Rahul" and "rahul" are the same account. */
    fun normalizeUsername(raw: String): String = raw.trim().lowercase()

    fun normalizeServiceId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    fun validateRegistration(request: RegistrationRequest): List<AuthIssue> = buildList {
        val name = request.fullName.trim()
        if (name.length < MIN_NAME_LENGTH) {
            add(AuthIssue(AuthField.FULL_NAME, "Enter your full name."))
        } else if (name.length > MAX_NAME_LENGTH) {
            add(AuthIssue(AuthField.FULL_NAME, "Name is too long."))
        }

        val username = request.username.trim()
        when {
            username.isEmpty() -> add(AuthIssue(AuthField.USERNAME, "Enter a username or email."))
            username.length > MAX_USERNAME_LENGTH ->
                add(AuthIssue(AuthField.USERNAME, "Username is too long."))
            !username.contains('@') && !username.matches(USERNAME_PATTERN) ->
                add(AuthIssue(AuthField.USERNAME, "Use an email address or 3+ letters/digits/._- ."))
        }

        validatePassword(request.password).forEach(::add)

        if (request.password != request.confirmPassword) {
            add(AuthIssue(AuthField.CONFIRM_PASSWORD, "Passwords do not match."))
        }

        val serviceId = normalizeServiceId(request.serviceId)
        if (serviceId != null && serviceId.length > MAX_SERVICE_ID_LENGTH) {
            add(AuthIssue(AuthField.SERVICE_ID, "Service ID is too long."))
        }
    }

    fun validatePassword(password: String): List<AuthIssue> = buildList {
        if (password.isEmpty()) {
            add(AuthIssue(AuthField.PASSWORD, "Enter a password."))
            return@buildList
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            add(AuthIssue(AuthField.PASSWORD, "Password must be at least $MIN_PASSWORD_LENGTH characters."))
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            add(AuthIssue(AuthField.PASSWORD, "Password is too long."))
        }
        if (password.none { it.isDigit() }) {
            add(AuthIssue(AuthField.PASSWORD, "Password must contain at least one number."))
        }
        if (password.none { it.isLetter() }) {
            add(AuthIssue(AuthField.PASSWORD, "Password must contain at least one letter."))
        }
    }

    fun validateLogin(username: String, password: String): List<AuthIssue> = buildList {
        if (username.isBlank()) add(AuthIssue(AuthField.USERNAME, "Enter your username."))
        if (password.isEmpty()) add(AuthIssue(AuthField.PASSWORD, "Enter your password."))
    }

    private val USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,}$")
}
