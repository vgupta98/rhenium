package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.domain.faces.FaceAlignment
import com.vishalgupta.photoselector.testing.ImageFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the *real* bundled YuNet and SFace blobs through ONNX Runtime — the integration test
 * proving both ship, load, run and produce sane output, and the CI guard that they are actually
 * packaged (they are classpath resources, so a packaging slip fails here).
 *
 * These deliberately do NOT assert "finds a face": that needs a real photograph of a person, and
 * checking a licence-clean one into the repo is not worth it for a headless PR. Correctness of the
 * *decode* is covered exhaustively and model-free by `YuNetPostProcessingTest`, and was additionally
 * diffed against OpenCV's own `FaceDetectorYN` on real photographs (see `tools/face-models/README.md`).
 * What these tests cover is everything in between: preprocessing, output-name plumbing, tensor
 * shapes, and the fail-soft contract.
 */
class OnnxFaceModelsTest {

    private val detector = OnnxFaceDetector.Loader.fromResource()
    private val embedder = OnnxFaceEmbedder.Loader.fromResource()

    @AfterTest
    fun tearDown() {
        detector.close()
        embedder.close()
    }

    @Test
    fun detector_runsTheWholePipelineOnASyntheticImage() {
        // A ramp has no faces, so the expectation is "a clean, empty, non-null answer" - which still
        // exercises letterboxing, all twelve output heads by name, the decode and the unletterbox.
        val detections = assertNotNull(detector.detect(ImageFixtures.ramp(320, 240)), "inference must not fail")

        for (d in detections) {
            assertTrue(d.score in 0f..1f, "score out of range: ${d.score}")
            assertTrue(d.box.width > 0f && d.box.height > 0f)
            assertEquals(5, d.landmarks.size)
            assertTrue(d.landmarks.all { it.x.isFinite() && it.y.isFinite() })
        }
    }

    @Test
    fun detector_isDeterministicAndHandlesOddSizes() {
        // Non-square and tiny inputs both go through the letterbox path; neither may throw.
        val first = assertNotNull(detector.detect(ImageFixtures.checker(97, 33)))
        val second = assertNotNull(detector.detect(ImageFixtures.checker(97, 33)))

        assertEquals(first.size, second.size)
        assertEquals(first.map { it.score }, second.map { it.score })
        assertNotNull(detector.detect(ImageFixtures.solid(1, 1)))
    }

    @Test
    fun embedder_returnsAFiniteUnitVectorOfTheProbedWidth() {
        assertTrue(embedder.dimensions > 0, "expected a positive width, got ${embedder.dimensions}")

        val crop = ImageFixtures.ramp(FaceAlignment.TEMPLATE_EDGE, FaceAlignment.TEMPLATE_EDGE)
        val vec = assertNotNull(embedder.embed(crop)).values

        assertEquals(embedder.dimensions, vec.size)
        assertTrue(vec.all { it.isFinite() }, "embedding had non-finite components")
        assertTrue(abs(norm(vec) - 1f) < 1e-3f, "expected an L2-normalized vector, norm was ${norm(vec)}")
    }

    @Test
    fun embedder_isDeterministicAndSeparatesDifferentCrops() {
        val edge = FaceAlignment.TEMPLATE_EDGE
        val a = assertNotNull(embedder.embed(ImageFixtures.ramp(edge, edge)))
        val b = assertNotNull(embedder.embed(ImageFixtures.ramp(edge, edge)))
        val other = assertNotNull(embedder.embed(ImageFixtures.checker(edge, edge)))

        assertTrue(a.cosineSimilarityTo(b) > 0.999f, "identical crops must embed identically")
        assertTrue(
            a.cosineSimilarityTo(other) < b.cosineSimilarityTo(a),
            "visually different crops must be further apart than identical ones",
        )
    }

    @Test
    fun embedder_rejectsACropOfTheWrongSizeRatherThanGuessing() {
        assertEquals(null, embedder.embed(ImageFixtures.ramp(64, 64)))
    }

    @Test
    fun bothSessionsAreSafeUnderConcurrentRuns() = runTest {
        // FaceScanner fans photos out against these single shared sessions, exactly as the Similarity
        // pass does with the embedding model. Real parallelism comes from async(Dispatchers.IO).
        val edge = FaceAlignment.TEMPLATE_EDGE
        val reference = assertNotNull(embedder.embed(ImageFixtures.ramp(edge, edge)))

        val results = (0 until 8).map {
            async(Dispatchers.IO) {
                detector.detect(ImageFixtures.ramp(160, 120))
                assertNotNull(embedder.embed(ImageFixtures.ramp(edge, edge)))
            }
        }.awaitAll()

        for (vec in results) {
            assertTrue(reference.cosineSimilarityTo(vec) > 0.999f, "concurrent embeds must match")
        }
    }

    private fun norm(v: FloatArray): Float {
        var s = 0f
        for (x in v) s += x * x
        return sqrt(s)
    }
}
