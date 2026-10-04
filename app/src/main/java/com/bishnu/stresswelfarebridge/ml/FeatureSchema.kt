package com.bishnu.stresswelfarebridge.ml

/**
 * The canonical StressSense stress-model feature schema.
 *
 * This is the **single source of truth** for feature identity and ordering. The Python training
 * pipeline (`ml/train_wesad_stress.py`) writes this exact ordering into the exported model JSON, and
 * the on-device inference reads it back from there. Nothing re-declares a feature list, so Python
 * and Android cannot silently drift apart in feature order or in which feature sits at which index.
 *
 * Rules encoded here, and relied on by [FeatureExtractor]:
 *  - features are grouped by **physiological role**, not by sensor, so that a context variable can
 *    never be mistaken for a direct stress signal (Phase 3 / Phase 15 of the model specification);
 *  - every feature is optional at inference time. A missing value is represented as `NaN` and is
 *    imputed from the training-set median at model level — **never** as a physiological zero
 *    (Phase 8);
 *  - nothing here is a medical claim. These are research features for a stress *estimate*.
 *
 * @see MODEL_VERSION for the versioning triple stored with every prediction.
 */
object FeatureSchema {

    // -- versioning (Phase 22) ----------------------------------------------------------------------

    const val MODEL_VERSION = "wesad-loso-logreg-v1"
    const val FEATURE_SCHEMA_VERSION = 1
    const val PREPROCESSING_VERSION = 1
    const val BASELINE_VERSION = 1
    const val CALIBRATION_VERSION = 1

    /** Semantic role of each feature group. */
    enum class Group {
        /** Directly measured acute physiology for the current moment. */
        ACUTE,

        /** Deviation of acute physiology from this person's own rolling baseline. */
        BASELINE_DEVIATION,

        /** Respiration. */
        RESPIRATION,

        /**
         * Recent physical load. Present so that a high heart rate caused by exertion is
         * distinguishable from a high heart rate caused by stress.
         */
        ACTIVITY,

        /** Change over the person's own recent history. */
        TEMPORAL,

        /**
         * Measurement trustworthiness. Used both as model input and as the abstention gate, so a
         * poor camera trace cannot be laundered into a confident score.
         */
        QUALITY
    }

    /** One feature: its canonical name, the group it belongs to, and its unit (for reporting). */
    data class Feature(
        val name: String,
        val group: Group,
        val unit: String
    ) {
        /**
         * Whether a baseline-relative z-score can be formed for this feature. Only features with
         * an established personal baseline and a meaningful natural spread are standardised.
         */
        val baselineable: Boolean get() = group == Group.ACUTE || group == Group.RESPIRATION
    }

    private fun acute(n: String, u: String) = Feature(n, Group.ACUTE, u)
    private fun resp(n: String, u: String) = Feature(n, Group.RESPIRATION, u)
    private fun dev(n: String, u: String) = Feature(n, Group.BASELINE_DEVIATION, u)
    private fun act(n: String, u: String) = Feature(n, Group.ACTIVITY, u)
    private fun tmp(n: String, u: String) = Feature(n, Group.TEMPORAL, u)
    private fun qual(n: String, u: String) = Feature(n, Group.QUALITY, u)

    /**
     * Every candidate feature, in canonical order. This order is the model's input contract.
     *
     * Deliberately **excluded**, with the reason recorded here rather than silently dropped:
     *  - `sleep_duration` / `sleep_quality` — Health Connect supplies these on device, but the
     *    training corpus (WESAD) is a daytime laboratory protocol containing no sleep data at all.
     *    Without sleep labels there is no way to *validate* a sleep feature, and an unvalidated
     *    one is a guess wearing a model's clothing.
     *  - `spo2` — same problem: WESAD carries no oximetry, so its contribution is unmeasurable.
     *  - `bmi` / `height` / `weight` — available from Health Connect, absent from WESAD.
     *  - `steps` / `distance` / `calories` / `floors` — see [ACTIVITY_NOTE]: the training corpus
     *    provides an accelerometer-derived movement index instead of step counts.
     */
    val ALL: List<Feature> = listOf(
        // A. Acute physiology.
        acute("hr_bpm", "bpm"),
        acute("rmssd_ms", "ms"),
        acute("sdnn_ms", "ms"),
        acute("mean_nn_ms", "ms"),
        acute("sdsd_ms", "ms"),
        acute("pnn50_pct", "%"),
        // B. Deviation from the personal baseline (Phase 5/6).
        dev("hr_z", "sd"),
        dev("rmssd_z", "sd"),
        dev("sdnn_z", "sd"),
        dev("mean_nn_z", "sd"),
        // C. Respiration.
        resp("resp_rate_bpm", "breaths/min"),
        resp("resp_rate_z", "sd"),
        // D. Physical load context (Phase 15).
        act("activity_recent_norm", "0-1"),
        // E. Temporal context (Phase 7).
        tmp("hr_delta_recent", "bpm"),
        tmp("rmssd_delta_recent", "ms"),
        tmp("hr_slope_recent", "bpm/measure"),
        tmp("rmssd_slope_recent", "ms/measure"),
        // F. Signal quality / abstention inputs (Phase 14).
        qual("ppg_quality", "0-1"),
        qual("confidence", "0-1"),
        qual("valid_nn_ratio", "0-1"),
        qual("artifact_ratio", "0-1"),
        qual("duration_s", "s")
    )

    val NAMES: List<String> = ALL.map { it.name }

    private val byName: Map<String, Feature> = ALL.associateBy { it.name }

    fun feature(name: String): Feature? = byName[name]

    fun indexOf(name: String): Int = NAMES.indexOf(name)

    /** Feature names belonging to a group, in canonical order. */
    fun namesIn(group: FeatureSchema.Group): List<String> =
        ALL.filter { it.group == group }.map { it.name }

    /**
     * Recorded limitation, surfaced in the model report rather than buried in a comment.
     *
     * WESAD is a controlled laboratory protocol: subjects are seated and instructed to stay still,
     * so the wrist accelerometer records posture shifts rather than genuine step counts. The
     * training pipeline therefore derives `activity_recent_norm` from accelerometer magnitude, while
     * the device derives it from Health Connect steps / active minutes. Both are "recent physical
     * load, roughly normalised to 0-1", which is what the model consumes — but they are not the
     * same instrument, and that is a genuine train/deploy distribution shift, not a rounding detail.
     */
    const val ACTIVITY_NOTE =
        "activity_recent_norm: training proxy = wrist accelerometer magnitude (WESAD, seated lab " +
            "protocol); deployment = Health Connect steps / active minutes. Both approximate " +
            "recent physical load. Not identical instruments."
}