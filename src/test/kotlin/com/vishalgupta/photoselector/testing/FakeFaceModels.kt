package com.vishalgupta.photoselector.testing

import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.faces.FaceDetection
import com.vishalgupta.photoselector.domain.faces.FaceDetector
import com.vishalgupta.photoselector.domain.faces.FaceEmbedder
import com.vishalgupta.photoselector.domain.faces.FaceEmbedding
import com.vishalgupta.photoselector.domain.faces.FacePoint
import com.vishalgupta.photoselector.domain.model.DecodedImage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared in-memory face-model fakes, the counterpart to [FakeEmbeddingModel]. Both count their calls
 * so a test can prove the [com.vishalgupta.photoselector.data.faces.FaceCache] actually short-circuits
 * a rescan, and both can be told to fail so the "failure is never cached" contract is testable.
 */
class FakeFaceDetector(
    override val id: String = "fake-detector",
    private val perImage: (DecodedImage) -> List<FaceDetection>? = { listOf(detection()) },
) : FaceDetector {
    val calls = AtomicInteger(0)

    override fun detect(image: DecodedImage): List<FaceDetection>? {
        calls.incrementAndGet()
        return perImage(image)
    }

    companion object {
        fun detection(score: Float = 0.9f): FaceDetection = FaceDetection(
            box = FaceBox(0.25f, 0.25f, 0.5f, 0.5f),
            landmarks = List(FaceDetection.LANDMARK_COUNT) { i -> FacePoint(0.3f + i * 0.05f, 0.4f + i * 0.03f) },
            score = score,
        )
    }
}

/**
 * Returns a fixed unit vector per call, so tests control clustering exactly. [vectors] is consumed in
 * call order; the last entry repeats once exhausted.
 */
class FakeFaceEmbedder(
    override val id: String = "fake-embedder",
    override val dimensions: Int = 2,
    private val vectors: List<FloatArray> = listOf(floatArrayOf(1f, 0f)),
    private val failing: Boolean = false,
) : FaceEmbedder {
    val calls = AtomicInteger(0)

    override fun embed(alignedFace: DecodedImage): FaceEmbedding? {
        val index = calls.getAndIncrement()
        if (failing) return null
        return FaceEmbedding(vectors[index.coerceAtMost(vectors.lastIndex)].copyOf())
    }
}
