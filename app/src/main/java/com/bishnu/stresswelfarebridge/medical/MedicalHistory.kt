package com.bishnu.stresswelfarebridge.medical

import com.bishnu.stresswelfarebridge.auth.AccountStore
import com.bishnu.stresswelfarebridge.auth.KeyValueStore
import com.bishnu.stresswelfarebridge.auth.Session
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A personnel member's own medical history.
 *
 * This is **private health information**. It is stored per account, readable only by the account
 * that entered it, and is never placed on a commander-facing type. There is deliberately no
 * commander accessor on [MedicalHistoryRepository] at all - the absence of the method is the
 * enforcement, rather than a role check inside a method that could later be widened by accident.
 */
data class MedicalHistory(
    val userId: String,
    val hasConditions: Boolean = false,
    val conditions: List<String> = emptyList(),
    val hasAllergies: Boolean = false,
    val allergies: List<String> = emptyList(),
    val medications: String = "",
    val hasSurgeries: Boolean = false,
    val surgeries: String = "",
    val hasHospitalizations: Boolean = false,
    val hospitalizations: String = "",
    val hasInjuries: Boolean = false,
    val injuries: String = "",
    val ongoingTreatment: String = "",
    val notes: String = "",
    val updatedAt: Instant? = null
) {
    /** True when the person has entered nothing at all. */
    val isEmpty: Boolean
        get() = !hasConditions && !hasAllergies && medications.isBlank() && !hasSurgeries &&
            !hasHospitalizations && !hasInjuries && ongoingTreatment.isBlank() && notes.isBlank()

    /** True when there is anything worth summarising on the Profile screen. */
    val hasAnything: Boolean get() = !isEmpty

    /**
     * Short, non-exhaustive summary for Profile.
     *
     * Intentionally *not* the whole record: Profile shows conditions, allergies, medications and a
     * one-line note that further history exists, and the full detail stays behind "View / Edit".
     */
    fun summaryLines(): List<String> = buildList {
        if (hasConditions && conditions.isNotEmpty()) add("Conditions: " + conditions.joinToString(", "))
        if (hasAllergies && allergies.isNotEmpty()) add("Allergies: " + allergies.joinToString(", "))
        if (medications.isNotBlank()) add("Medications: " + medications.trim())
        val other = listOfNotNull(
            surgeries.takeIf { hasSurgeries && it.isNotBlank() }?.let { "Previous surgery" },
            hospitalizations.takeIf { hasHospitalizations && it.isNotBlank() }?.let { "Previous hospitalization" },
            injuries.takeIf { hasInjuries && it.isNotBlank() }?.let { "Significant injury" },
            ongoingTreatment.takeIf { it.isNotBlank() }?.let { "Ongoing treatment" }
        )
        if (other.isNotEmpty()) add("Significant history: " + other.joinToString(", "))
    }
}

/**
 * Persistence for [MedicalHistory], scoped to the authenticated account.
 *
 * Reuses the app's existing JSON-in-SharedPreferences abstraction, so a future backend swap touches
 * one class. Every read and write goes through a [Session], which is what enforces ownership: there
 * is no method that takes a user id from the caller, so one person cannot address another's record.
 */
class MedicalHistoryRepository(
    private val store: KeyValueStore,
    private val clock: () -> Instant = { Instant.now() }
) {

    /** The signed-in person's history, or null when none has been recorded. */
    fun get(session: Session?): MedicalHistory? {
        val s = session ?: return null
        if (s.role.isCommander) return null
        val raw = store.getString(key(s.accountId)) ?: return null
        return runCatching { fromJson(JSONObject(raw), s.accountId) }.getOrNull()
    }

    fun exists(session: Session?): Boolean = get(session) != null

    /**
     * Saves the signed-in person's history.
     *
     * Returns false for a commander session or a missing session. The account id always comes from
     * the session, never from the record, so a caller cannot write into someone else's entry.
     */
    fun save(session: Session?, history: MedicalHistory): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        val owned = history.copy(userId = s.accountId, updatedAt = clock())
        store.putString(key(s.accountId), toJson(owned).toString())
        return true
    }

    fun clear(session: Session?): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        store.remove(key(s.accountId))
        return true
    }

    private fun key(userId: String) = "medical_history_v1_$userId"

    private fun toJson(h: MedicalHistory) = JSONObject().apply {
        put("hasConditions", h.hasConditions)
        put("conditions", JSONArray(h.conditions))
        put("hasAllergies", h.hasAllergies)
        put("allergies", JSONArray(h.allergies))
        put("medications", h.medications)
        put("hasSurgeries", h.hasSurgeries)
        put("surgeries", h.surgeries)
        put("hasHospitalizations", h.hasHospitalizations)
        put("hospitalizations", h.hospitalizations)
        put("hasInjuries", h.hasInjuries)
        put("injuries", h.injuries)
        put("ongoingTreatment", h.ongoingTreatment)
        put("notes", h.notes)
        put("updatedAt", h.updatedAt?.toEpochMilli() ?: JSONObject.NULL)
    }

    private fun fromJson(j: JSONObject, userId: String): MedicalHistory {
        fun strings(key: String): List<String> {
            val arr = j.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }
        return MedicalHistory(
            userId = userId,
            hasConditions = j.optBoolean("hasConditions", false),
            conditions = strings("conditions"),
            hasAllergies = j.optBoolean("hasAllergies", false),
            allergies = strings("allergies"),
            medications = j.optString("medications", ""),
            hasSurgeries = j.optBoolean("hasSurgeries", false),
            surgeries = j.optString("surgeries", ""),
            hasHospitalizations = j.optBoolean("hasHospitalizations", false),
            hospitalizations = j.optString("hospitalizations", ""),
            hasInjuries = j.optBoolean("hasInjuries", false),
            injuries = j.optString("injuries", ""),
            ongoingTreatment = j.optString("ongoingTreatment", ""),
            notes = j.optString("notes", ""),
            updatedAt = j.optLong("updatedAt", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        )
    }
}
