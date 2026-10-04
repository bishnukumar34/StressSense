package com.bishnu.stresswelfarebridge.auth

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Minimal persistence port.
 *
 * The project has no database, so accounts and records are stored as JSON in the app's existing
 * SharedPreferences. Hiding that behind this interface keeps the whole auth/domain layer testable on
 * a plain JVM (the tests use [InMemoryKeyValueStore]) and keeps a future backend swap to one file.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/** Android implementation backed by the app's existing "stresssense" preferences. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/** In-memory store for unit tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() { map.clear() }
}

/** Outcome of a register/login attempt. Failures carry displayable messages, never credentials. */
sealed class AuthResult {
    data class Success(val session: Session) : AuthResult()
    data class Failure(val issues: List<AuthIssue>, val message: String) : AuthResult()
}

/**
 * Accounts and session management.
 *
 * Responsibilities kept deliberately narrow and explicit:
 *  - register with duplicate prevention (case-insensitive username) and hashed passwords;
 *  - login verifies the password against the stored salt + hash, never against plaintext;
 *  - the active session is an opaque random token, so a persisted session reveals nothing about the
 *    password and cannot be derived from it;
 *  - logout clears the session but **keeps the accounts**, so a user can sign back in.
 */
class AccountStore(
    private val accounts: KeyValueStore,
    private val sessionStore: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    // -- accounts --------------------------------------------------------------------------------

    fun allAccounts(): List<Account> = readAccounts()

    fun accountById(id: String): Account? = readAccounts().firstOrNull { it.id == id }

    fun accountByUsername(username: String): Account? {
        val key = AuthRules.normalizeUsername(username)
        return readAccounts().firstOrNull { it.username == key }
    }

    fun isUsernameTaken(username: String): Boolean = accountByUsername(username) != null

    /** Creates an account and signs the new user in. */
    fun register(request: RegistrationRequest): AuthResult {
        val issues = AuthRules.validateRegistration(request)
        if (issues.isNotEmpty()) {
            return AuthResult.Failure(issues, "Please correct the highlighted fields.")
        }
        val username = AuthRules.normalizeUsername(request.username)
        if (isUsernameTaken(username)) {
            return AuthResult.Failure(
                listOf(AuthIssue(AuthField.USERNAME, "That username is already registered.")),
                "That username is already registered."
            )
        }
        val (salt, hash) = PasswordHasher.hashWithNewSalt(request.password)
        val account = Account(
            id = idGenerator(),
            fullName = request.fullName.trim(),
            username = username,
            role = request.role,
            serviceId = AuthRules.normalizeServiceId(request.serviceId),
            passwordHash = hash,
            passwordSalt = salt,
            createdAtMs = clock()
        )
        writeAccounts(readAccounts() + account)
        return AuthResult.Success(startSession(account))
    }

    /** Verifies credentials and starts a session. */
    fun login(username: String, password: String): AuthResult {
        val issues = AuthRules.validateLogin(username, password)
        if (issues.isNotEmpty()) {
            return AuthResult.Failure(issues, "Enter your username and password.")
        }
        val account = accountByUsername(username)
        // One generic message for "no such user" and "wrong password" so the login form cannot be
        // used to discover which usernames exist.
        if (account == null || !PasswordHasher.verify(password, account.passwordSalt, account.passwordHash)) {
            return AuthResult.Failure(
                listOf(AuthIssue(AuthField.FORM, "Incorrect username or password.")),
                "Incorrect username or password."
            )
        }
        return AuthResult.Success(startSession(account))
    }

    // -- session --------------------------------------------------------------------------------

    fun currentSession(): Session? {
        val token = sessionStore.getString(KEY_SESSION_TOKEN) ?: return null
        val id = sessionStore.getString(KEY_SESSION_ACCOUNT) ?: return null
        val account = accountById(id) ?: return null
        return Session(token, account.toPublicSummary())
    }

    fun logout() {
        sessionStore.remove(KEY_SESSION_TOKEN)
        sessionStore.remove(KEY_SESSION_ACCOUNT)
    }

    private fun startSession(account: Account): Session {
        val token = SessionTokens.new()
        sessionStore.putString(KEY_SESSION_TOKEN, token)
        sessionStore.putString(KEY_SESSION_ACCOUNT, account.id)
        return Session(token, account.toPublicSummary())
    }


    // -- persistence ----------------------------------------------------------------------------

    private fun readAccounts(): List<Account> {
        val raw = accounts.getString(KEY_ACCOUNTS) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::accountFromJson)
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAccounts(list: List<Account>) {
        val array = JSONArray()
        list.forEach { array.put(accountToJson(it)) }
        accounts.putString(KEY_ACCOUNTS, array.toString())
    }

    private fun accountToJson(account: Account): JSONObject = JSONObject().apply {
        put("id", account.id)
        put("fullName", account.fullName)
        put("username", account.username)
        put("role", account.role.name)
        put("serviceId", account.serviceId ?: JSONObject.NULL)
        put("passwordHash", account.passwordHash)
        put("passwordSalt", account.passwordSalt)
        put("createdAtMs", account.createdAtMs)
    }

    private fun accountFromJson(json: JSONObject): Account? = runCatching {
        Account(
            id = json.getString("id"),
            fullName = json.getString("fullName"),
            username = json.getString("username"),
            role = runCatching { Role.valueOf(json.getString("role")) }.getOrDefault(Role.PERSONNEL),
            serviceId = json.optString("serviceId").takeIf { it.isNotBlank() && it != "null" },
            passwordHash = json.getString("passwordHash"),
            passwordSalt = json.getString("passwordSalt"),
            createdAtMs = json.optLong("createdAtMs", 0L)
        )
    }.getOrNull()

    private companion object {
        const val KEY_ACCOUNTS = "accounts_v1"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_SESSION_ACCOUNT = "session_account"
    }
}

