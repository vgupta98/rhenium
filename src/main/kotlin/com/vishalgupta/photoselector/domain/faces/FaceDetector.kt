package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.DecodedImage

/**
 * Finds faces in a decoded image. The seam between "which detector" and the rest of the pipeline —
 * mirrors [com.vishalgupta.photoselector.data.ai.EmbeddingModel]'s shape so the shipped ONNX
 * implementation can be swapped (or stubbed in tests) without touching the scanner.
 *
 * [id] identifies the *producing* detector and is folded into the face cache key, so swapping the
 * model invalidates cached detections automatically. Bump it whenever the model blob or the decode
 * changes (identical rule to `OnnxEmbeddingModel.DEFAULT_ID`).
 *
 * [detect] returns `null` on inference failure rather than throwing — one bad frame must never take
 * down a whole-root scan — and an empty list for "ran fine, no faces". The distinction matters:
 * a failure is not cached, an empty result is.
 */
interface FaceDetector {
    val id: String

    fun detect(image: DecodedImage): List<FaceDetection>?
}

/**
 * Turns an *aligned* face crop into a comparable [FaceEmbedding]. Same contract as [FaceDetector]:
 * [id] is folded into the cache key, and inference failure is `null`, never a throw.
 *
 * The input must be the canonical [FaceAlignment.TEMPLATE_EDGE]-square crop produced by
 * [FaceAlignment.alignedCrop] — recognition models are trained on that exact framing and degrade
 * badly on a raw bounding-box crop. [dimensions] is probed from the graph at load, never hard-coded.
 */
interface FaceEmbedder {
    val id: String
    val dimensions: Int

    fun embed(alignedFace: DecodedImage): FaceEmbedding?
}
