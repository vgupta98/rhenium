package com.vishalgupta.photoselector.domain.insight

/**
 * Turns a raw [InsightValue.Scalar] score into a human [InsightBand] by comparing it to a [threshold].
 * Pure and set-derived, following the `SimilarityGrouper` adaptive-threshold precedent: a lone raw
 * score can't be banded in isolation, so the threshold is derived once from the analyzed set's own
 * distribution (a percentile) — the sharper end of *this folder* is "Sharp", not some universal cut.
 *
 * Direction is fixed to "higher is [atOrAbove]": a score at or above the threshold lands in
 * [atOrAbove], below it in [below]. That matches sharpness (a crisper frame scores higher) and every
 * other "more is better" scalar; a future "lower is better" signal supplies swapped bands.
 */
class ScalarBanding(
    val threshold: Float,
    val range: InsightRange,
    private val atOrAbove: InsightBand,
    private val below: InsightBand,
) {
    fun band(raw: Float): InsightBand = if (raw >= threshold) atOrAbove else below

    /** The raw score wrapped as a fully-banded [InsightValue.Scalar] ready for display. */
    fun scalar(raw: Float): InsightValue.Scalar =
        InsightValue.Scalar(raw = raw, range = range, band = band(raw))

    companion object {
        /**
         * Derive a banding from the analyzed set's own scores: the threshold is the [percentile] of the
         * distribution (0f..1f), so a fixed fraction of the folder lands in [below] and the rest in
         * [atOrAbove]. The [range] spans the observed min..max for the panel's bar. An empty or degenerate
         * distribution falls back to [provisional].
         */
        fun fromDistribution(
            values: List<Float>,
            percentile: Float,
            atOrAbove: InsightBand,
            below: InsightBand,
            provisionalThreshold: Float,
            provisionalRange: InsightRange,
        ): ScalarBanding {
            if (values.isEmpty()) {
                return provisional(provisionalThreshold, provisionalRange, atOrAbove, below)
            }
            val sorted = values.sorted()
            val threshold = percentileOf(sorted, percentile)
            val range = InsightRange(sorted.first(), sorted.last().coerceAtLeast(sorted.first() + 1f))
            return ScalarBanding(threshold, range, atOrAbove, below)
        }

        /** A rough absolute banding used before the folder has ever been analyzed (no distribution yet). */
        fun provisional(
            threshold: Float,
            range: InsightRange,
            atOrAbove: InsightBand,
            below: InsightBand,
        ): ScalarBanding = ScalarBanding(threshold, range, atOrAbove, below)

        /** Lower-interpolation percentile of an already-sorted, non-empty list. */
        private fun percentileOf(sorted: List<Float>, percentile: Float): Float {
            val p = percentile.coerceIn(0f, 1f)
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }
}

/**
 * Per-provider policy for turning a set of raw scalars into a [ScalarBanding]. One per insight provider,
 * so a future signal can band on its own percentile/bands without touching the coordinator. Pure.
 */
interface BandingStrategy {
    /** The banding derived from an analyzed folder's own distribution (the adaptive, set-relative cut). */
    fun fromDistribution(raws: List<Float>): ScalarBanding

    /** A rough absolute banding used before any folder analysis (the panel's provisional band). */
    fun provisional(): ScalarBanding
}
