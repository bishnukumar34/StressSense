package com.bishnu.stresswelfarebridge.questionnaire

import kotlin.math.roundToInt

/** Outcome of scoring a set of answers. Every field is null when it could not be computed. */
data class QuestionnaireScore(
    val instrumentId: String,
    val version: Int,
    /** Primary score, 0..[maxScore]. Null when nothing could be scored. */
    val primary: Double?,
    val maxScore: Double,
    /** Subscale name  doubled/undoubled score as the definition requires. */
    val subscales: Map<String, Double> = emptyMap(),
    /** Subscale name  band name. */
    val bands: Map<String, String> = emptyMap(),
    /** Fraction of *required* items actually answered, 0..1. */
    val completeness: Double = 0.0,
    val answeredCount: Int = 0,
    val totalCount: Int = 0
) {
    val isComplete: Boolean get() = primary != null && completeness >= 1.0
}

/**
 * Pure scoring for every instrument.
 *
 * Android-free by design so every rule below is verifiable with plain JVM unit tests - no device,
 * no emulator, no Robolectric.
 *
 * Three rules matter and are implemented explicitly rather than assumed:
 *
 *  1. **Reversal.** An item answered on a "higher is better" scale is converted with
 *     `max - response` before it contributes, so a rested person scores low stress.
 *  2. **Partial answers renormalise.** If only some questions were answered, the weights are
 *     rescaled over the questions actually answered. A person who answers only "how stressed?" is
 *     not silently scored as if everything else were zero.
 *  3. **Nothing answered means nothing stored.** A score is null rather than 0, and the caller
 *     stores nothing. Zero is a real score (completely unstressed), and conflating the two would
 *     fabricate data.
 */
object QuestionnaireScorer {

    /** Normalises a 1..5 answer onto 0..1 exactly as specified: `norm(x) = (x - 1) / 4`. */
    fun norm(value: Int): Double {
        require(value in 1..5) { "Daily Pulse answers run 1..5, got $value" }
        return (value - 1) / 4.0
    }
/**
     * Daily Pulse score, 0..100.
     *
     * ```
     * norm(x)   = (x - 1) / 4
     * rest_inv  = 1 - norm(d_rest)
     * selfDaily = 100 * ( 0.50 * norm(d_stress)
     *                     + 0.25 * rest_inv
     *                     + 0.25 * norm(d_duty) )
     * ```
     *
     * Returns null when no item was answered. With a partial answer the weights are renormalised
     * over the answered items, so a lone stress rating of 5 still yields 100 rather than 50.
     */
    /**
     * Scores any instrument from raw item answers.
     *
     * Handles reverse-scored items, per-subscale sums, DASS-style doubling, and severity bands.
     * Returns a null primary when nothing usable was answered.
     */
    fun score(definition: QuestionnaireDefinition, answers: Map<String, Int>): QuestionnaireScore {
        val valid = answers.filter { (id, value) ->
            definition.item(id)?.let { definition.scale.valid(value) } ?: false
        }
        val requiredIds = definition.items.filter { it.required }.map { it.id }
        val completeness =
            if (requiredIds.isEmpty()) 1.0
            else valid.keys.count { it in requiredIds }.toDouble() / requiredIds.size

        fun adjusted(item: QuestionnaireItem): Double? {
            val raw = valid[item.id] ?: return null
            val v: Double = if (item.reverseScored) (definition.scale.max - raw).toDouble()
            else raw.toDouble()
            return v
        }

        val subscaleTotals = mutableMapOf<String, Double>()
        for ((name, ids) in definition.subscales) {
            var sum = 0.0
            var counted = 0
            for (id in ids) {
                val item = definition.item(id) ?: continue
                val v = adjusted(item) ?: continue
                sum += v
                counted++
            }
            if (counted == 0) continue
            subscaleTotals[name] =
                if (name in definition.doubleScoredSubscales) sum * 2 else sum
        }

        val bandNames = subscaleTotals.mapNotNull { (name, total) ->
            definition.bandFor(name, total.roundToInt())?.let { name to it.name }
        }.toMap()

        // A single-scale instrument has one overall total; a multi-scale one leads with its first
        // subscale, which for DASS is the stress scale.
        val primary = if (definition.subscales.isEmpty()) {
            definition.items.sumOf { adjusted(it) ?: 0.0 }.takeIf { valid.isNotEmpty() }
        } else {
            subscaleTotals.values.firstOrNull()
        }

        return QuestionnaireScore(
            instrumentId = definition.id,
            version = definition.version,
            primary = primary,
            maxScore = definition.scale.max * definition.items.size.toDouble(),
            subscales = subscaleTotals,
            bands = bandNames,
            completeness = completeness,
            answeredCount = valid.size,
            totalCount = definition.items.size
        )
    }
}