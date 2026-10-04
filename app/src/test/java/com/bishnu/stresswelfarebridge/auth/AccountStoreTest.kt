package com.bishnu.stresswelfarebridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auth tests: registration, duplicate prevention, login, sessions, logout and role routing.
 *
 * Everything runs against [InMemoryKeyValueStore], so these exercise the real [AccountStore] logic
 * (hashing included) without Android.
 */
class AccountStoreTest {

    private fun store(
        accounts: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        session: InMemoryKeyValueStore = InMemoryKeyValueStore()
    ): AccountStore = AccountStore(accounts, session, clock = { 1_700_000_000_000L })

    private fun personnelRequest(username: String = "rahul", password: String = "soldier123") =
        RegistrationRequest(
            fullName = "Rahul Kumar",
            username = username,
            password = password,
            confirmPassword = password,
            role = Role.PERSONNEL,
            serviceId = "P1024"
        )

    private fun commanderRequest(username: String = "cmd", password: String = "command7") =
        RegistrationRequest(
            fullName = "Major Mehta",
            username = username,
            password = password,
            confirmPassword = password,
            role = Role.COMMANDER,
            serviceId = "C001"
        )

    private fun AuthResult.successSession(): Session =
        (this as? AuthResult.Success)?.session ?: error("expected success but was $this")

    private fun AuthResult.failureIssues(): List<AuthIssue> =
        (this as? AuthResult.Failure)?.issues ?: error("expected failure but was $this")

    // ------------------------------------------------------------------ registration

    @Test
    fun personnelCanRegister() {
        val session = store().register(personnelRequest()).successSession()
        assertEquals(Role.PERSONNEL, session.role)
        assertEquals("Rahul Kumar", session.account.fullName)
        assertEquals("P1024", session.account.serviceId)
        assertEquals("rahul", session.account.username)
    }

    @Test
    fun commanderCanRegister() {
        val session = store().register(commanderRequest()).successSession()
        assertEquals(Role.COMMANDER, session.role)
        assertTrue(session.isCommander)
        assertEquals("C001", session.account.serviceId)
    }

    @Test
    fun registrationStoresNoPlainTextPassword() {
        val accounts = InMemoryKeyValueStore()
        val s = store(accounts)
        s.register(personnelRequest(password = "soldier123"))

        val raw = accounts.getString("accounts_v1").orEmpty()
        assertTrue("password must not be stored in plain text", !raw.contains("soldier123"))
        val stored = s.accountByUsername("rahul")!!
        assertNotEquals("soldier123", stored.passwordHash)
        assertTrue(stored.passwordSalt.isNotBlank())
    }

    @Test
    fun duplicateRegistrationIsRejectedCaseInsensitively() {
        val s = store()
        s.register(personnelRequest(username = "rahul"))
        val second = s.register(personnelRequest(username = "RaHuL"))
        assertTrue(second.failureIssues().any { it.field == AuthField.USERNAME })
        // The original account is untouched and there is still exactly one.
        assertEquals(1, s.allAccounts().size)
    }

    @Test
    fun mismatchedConfirmationIsRejected() {
        val result = store().register(personnelRequest().copy(confirmPassword = "different1"))
        assertTrue(result.failureIssues().any { it.field == AuthField.CONFIRM_PASSWORD })
    }

    @Test
    fun weakPasswordIsRejected() {
        val short = store().register(personnelRequest(password = "abc"))
        assertTrue(short.failureIssues().any { it.field == AuthField.PASSWORD })

        val noDigit = store().register(personnelRequest(password = "abcdefghij"))
        assertTrue(noDigit.failureIssues().any { it.field == AuthField.PASSWORD })
    }

    @Test
    fun blankRequiredFieldsAreRejected() {
        val result = store().register(personnelRequest(username = "   "))
        assertTrue(result.failureIssues().any { it.field == AuthField.USERNAME })
    }

    @Test
    fun serviceIdIsOptionalAndBlankValuesAreStoredAsNull() {
        val s = store()
        s.register(personnelRequest(username = "noservice").copy(serviceId = "   "))
        assertNull(s.accountByUsername("noservice")!!.serviceId)
    }

    // ------------------------------------------------------------------ login

    @Test
    fun validCredentialsStartASession() {
        val s = store()
        s.register(personnelRequest())
        s.logout()

        val session = s.login("rahul", "soldier123").successSession()
        assertEquals("rahul", session.account.username)
        assertEquals(Role.PERSONNEL, session.role)
    }

