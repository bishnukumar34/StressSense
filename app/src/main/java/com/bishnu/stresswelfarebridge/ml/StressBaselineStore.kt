package com.bishnu.stresswelfarebridge.ml

import com.bishnu.stresswelfarebridge.auth.KeyValueStore
import com.bishnu.stresswelfarebridge.auth.Session
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the per-account state the deployed model personalises against: the
 * [PersonalBaseline] and the [ObservationHistory].
 *
 * ### Why this exists
 * Both objects were created with `remember(session?.accountId)` and held only in memory, so every
 * app restart, process death or session change threw the personal reference away. Because
 * [PersonalBaseline.MIN_SAMPLES] is 5, a person who measured four times and then closed the app was
 * told "1 more reading needed" again and again - the five-reading path to a real score was effectively
 * unreachable. The model itself was never the problem; forgetting was.
 *
 * ### Deliberately identical in shape to [StressMeasurementStore]
 * Same [KeyValueStore], same [Session] scoping, same "commander gets nothing" rule, same defensive
 * `runCatching` reads, same per-account key suffix. One persistence pattern for the whole ML layer, not
 * a second unrelated one. No network, no database, no new dependency.
 *
 * ### Backward compatibility
 * [load] treats any missing or unreadable state as "this account has no baseline yet" and returns
 * empty objects. An install that predates this feature, or a partially written record, therefore starts
 * cleanly from zero rather than crashing - and existing [StressMeasurementRecord] history is a
 * different key entirely and is never touched or deleted.
 */
class StressBaselineStore(private val store: KeyValueStore) {

    /**
     * Reads this account's saved state into [baseline] and [history].
     *
     * Returns false when there was nothing stored, which is not an error - it just means the account
     * has not contributed any qualifying reading yet.
     */
    fun load(session: Session?, baseline: PersonalBaseline, history: ObservationHistory): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        val raw = store.getString(key(s.accountId)) ?: return false
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false

        // Restore history first: neither restore path throws, but an independent failure in one must
        // not prevent the other from being restored.
        val observations = json.optJSONArray("observations")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::observationFromJson) }
        }
        if (observations != null) history.restore(observations)

        val baselineJson = json.optJSONObject("baseline")
        if (baselineJson != null) {
            val metrics = baselineJson.optJSONObject("metrics")
            val restored = if (metrics == null) {
                emptyMap()
            } else {
                metrics.keys().asSequence().associateWith { metric ->
                    val arr = metrics.optJSONArray(metric) ?: JSONArray()
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::sampleFromJson) }
                }
            }
            baseline.restore(BaselineSnapshot(restored))
        }
        return observations != null || baselineJson != null
    }

    /**
     * Writes [baseline] and [history] for this account, immediately.
     *
     * Called after every qualifying measurement rather than on close, so a crash or a kill cannot lose
     * readings the person genuinely made.
     */
    fun save(session: Session?, baseline: PersonalBaseline, history: ObservationHistory): Boolean {
        val s = session ?: return false
        if (s.role.isCommander) return false
        val json = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("baseline", JSONObject().apply {
                val metrics = JSONObject()
                baseline.snapshot().metrics.forEach { (name, samples) ->
                    val arr = JSONArray()
                    samples.forEach { arr.put(sampleToJson(it)) }
                    metrics.put(name, arr)
                }
                put("metrics", metrics)
            })
            put("observations", JSONArray().apply {
                history.snapshot().forEach { put(observationToJson(it)) }
            })
        }
        store.putString(key(s.accountId), json.toString())
        return true
    }

    /** Forgets this account's baseline and history. Used when a test needs a clean slate. */
    fun clear(session: Session?) {
        val s = session ?: return
        store.remove(key(s.accountId))
    }

    // -- JSON -------------------------------------------------------------------------------------------

    private fun sampleToJson(s: BaselineSample): JSONObject = JSONObject().apply {
        put("atMs", s.atMillis)
        put("value", s.value)
    }

    private fun sampleFromJson(j: JSONObject): BaselineSample? = runCatching {
        BaselineSample(
            atMillis = j.getLong("atMs"),
            value = j.getDouble("value")
        )
    }.getOrNull()

    private fun observationToJson(o: ObservationEntry): JSONObject = JSONObject().apply {
        put("atMs", o.atMillis)
        put("hr", o.hr ?: JSONObject.NULL)
        put("rmssd", o.rmssd ?: JSONObject.NULL)
    }

    private fun observationFromJson(j: JSONObject): ObservationEntry? = runCatching {
        ObservationEntry(
            atMillis = j.getLong("atMs"),
            hr = j.optDoubleOrNull("hr"),
            rmssd = j.optDoubleOrNull("rmssd")
        )
    }.getOrNull()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private fun key(userId: String) = "stress_baseline_v1_$userId"

    private companion object {
        const val FORMAT_VERSION = 1
    }
}