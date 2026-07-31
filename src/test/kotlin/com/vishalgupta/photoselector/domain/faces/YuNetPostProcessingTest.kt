package com.vishalgupta.photoselector.domain.faces

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-computed decode expectations, with no model in sight — this is the pure half of the riskiest
 * unit in the face pipeline, and a subtly wrong stride/grid decode produces plausible-looking but
 * offset boxes that nothing else in this PR would catch.
 *
 * A `640` canvas would need 8400 cells of fixture, so these run the decode at a `64` canvas: same
 * strides, same arithmetic, grids of 8x8 / 4x4 / 2x2.
 *
 * (The port itself was additionally diffed against OpenCV's `FaceDetectorYN` on real photographs;
 * every box, landmark and score matched to float noise. See `tools/face-models/README.md`.)
 */
class YuNetPostProcessingTest {

    private val edge = 64

    /** All-zero heads for the given canvas — the baseline every test perturbs. */
    private fun blankHeads(): List<YuNetPostProcessing.StrideOutputs> =
        YuNetPostProcessing.STRIDES.map { stride ->
            val cells = (edge / stride) * (edge / stride)
            YuNetPostProcessing.StrideOutputs(
                cls = FloatArray(cells),
                obj = FloatArray(cells),
                bbox = FloatArray(cells * 4),
                kps = FloatArray(cells * 10),
            )
        }

    /** Writes one detection into the [strideIndex] head at grid cell (row, col). */
    private fun MutableList<YuNetPostProcessing.StrideOutputs>.plant(
        strideIndex: Int,
        row: Int,
        col: Int,
        cls: Float,
        obj: Float,
        bbox: FloatArray,
        kps: FloatArray = FloatArray(10),
    ) {
        val head = this[strideIndex]
        val cols = edge / YuNetPostProcessing.STRIDES[strideIndex]
        val idx = row * cols + col
        head.cls[idx] = cls
        head.obj[idx] = obj
        bbox.copyInto(head.bbox, idx * 4)
        kps.copyInto(head.kps, idx * 10)
    }

    @Test
    fun decode_emptyOutput_yieldsNoDetections() {
        assertEquals(emptyList(), YuNetPostProcessing.decode(blankHeads(), inputEdge = edge))
    }

    @Test
    fun decode_stride8Cell_placesBoxAndLandmarksExactly() {
        val heads = blankHeads().toMutableList()
        // Cell (r=2, c=3) on stride 8. cls=1, obj=0.25 -> score = sqrt(1 * 0.25) = 0.5.
        // cx = (3 + 0.5) * 8 = 28 ; cy = (2 + 0.5) * 8 = 20
        // w  = exp(ln 4) * 8 = 32 ; h  = exp(ln 2) * 8 = 16
        // top-left = (28 - 16, 20 - 8) = (12, 12)
        // landmark n: x = (n + 3) * 8 ; y = (n/2 + 2) * 8
        heads.plant(
            strideIndex = 0, row = 2, col = 3, cls = 1f, obj = 0.25f,
            bbox = floatArrayOf(0.5f, 0.5f, ln(4f), ln(2f)),
            kps = FloatArray(10) { i -> if (i % 2 == 0) (i / 2).toFloat() else (i / 2) * 0.5f },
        )

        val detections = YuNetPostProcessing.decode(heads, inputEdge = edge, scoreThreshold = 0.4f)

        assertEquals(1, detections.size)
        val d = detections.single()
        assertEquals(0.5f, d.score, 1e-5f)
        assertEquals(12f / 64, d.box.x, 1e-5f)
        assertEquals(12f / 64, d.box.y, 1e-5f)
        assertEquals(32f / 64, d.box.width, 1e-5f)
        assertEquals(16f / 64, d.box.height, 1e-5f)
        for (n in 0 until FaceDetection.LANDMARK_COUNT) {
            assertEquals((n + 3) * 8f / 64, d.landmarks[n].x, 1e-5f)
            assertEquals((n * 0.5f + 2) * 8f / 64, d.landmarks[n].y, 1e-5f)
        }
    }

    @Test
    fun decode_scalesWithTheStrideOfTheHeadItCameFrom() {
        // The same cell indices on the stride-32 head must land 4x further out than on stride 8.
        val heads = blankHeads().toMutableList()
        heads.plant(
            strideIndex = 2, row = 1, col = 1, cls = 1f, obj = 1f,
            bbox = floatArrayOf(0f, 0f, 0f, 0f), // cx = 32, cy = 32, w = h = 32
        )

        val d = YuNetPostProcessing.decode(heads, inputEdge = edge).single()

        assertEquals(16f / 64, d.box.x, 1e-5f)
        assertEquals(16f / 64, d.box.y, 1e-5f)
        assertEquals(32f / 64, d.box.width, 1e-5f)
        // All-zero kps offsets still land on the cell origin, scaled by the stride.
        assertEquals(32f / 64, d.landmarks[0].x, 1e-5f)
    }

