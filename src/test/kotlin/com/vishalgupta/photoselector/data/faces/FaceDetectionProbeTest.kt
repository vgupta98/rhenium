package com.vishalgupta.photoselector.data.faces

import com.sun.jna.Platform
import com.vishalgupta.photoselector.data.format.DefaultPhotoFormatRegistry
import com.vishalgupta.photoselector.data.format.HeicDecoder
import com.vishalgupta.photoselector.data.format.JpegDecoder
import com.vishalgupta.photoselector.data.format.PngDecoder
import com.vishalgupta.photoselector.data.format.RawDecoder
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.faces.YuNetPostProcessing
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The one thing the rest of the face suite deliberately cannot assert: that YuNet actually returns a
 * **true positive** on a real photograph of a person, and that the whole scan turns it into a
 * [com.vishalgupta.photoselector.domain.faces.Person].
 *
 * Everything else is model-free or synthetic — `YuNetPostProcessingTest` pins the decode exhaustively,
 * `OnnxFaceModelsTest` proves the blobs load and run, `FaceClustererTest` pins the clustering — but a
 * synthetic ramp has no faces in it, so "0 people found" is indistinguishable from a broken pipeline.
 * That ambiguity is exactly what the app's explicit "face scanning unavailable" state exists to
 * disambiguate for a *user*; this is the same disambiguation for CI and for a local change to the
 * detector, alignment or threshold.
 *
 * **Self-skipping, and asset-free.** Checking a licence-clean photograph of an identifiable person
 * into the repo is a licence and privacy problem not worth having, so this follows the same
 * user-supplied-fixture pattern as `RawDecodeScreenshotTest`: drop a few photos of people into
 * `build/face-probe/` (or point `FACE_PROBE_DIR` at a folder) and run `./gradlew test`. With no
 * fixtures it skips, so it is free in CI and for everyone who hasn't opted in.
 *
 * PII: the fixtures are a local, gitignored `build/` folder and nothing here logs a crop, an
 * embedding or a name — only counts.
 */
class FaceDetectionProbeTest {

    @get:Rule val cacheDir = TemporaryFolder()

    @Test
    fun `a real photograph of a person yields at least one face and one person`() = runTest {
        val fixtures = locateFixtures()
        assumeTrue(
            "drop photos of people in build/face-probe/ (or set FACE_PROBE_DIR) to exercise this",
            fixtures.isNotEmpty(),
        )

        val registry = DefaultPhotoFormatRegistry(
            decoders = buildList {
                add(JpegDecoder())
                add(PngDecoder())
                if (HeicDecoder.isSupportedOnThisPlatform()) add(HeicDecoder())
                if (RawDecoder.isSupportedOnThisPlatform()) add(RawDecoder())
            },
        )
        val detector = OnnxFaceDetector.Loader.fromResource()
        val embedder = OnnxFaceEmbedder.Loader.fromResource()
        try {
            val scanner = FaceScanner(
                detector = detector,
                embedder = embedder,
                // Its own temp dir, so a probe run can never poison (or be answered by) the real cache.
                cache = FaceCache(
                    cacheDir = cacheDir.root.toPath(),
                    detectorId = detector.id,
                    embedderId = embedder.id,
                ),
                decode = { photo ->
                    registry.decoderFor(photo.absolutePath)
                        ?.decode(photo.absolutePath, YuNetPostProcessing.INPUT_EDGE)
                },
                newPersonId = { PersonId(UUID.randomUUID().toString()) },
            )

            val photos = fixtures.map { path ->
                Photo(
                    id = PhotoId(path.fileName.toString()),
                    absolutePath = path,
                    relativePath = path.fileName.toString(),
                    fileName = path.fileName.toString(),
                    sizeBytes = Files.size(path),
                    lastModifiedEpochMs = 0,
                )
            }

            val people = scanner.scan(photos)
            val faces = people.sumOf { it.faces.size }
            println("face probe: ${photos.size} photo(s) -> $faces face(s) in ${people.size} person(s)")

            assertTrue(
                faces >= 1,
                "the detector found no faces in ${photos.size} photo(s) of people — either the " +
                    "fixtures have no faces in them, or detection/alignment/thresholding is broken",
            )
            assertTrue(people.isNotEmpty(), "faces were detected but clustered into nobody")
            // Every clustered face must carry the box the People screen crops from; a person with no
            // drawable cover renders as a grey glyph, which reads as a broken screen.
            assertTrue(
                people.all { person -> person.faces.all { it.box != null } && person.coverFace != null },
                "every scanned face must carry its detection box, or the naming UI has nothing to crop",
            )
        } finally {
            detector.close()
            embedder.close()
        }
    }

    /** Photos under `FACE_PROBE_DIR` (or `build/face-probe/`) whose extension some decoder claims. */
    private fun locateFixtures(): List<Path> {
        val dir = Path.of(System.getenv("FACE_PROBE_DIR") ?: "build/face-probe")
        if (!Files.isDirectory(dir)) return emptyList()
        val extensions = buildSet {
            addAll(JpegDecoder().format.extensions)
            addAll(PngDecoder().format.extensions)
            if (Platform.isMac()) {
                addAll(HeicDecoder().format.extensions)
                addAll(RawDecoder().format.extensions)
            }
        }
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in extensions }
                .sorted()
                .toList()
        }
    }
}
