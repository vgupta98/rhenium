package com.vishalgupta.photoselector.domain.insight

import com.vishalgupta.photoselector.domain.model.Photo

/** Stable identity of an [InsightProvider], referenced by a [com.vishalgupta.photoselector.domain.model.CategoryRule.Leaf]. */
@JvmInline
value class InsightProviderId(val value: String)

/**
 * The provider seam: **any model that outputs one typed data point per photo**. The feature-agnostic
 * generalization of `data/ai/EmbeddingModel` + `PhotoFeatureExtractor` — a provider self-describes with
 * an [id] and a [version] (both fold into the [com.vishalgupta.photoselector.data.ai.InsightCache] key,
 * so a swap or bump re-keys the cache automatically) and computes a photo's [InsightValue] on demand.
 *
 * `suspend` because the work is a decode plus a metric (or, later, an inference) off the main thread;
 * a `null` return means the value is unassessable for this photo (unsupported/unreadable) and callers
 * treat it as "no signal" rather than guessing. Phase 1 ships one — `SharpnessInsightProvider` — over
 * the cheap decode + variance-of-Laplacian the app already computes; no new model, network or ONNX.
 */
interface InsightProvider {
    val id: InsightProviderId
    val version: Int
    /** Human name for the panel row (e.g. "Sharpness"). Part of the provider's self-description. */
    val displayName: String
    suspend fun value(photo: Photo): InsightValue?
}
