package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.DecodedImage

/**
 * Warps a detected face onto SFace's canonical 112x112 template using its five landmarks — the step
 * that makes two photos of the same person comparable at all. A raw bounding-box crop is not enough:
 * recognition models are trained on this exact framing (eyes on a fixed line, fixed inter-ocular
 * distance), and feeding them an unaligned crop collapses the embedding's discriminative power.
 *
 * ## The transform
 * A **similarity** transform (uniform scale + rotation + translation, no shear, no reflection) fitted
 * to the five landmark pairs by least squares. OpenCV's `FaceRecognizerSFImpl::getSimilarityTransformMatrix`
 * does this via the full Umeyama/SVD routine including a reflection branch; the closed form below is
 * the same least-squares solution for the non-reflected case, which is the only meaningful one for
 * face landmarks (a reflected fit would mean the detector returned a mirrored face). Dropping the SVD
 * keeps this pure Kotlin with no matrix library.
 *
 * Given demeaned source points `p` and template points `q`:
 * ```
 *   a = sum(p . q) / sum(|p|^2)      (scale * cos t)
 *   b = sum(p x q) / sum(|p|^2)      (scale * sin t)
 *   M = [[a, -b], [b, a]] ,  t = qMean - M * pMean
 * ```
 *
 * ## Degenerate input
 * Collinear or coincident landmarks make `sum(|p|^2)` vanish. [solve] returns `null` there rather
 * than dividing by zero, and [alignedCrop] falls back to a plain box crop — never NaN, never a throw.
 */
object FaceAlignment {

    /** Edge of the canonical aligned crop the recognition model expects. */
    const val TEMPLATE_EDGE = 112

    /**
     * The canonical five-point template, in pixels on a [TEMPLATE_EDGE]-square canvas, in
     * [FaceDetection.landmarks] order (right eye, left eye, nose tip, right mouth, left mouth).
     * Verbatim from OpenCV's SFace `src_point` / the ArcFace reference template.
     */
    val TEMPLATE: List<FacePoint> = listOf(
        FacePoint(38.2946f, 51.6963f),
        FacePoint(73.5318f, 51.5014f),
        FacePoint(56.0252f, 71.7366f),
        FacePoint(41.5493f, 92.3655f),
        FacePoint(70.7299f, 92.2041f),
    )

    /**
     * A 2D similarity transform `p -> (a*x - b*y + tx, b*x + a*y + ty)`.
     */
    data class SimilarityTransform(val a: Float, val b: Float, val tx: Float, val ty: Float) {
        fun apply(x: Float, y: Float): FacePoint = FacePoint(a * x - b * y + tx, b * x + a * y + ty)

        fun apply(point: FacePoint): FacePoint = apply(point.x, point.y)

        /** The inverse transform, or null when this one is singular (zero scale). */
        fun invert(): SimilarityTransform? {
            val det = a * a + b * b
            if (det <= EPSILON) return null
            val ia = a / det
            val ib = -b / det
            return SimilarityTransform(
                a = ia,
                b = ib,
                tx = -(ia * tx - ib * ty),
                ty = -(ib * tx + ia * ty),
            )
        }
    }

    /**
     * Least-squares similarity transform mapping [source] (pixel coordinates in the photo) onto
     * [target] (defaults to [TEMPLATE]). Null when the fit is degenerate — coincident or otherwise
     * zero-spread source points.
     */
    fun solve(source: List<FacePoint>, target: List<FacePoint> = TEMPLATE): SimilarityTransform? {
        if (source.size != target.size || source.isEmpty()) return null
        val n = source.size
        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f
        for (i in 0 until n) {
            srcMeanX += source[i].x
            srcMeanY += source[i].y
            dstMeanX += target[i].x
            dstMeanY += target[i].y
        }
        srcMeanX /= n
        srcMeanY /= n
        dstMeanX /= n
        dstMeanY /= n

        var dot = 0f   // sum(p . q)
        var cross = 0f // sum(p x q)
        var norm = 0f  // sum(|p|^2)
        for (i in 0 until n) {
            val px = source[i].x - srcMeanX
            val py = source[i].y - srcMeanY
            val qx = target[i].x - dstMeanX
            val qy = target[i].y - dstMeanY
            dot += px * qx + py * qy
            cross += px * qy - py * qx
            norm += px * px + py * py
        }
        if (norm <= EPSILON) return null

        val a = dot / norm
        val b = cross / norm
        if (!a.isFinite() || !b.isFinite() || (a * a + b * b) <= EPSILON) return null
        return SimilarityTransform(
            a = a,
            b = b,
            tx = dstMeanX - (a * srcMeanX - b * srcMeanY),
            ty = dstMeanY - (b * srcMeanX + a * srcMeanY),
        )
    }

