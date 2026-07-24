package com.vishalgupta.photoselector.domain.insight

import com.vishalgupta.photoselector.domain.model.Photo

/**
 * The read seam the [com.vishalgupta.photoselector.domain.insight.PredicateTreeResolver] pulls a leaf's
 * insight through. Deliberately **cache-only and gated**: it returns a value only when the folder's
 * insight pass has completed and the photo's score is cached — never triggering a compute — so
 * resolving a smart category never kicks off the expensive whole-folder pass off the back of a bind.
 *
 * Returns `null` for: an unknown/unavailable [InsightProviderId] (graceful degrade — the leaf then
 * matches nothing but the category stays smart), a photo not yet analyzed, or a not-yet-analyzed root.
 * The returned [InsightValue.Scalar] is already banded against the last analyzed distribution.
 */
interface InsightSource {
    suspend fun bandedValue(providerId: InsightProviderId, photo: Photo): InsightValue?
}

/**
 * Whether a root's insight pass has run, for the tri-state analyzed signal the rail surfaces. Insight
 * membership needs three states the binary "resolved set" can't express: not analyzed yet (prompt the
 * user, count `—`), analyzed (a real count, possibly 0), and stale (analyzed, but the folder's photo
 * set has since changed so the membership may be out of date — offer a re-analyze).
 */
enum class InsightAnalysisStatus { NotAnalyzed, Analyzed, Stale }
