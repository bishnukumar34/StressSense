package com.bishnu.stresswelfarebridge.questionnaire.ui

import com.bishnu.stresswelfarebridge.StressEngine
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.questionnaire.CheckInStatus
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulse
import com.bishnu.stresswelfarebridge.questionnaire.DailyPulseRecord
import com.bishnu.stresswelfarebridge.questionnaire.DailySensorDay
import com.bishnu.stresswelfarebridge.questionnaire.EpisodeState
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireDefinition
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireInstrument
import com.bishnu.stresswelfarebridge.questionnaire.Pss10
import com.bishnu.stresswelfarebridge.questionnaire.Pss10Record
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireRepository
import com.bishnu.stresswelfarebridge.questionnaire.QuestionnaireTriggerEngine
import com.bishnu.stresswelfarebridge.questionnaire.StressInsight
import com.bishnu.stresswelfarebridge.questionnaire.StressInsightBuilder
import com.bishnu.stresswelfarebridge.questionnaire.TriggerType
import com.bishnu.stresswelfarebridge.questionnaire.VerificationStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the personnel-side questionnaire UI needs, in one snapshot.
 *
 * A single immutable state object means the UI renders one thing and cannot drift between what the
 * dashboard card says and what the insight screen says.
 */
data class QuestionnaireUiState(
    /** Today's Daily Pulse, or null when the person has not answered yet. */
    val todayPulse: DailyPulseRecord? = null,
    /** All stored pulses, oldest first, for the trend card. */
    val history: List<DailyPulseRecord> = emptyList(),
    /** PSS-10 responses, newest first. */
    val pss10Records: List<Pss10Record> = emptyList(),
    /** Whether a PSS-10 may be completed for the current calendar month. */
    val pss10Available: Boolean = false,
    /** The calendar month a PSS-10 would currently belong to. */
    val currentMonth: java.time.YearMonth? = null,
    val checkInAvailable: Boolean = false,
    /** Why the offer is (or is not) available. Developer-facing; not shown verbatim to the user. */
    val checkInReason: String = "",
    val episodeState: EpisodeState = EpisodeState.NORMAL,
    /** Combined two-dimension interpretation. */
    val insight: StressInsight = StressInsightBuilder.build(null, null, null, null),
    val loading: Boolean = true,
    val error: String? = null
) {
    /** Latest PSS-10 score 0-40, or null when none completed. Never a fabricated zero. */
    val perceivedScore: Int? get() = latestPss10?.score

    /** Latest completed PSS-10 - the perceived-stress reading. */
    val latestPss10: Pss10Record? get() = pss10Records.firstOrNull { it.completed }

    /** Set when the physiological reading is thin; the UI caveats rather than guessing. */
    val physiologicalCaveat: String? get() = insight.physiological.reason

    val hasAnyHistory: Boolean get() = history.isNotEmpty()
}

/**
 * Owns questionnaire state for the personnel side.
 *
 * Deliberately not a ViewModel: this project has none, and the existing screens hold state with
 * `remember` in the composable that owns them. This plays the same role - one remembered instance
 * created at the top of the app. Creating it inside a Composable body would rebuild it on every
 * recomposition and lose answers already given.
 *
 * The controller never computes a physiological score. It receives the one the existing
 * [StressEngine] produced and passes it to [StressInsightBuilder] untouched.
 */