    @Test
    fun decode_scoreIsTheGeometricMeanOfClsAndObj_andClamps() {
        val heads = blankHeads().toMutableList()
        // Out-of-range values must clamp to 0..1 before the sqrt, not produce NaN.
        heads.plant(0, 0, 0, cls = 4f, obj = -1f, bbox = floatArrayOf(0f, 0f, 0f, 0f))
        heads.plant(0, 0, 1, cls = 0.81f, obj = 1f, bbox = floatArrayOf(0f, 0f, 0f, 0f))

        val detections = YuNetPostProcessing.decode(heads, inputEdge = edge, scoreThreshold = 0.1f)

        // cell (0,0): clamped obj = 0 -> score 0, below threshold. cell (0,1): sqrt(0.81) = 0.9.
        assertEquals(1, detections.size)
        assertEquals(0.9f, detections.single().score, 1e-5f)
    }

    @Test
    fun decode_belowThresholdCellsAreDropped() {
        val heads = blankHeads().toMutableList()
        heads.plant(0, 4, 4, cls = 0.5f, obj = 0.5f, bbox = floatArrayOf(0f, 0f, 0f, 0f)) // score 0.5

        assertTrue(YuNetPostProcessing.decode(heads, inputEdge = edge, scoreThreshold = 0.6f).isEmpty())
        assertEquals(1, YuNetPostProcessing.decode(heads, inputEdge = edge, scoreThreshold = 0.4f).size)
    }

    @Test
    fun decode_headsThatDoNotMatchTheGridAreSkippedNotReadOutOfBounds() {
        val truncated = YuNetPostProcessing.STRIDES.map {
            YuNetPostProcessing.StrideOutputs(FloatArray(1), FloatArray(1), FloatArray(4), FloatArray(10))
        }
        assertEquals(emptyList(), YuNetPostProcessing.decode(truncated, inputEdge = edge))
        // A wrong number of heads entirely is also a clean empty, never an index crash.
        assertEquals(emptyList(), YuNetPostProcessing.decode(emptyList(), inputEdge = edge))
    }

    // --- NMS ---

    private fun det(x: Float, y: Float, w: Float, h: Float, score: Float) = FaceDetection(
        box = FaceBox(x, y, w, h),
        landmarks = List(FaceDetection.LANDMARK_COUNT) { FacePoint(x, y) },
        score = score,
    )

    @Test
    fun nms_dropsAnOverlappingWeakerBox() {
        val strong = det(0f, 0f, 1f, 1f, score = 0.9f)
        val nearlyIdentical = det(0.05f, 0.05f, 1f, 1f, score = 0.7f)

        val kept = YuNetPostProcessing.nonMaximumSuppression(listOf(nearlyIdentical, strong), iouThreshold = 0.3f)

        assertEquals(listOf(0.9f), kept.map { it.score })
    }

    @Test
    fun nms_keepsDisjointBoxes() {
        val a = det(0f, 0f, 1f, 1f, score = 0.9f)
        val b = det(10f, 10f, 1f, 1f, score = 0.7f)

        val kept = YuNetPostProcessing.nonMaximumSuppression(listOf(a, b), iouThreshold = 0.3f)

        // Both survive, strongest first.
        assertEquals(listOf(0.9f, 0.7f), kept.map { it.score })
    }

    @Test
    fun nms_keepsAnOverlapUnderTheThreshold() {
        // Two 1x1 boxes offset by 0.5 in x: intersection 0.5, union 1.5 -> IoU 0.333.
        val a = det(0f, 0f, 1f, 1f, score = 0.9f)
        val b = det(0.5f, 0f, 1f, 1f, score = 0.8f)

        assertEquals(2, YuNetPostProcessing.nonMaximumSuppression(listOf(a, b), iouThreshold = 0.4f).size)
        assertEquals(1, YuNetPostProcessing.nonMaximumSuppression(listOf(a, b), iouThreshold = 0.2f).size)
    }

    @Test
    fun nms_capsAtTopK() {
        val many = (0 until 10).map { det(it * 10f, 0f, 1f, 1f, score = 0.5f + it / 100f) }
        assertEquals(3, YuNetPostProcessing.nonMaximumSuppression(many, iouThreshold = 0.3f, topK = 3).size)
    }

    // --- letterbox mapping ---

    @Test
    fun unletterbox_undoesThePaddingForANonSquareImage() {
        // A 2:1 image fills the full canvas width but only half its height.
        val canvasDetection = det(0.25f, 0.10f, 0.5f, 0.20f, score = 0.9f)

        val mapped = YuNetPostProcessing.unletterbox(listOf(canvasDetection), usedWidth = 1f, usedHeight = 0.5f)

        val d = mapped.single()
        assertEquals(0.25f, d.box.x, 1e-5f)   // x unchanged - the width was fully used
        assertEquals(0.20f, d.box.y, 1e-5f)   // y doubled - only half the height was used
        assertEquals(0.5f, d.box.width, 1e-5f)
        assertEquals(0.40f, d.box.height, 1e-5f)
        assertEquals(0.20f, d.landmarks[0].y, 1e-5f)
    }

    @Test
    fun unletterbox_dropsBoxesThatLieEntirelyInThePadding() {
        val inPadding = det(0.8f, 0.8f, 0.1f, 0.1f, score = 0.9f)
        assertTrue(YuNetPostProcessing.unletterbox(listOf(inPadding), usedWidth = 0.5f, usedHeight = 0.5f).isEmpty())
        // A degenerate letterbox is likewise empty rather than a divide-by-zero.
        assertTrue(YuNetPostProcessing.unletterbox(listOf(inPadding), usedWidth = 0f, usedHeight = 1f).isEmpty())
    }
}
