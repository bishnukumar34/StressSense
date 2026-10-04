package com.bishnu.stresswelfarebridge.ppg

/**
 * Chooses which (region, band) pair to trust, on measured evidence rather than assumption.
 *
 * A fixed central ROI on the green channel is the classic weak point of phone PPG: the finger is
 * rarely perfectly centred, and under a torch the most pulsatile band varies with skin tone, finger
 * thickness and exposure. This selector keeps a short rolling history of a quality score for every
 * candidate and commits to the best one, with hysteresis so it does not oscillate between two
 * equally mediocre sources frame to frame.
 *
 * The choice is made from *real* acquisition scores ([PpgAcquisition]) — never a hard-coded
 * "green is always best" — and a clipped patch is penalised hard, because a saturated region has
 * large variance and would otherwise win on amplitude alone.
 *
 * Re-selection is deliberately allowed *during* a measurement ([reset]): if the finger slides off the
 * selected patch, following the finger beats preserving a bad selection, and the HRV quality gate
 * still refuses to publish an incoherent result afterwards.
 */
class PpgSourceSelector(
    /** Frames of history each candidate is scored over. */
    private val windowFrames: Int = DEFAULT_WINDOW_FRAMES,
    /** A challenger must beat the incumbent by this much before it is adopted. */
    private val switchMargin: Double = DEFAULT_SWITCH_MARGIN
) {

    private class Candidate {
        val key: PpgSourceKey
        var sumScore = 0.0
        var frames = 0
        var clippedFrames = 0
        var lastSeenFrame = -1

        constructor(key: PpgSourceKey) {
            this.key = key
        }

        /** Decayed mean so that stale evidence stops counting. */
        fun meanScore(decay: Double = 1.0): Double =
            if (frames == 0) 0.0 else (sumScore / frames) * decay

        fun add(score: Double, clipped: Boolean) {
            sumScore += score
            frames++
            if (clipped) clippedFrames++
        }

        fun clipRatio(): Double = if (frames == 0) 0.0 else clippedFrames.toDouble() / frames
    }

    private val candidates = LinkedHashMap<PpgSourceKey, Candidate>()
    private var frameIndex = 0
    private var committed: PpgSourceKey? = null

    /** The (region, band) currently being recorded. */
    val selected: PpgSourceKey? get() = committed

    /**
     * Records the acquisition score of one source for one frame and returns the source that should
     * be used from now on.
     *
     * @param scores measured acquisition score per source for this frame
     * @param clipped sources whose frame is saturated/crushed this frame
     */
    fun update(scores: Map<PpgSourceKey, Double>, clipped: Set<PpgSourceKey> = emptySet()): PpgSourceKey? {
        frameIndex++
        for ((key, score) in scores) {
            val candidate = candidates.getOrPut(key) { Candidate(key) }
            candidate.add(score, key in clipped)
            // Refreshed on *every* observation, so `age` counts only frames the source was absent.
            // Setting it only on the first frame would freeze stale evidence forever.
            candidate.lastSeenFrame = frameIndex
        }

        val best = candidates.values.maxByOrNull { effectiveScore(it) } ?: return committed
        // If every candidate is excluded (everything clipped), keep the incumbent rather than
        // committing to a source that carries no information.
        if (effectiveScore(best) <= 0.0) return committed

        val incumbent = committed?.let { candidates[it] }
        val incumbentScore = incumbent?.let { effectiveScore(it) } ?: -1.0
        if (committed == null || effectiveScore(best) > incumbentScore + switchMargin) {
            committed = best.key
        }
        return committed
    }

    /**
     * The score a candidate is judged by: its recent mean, discounted for staleness, and **excluded
     * entirely** when it has been clipped on more than half of its observations.
     *
     * Exclusion rather than a discount is the physically correct choice: a patch that has been
     * saturated more often than not is carrying no usable information at all, so it must not be
     * selectable however large its raw amplitude looked. The clip test lives in this single function
     * so that the incumbent and the challenger are always compared on identical terms.
     */
    private fun effectiveScore(candidate: Candidate): Double {
        if (candidate.clipRatio() > CLIP_EXCLUSION_RATIO) return 0.0
        return candidate.meanScore(decayFor(candidate.lastSeenFrame))
    }

    /** Decays a candidate's influence once it stops being observed. */
    private fun decayFor(lastSeenFrame: Int): Double {
        if (lastSeenFrame < 0) return 1.0
        val age = frameIndex - lastSeenFrame
        if (age <= 0) return 1.0
        return (1.0 / (1.0 + age / windowFrames.toDouble())).coerceIn(0.2, 1.0)
    }

    /** Forgets everything; used when a new measurement starts. */
    fun reset() {
        candidates.clear()
        frameIndex = 0
        committed = null
    }

    /** Ranked alternatives, best first — used by the diagnostics log and the recovery prompt. */
    fun ranking(): List<Pair<PpgSourceKey, Double>> =
        candidates.values
            .map { it.key to it.meanScore() }
            .sortedByDescending { it.second }

    companion object {
        const val DEFAULT_WINDOW_FRAMES = 45
        const val DEFAULT_SWITCH_MARGIN = 0.05

        /** A source clipped on more than this fraction of its observations is excluded outright. */
        const val CLIP_EXCLUSION_RATIO = 0.5
    }
}
