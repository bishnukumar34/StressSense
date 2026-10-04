package com.bishnu.stresswelfarebridge.questionnaire

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.KeyValueStore
import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.Access
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Persistence for questionnaire data, plus the authorisation boundary around it.
 *
 * Storage reuses the app's existing JSON-in-SharedPreferences layer ([KeyValueStore]) via the same
 * abstraction the account store uses, so swapping in a backend later touches one class rather than
 * the UI. Records are keyed by **account id**, so one person can never read another's answers.
 *
 * ### Privacy rule
 * [responsesFor], [dailyPulses] and [episodeFor] are personnel-only; a commander calling them gets
 * nothing. The commander-facing surface is [commanderSummary], which can only ever return a
 * [CheckInStatus] " there is deliberately no commander API that returns answers, subscale scores or
 * item text. The absence of such a method *is* the enforcement, rather than a check inside one.
 *
 * ### Offline
 * A completed response is written immediately and flagged [QuestionnaireResponse.pendingSync]. It is
 * never dropped for want of a network, and [pendingSyncResponses] is the queue a future backend
 * drains. With no backend configured today, that queue honestly reports everything as unsent rather
 * than pretending an upload happened.
 */
class QuestionnaireRepository(
    private val store: KeyValueStore,
    private val accounts: AccountStore,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    // -- Daily Pulse ---------------------------------------------------------------------------

    /**
     * Records today's pulse.
     *
     * Returns null and stores nothing when no item was answered: an untouched prompt is not data,
     * and writing a zero would fabricate a "completely unstressed" reading.
     */
    fun saveDailyPulse(
        session: Session?,
        date: LocalDate,
        dStress: Int?,
        dRest: Int?,
        dDuty: Int?,
        checkinRequested: Boolean
    ): DailyPulseRecord? {
        val s = session ?: return null
        if (s.role.isCommander) return null

        // No score is computed here: the Daily Check-in is context only. Nothing answered means
        // nothing is stored, so an untouched prompt never becomes a fake reading.
        if (!DailyPulse.hasAnyAnswer(dStress, dRest, dDuty)) return null

        val record = DailyPulseRecord(
            userId = s.accountId,
            date = date,
            dStress = dStress,
            dRest = dRest,
            dDuty = dDuty,
            checkinRequested = checkinRequested,
            createdAt = clock()
        )
        // One pulse per day: re-answering replaces the same day rather than accumulating rows.
        val list = readDailyPulses(s.accountId).filterNot { it.date == date }.toMutableList()
        list.add(record)
        writeDailyPulses(s.accountId, list)
        return record
    }

    /** The person's own pulses. Never another account's. */
    fun dailyPulses(session: Session?): List<DailyPulseRecord> {
        val s = session ?: return emptyList()
        if (s.role.isCommander) return emptyList()
        // userId is not stored per row (the key already scopes it), so it is restored from the
        // session here. Without this a re-read record would look like it belongs to nobody.
        return readDailyPulses(s.accountId)
            .map { it.copy(userId = s.accountId) }
            .sortedBy { it.date }
    }

    fun dailyPulseFor(session: Session?, date: LocalDate): DailyPulseRecord? =
        dailyPulses(session).firstOrNull { it.date == date }

    /** True once a pulse exists for that day, so the prompt can be hidden. */
    fun hasPulseFor(session: Session?, date: LocalDate): Boolean =
        dailyPulseFor(session, date) != null
// -- Questionnaire responses (PRIVATE) --------------------------------------------------------

    /** Personnel-only. Saves a scored response. */
    fun saveResponse(
        session: Session?,
        definition: QuestionnaireDefinition,
        answers: Map<String, Int>,
        triggerType: TriggerType,
        completed: Boolean
    ): Access<QuestionnaireResponse> {
        val s = session ?: return Access.Denied(NO_SESSION)
        if (s.role.isCommander) return Access.Denied(PRIVATE_TO_PERSONNEL)

        val score = QuestionnaireScorer.score(definition, answers)
        val response = QuestionnaireResponse(
            id = idGenerator(), userId = s.accountId, instrument = definition.id,
            version = definition.version, administeredAt = clock(), triggerType = triggerType,
            items = answers, scores = score, completed = completed
        )
        val list = readResponses(s.accountId).toMutableList()
        list.add(response)
        writeResponses(s.accountId, list)
        return Access.Allowed(response)
    }

    /**
     * Personnel-only. The signed-in person's own answers.
     *
     * A commander session gets an empty list rather than another person's data. The check lives in
     * the data layer, not the UI, so hiding a screen cannot become the only defence.
     */
    fun responsesFor(session: Session?): List<QuestionnaireResponse> {
        val s = session ?: return emptyList()
        if (s.role.isCommander) return emptyList()
        // Restore the owning account id that the storage key already implies but the row omits.
        return readResponses(s.accountId)
            .map { it.copy(userId = s.accountId) }
            .sortedByDescending { it.administeredAt }
    }

    /** Latest completed response for an instrument, personnel-only. */
    fun latestResponse(session: Session?, instrumentId: String): QuestionnaireResponse? =
        responsesFor(session).firstOrNull { it.instrument == instrumentId && it.completed }

    // -- PSS-10 (perceived stress, once per calendar month) ---------------------------------------

    /**
     * Saves a completed PSS-10 for [month].
     *
     * Refuses when that calendar month already has a completed response, which is what prevents an
     * accidental duplicate submission. Previous months are untouched, so November never overwrites
     * October.
     */
    fun savePss10(
        session: Session?,
        month: java.time.YearMonth,
        answers: Map<Int, Int>
    ): Pss10Record? {
        val s = session ?: return null
        if (s.role.isCommander) return null
        if (!pss10Available(session, month)) return null

        val score = Pss10.score(answers)
        if (score == null) return null

        val record = Pss10Record(
            id = idGenerator(),
            userId = s.accountId,
            version = QuestionnaireInstrument.PSS_10.version,
            completedAt = clock(),
            month = month,
            answers = answers,
            score = score,
            completed = true
        )
        val list = readPss10(s.accountId).toMutableList()
        list.add(record)
        writePss10(s.accountId, list)
        return record
    }

    /** Every PSS-10 the signed-in person has completed, newest first. */
    fun pss10History(session: Session?): List<Pss10Record> {
        val s = session ?: return emptyList()
        if (s.role.isCommander) return emptyList()
        return readPss10(s.accountId)
            .map { it.copy(userId = s.accountId) }
            // Month is the tie-breaker: two completions can share a timestamp, and the
            // ordering must still be newest month first.
            .sortedWith(compareByDescending<Pss10Record> { it.month }.thenByDescending { it.completedAt })
    }

    /** True when [month] has no completed PSS-10 yet. */
    fun pss10Available(session: Session?, month: java.time.YearMonth): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        val done = pss10History(session).filter { it.completed }.map { it.month }.toSet()
        return Pss10.isAvailable(month, done)
    }

    /** The completed PSS-10 for [month], or null. */
    fun pss10ForMonth(session: Session?, month: java.time.YearMonth): Pss10Record? =
        pss10History(session).firstOrNull { it.month == month && it.completed }

    private fun readPss10(userId: String): List<Pss10Record> {
        val raw = store.getString(pssKey(userId)) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::pss10FromJson) }
        }.getOrDefault(emptyList())
    }

    private fun writePss10(userId: String, list: List<Pss10Record>) {
        val arr = JSONArray()
        list.forEach { arr.put(pss10ToJson(it)) }
        store.putString(pssKey(userId), arr.toString())
    }

    private fun pss10ToJson(r: Pss10Record) = JSONObject().apply {
        put("id", r.id)
        put("version", r.version)
        put("completedAt", r.completedAt.toEpochMilli())
        put("month", r.month.toString())
        put("completed", r.completed)
        put("score", r.score ?: JSONObject.NULL)
        put("answers", JSONObject().apply { r.answers.forEach { (k, v) -> put(k.toString(), v) } })
    }

    private fun pss10FromJson(j: JSONObject) = runCatching {
        val answersJson = j.optJSONObject("answers") ?: JSONObject()
        Pss10Record(
            id = j.getString("id"),
            userId = "",
            version = j.optInt("version", 1),
            completedAt = Instant.ofEpochMilli(j.getLong("completedAt")),
            month = java.time.YearMonth.parse(j.getString("month")),
            answers = answersJson.keys().asSequence().associate { it.toInt() to answersJson.getInt(it) },
            score = j.optIntOrNull("score"),
            completed = j.optBoolean("completed", false)
        )
    }.getOrNull()
    // -- Offline sync queue ----------------------------------------------------------------------

    /**
     * Completed responses still waiting to be sent. Personnel-only.
     *
     * With no backend configured this returns what is on disk. Inventing a successful upload would
     * be worse than an honest unsent queue.
     */
    fun pendingSyncResponses(session: Session?): List<QuestionnaireResponse> =
        responsesFor(session).filter { it.pendingSync }

    fun markSynced(session: Session?, responseId: String): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        writeResponses(
            s.accountId,
            readResponses(s.accountId).map {
                if (it.id == responseId) it.copy(pendingSync = false) else it
            }
        )
        return true
    }

    // -- Episodes ---------------------------------------------------------------------------------

    fun openEpisode(session: Session?, level: Int): StressEpisode? {
        val s = session ?: return null
        if (s.role.isCommander) return null
        val episode = StressEpisode(
            id = idGenerator(), userId = s.accountId, openedAt = clock(),
            closedAt = null, lastTriggerLevel = level
        )
        store.putString(episodeKey(s.accountId), toJson(episode).toString())
        return episode
    }

    fun episodeFor(session: Session?): StressEpisode? {
        val s = session ?: return null
        if (s.role.isCommander) return null
        val raw = store.getString(episodeKey(s.accountId)) ?: return null
        // Restore the owning account id so a later saveEpisode ownership check still passes; the row
        // itself does not repeat it because the storage key already scopes it.
        return fromJson(JSONObject(raw))?.copy(userId = s.accountId)
    }

    fun saveEpisode(session: Session?, episode: StressEpisode): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        if (episode.userId != s.accountId) return false
        store.putString(episodeKey(s.accountId), toJson(episode).toString())
        return true
    }

    /** Records that the offer was made, so an episode never offers twice at the same level. */
    fun markOfferMade(session: Session?, episodeId: String): StressEpisode? {
        val current = episodeFor(session) ?: return null
        if (current.id != episodeId) return current
        val updated = current.copy(offerMade = true, lastOfferedAt = clock())
        saveEpisode(session, updated)
        return updated
    }

    /**
     * Records that a questionnaire was offered, at [level].
     *
     * [lastTriggerLevel] is updated to the new level so that a later re-escalation has to clear a
     * fresh `sharpRise` margin from *this* offer rather than from the original episode opening.
     */
    fun recordOffer(session: Session?, episodeId: String, level: Int): StressEpisode? {
        val current = episodeFor(session) ?: return null
        if (current.id != episodeId) return current
        val updated = current.copy(
            offerMade = true,
            lastOfferedAt = clock(),
            lastTriggerLevel = level,
            dismissedAt = null,
            reminderSent = false
        )
        saveEpisode(session, updated)
        return updated
    }

    // -- Commander-visible surface (no answers) ----------------------------------------------------

    /**
     * Commander summary for one account.
     *
     * Carries only completion status and the person's own check-in request. It is impossible for this
     * type to express a DASS answer, a subscale score or an item response, because it has no field
     * for one.
     */
    fun commanderSummary(session: Session?, targetAccountId: String): Access<PersonnelCheckInSummary> {
        if (session == null) return Access.Denied(NO_SESSION)
        if (!session.isCommander) return Access.Denied(COMMANDER_ONLY)

        val responses = readResponses(targetAccountId)
        // PSS-10 is stored separately from the generic response store, so it must be folded in
        // explicitly or a completed monthly instrument would report as NONE. Status only: the
        // answers stay in the personnel record and never cross this boundary.
        val pssRecords = readPss10(targetAccountId).filter { it.completed }
        val latest = listOfNotNull<Instant>(
            responses.filter { it.completed }.maxByOrNull { it.administeredAt }?.administeredAt,
            pssRecords.maxByOrNull { it.completedAt }?.completedAt
        ).maxOrNull()
        val status = when {
            pssRecords.isNotEmpty() || latest != null -> CheckInStatus.COMPLETED
            responses.isNotEmpty() -> CheckInStatus.OFFERED
            else -> CheckInStatus.NONE
        }
        val account = accounts.accountById(targetAccountId)
        return Access.Allowed(
            PersonnelCheckInSummary(
                userId = targetAccountId,
                displayName = account?.fullName ?: targetAccountId,
                status = status,
                lastCompletedAt = latest,
                checkInRequested = readDailyPulses(targetAccountId).any { it.checkinRequested }
            )
        )
    }

    /** Commander summaries for every personnel account. Never includes answers. */
    fun commanderSummaries(session: Session?): List<PersonnelCheckInSummary> {
        if (session == null || !session.isCommander) return emptyList()
        return accounts.allAccounts()
            .filter { !it.role.isCommander }
            .mapNotNull { commanderSummary(session, it.id).valueOrNull() }
    }

    // -- serialisation ---------------------------------------------------------------------------

    private fun readDailyPulses(userId: String): List<DailyPulseRecord> {
        val raw = store.getString(pulseKey(userId)) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::pulseFromJson) }
        }.getOrDefault(emptyList())
    }

    private fun writeDailyPulses(userId: String, list: List<DailyPulseRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(pulseToJson(it)) }
        store.putString(pulseKey(userId), arr.toString())
    }

    private fun readResponses(userId: String): List<QuestionnaireResponse> {
        val raw = store.getString(responseKey(userId)) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::responseFromJson) }
        }.getOrDefault(emptyList())
    }

    private fun writeResponses(userId: String, list: List<QuestionnaireResponse>) {
        val arr = JSONArray()
        list.forEach { arr.put(responseToJson(it)) }
        store.putString(responseKey(userId), arr.toString())
    }

    private fun pulseToJson(r: DailyPulseRecord) = JSONObject().apply {
        put("date", r.date.toString())
        put("dStress", r.dStress ?: JSONObject.NULL)
        put("dRest", r.dRest ?: JSONObject.NULL)
        put("dDuty", r.dDuty ?: JSONObject.NULL)
        put("checkinRequested", r.checkinRequested)
        put("createdAt", r.createdAt.toEpochMilli())
    }

    private fun pulseFromJson(j: JSONObject) = runCatching {
        DailyPulseRecord(
            userId = "",
            date = LocalDate.parse(j.getString("date")),
            dStress = j.optIntOrNull("dStress"),
            dRest = j.optIntOrNull("dRest"),
            dDuty = j.optIntOrNull("dDuty"),
            checkinRequested = j.optBoolean("checkinRequested", false),
            createdAt = Instant.ofEpochMilli(j.getLong("createdAt"))
        )
    }.getOrNull()

    private fun responseToJson(r: QuestionnaireResponse) = JSONObject().apply {
        put("id", r.id)
        put("instrument", r.instrument)
        put("version", r.version)
        put("administeredAt", r.administeredAt.toEpochMilli())
        put("triggerType", r.triggerType.name)
        put("completed", r.completed)
        put("pendingSync", r.pendingSync)
        put("scores", JSONObject().apply {
            put("primary", r.scores.primary ?: JSONObject.NULL)
            put("subscales", JSONObject(r.scores.subscales.mapValues { it.value }))
            put("bands", JSONObject(r.scores.bands))
        })
        put("itemsJson", JSONObject(r.items.mapValues { it.value }))
    }

    private fun responseFromJson(j: JSONObject) = runCatching {
        val scoresObj = j.getJSONObject("scores")
        val subsObj = scoresObj.optJSONObject("subscales") ?: JSONObject()
        val bandsObj = scoresObj.optJSONObject("bands") ?: JSONObject()
        val itemsObj = j.optJSONObject("itemsJson") ?: JSONObject()

        val items: Map<String, Int> = itemsObj.keys().asSequence().associateWith { itemsObj.getInt(it) }
        val subscales: Map<String, Double> =
            subsObj.keys().asSequence().associateWith { subsObj.getDouble(it) }
        val bands: Map<String, String> =
            bandsObj.keys().asSequence().associateWith { bandsObj.getString(it) }
        val primary: Double? = scoresObj.optDoubleOrNull("primary")

        QuestionnaireResponse(
            id = j.getString("id"),
            userId = "",
            instrument = j.getString("instrument"),
            version = j.optInt("version", 1),
            administeredAt = Instant.ofEpochMilli(j.getLong("administeredAt")),
            triggerType = runCatching { TriggerType.valueOf(j.getString("triggerType")) }
                .getOrDefault(TriggerType.MANUAL),
            items = items,
            scores = QuestionnaireScore(
                instrumentId = j.getString("instrument"),
                version = j.optInt("version", 1),
                primary = primary,
                maxScore = 42.0,
                subscales = subscales,
                bands = bands
            ),
            completed = j.optBoolean("completed", false),
            pendingSync = j.optBoolean("pendingSync", true)
        )
    }.getOrNull()

    private fun toJson(e: StressEpisode) = JSONObject().apply {
        put("id", e.id)
        put("openedAt", e.openedAt.toEpochMilli())
        put("closedAt", e.closedAt?.toEpochMilli() ?: JSONObject.NULL)
        put("lastTriggerLevel", e.lastTriggerLevel)
        put("offerMade", e.offerMade)
        put("lastOfferedAt", e.lastOfferedAt?.toEpochMilli() ?: JSONObject.NULL)
        put("dismissedAt", e.dismissedAt?.toEpochMilli() ?: JSONObject.NULL)
        put("reminderSent", e.reminderSent)
    }

    private fun fromJson(j: JSONObject) = runCatching {
        StressEpisode(
            id = j.getString("id"),
            userId = "",
            openedAt = Instant.ofEpochMilli(j.getLong("openedAt")),
            closedAt = j.epochOrNull("closedAt"),
            lastTriggerLevel = j.optInt("lastTriggerLevel", 0),
            offerMade = j.optBoolean("offerMade", false),
            lastOfferedAt = j.epochOrNull("lastOfferedAt"),
            dismissedAt = j.epochOrNull("dismissedAt"),
            reminderSent = j.optBoolean("reminderSent", false)
        )
    }.getOrNull()

    private fun JSONObject.epochOrNull(key: String): Instant? =
        if (isNull(key)) null else Instant.ofEpochMilli(getLong(key))

    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private fun pulseKey(userId: String) = "qpulse_v1_$userId"
    private fun responseKey(userId: String) = "qresponse_v1_$userId"
    private fun episodeKey(userId: String) = "qepisode_v1_$userId"
    private fun pssKey(userId: String) = "pss10_v1_$userId"

    companion object {
        const val NO_SESSION = "Sign in to continue."
        const val PRIVATE_TO_PERSONNEL =
            "Questionnaire answers are private to the person who gave them."
        const val COMMANDER_ONLY = "Commander access only."
    }
}