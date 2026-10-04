package com.bishnu.stresswelfarebridge.commander

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.KeyValueStore
import com.bishnu.stresswelfarebridge.auth.Session
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authorization boundary for personnel monitoring data.
 *
 * **Every** read and write of a [PersonnelRecord] goes through this class, and each method checks
 * the caller's [Session] before touching storage. Hiding a button in Compose is not access control;
 * the checks live here, in the data layer, so a personnel session cannot obtain another person's
 * record even if the UI is bypassed.
 *
 * Rules enforced:
 *  - a record can only be written for the account that owns the session (personnel publish their own);
 *  - only a commander may list all personnel, list a zone, or open another person's record;
 *  - personnel may open exactly one record — their own;
 *  - records are keyed by account id, so there is no global, shared health bucket.
 */
class PersonnelDirectory(
    private val store: KeyValueStore,
    private val accounts: AccountStore
) {

    // -- writes ----------------------------------------------------------------------------------

    /**
     * Publishes the signed-in personnel member's own record.
     *
     * The account id and identity are taken from the session, never from the caller, so a personnel
     * member cannot write into somebody else's record even by constructing a [PersonnelRecord]
     * themselves.
     */
    fun publishOwnRecord(session: Session?, record: PersonnelRecord): Access<PersonnelRecord> {
        val s = session ?: return Access.Denied(NO_SESSION)
        if (s.role.isCommander) return Access.Denied(COMMANDERS_DO_NOT_PUBLISH)
        if (record.accountId != s.accountId) {
            return Access.Denied("You can only publish your own record.")
        }
        val owned = record.copy(
            accountId = s.accountId,
            fullName = s.account.fullName,
            serviceId = s.account.serviceId
        )
        store.putString(keyFor(s.accountId), toJson(owned).toString())
        addToIndex(s.accountId)
        return Access.Allowed(owned)
    }

    // -- reads -----------------------------------------------------------------------------------

    /** The signed-in personnel member's own record, or null if none has been published. */
    fun ownRecord(session: Session?): PersonnelRecord? {
        val s = session ?: return null
        return read(s.accountId)
    }

    /** Commander-only: every personnel record on this device store, grouped into zones. */
    fun commanderOverview(session: Session?): Access<CommanderOverview> {
        if (session == null) return Access.Denied(NO_SESSION)
        if (!session.isCommander) return Access.Denied(COMMANDER_ONLY)
        val all = allRecords()
        val grouped = StressZone.ordered.associateWith { zone ->
            all.filter { it.zone == zone }.sortedBy { it.fullName.lowercase() }
        }
        val unclassified = all.filter { it.zone == null }.sortedBy { it.fullName.lowercase() }
        return Access.Allowed(
            CommanderOverview(
                zones = grouped,
                unclassified = unclassified,
                lastUpdatedMs = all.maxOfOrNull { it.lastUpdatedMs }
            )
        )
    }

    /** Commander-only: the personnel currently filed under [zone]. */
    fun zoneMembers(session: Session?, zone: StressZone): Access<List<PersonnelRecord>> =
        when (val overview = commanderOverview(session)) {
            is Access.Denied -> overview
            is Access.Allowed -> Access.Allowed(overview.value.zones[zone].orEmpty())
        }

    /**
     * One person's detail record.
     *
     * Allowed for the owner of the record and for a commander; denied for anyone else, so a
     * personnel account cannot read a colleague by guessing an account id.
     */
    fun personnelDetail(session: Session?, accountId: String): Access<PersonnelRecord> {
        val s = session ?: return Access.Denied(NO_SESSION)
        if (s.accountId != accountId && !s.isCommander) return Access.Denied(OWN_DATA_ONLY)
        val record = read(accountId)
            ?: return Access.Denied("No record has been published for this personnel member yet.")
        // Defence in depth: a record is only visible while its owning personnel account still
        // exists, so a stale id can never surface an orphaned record.
        val owner = accounts.accountById(accountId)
            ?: return Access.Denied("No record has been published for this personnel member yet.")
        if (owner.role.isCommander) return Access.Denied(COMMANDER_DATA_ONLY)
        return Access.Allowed(record)
    }

    // -- storage ---------------------------------------------------------------------------------

    /** All stored records, ordered by the index the publisher maintains. */
    private fun allRecords(): List<PersonnelRecord> = readIndex().mapNotNull(::read)

    private fun readIndex(): List<String> {
        val raw = store.getString(KEY_INDEX) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    private fun addToIndex(accountId: String) {
        val index = readIndex()
        if (index.contains(accountId)) return
        val array = JSONArray()
        (index + accountId).forEach { array.put(it) }
        store.putString(KEY_INDEX, array.toString())
    }

    private fun read(accountId: String): PersonnelRecord? {
        val raw = store.getString(keyFor(accountId)) ?: return null
        return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun keyFor(accountId: String) = "$KEY_PREFIX$accountId"

    // -- (de)serialisation -----------------------------------------------------------------------

    private fun toJson(record: PersonnelRecord): JSONObject = JSONObject().apply {
        put("accountId", record.accountId)
        put("fullName", record.fullName)
        put("serviceId", record.serviceId ?: JSONObject.NULL)
        put("stressScore", record.stressScore ?: JSONObject.NULL)
        put("stressLevel", record.stressLevel)
        put("zone", record.zone?.name ?: JSONObject.NULL)
        put("confidence", record.confidence)
        put("usingRealData", record.usingRealData)
        put("dataQuality", record.dataQuality)
        put("baselineDays", record.baselineDays)
        put("lastUpdatedMs", record.lastUpdatedMs)
        put("steps", record.steps)
        put("exerciseMinutes", record.exerciseMinutes)
        put("sleepMinutes", record.sleepMinutes)
        put("avgHr", record.avgHr ?: JSONObject.NULL)
        put("avgHrv", record.avgHrv ?: JSONObject.NULL)
        put("restingHr", record.restingHr ?: JSONObject.NULL)
        put("respiratoryRate", record.respiratoryRate ?: JSONObject.NULL)
        put("spo2", record.spo2 ?: JSONObject.NULL)
        put("cameraHrv", record.cameraHrv?.let { c ->
            JSONObject().apply {
                put("rmssdMs", c.rmssdMs)
                put("sdnnMs", c.sdnnMs)
                put("heartRateBpm", c.heartRateBpm)
                put("signalQuality", c.signalQuality)
                put("confidence", c.confidence)
                put("validBeatCount", c.validBeatCount)
                put("measuredAtMs", c.measuredAtMs)
                put("source", c.source)
            }
        } ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): PersonnelRecord? = runCatching {
        val camera = json.optJSONObject("cameraHrv")?.let { c ->
            CameraHrvSummary(
                rmssdMs = c.getDouble("rmssdMs"),
                sdnnMs = c.getDouble("sdnnMs"),
                heartRateBpm = c.getDouble("heartRateBpm"),
                signalQuality = c.getString("signalQuality"),
                confidence = c.getDouble("confidence"),
                validBeatCount = c.getInt("validBeatCount"),
                measuredAtMs = c.getLong("measuredAtMs"),
                source = c.optString("source")
            )
        }
        PersonnelRecord(
            accountId = json.getString("accountId"),
            fullName = json.getString("fullName"),
            serviceId = json.optString("serviceId").takeIf { it.isNotBlank() && it != "null" },
            stressScore = json.optInt("stressScore", -1).takeIf { it >= 0 },
            stressLevel = json.optString("stressLevel", UNCLASSIFIED),
            zone = json.optString("zone").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { StressZone.valueOf(it) }.getOrNull() },
            confidence = json.optInt("confidence", 0),
            usingRealData = json.optBoolean("usingRealData", false),
            dataQuality = json.optString("dataQuality", ""),
            baselineDays = json.optInt("baselineDays", 0),
            lastUpdatedMs = json.optLong("lastUpdatedMs", 0L),
            steps = json.optLong("steps", 0L),
            exerciseMinutes = json.optLong("exerciseMinutes", 0L),
            sleepMinutes = json.optLong("sleepMinutes", 0L),
            avgHr = json.optDoubleOrNull("avgHr"),
            avgHrv = json.optDoubleOrNull("avgHrv"),
            restingHr = json.optDoubleOrNull("restingHr"),
            respiratoryRate = json.optDoubleOrNull("respiratoryRate"),
            spo2 = json.optDoubleOrNull("spo2"),
            cameraHrv = camera
        )
    }.getOrNull()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private companion object {
        const val KEY_PREFIX = "record_v1_"
        const val KEY_INDEX = "record_index_v1"
        const val UNCLASSIFIED = "Awaiting data"
        const val NO_SESSION = "Sign in to continue."
        const val COMMANDER_ONLY = "Commander access only."
        const val OWN_DATA_ONLY = "You can only view your own record."
        const val COMMANDER_DATA_ONLY = "Commanders do not have personnel records."
        const val COMMANDERS_DO_NOT_PUBLISH = "Commanders do not publish personnel records."
    }
}

