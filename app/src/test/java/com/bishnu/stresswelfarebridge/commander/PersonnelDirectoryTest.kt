package com.bishnu.stresswelfarebridge.commander

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
import org.junit.Test

/**
 * Authorization and stress-grouping tests.
 *
 * The authorization cases are the important ones: they assert that the *data layer* refuses
 * cross-account access, which is what makes the role split real rather than a UI convention.
 */
class PersonnelDirectoryTest {

    private val accounts = AccountStore(InMemoryKeyValueStore(), InMemoryKeyValueStore())
    private val directory = PersonnelDirectory(InMemoryKeyValueStore(), accounts)

    private fun register(
        username: String,
        role: Role,
        fullName: String = "Person $username",
        serviceId: String? = "ID-$username"
    ): Session {
        val result = accounts.register(
            RegistrationRequest(
                fullName = fullName,
                username = username,
                password = "passw0rd123",
                confirmPassword = "passw0rd123",
                role = role,
                serviceId = serviceId
            )
        )
        return (result as? AuthResult.Success)?.session ?: error("registration failed: $result")
    }

    /** Publishes a record for [session] as if the engine had scored it. */
    private fun publish(session: Session, score: Int?, zone: StressZone?, updatedAt: Long = 1_700_000_000_000L) {
        val access = directory.publishOwnRecord(
            session,
            PersonnelRecord(
                accountId = session.accountId,
                fullName = session.account.fullName,
                serviceId = session.account.serviceId,
                stressScore = score,
                stressLevel = zone?.description ?: "Awaiting data",
                zone = zone,
                confidence = if (score != null) 80 else 0,
                usingRealData = score != null,
                dataQuality = if (score != null) "Baseline built from 3 day(s)." else "No usable reading",
                baselineDays = if (score != null) 3 else 0,
                lastUpdatedMs = updatedAt,
                steps = 6000,
                exerciseMinutes = 30,
                sleepMinutes = 420,
                avgHr = if (score != null) 72.0 else null,
                avgHrv = if (score != null) 45.0 else null,
                restingHr = if (score != null) 62.0 else null,
                respiratoryRate = null,
                spo2 = null
            )
        )
        assertTrue("publish was denied: ${access.deniedReason()}", access.isAllowed)
    }

    private fun sampleRecord(
        accountId: String = "x",
        fullName: String = "X",
        score: Int? = null,
        zone: StressZone? = null
    ) = PersonnelRecord(
        accountId = accountId, fullName = fullName, serviceId = null,
        stressScore = score, stressLevel = zone?.description ?: "Awaiting data", zone = zone,
        confidence = 50, usingRealData = score != null, dataQuality = "", baselineDays = 3,
        lastUpdatedMs = 1L, steps = 0, exerciseMinutes = 0, sleepMinutes = 0,
        avgHr = null, avgHrv = null, restingHr = null, respiratoryRate = null, spo2 = null
    )

    // ------------------------------------------------------------------ authorization

    @Test
    fun personnelCannotAccessCommanderData() {
        val personnel = register("rahul", Role.PERSONNEL)
        val commander = register("cmd", Role.COMMANDER)

        assertTrue(directory.commanderOverview(personnel) is Access.Denied)
        assertTrue(directory.zoneMembers(personnel, StressZone.RED) is Access.Denied)
        // The commander, by contrast, is served.
        assertNotNull(directory.commanderOverview(commander))
        assertTrue(directory.commanderOverview(commander).isAllowed)
    }

    @Test
    fun commanderCanAccessAuthorizedPersonnelData() {
        val personnel = register("rahul", Role.PERSONNEL)
        val commander = register("cmd", Role.COMMANDER)
        publish(personnel, 22, StressZone.GREEN)

        val overview = directory.commanderOverview(commander)
        assertTrue(overview is Access.Allowed)
        assertEquals(1, (overview as Access.Allowed).value.count(StressZone.GREEN))
        assertTrue(directory.personnelDetail(commander, personnel.accountId).isAllowed)
    }

