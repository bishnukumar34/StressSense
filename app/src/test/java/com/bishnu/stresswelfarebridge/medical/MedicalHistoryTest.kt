package com.bishnu.stresswelfarebridge.medical

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.AuthResult
import com.bishnu.stresswelfarebridge.auth.InMemoryKeyValueStore
import com.bishnu.stresswelfarebridge.auth.RegistrationRequest
import com.bishnu.stresswelfarebridge.auth.Role
import com.bishnu.stresswelfarebridge.auth.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Medical-history persistence, ownership and the commander privacy boundary.
 */
class MedicalHistoryTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: MedicalHistoryRepository
    private lateinit var personnel: Session
    private lateinit var other: Session
    private lateinit var commander: Session
    private var counter = 0
    private val now: Instant = Instant.parse("2026-03-01T10:00:00Z")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "a${counter++}" })
        personnel = register("jdoe", Role.PERSONNEL)
        other = register("asmith", Role.PERSONNEL)
        commander = register("cdr", Role.COMMANDER)
        repo = MedicalHistoryRepository(store) { now }
    }

    private fun register(username: String, role: Role): Session {
        val r = accounts.register(
            RegistrationRequest(
                fullName = username.replaceFirstChar { it.uppercase() },
                username = username, password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23", role = role,
                serviceId = if (role == Role.PERSONNEL) "SVC-$username" else null
            )
        )
        assertTrue("register $username", r is AuthResult.Success)
        return (r as AuthResult.Success).session
    }

    private fun sample() = MedicalHistory(
        userId = "ignored-on-write",
        hasConditions = true,
        conditions = listOf("Asthma"),
        hasAllergies = true,
        allergies = listOf("Penicillin"),
        medications = "Salbutamol inhaler",
        hasSurgeries = true,
        surgeries = "Appendectomy 2010",
        hasInjuries = true,
        injuries = "Left wrist fracture",
        ongoingTreatment = "Annual asthma review"
    )

    @Test
    fun nothingRecordedMeansNothingStored() {
        assertNull(repo.get(personnel))
        assertFalse(repo.exists(personnel))
    }

    @Test
    fun savingAndReadingBackWorks() {
        assertTrue(repo.save(personnel, sample()))
        val read = repo.get(personnel)
        assertNotNull(read)
        assertEquals(listOf("Asthma"), read!!.conditions)
        assertEquals("Salbutamol inhaler", read.medications)
        assertEquals(now, read.updatedAt)
    }

    @Test
    fun savingIsIdempotentRatherThanDuplicating() {
        repo.save(personnel, sample())
        repo.save(personnel, sample().copy(medications = "Updated"))
        val read = repo.get(personnel)!!
        assertEquals("Updated", read.medications)
        assertEquals(listOf("Asthma"), read.conditions)
    }

    @Test
    fun historySurvivesRestart() {
        repo.save(personnel, sample())
        val reopened = MedicalHistoryRepository(store) { now }
        assertNotNull(reopened.get(personnel))
    }

    @Test
    fun accountIdComesFromTheSessionNotTheRecord() {
        repo.save(personnel, sample().copy(userId = "someone-else"))
        assertEquals(personnel.accountId, repo.get(personnel)!!.userId)
    }

    @Test
    fun oneUserCannotReadAnotherUsersHistory() {
        repo.save(personnel, sample())
        assertNull(repo.get(other))
        assertFalse(repo.exists(other))
    }

    @Test
    fun oneUserCannotOverwriteAnotherUsersHistory() {
        repo.save(other, sample().copy(medications = "Smith medication"))
        // Saving under our own key must not touch Smith's record.
        repo.save(personnel, sample().copy(medications = "Doe medication"))
        assertEquals("Smith medication", repo.get(other)!!.medications)
        assertEquals("Doe medication", repo.get(personnel)!!.medications)
    }

    @Test
    fun commanderCannotReadOrWriteMedicalHistory() {
        repo.save(personnel, sample())
        assertNull("commander must not read medical history", repo.get(commander))
        assertFalse(repo.exists(commander))
        assertFalse(repo.save(commander, sample()))
        assertFalse(repo.clear(commander))
    }

    @Test
    fun noSessionIsDeniedRatherThanCrashing() {
        assertNull(repo.get(null))
        assertFalse(repo.save(null, sample()))
        assertFalse(repo.exists(null))
    }

    @Test
    fun summaryShowsImportantFieldsOnly() {
        val lines = sample().summaryLines()
        assertTrue(lines.any { it.contains("Asthma") })
        assertTrue(lines.any { it.contains("Penicillin") })
        assertTrue(lines.any { it.contains("Salbutamol") })
        assertTrue(lines.any { it.contains("Previous surgery") })
    }

    @Test
    fun emptyHistoryReportsNothingRecorded() {
        assertTrue(MedicalHistory(userId = "u").isEmpty)
        assertFalse(MedicalHistory(userId = "u").hasAnything)
        assertTrue(MedicalHistory(userId = "u").summaryLines().isEmpty())
    }

    @Test
    fun summaryTypeCarriesNoCommanderFacingFields() {
        val names = MedicalHistory::class.java.declaredFields.map { it.name }
        // Guard: nothing that would let a commander-facing projection carry this record.
        assertFalse(names.any { it.equals("id", true) })
    }
}