    /**
     * The aligned [TEMPLATE_EDGE]-square BGRA crop of [detection] taken from [image], ready for a
     * [FaceEmbedder]. Sampled bilinearly through the *inverse* transform (destination-driven, so
     * every output pixel is written exactly once) with edge clamping.
     *
     * Falls back to a square box crop around the detection when the landmark fit is degenerate, so a
     * pathological detection still yields a usable — if less discriminative — embedding instead of
     * nothing.
     */
    fun alignedCrop(image: DecodedImage, detection: FaceDetection): DecodedImage {
        val w = image.width
        val h = image.height
        val pixels = detection.landmarks.map { FacePoint(it.x * w, it.y * h) }
        val inverse = solve(pixels)?.invert() ?: return boxCrop(image, detection)
        return sample(image) { dx, dy -> inverse.apply(dx, dy) }
    }

    /** Square, aspect-preserving crop centred on the detection box — the degenerate-landmark fallback. */
    private fun boxCrop(image: DecodedImage, detection: FaceDetection): DecodedImage {
        val w = image.width
        val h = image.height
        val edge = maxOf(detection.box.width * w, detection.box.height * h, 1f)
        val originX = detection.box.centerX * w - edge / 2f
        val originY = detection.box.centerY * h - edge / 2f
        val step = edge / TEMPLATE_EDGE
        return sample(image) { dx, dy -> FacePoint(originX + dx * step, originY + dy * step) }
    }

    /** Renders a [TEMPLATE_EDGE]-square BGRA image by bilinear-sampling [image] at [sourceOf] per output pixel. */
    private inline fun sample(image: DecodedImage, sourceOf: (Float, Float) -> FacePoint): DecodedImage {
        val w = image.width
        val h = image.height
        val src = image.bgraBytes
        val out = ByteArray(TEMPLATE_EDGE * TEMPLATE_EDGE * 4)
        var o = 0
        for (dy in 0 until TEMPLATE_EDGE) {
            for (dx in 0 until TEMPLATE_EDGE) {
                // Pixel centres, matching the OpenCV/PIL sampling convention used elsewhere in the app.
                val p = sourceOf(dx + 0.5f, dy + 0.5f)
                val sx = (p.x - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val sy = (p.y - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = (x0 + 1).coerceAtMost(w - 1)
                val y1 = (y0 + 1).coerceAtMost(h - 1)
                val fx = sx - x0
                val fy = sy - y0
                val i00 = (y0 * w + x0) * 4
                val i01 = (y0 * w + x1) * 4
                val i10 = (y1 * w + x0) * 4
                val i11 = (y1 * w + x1) * 4
                for (ch in 0 until 3) {
                    val top = lerp(u8(src[i00 + ch]), u8(src[i01 + ch]), fx)
                    val bottom = lerp(u8(src[i10 + ch]), u8(src[i11 + ch]), fx)
                    out[o + ch] = lerp(top, bottom, fy).toInt().coerceIn(0, 255).toByte()
                }
                out[o + 3] = 255.toByte()
                o += 4
            }
        }
        return DecodedImage(width = TEMPLATE_EDGE, height = TEMPLATE_EDGE, bgraBytes = out)
    }

    private const val EPSILON = 1e-9f

    private fun u8(b: Byte): Float = (b.toInt() and 0xFF).toFloat()
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
