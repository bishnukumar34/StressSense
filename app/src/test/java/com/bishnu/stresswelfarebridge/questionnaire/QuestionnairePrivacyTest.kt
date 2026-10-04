package com.bishnu.stresswelfarebridge.questionnaire

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
import java.time.LocalDate

/**
 * The privacy boundary.
 *
 * Questionnaire answers are the person's own. These tests assert that a commander session cannot
 * obtain them through any repository method, and that the commander-facing type is structurally
 * incapable of carrying an answer in the first place.
 */
class QuestionnairePrivacyTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var accounts: AccountStore
    private lateinit var repo: QuestionnaireRepository
    private lateinit var personnel: Session
    private lateinit var otherPersonnel: Session
    private lateinit var commander: Session
    private var counter = 0

    private val today: LocalDate = LocalDate.parse("2026-03-01")
    private val now: Instant = Instant.parse("2026-03-01T09:00:00Z")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        accounts = AccountStore(store, store, { now.toEpochMilli() }, { "acc-${counter++}" })
        personnel = register("jdoe", Role.PERSONNEL)
        otherPersonnel = register("asmith", Role.PERSONNEL)
        commander = register("cdr", Role.COMMANDER)
        repo = QuestionnaireRepository(store, accounts, { now }, { "resp-${counter++}" })
    }

    private fun register(username: String, role: Role): Session {
        val result = accounts.register(
            RegistrationRequest(
                fullName = username.replaceFirstChar { it.uppercase() },
                username = username,
                password = "Passw0rd!23",
                confirmPassword = "Passw0rd!23",
                role = role,
                serviceId = if (role == Role.PERSONNEL) "SVC-$username" else null
            )
        )
        assertTrue("registration failed for $username: $result", result is AuthResult.Success)
        return (result as AuthResult.Success).session
    }

    private fun seedResponse() {
        repo.saveResponse(
            personnel, DailyPulse.definition, mapOf("d_stress" to 4), TriggerType.DAILY, true
        )
    }

    // -- personnel own their data ---------------------------------------------------------

    @Test
    fun personnelCanSaveAndReadTheirOwnPulse() {
        val saved = repo.saveDailyPulse(personnel, today, 4, 2, 3, false)
        assertNotNull(saved)
        assertEquals(saved!!.dStress, repo.dailyPulseFor(personnel, today)!!.dStress)
    }

    @Test
    fun personnelCanSaveAndReadTheirOwnQuestionnaire() {
        seedResponse()
        val responses = repo.responsesFor(personnel)
        assertEquals(1, responses.size)
        assertEquals(4, responses.first().items["d_stress"])
    }

    @Test
    fun onePersonCannotSeeAnothersAnswers() {
        seedResponse()
        assertTrue(repo.responsesFor(otherPersonnel).isEmpty())
    }

    @Test
    fun onePersonCannotSeeAnothersPulse() {
        repo.saveDailyPulse(personnel, today, 4, 2, 3, false)
        assertTrue(repo.dailyPulses(otherPersonnel).isEmpty())
    }

    // -- commander must never read answers --------------------------------------------------

    @Test
    fun commanderCannotReadQuestionnaireAnswers() {
        seedResponse()
        assertTrue("commander must not receive answers", repo.responsesFor(commander).isEmpty())
    }

    @Test
    fun commanderCannotReadPulses() {
        repo.saveDailyPulse(personnel, today, 4, 2, 3, false)
        assertTrue(repo.dailyPulses(commander).isEmpty())
    }

    @Test
    fun commanderCannotWriteAPulse() {
        assertNull(repo.saveDailyPulse(commander, today, 4, 2, 3, false))
    }

    @Test
    fun commanderCannotWriteAResponse() {
        val result = repo.saveResponse(
            commander, DailyPulse.definition, mapOf("d_stress" to 1), TriggerType.MANUAL, true
        )
        assertFalse(result.isAllowed)
        assertTrue(result.deniedReason()!!.contains("private"))
    }

    @Test
    fun commanderCannotReadAnEpisode() {
        repo.openEpisode(personnel, 70)
        assertNull(repo.episodeFor(commander))
    }

    @Test
    fun commanderCannotMarkAnEpisodeOfferMade() {
        val ep = repo.openEpisode(personnel, 70)!!
        assertNull(repo.markOfferMade(commander, ep.id))
    }

    @Test
    fun commanderCannotMarkResponsesSynced() {
        seedResponse()
        assertFalse(repo.markSynced(commander, repo.responsesFor(personnel).first().id))
    }

    @Test
    fun commanderCanSeeOnlyCompletionStatus() {
        seedResponse()
        val summary = repo.commanderSummary(commander, personnel.accountId)
        assertTrue(summary.isAllowed)
        val s = summary.valueOrNull()!!
        assertEquals(CheckInStatus.COMPLETED, s.status)
        assertNotNull(s.lastCompletedAt)
    }

    @Test
    fun anIncompleteCheckInIsNotReportedAsCompleted() {
        repo.saveResponse(personnel, DailyPulse.definition, emptyMap(), TriggerType.TRIGGERED, false)
        val s = repo.commanderSummary(commander, personnel.accountId).valueOrNull()!!
        assertTrue(s.status != CheckInStatus.COMPLETED)
    }

    @Test
    fun commanderSeesTheCheckInRequestButNotTheAnswers() {
        repo.saveDailyPulse(personnel, today, 5, 1, 5, true)
        val s = repo.commanderSummary(commander, personnel.accountId).valueOrNull()!!
        assertTrue("the person explicitly asked to be checked in", s.checkInRequested)
    }

    @Test
    fun personnelCannotUseTheCommanderSummarySurface() {
        assertFalse(repo.commanderSummary(personnel, personnel.accountId).isAllowed)
    }

    @Test
    fun commanderSummariesCoverPersonnelButNotCommanders() {
        assertEquals(2, repo.commanderSummaries(commander).size)
        assertTrue(repo.commanderSummaries(otherPersonnel).isEmpty())
    }

    @Test
    fun summaryTypeHasNoFieldThatCouldCarryAnAnswer() {
        val names = PersonnelCheckInSummary::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.contains("item", true) })
        assertFalse(names.any { it.contains("answer", true) })
        assertFalse(names.any { it.contains("score", true) })
        assertFalse(names.any { it.contains("response", true) })
    }

    @Test
    fun checkInStatusVocabularyCarriesNoScores() {
        assertEquals(4, CheckInStatus.values().size)
    }

    // -- unauthorised access ---------------------------------------------------------------

    @Test
    fun noSessionAccessIsDeniedRatherThanCrashing() {
        assertTrue(repo.responsesFor(null).isEmpty())
        assertTrue(repo.dailyPulses(null).isEmpty())
        assertNull(repo.episodeFor(null))
        assertNull(repo.saveDailyPulse(null, today, 3, 3, 3, false))
        assertFalse(repo.commanderSummary(null, "x").isAllowed)
        assertTrue(repo.commanderSummaries(null).isEmpty())
    }

    @Test
    fun episodeCannotBeSavedIntoAnotherAccountsRecord() {
        val foreign = StressEpisode(
            id = "e", userId = otherPersonnel.accountId, openedAt = now,
            closedAt = null, lastTriggerLevel = 70
        )
        assertFalse(repo.saveEpisode(personnel, foreign))
    }

    @Test
    fun aCommanderSummaryIsStillStatusOnlyForAnyAccount() {
        val result = repo.commanderSummary(commander, otherPersonnel.accountId)
        assertTrue(result.isAllowed)
        assertTrue(result.valueOrNull()!!.status in CheckInStatus.values())
    }

    // -- offline behaviour ------------------------------------------------------------------

    @Test
    fun completedResponsesAreQueuedForSyncAndSurviveWithoutNetwork() {
        seedResponse()
        val pending = repo.pendingSyncResponses(personnel)
        assertEquals("a completed response must never be lost offline", 1, pending.size)
        assertTrue(pending.first().pendingSync)
        assertEquals(1, repo.responsesFor(personnel).size)
    }

    @Test
    fun markSyncedClearsThePendingFlag() {
        seedResponse()
        val id = repo.responsesFor(personnel).first().id
        assertTrue(repo.markSynced(personnel, id))
        assertTrue(repo.pendingSyncResponses(personnel).isEmpty())
    }

    // -- nothing answered stores nothing -------------------------------------------------------

    @Test
    fun untouchedPulseStoresNothing() {
        assertNull(repo.saveDailyPulse(personnel, today, null, null, null, false))
        assertTrue(repo.dailyPulses(personnel).isEmpty())
    }

    @Test
    fun pulseIsAtMostOncePerDay() {
        repo.saveDailyPulse(personnel, today, 4, 2, 3, false)
        repo.saveDailyPulse(personnel, today, 1, 5, 1, false)
        val all = repo.dailyPulses(personnel)
        assertEquals(1, all.size)
        assertEquals(1, all.first().dStress)
    }

    @Test
    fun episodeLifecycleIsPersisted() {
        val ep = repo.openEpisode(personnel, 72)!!
        assertEquals(72, repo.episodeFor(personnel)!!.lastTriggerLevel)
        repo.markOfferMade(personnel, ep.id)
        assertTrue(repo.episodeFor(personnel)!!.offerMade)
    }
}