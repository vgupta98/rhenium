package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.testing.ImageFixtures
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceAlignmentTest {

    private fun transformed(scale: Float, degrees: Float, dx: Float, dy: Float): List<FacePoint> {
        val t = degrees * Math.PI.toFloat() / 180f
        val a = scale * cos(t)
        val b = scale * sin(t)
        return FaceAlignment.TEMPLATE.map { p -> FacePoint(a * p.x - b * p.y + dx, b * p.x + a * p.y + dy) }
    }

    @Test
    fun solve_mapsTheSourceLandmarksOntoTheTemplate() {
        // A face at 3x scale, rotated 20 degrees and translated: the fitted transform must undo all of it.
        val source = transformed(scale = 3f, degrees = 20f, dx = 250f, dy = -40f)

        val transform = assertNotNull(FaceAlignment.solve(source))

        for (i in source.indices) {
            val mapped = transform.apply(source[i])
            assertEquals(FaceAlignment.TEMPLATE[i].x, mapped.x, 1e-2f)
            assertEquals(FaceAlignment.TEMPLATE[i].y, mapped.y, 1e-2f)
        }
    }

    @Test
    fun solve_ofTheTemplateItselfIsTheIdentity() {
        val transform = assertNotNull(FaceAlignment.solve(FaceAlignment.TEMPLATE))

        assertEquals(1f, transform.a, 1e-4f)
        assertEquals(0f, transform.b, 1e-4f)
        assertEquals(0f, transform.tx, 1e-3f)
        assertEquals(0f, transform.ty, 1e-3f)
    }

    @Test
    fun invert_roundTripsAPoint() {
        val forward = assertNotNull(FaceAlignment.solve(transformed(2f, -35f, 11f, 7f)))
        val back = assertNotNull(forward.invert())

        val p = FacePoint(123.5f, -42.25f)
        val round = back.apply(forward.apply(p))
        assertEquals(p.x, round.x, 1e-2f)
        assertEquals(p.y, round.y, 1e-2f)
    }

    @Test
    fun solve_degenerateLandmarksReturnNullRatherThanNaN() {
        val coincident = List(FaceDetection.LANDMARK_COUNT) { FacePoint(10f, 10f) }
        assertNull(FaceAlignment.solve(coincident))
        assertNull(FaceAlignment.solve(emptyList()))
    }

    @Test
    fun solve_collinearLandmarksStayFinite() {
        // A perfectly collinear (but non-degenerate) set is a poor fit, not an invalid one: the
        // least-squares solution still exists and must never come back as NaN.
        val collinear = List(FaceDetection.LANDMARK_COUNT) { i -> FacePoint(i * 10f, i * 10f) }

        val transform = assertNotNull(FaceAlignment.solve(collinear))

        assertTrue(transform.a.isFinite() && transform.b.isFinite())
        assertTrue(transform.tx.isFinite() && transform.ty.isFinite())
        val mapped = transform.apply(collinear.first())
        assertTrue(mapped.x.isFinite() && mapped.y.isFinite())
    }

    @Test
    fun alignedCrop_isAlwaysTheCanonicalSizeAndOpaque() {
        val image = ImageFixtures.ramp(200, 140)
        val detection = FaceDetection(
            box = FaceBox(0.3f, 0.3f, 0.3f, 0.3f),
            landmarks = transformed(scale = 0.4f, degrees = 5f, dx = 60f, dy = 40f),
            score = 0.9f,
        )

        val crop = FaceAlignment.alignedCrop(image, detection)

        assertEquals(FaceAlignment.TEMPLATE_EDGE, crop.width)
        assertEquals(FaceAlignment.TEMPLATE_EDGE, crop.height)
        assertEquals(FaceAlignment.TEMPLATE_EDGE * FaceAlignment.TEMPLATE_EDGE * 4, crop.bgraBytes.size)
        for (i in 3 until crop.bgraBytes.size step 4) {
            assertEquals(255.toByte(), crop.bgraBytes[i], "alpha must be opaque")
        }
    }

    @Test
    fun alignedCrop_recoversTheOriginalPixelsThroughTheWarp() {
        // A rigid, axis-aligned "face": the aligned crop must reproduce the source colours it samples,
        // which catches an inverted or transposed transform that a size check alone would not.
        val image = ImageFixtures.solid(120, 120, r = 20, g = 140, b = 250)
        val detection = FaceDetection(
            box = FaceBox(0.2f, 0.2f, 0.5f, 0.5f),
            landmarks = transformed(scale = 0.8f, degrees = 0f, dx = 20f, dy = 10f),
            score = 0.9f,
        )

        val crop = FaceAlignment.alignedCrop(image, detection)

        // Centre pixel of the crop maps inside the (uniform) source, so it must carry its colour.
        val centre = ((FaceAlignment.TEMPLATE_EDGE / 2) * FaceAlignment.TEMPLATE_EDGE + FaceAlignment.TEMPLATE_EDGE / 2) * 4
        assertTrue(abs((crop.bgraBytes[centre].toInt() and 0xFF) - 250) <= 1, "blue channel")
        assertTrue(abs((crop.bgraBytes[centre + 1].toInt() and 0xFF) - 140) <= 1, "green channel")
        assertTrue(abs((crop.bgraBytes[centre + 2].toInt() and 0xFF) - 20) <= 1, "red channel")
    }

    @Test
    fun alignedCrop_degenerateLandmarksFallBackToABoxCropInsteadOfFailing() {
        val image = ImageFixtures.checker(80, 80)
        val detection = FaceDetection(
            box = FaceBox(0.25f, 0.25f, 0.5f, 0.5f),
            landmarks = List(FaceDetection.LANDMARK_COUNT) { FacePoint(0.5f, 0.5f) },
            score = 0.9f,
        )

        val crop = FaceAlignment.alignedCrop(image, detection)

        assertEquals(FaceAlignment.TEMPLATE_EDGE, crop.width)
        assertTrue(crop.bgraBytes.any { it != 0.toByte() }, "the fallback crop must carry real pixels")
    }
}
