package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.DecodedImage
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pure decode of YuNet's raw graph outputs into [FaceDetection]s: per-stride grid decode, score
 * fusion, and non-maximum suppression. Kept in the domain, free of ONNX Runtime, so the riskiest
 * piece of the face pipeline is unit-testable against hand-computed tensors with no model present.
 *
 * ## Ground truth
 * Ported line-by-line from OpenCV's `FaceDetectorYNImpl::postProcess`
 * (`modules/objdetect/src/face_detect.cpp`, 4.10.0), which is the reference implementation for the
 * bundled `face_detection_yunet_2023mar` graph. Note that opencv_zoo's `models/face_detection_yunet/
 * yunet.py` no longer contains a decode — it delegates to `cv.FaceDetectorYN`, and the 2021 revision
 * of that file decodes the *older*, anchor-based YuNet (priors, variances). The 2023mar model is
 * anchor-free, so the prior-box maths in that old script does not apply here; the C++ postProcess is
 * the correct reference and is what this file mirrors.
 *
 * ## The graph
 * Fixed `[1, 3, 640, 640]` BGR input (see `OnnxFaceDetector`), 12 outputs — `cls_S`, `obj_S`,
 * `bbox_S`, `kps_S` for strides 8/16/32. Each head is a flat row-major grid of `(edge/S)^2` cells:
 *  - `cls`/`obj`: 1 value per cell; the detection score is `sqrt(clamp01(cls) * clamp01(obj))`.
 *  - `bbox`: 4 values — centre offsets *in cells* from the cell's top-left, and log-space
 *    width/height *in strides*: `cx = (c + b0) * S`, `w = exp(b2) * S`.
 *  - `kps`: 10 values — five `(x, y)` offsets in cells: `x = (k + c) * S`.
 *
 * ## Coordinates
 * [decode] returns coordinates **normalised to the network canvas** (`0..1` of the 640-square input),
 * not pixels and not yet source-image coordinates. [unletterbox] then maps a canvas-normalised
 * detection back onto the source image. Splitting it this way keeps both halves pure and lets the
 * letterbox mapping be tested independently of the decode.
 */
object YuNetPostProcessing {

    /** The bundled graph's fixed square input edge. */
    const val INPUT_EDGE = 640

    /** Feature-map strides, in graph output order. */
    val STRIDES: List<Int> = listOf(8, 16, 32)

    /** OpenCV's default score threshold for this model. */
    const val DEFAULT_SCORE_THRESHOLD = 0.6f

    /** OpenCV's default NMS IoU threshold for this model. */
    const val DEFAULT_NMS_THRESHOLD = 0.3f

    /** Upper bound on detections kept from one image, matching OpenCV's `top_k` intent. */
    const val DEFAULT_TOP_K = 500

    /**
     * The four raw head tensors for one stride, each flattened row-major over `rows x cols` cells.
     * Sizes are validated by [decode] rather than here so a malformed graph output degrades to
     * "no detections" instead of throwing inside an inference loop.
     */
    class StrideOutputs(
        val cls: FloatArray,
        val obj: FloatArray,
        val bbox: FloatArray,
        val kps: FloatArray,
    )

    /**
     * Decodes [outputs] (one entry per [STRIDES] entry, in that order) into detections whose
     * coordinates are normalised to the `inputEdge`-square network canvas, NMS-suppressed and capped
     * at [topK], strongest first. Returns an empty list for empty/short/malformed outputs.
     */
    fun decode(
        outputs: List<StrideOutputs>,
        inputEdge: Int = INPUT_EDGE,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        nmsThreshold: Float = DEFAULT_NMS_THRESHOLD,
        topK: Int = DEFAULT_TOP_K,
    ): List<FaceDetection> {
        if (outputs.size != STRIDES.size || inputEdge <= 0) return emptyList()
        val canvas = inputEdge.toFloat()
        val candidates = ArrayList<FaceDetection>()

        for ((s, stride) in STRIDES.withIndex()) {
            val head = outputs[s]
            val cols = inputEdge / stride
            val rows = inputEdge / stride
            val cells = rows * cols
            // A head that doesn't match the grid means the graph isn't the one we decode for; skip it
            // rather than reading out of bounds.
            if (head.cls.size < cells || head.obj.size < cells ||
                head.bbox.size < cells * 4 || head.kps.size < cells * 10
            ) {
                continue
            }

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    val clsScore = head.cls[idx].coerceIn(0f, 1f)
                    val objScore = head.obj[idx].coerceIn(0f, 1f)
                    val score = sqrt(clsScore * objScore)
                    if (score < scoreThreshold) continue

                    val cx = (c + head.bbox[idx * 4 + 0]) * stride
                    val cy = (r + head.bbox[idx * 4 + 1]) * stride
                    val w = exp(head.bbox[idx * 4 + 2]) * stride
                    val h = exp(head.bbox[idx * 4 + 3]) * stride
                    if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite()) continue

                    val landmarks = ArrayList<FacePoint>(FaceDetection.LANDMARK_COUNT)
                    for (n in 0 until FaceDetection.LANDMARK_COUNT) {
                        landmarks += FacePoint(
                            x = (head.kps[idx * 10 + 2 * n] + c) * stride / canvas,
                            y = (head.kps[idx * 10 + 2 * n + 1] + r) * stride / canvas,
                        )
                    }
                    candidates += FaceDetection(
                        box = FaceBox(
                            x = (cx - w / 2f) / canvas,
                            y = (cy - h / 2f) / canvas,
                            width = w / canvas,
                            height = h / canvas,
                        ),
                        landmarks = landmarks,
                        score = score,
                    )
                }
            }
        }
        return nonMaximumSuppression(candidates, nmsThreshold, topK)
    }

    /**
     * Greedy IoU non-maximum suppression, strongest box first: a box is kept unless it overlaps an
     * already-kept box by more than [iouThreshold]. Capped at [topK].
     */
    fun nonMaximumSuppression(
        detections: List<FaceDetection>,
        iouThreshold: Float = DEFAULT_NMS_THRESHOLD,
        topK: Int = DEFAULT_TOP_K,
    ): List<FaceDetection> {
        if (detections.size <= 1) return detections
        val ordered = detections.sortedByDescending { it.score }
        val kept = ArrayList<FaceDetection>(min(ordered.size, topK))
        for (candidate in ordered) {
            if (kept.size >= topK) break
            if (kept.none { intersectionOverUnion(it.box, candidate.box) > iouThreshold }) {
                kept += candidate
            }
        }
        return kept
    }

    /** Intersection-over-union of two boxes; `0` when either is degenerate. */
    fun intersectionOverUnion(a: FaceBox, b: FaceBox): Float {
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        if (areaA <= 0f || areaB <= 0f) return 0f
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.width, b.x + b.width)
        val y2 = min(a.y + a.height, b.y + b.height)
        val overlap = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = areaA + areaB - overlap
        return if (union <= 0f) 0f else overlap / union
    }

    private fun u8(b: Byte): Float = (b.toInt() and 0xFF).toFloat()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** A source image resampled into the network's square canvas, plus what [unletterbox] needs to undo it. */
    class Letterboxed(
        /** CHW float buffer in **BGR** channel order, values 0..255. */
        val planarBgr: FloatArray,
        /** Fraction of the canvas width the image occupies; the rest is padding. */
        val usedWidth: Float,
        /** Fraction of the canvas height the image occupies; the rest is padding. */
        val usedHeight: Float,
    )

    /**
     * Bilinear-resizes [image] (BGRA) into the **top-left** of an [inputEdge]-square canvas, aspect
     * ratio preserved, the remainder left black — matching OpenCV's `copyMakeBorder(0, bottom, 0,
     * right)`, so the two share an origin and [unletterbox] is a pure divide. Squashing to the square
     * instead would distort every face, and the landmarks feed a similarity transform that assumes
     * true proportions.
     *
     * Lives here, next to its inverse, on purpose: the occupied-fraction arithmetic is the *one*
     * thing both halves must agree on, and splitting it across the ONNX wrapper and this object is
     * how a silently-offset box gets shipped. Keeping them together also makes the round trip
     * testable with no model present.
     */
    fun letterbox(image: DecodedImage, inputEdge: Int = INPUT_EDGE): Letterboxed {
        val w = image.width
        val h = image.height
        val out = FloatArray(3 * inputEdge * inputEdge)
        if (w <= 0 || h <= 0) return Letterboxed(out, 0f, 0f)

        val scale = minOf(inputEdge.toFloat() / w, inputEdge.toFloat() / h)
        // The filled region is whole pixels, and unletterbox divides by THIS, not by the exact
        // scale*w - otherwise the two disagree by up to a pixel at the canvas edge.
        val usedW = Math.round(w * scale).coerceIn(1, inputEdge)
        val usedH = Math.round(h * scale).coerceIn(1, inputEdge)
        val plane = inputEdge * inputEdge
        val src = image.bgraBytes

        for (oy in 0 until usedH) {
            // Pixel-centre mapping, clamped - the convention used across the app's resamplers.
            val sy = ((oy + 0.5f) / scale - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(h - 1)
            val wy = sy - y0
            for (ox in 0 until usedW) {
                val sx = ((ox + 0.5f) / scale - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(w - 1)
                val wx = sx - x0

                val i00 = (y0 * w + x0) * 4
                val i01 = (y0 * w + x1) * 4
                val i10 = (y1 * w + x0) * 4
                val i11 = (y1 * w + x1) * 4
                val o = oy * inputEdge + ox
                // Source is BGRA and the network wants BGR, so the channel index carries straight over.
                for (c in 0 until 3) {
                    val top = lerp(u8(src[i00 + c]), u8(src[i01 + c]), wx)
                    val bottom = lerp(u8(src[i10 + c]), u8(src[i11 + c]), wx)
                    out[c * plane + o] = lerp(top, bottom, wy)
                }
            }
        }
        return Letterboxed(out, usedW.toFloat() / inputEdge, usedH.toFloat() / inputEdge)
    }

    /**
     * Maps canvas-normalised detections back onto the source image — the inverse of [letterbox].
     * Since the image sits at the canvas origin, [usedWidth]/[usedHeight] (the fraction of the canvas
     * it occupies) are the only correction needed: divide through.
     *
     * Detections whose box lands entirely in the padding (a spurious hit on black) are dropped.
     */
    fun unletterbox(
        detections: List<FaceDetection>,
        usedWidth: Float,
        usedHeight: Float,
    ): List<FaceDetection> {
        if (usedWidth <= 0f || usedHeight <= 0f) return emptyList()
        return detections.mapNotNull { det ->
            val x = det.box.x / usedWidth
            val y = det.box.y / usedHeight
            val w = det.box.width / usedWidth
            val h = det.box.height / usedHeight
            if (x >= 1f || y >= 1f || x + w <= 0f || y + h <= 0f) return@mapNotNull null
            FaceDetection(
                box = FaceBox(x = x, y = y, width = w, height = h),
                landmarks = det.landmarks.map { FacePoint(it.x / usedWidth, it.y / usedHeight) },
                score = det.score,
            )
        }
    }
}