    @Test
    fun personnelCanAccessOnlyTheirOwnData() {
        val first = register("rahul", Role.PERSONNEL)
        val second = register("asha", Role.PERSONNEL)
        publish(first, 22, StressZone.GREEN)
        publish(second, 78, StressZone.RED)

        // Own record: allowed.
        assertTrue(directory.personnelDetail(first, first.accountId).isAllowed)
        assertNotNull(directory.ownRecord(first))

        // Someone else's record: denied, even with the correct account id.
        val crossAccess = directory.personnelDetail(first, second.accountId)
        assertTrue(crossAccess is Access.Denied)
        assertNull(crossAccess.valueOrNull())
    }

    @Test
    fun aPersonnelAccountCannotWriteIntoAnotherPersonsRecord() {
        val victim = register("asha", Role.PERSONNEL)
        val attacker = register("rahul", Role.PERSONNEL)
        publish(victim, 50, StressZone.YELLOW)

        // The attacker constructs a record carrying the victim's account id.
        val forged = sampleRecord(victim.accountId, "Forged", score = 5, zone = StressZone.GREEN)
        assertTrue(directory.publishOwnRecord(attacker, forged) is Access.Denied)
        // The victim's real record is unchanged.
        val victimRecord = directory.personnelDetail(victim, victim.accountId)
        assertEquals(50, (victimRecord as Access.Allowed).value.stressScore)
    }

    @Test
    fun commandersDoNotPublishOrHoldPersonnelRecords() {
        val commander = register("cmd", Role.COMMANDER)
        val forged = sampleRecord(commander.accountId, "Major Mehta", 10, StressZone.GREEN)
        assertTrue(directory.publishOwnRecord(commander, forged) is Access.Denied)
        assertTrue(directory.personnelDetail(commander, commander.accountId) is Access.Denied)
    }

    @Test
    fun noSessionMeansNoAccess() {
        assertTrue(directory.commanderOverview(null) is Access.Denied)
        assertTrue(directory.personnelDetail(null, "any-id") is Access.Denied)
        assertTrue(directory.publishOwnRecord(null, sampleRecord()) is Access.Denied)
        assertNull(directory.ownRecord(null))
    }


    // ------------------------------------------------------------------ stress grouping

    @Test
    fun personnelAreGroupedIntoTheCorrectZones() {
        val commander = register("cmd", Role.COMMANDER)
        val low1 = register("low1", Role.PERSONNEL)
        val low2 = register("low2", Role.PERSONNEL)
        val mid = register("mid1", Role.PERSONNEL)
        val high = register("high1", Role.PERSONNEL)

        publish(low1, 12, StressZone.GREEN)
        publish(low2, 34, StressZone.GREEN)
        publish(mid, 50, StressZone.YELLOW)
        publish(high, 91, StressZone.RED)

        val overview = directory.commanderOverview(commander) as Access.Allowed
        assertEquals(2, overview.value.count(StressZone.GREEN))
        assertEquals(1, overview.value.count(StressZone.YELLOW))
        assertEquals(1, overview.value.count(StressZone.RED))
        assertEquals(4, overview.value.totalPersonnel)
        assertEquals(
            listOf("Person low1", "Person low2"),
            overview.value.zones[StressZone.GREEN]!!.map { it.fullName }
        )
    }

    @Test
    fun anEmptyZoneReportsAnEmptyListRatherThanAnError() {
        val commander = register("cmd", Role.COMMANDER)
        publish(register("low1", Role.PERSONNEL), 12, StressZone.GREEN)

        val members = directory.zoneMembers(commander, StressZone.RED)
        assertTrue(members is Access.Allowed)
        assertTrue((members as Access.Allowed).value.isEmpty())
    }

