package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.domain.insight.InsightProvider
import com.vishalgupta.photoselector.domain.insight.InsightProviderId
import com.vishalgupta.photoselector.domain.insight.InsightValue
import com.vishalgupta.photoselector.domain.model.DecodedImage
import com.vishalgupta.photoselector.domain.model.Photo

/**
 * The first [InsightProvider], proving the platform loop on the sharpness the app already computes — no
 * new model, no ONNX, no network. It emits the raw **variance-of-Laplacian** ([SharpnessScorer]) over the
 * cheap [decodeForSharpness] path (deliberately much cheaper than the embedding pass), memoized in an
 * [InsightCache].
 *
 * It opportunistically reuses a sharpness value already sitting in the [EmbeddingCache] on a hit — the
 * Similarity pass scores sharpness on the *same* 768px canonical canvas, so the value is identical and a
 * folder already grouped by Similarity needs no second decode. Otherwise it decodes and scores once,
 * accepting the minor duplication between the two caches. The emitted [InsightValue.Scalar] carries the
 * [raw][InsightValue.Scalar.raw] only; banding against the folder's distribution is the consumer's job.
 */
class SharpnessInsightProvider(
    private val cache: InsightCache,
    private val decodeForSharpness: suspend (Photo) -> DecodedImage?,
    private val embeddingCache: EmbeddingCache? = null,
) : InsightProvider {

    override val id: InsightProviderId = ID
    override val version: Int = VERSION
    override val displayName: String = "Sharpness"

    override suspend fun value(photo: Photo): InsightValue? {
        cache.get(photo)?.let { return InsightValue.Scalar(raw = it) }
        // Opportunistic: a Similarity pass already scored sharpness on the same canonical canvas.
        embeddingCache?.get(photo)?.let { features ->
            cache.put(photo, features.sharpness)
            return InsightValue.Scalar(raw = features.sharpness)
        }
        val image = decodeForSharpness(photo) ?: return null
        val raw = SharpnessScorer.score(image)
        cache.put(photo, raw)
        return InsightValue.Scalar(raw = raw)
    }

    companion object {
        val ID: InsightProviderId = InsightProviderId("sharpness")
        const val VERSION: Int = 1
    }
}