    @Test
    fun wrongPasswordIsRejected() {
        val s = store()
        s.register(personnelRequest())
        assertTrue(s.login("rahul", "wrong-password") is AuthResult.Failure)
    }

    @Test
    fun unknownUserAndWrongPasswordGiveTheSameGenericMessage() {
        val s = store()
        s.register(personnelRequest())
        val unknown = s.login("nobody", "soldier123") as AuthResult.Failure
        val wrongPassword = s.login("rahul", "not-the-password") as AuthResult.Failure
        // Identical wording so the login form cannot be used to discover registered usernames.
        assertEquals(unknown.message, wrongPassword.message)
    }

    @Test
    fun loginIsCaseInsensitiveOnUsername() {
        val s = store()
        s.register(personnelRequest(username = "Rahul"))
        assertTrue(s.login("RAHUL", "soldier123") is AuthResult.Success)
    }

    @Test
    fun emptyLoginInputIsRejectedBeforeAnyLookup() {
        assertTrue(store().login("", "").failureIssues().isNotEmpty())
    }

    // ------------------------------------------------------------------ session lifecycle

    @Test
    fun sessionPersistsAcrossStoreInstancesAndSurvivesProcessRestart() {
        val accounts = InMemoryKeyValueStore()
        val sessions = InMemoryKeyValueStore()

        store(accounts, sessions).register(personnelRequest())

        // A brand-new AccountStore over the same storage is what a cold app start sees.
        val restored = store(accounts, sessions).currentSession()
        assertNotNull(restored)
        assertEquals("rahul", restored!!.account.username)
        assertTrue(restored.token.isNotBlank())
    }

    @Test
    fun logoutClearsTheSessionButKeepsTheAccount() {
        val s = store()
        s.register(personnelRequest())
        assertNotNull(s.currentSession())

        s.logout()
        assertNull(s.currentSession())
        // The account survives so the user can sign back in.
        assertEquals(1, s.allAccounts().size)
        assertTrue(s.login("rahul", "soldier123") is AuthResult.Success)
    }

    @Test
    fun sessionTokenIsNotDerivedFromThePassword() {
        val s = store()
        val session = s.register(personnelRequest(password = "soldier123")).successSession()
        assertFalse(session.token.contains("soldier123"))
        assertFalse(session.token.contains("rahul"))
    }

    // ------------------------------------------------------------------ role routing

    @Test
    fun eachRoleLogsIntoItsOwnDestination() {
        val s = store()
        val personnel = s.register(personnelRequest()).successSession()
        s.logout()
        val commander = s.register(commanderRequest()).successSession()

        assertEquals(Role.PERSONNEL, personnel.role)
        assertFalse(personnel.isCommander)
        assertEquals(Role.COMMANDER, commander.role)
        assertTrue(commander.isCommander)
    }

    @Test
    fun aPersonnelAccountCannotAuthenticateAsCommander() {
        val s = store()
        s.register(personnelRequest())
        // The role comes from the stored account; login offers no way to request another one.
        assertEquals(Role.PERSONNEL, s.login("rahul", "soldier123").successSession().role)
    }

    // ------------------------------------------------------------------ hashing

    @Test
    fun hashingIsSaltedSoIdenticalPasswordsDiffer() {
        val (saltA, hashA) = PasswordHasher.hashWithNewSalt("same-password")
        val (saltB, hashB) = PasswordHasher.hashWithNewSalt("same-password")
        assertNotEquals(saltA, saltB)
        assertNotEquals(hashA, hashB)
        assertTrue(PasswordHasher.verify("same-password", saltA, hashA))
        assertTrue(PasswordHasher.verify("same-password", saltB, hashB))
    }

    @Test
    fun verificationFailsForWrongPasswordAndCorruptStoredMaterial() {
        val (salt, hash) = PasswordHasher.hashWithNewSalt("correct-horse")
        assertFalse(PasswordHasher.verify("wrong-horse", salt, hash))
        // Corrupted salt/hash must deny access, never throw.
        assertFalse(PasswordHasher.verify("correct-horse", "not-hex", hash))
        assertFalse(PasswordHasher.verify("correct-horse", salt, ""))
    }

    @Test
    fun noPasswordMaterialLeaksThroughThePublicSummary() {
        val s = store()
        val session = s.register(personnelRequest()).successSession()
        assertFalse(session.account.toString().contains("soldier123"))
        // The summary type simply has no field that could carry a hash.
        val fields = AccountSummary::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("passwordHash"))
        assertFalse(fields.contains("passwordSalt"))
        assertFalse(fields.contains("password"))
    }
}

