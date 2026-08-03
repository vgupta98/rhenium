package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.domain.faces.FaceClusterer
import com.vishalgupta.photoselector.domain.faces.FaceId
import com.vishalgupta.photoselector.domain.faces.FaceRef
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.testing.FakeFaceDetector
import com.vishalgupta.photoselector.testing.FakeFaceEmbedder
import com.vishalgupta.photoselector.testing.ImageFixtures
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceScannerTest {

    private fun tempCache(): FaceCache {
        val dir = Files.createTempDirectory("face-scanner-test").also { it.toFile().deleteOnExit() }
        return FaceCache(cacheDir = dir, detectorId = "det", embedderId = "emb")
    }

    private fun photo(name: String) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/photos/$name.jpg"),
        relativePath = "$name.jpg",
        fileName = "$name.jpg",
        sizeBytes = 1000,
        lastModifiedEpochMs = 1000,
    )

    private fun scanner(
        detector: FakeFaceDetector = FakeFaceDetector(),
        embedder: FakeFaceEmbedder = FakeFaceEmbedder(),
        cache: FaceCache = tempCache(),
    ) = FaceScanner(
        detector = detector,
        embedder = embedder,
        cache = cache,
        decode = { ImageFixtures.ramp(64, 64) },
        rule = FaceClusterer.fixed(similarity = 0.7f),
        newPersonId = { PersonId("p-${counter++}") },
    )

    private var counter = 0

    @Test
    fun scan_clustersEveryPhotosFacesIntoPeople() = runTest {
        // Two photos of the same person, one of someone else (orthogonal vector).
        val embedder = FakeFaceEmbedder(
            vectors = listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
        )
        val photos = listOf(photo("a"), photo("b"), photo("c"))

        val people = scanner(embedder = embedder).scan(photos)

        assertEquals(2, people.size)
        val together = people.first { it.faces.size == 2 }
        assertEquals(setOf(PhotoId("a"), PhotoId("b")), together.photos)
    }

    @Test
    fun scan_reportsProgressOncePerPhoto() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()

        scanner().scan(List(4) { photo("p$it") }) { processed, total -> synchronized(seen) { seen += processed to total } }

        assertEquals(4, seen.size)
        assertTrue(seen.all { it.second == 4 })
        assertEquals(setOf(1, 2, 3, 4), seen.map { it.first }.toSet())
    }

    @Test
    fun scan_secondPassReadsTheCacheInsteadOfRerunningInference() = runTest {
        val cache = tempCache()
        val detector = FakeFaceDetector()
        val photos = listOf(photo("a"), photo("b"))

        scanner(detector = detector, cache = cache).scan(photos)
        assertEquals(2, detector.calls.get())

        val second = FakeFaceDetector()
        scanner(detector = second, cache = cache).scan(photos)

        assertEquals(0, second.calls.get(), "a warm cache must skip detection entirely")
    }

    @Test
    fun scan_aDetectionFailureIsNotCached() = runTest {
        val cache = tempCache()
        val failing = FakeFaceDetector(perImage = { null })

        val people = scanner(detector = failing, cache = cache).scan(listOf(photo("a")))
        assertTrue(people.isEmpty())

        // Nothing was written, so a later healthy pass re-detects rather than treating it as empty.
        val healthy = FakeFaceDetector()
        scanner(detector = healthy, cache = cache).scan(listOf(photo("a")))
        assertEquals(1, healthy.calls.get())
    }

    @Test
    fun scan_aPhotoWithNoFacesIsCached() = runTest {
        val cache = tempCache()
        val none = FakeFaceDetector(perImage = { emptyList() })

        scanner(detector = none, cache = cache).scan(listOf(photo("a")))
        val second = FakeFaceDetector(perImage = { emptyList() })
        scanner(detector = second, cache = cache).scan(listOf(photo("a")))

        assertEquals(0, second.calls.get(), "'no faces here' is a real, reusable answer")
    }

    @Test
    fun scan_dropsAFaceWhoseEmbeddingFailed() = runTest {
        val people = scanner(embedder = FakeFaceEmbedder(failing = true)).scan(listOf(photo("a")))

        assertTrue(people.isEmpty(), "an un-embeddable face can't be clustered, so it isn't a person")
    }

    @Test
    fun scan_preservesANamedPersonAcrossAScan() = runTest {
        val alice = Person(
            id = PersonId("alice"),
            name = "Alice",
            faces = listOf(FaceRef(FaceId(PhotoId("older"), 0))),
            centroid = listOf(1f, 0f),
        )

        val people = scanner().scan(listOf(photo("a")), known = listOf(alice))

        val kept = assertNotNull(people.firstOrNull { it.id == PersonId("alice") })
        assertEquals("Alice", kept.name)
        assertEquals(setOf(PhotoId("a")), kept.photos)
    }

    @Test
    fun scan_ofNoPhotosKeepsOnlyNamedPeople() = runTest {
        val named = Person(PersonId("n"), name = "Named", faces = listOf(FaceRef(FaceId(PhotoId("x"), 0))), centroid = listOf(1f, 0f))
        val unnamed = Person(PersonId("u"), name = null, faces = listOf(FaceRef(FaceId(PhotoId("y"), 0))), centroid = listOf(0f, 1f))

        val people = scanner().scan(emptyList(), known = listOf(named, unnamed))

        assertEquals(listOf(PersonId("n")), people.map { it.id })
    }
}
