package com.bishnu.stresswelfarebridge.auth

/**
 * Core account model for the two-role StressSense system.
 *
 * There was no user model in the project before this: the app kept a bare `username` string in
 * SharedPreferences and accepted any password. These types are the foundation the role system needs
 * — a stable account id that owns records, an explicit role, and a service/personnel id.
 */
enum class Role {
    PERSONNEL,
    COMMANDER;

    val isCommander: Boolean get() = this == COMMANDER
}

/**
 * A registered account.
 *
 * [passwordHash]/[passwordSalt] are the only credential material ever persisted — the plaintext
 * password is never stored, logged, or kept in the session. [toPublicSummary] is what the rest of
 * the app should use when it needs to show who someone is, so a hash can never leak into the UI.
 */
data class Account(
    val id: String,
    val fullName: String,
    val username: String,
    val role: Role,
    val serviceId: String?,
    val passwordHash: String,
    val passwordSalt: String,
    val createdAtMs: Long
) {
    fun toPublicSummary(): AccountSummary = AccountSummary(id, fullName, username, role, serviceId)
}

/** Account identity without any credential material. */
data class AccountSummary(
    val id: String,
    val fullName: String,
    val username: String,
    val role: Role,
    val serviceId: String?
) {
    /** Identifier shown to a commander: the service id when one exists, else the username. */
    val displayIdentifier: String get() = serviceId?.takeIf { it.isNotBlank() } ?: username
}

/**
 * An authenticated session.
 *
 * [token] is an opaque random value, not anything derived from the password, so it reveals nothing
 * if it is ever read from storage. The session identifies the account; every data operation is
 * authorised against it, never against UI state.
 */
data class Session(
    val token: String,
    val account: AccountSummary
) {
    val accountId: String get() = account.id
    val role: Role get() = account.role
    val isCommander: Boolean get() = role.isCommander
}