    @Test
    fun personnelWithoutStressDataAreUnclassifiedAndNeverCountedAsLowStress() {
        val commander = register("cmd", Role.COMMANDER)
        publish(register("newbie", Role.PERSONNEL), score = null, zone = null)

        val overview = directory.commanderOverview(commander) as Access.Allowed
        // The critical assertion: missing data must never be displayed as a healthy result.
        assertEquals(0, overview.value.count(StressZone.GREEN))
        assertEquals(1, overview.value.unclassified.size)
        assertEquals("Person newbie", overview.value.unclassified.first().fullName)
    }

    @Test
    fun manyPersonnelAreCountedCorrectlyAcrossAllZones() {
        val commander = register("cmd", Role.COMMANDER)
        val sessions = (0 until 6).map { register("personnel$it", Role.PERSONNEL) }
        val scores = listOf(0, 20, 40, 60, 80, 100)
        sessions.forEachIndexed { index, session ->
            val score = scores[index]
            publish(session, score, StressZoneMapping.fromScore(score))
        }

        val overview = directory.commanderOverview(commander) as Access.Allowed
        assertEquals(6, overview.value.totalPersonnel)
        // 0 and 20 are green; 40 and 60 are yellow; 80 and 100 are red.
        assertEquals(2, overview.value.count(StressZone.GREEN))
        assertEquals(2, overview.value.count(StressZone.YELLOW))
        assertEquals(2, overview.value.count(StressZone.RED))
        assertEquals(0, overview.value.unclassified.size)
    }

    @Test
    fun aMissingRecordIsReportedAsMissingRatherThanAsZero() {
        val commander = register("cmd", Role.COMMANDER)
        register("ghost", Role.PERSONNEL)

        val detail = directory.personnelDetail(commander, "no-such-account")
        assertTrue(detail is Access.Denied)
        assertNull(detail.valueOrNull())
    }

    // ------------------------------------------------------------------ detail association

    @Test
    fun personnelDetailReturnsTheCorrectPersonAndNoOtherPersonsData() {
        val commander = register("cmd", Role.COMMANDER)
        val rahul = register("rahul", Role.PERSONNEL, fullName = "Rahul Kumar", serviceId = "P1024")
        val asha = register("asha", Role.PERSONNEL, fullName = "Asha Devi", serviceId = "P1041")
        publish(rahul, 20, StressZone.GREEN)
        publish(asha, 80, StressZone.RED)

        val detail = directory.personnelDetail(commander, rahul.accountId) as Access.Allowed
        assertEquals("Rahul Kumar", detail.value.fullName)
        assertEquals("P1024", detail.value.serviceId)
        assertEquals(StressZone.GREEN, detail.value.zone)
        // Asha's numbers must not leak into Rahul's record.
        assertNull(detail.value.cameraHrv)
        assertEquals(6000, detail.value.steps)
        assertEquals(20, detail.value.stressScore)
    }

    @Test
    fun commanderOverviewReportsTheLatestUpdateTime() {
        val commander = register("cmd", Role.COMMANDER)
        val older = register("old", Role.PERSONNEL)
        val newer = register("new", Role.PERSONNEL)
        publish(older, 10, StressZone.GREEN, updatedAt = 1_000L)
        publish(newer, 10, StressZone.GREEN, updatedAt = 5_000L)

        val overview = directory.commanderOverview(commander) as Access.Allowed
        assertEquals(5_000L, overview.value.lastUpdatedMs)
    }

    @Test
    fun recordsPersistAcrossDirectoryInstances() {
        val records = InMemoryKeyValueStore()
        val first = PersonnelDirectory(records, accounts)
        val session = register("persist", Role.PERSONNEL)
        first.publishOwnRecord(
            session,
            sampleRecord(session.accountId, session.account.fullName, 33, StressZone.GREEN)
        )

        // A fresh directory over the same storage is what an app restart sees.
        val reopened = PersonnelDirectory(records, accounts)
        val commander = register("cmd2", Role.COMMANDER)
        val overview = reopened.commanderOverview(commander) as Access.Allowed
        assertEquals(1, overview.value.count(StressZone.GREEN))
    }
}

