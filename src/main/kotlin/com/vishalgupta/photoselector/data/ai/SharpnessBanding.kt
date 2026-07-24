package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.domain.insight.BandingStrategy
import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightRange
import com.vishalgupta.photoselector.domain.insight.ScalarBanding

/**
 * The [BandingStrategy] for the sharpness provider: the sharper end of *this folder* is [InsightBand.Sharp],
 * the softer end [InsightBand.Soft]. The cut is the [SHARP_PERCENTILE] of the folder's own
 * variance-of-Laplacian distribution — adaptive per set, following the `SimilarityGrouper` precedent, so
 * "sharp" means "crisp for this shoot" rather than a meaningless absolute number.
 *
 * [PROVISIONAL_THRESHOLD] is a rough absolute fallback used only in the panel before a folder has been
 * analyzed (variance-of-Laplacian has no universal scale, so this is a hint, not a verdict).
 */
object SharpnessBanding : BandingStrategy {

    /** Photos at or above this percentile of the folder's sharpness distribution read as Sharp. */
    const val SHARP_PERCENTILE = 0.6f

    /** Rough absolute Laplacian-variance cut for the provisional (pre-analysis) panel band. */
    const val PROVISIONAL_THRESHOLD = 100f

    private val PROVISIONAL_RANGE = InsightRange(0f, 500f)

    override fun fromDistribution(raws: List<Float>): ScalarBanding = ScalarBanding.fromDistribution(
        values = raws,
        percentile = SHARP_PERCENTILE,
        atOrAbove = InsightBand.Sharp,
        below = InsightBand.Soft,
        provisionalThreshold = PROVISIONAL_THRESHOLD,
        provisionalRange = PROVISIONAL_RANGE,
    )

    override fun provisional(): ScalarBanding = ScalarBanding.provisional(
        threshold = PROVISIONAL_THRESHOLD,
        range = PROVISIONAL_RANGE,
        atOrAbove = InsightBand.Sharp,
        below = InsightBand.Soft,
    )
}