class QuestionnaireController(
    private val repository: QuestionnaireRepository,
    private val engine: QuestionnaireTriggerEngine = QuestionnaireTriggerEngine(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
    private val now: () -> Instant = { Instant.now() }
) {

    /**
     * Builds the personnel questionnaire state.
     *
     * @param physiologicalResult the score from the existing, unmodified physiological engine.
     * @param sensorDays recent per-day physiological values, used only for the *trigger* decision.
     */
    fun build(
        session: Session?,
        physiologicalResult: StressEngine.Result?,
        sensorDays: List<DailySensorDay> = emptyList()
    ): QuestionnaireUiState {
        if (session == null || session.isCommander) {
            return QuestionnaireUiState(loading = false)
        }

        val pulses = repository.dailyPulses(session)
        val todayPulse = pulses.firstOrNull { it.date == today() }
        val episode = repository.episodeFor(session)

        val decision = engine.evaluate(
            history = sensorDays,
            episode = episode,
            lastTriggeredAt = episode?.lastOfferedAt,
            now = now()
        )

        // The physiological reading only counts when the engine produced one from real data.
        // Anything else is passed through as null so the UI says so rather than guessing.
        val physiologicalScore =
            physiologicalResult?.score?.takeIf { physiologicalResult.usingRealData }
        val physiologicalReason = when {
            physiologicalScore != null -> null
            physiologicalResult == null -> "No physiological reading yet."
            !physiologicalResult.usingRealData -> "Not enough sensor data yet."
            else -> physiologicalResult.dataQuality.ifBlank { "Physiological data is limited." }
        }

        // PSS-10 supplies the perceived-stress reading. The Daily Check-in does not.
        val pss = repository.pss10History(session)
        val latestPss = pss.firstOrNull { it.completed }
        val month = java.time.YearMonth.now(zone)

        val insight = StressInsightBuilder.build(
            physiologicalScore = physiologicalScore,
            physiologicalReason = physiologicalReason,
            perceivedScore = latestPss?.score,
            perceivedSourceLabel = if (latestPss == null) null else "PSS-10"
        )

        return QuestionnaireUiState(
            todayPulse = todayPulse,
            history = pulses,
            pss10Records = pss,
            pss10Available = Pss10.isAvailable(
                month, pss.filter { it.completed }.map { it.month }.toSet()
            ),
            currentMonth = month,
            checkInAvailable = decision.shouldOffer,
            checkInReason = decision.reason,
            episodeState = decision.state,
            insight = insight,
            loading = false,
            error = null
        )
    }

    /** Saves a completed PSS-10 for [month]. Null when unavailable or incomplete. */
    fun savePss10(session: Session?, month: java.time.YearMonth, answers: Map<Int, Int>) =
        repository.savePss10(session, month, answers)

    /** PSS-10 history, newest first. */
    fun pss10History(session: Session?): List<Pss10Record> = repository.pss10History(session)

    /** Saves today's check-in. Returns the stored record, or null when nothing was answered. */
    fun saveDailyPulse(
        session: Session?,
        dStress: Int?,
        dRest: Int?,
        dDuty: Int?,
        checkinRequested: Boolean
    ): DailyPulseRecord? =
        repository.saveDailyPulse(session, today(), dStress, dRest, dDuty, checkinRequested)

    /**
     * Whether an instrument may actually be presented to a person.
     *
     * This is the single gate the UI consults; it never invents placeholder questions.
     *
     * It keys off [QuestionnaireDefinition.isPresentable], not `isShippable`, so a prototype build can
     * exercise a flow whose wording is [VerificationStatus.PROTOTYPE_PENDING_LICENCE]. That relaxation
     * is confined to *presentation*: DASS-21 is still [VerificationStatus.PENDING_WORDING] with no
     * wording at all, so it still refuses to start, and nothing here can make any instrument
     * [QuestionnaireDefinition.isShippable].
     */
    fun canStart(definition: QuestionnaireDefinition): Boolean = definition.isPresentable

    /** Why an instrument cannot be shown, for the development-safe placeholder screen. */
    fun unavailableReason(definition: QuestionnaireDefinition): String =
        when (definition.verification) {
            VerificationStatus.PENDING_WORDING ->
                "The stress questionnaire is still being prepared. Its questions are awaiting " +
                    "final verification against the official source, so it cannot be shown yet."
            VerificationStatus.BLOCKED_PENDING_LICENCE ->
                "This questionnaire is not available in your unit yet."
            VerificationStatus.PROTOTYPE_PENDING_LICENCE -> ""
            VerificationStatus.VERIFIED -> ""
        }

    /** The instrument offered when an episode triggers. */
    fun triggeredInstrument(): QuestionnaireDefinition = QuestionnaireInstrument.DASS_21

    /**
     * Records that the questionnaire was offered at the current level.
     *
     * Opens an episode when none is open, then stamps the offer so the cooldown starts from the
     * offer rather than from the episode's opening.
     */
    fun recordOffer(session: Session?, sensorDays: List<DailySensorDay>): Boolean {
        if (session == null || session.isCommander) return false
        val level = engine.triggerLevel(sensorDays)
        val episode = repository.episodeFor(session)
            ?: repository.openEpisode(session, level)
            ?: return false
        repository.recordOffer(session, episode.id, level)
        return true
    }

    /** Saves a response for an instrument that is cleared to be shown. */
    fun saveResponse(
        session: Session?,
        definition: QuestionnaireDefinition,
        answers: Map<String, Int>,
        triggerType: TriggerType,
        completed: Boolean
    ) = repository.saveResponse(session, definition, answers, triggerType, completed)

    /** Commander's authorised view: status only, never answers. */
    fun commanderStatus(session: Session?, targetAccountId: String): CheckInStatus? =
        repository.commanderSummary(session, targetAccountId).valueOrNull()?.status

    /** Convenience for the UI. */
    val dailyPulseInstrumentId: String = DailyPulse.INSTRUMENT_ID
}
