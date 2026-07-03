package com.vishalgupta.photoselector.data.export

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.test.runTest
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmpSidecarPhotoExporterTest {

    // --- The precedence rule (reject wins, then favourite, else nothing) -----------------------

    @Test
    fun `reject wins over favourite`() {
        assertEquals(RatingDecision.Rejected, decisionFor(isRejected = true, isFavourite = true))
        assertEquals(RatingDecision.Rejected, decisionFor(isRejected = true, isFavourite = false))
    }

    @Test
    fun `favourite maps to five stars when not rejected`() {
        assertEquals(RatingDecision.Favourite, decisionFor(isRejected = false, isFavourite = true))
    }

    @Test
    fun `neither bucket is undecided`() {
        assertEquals(RatingDecision.Undecided, decisionFor(isRejected = false, isFavourite = false))
    }

    // --- RAW-only scope: rated RAWs get sidecars, non-RAW photos are counted unsupported --------

    @Test
    fun `writes an xmp sidecar next to each rated RAW and skips non-RAW as unsupported`() = runTest {
        val dir = createTempDirectory("xmp-test")
        try {
            val fav = photo(dir, "IMG_1234.CR2", "fav")     // RAW, favourite -> rating 5
            val rej = photo(dir, "IMG_5678.NEF", "rej")     // RAW, rejected  -> rating -1
            val plainRaw = photo(dir, "IMG_4321.ARW", "raw")// RAW, undecided -> no sidecar (nothing to clear)
            val jpeg = photo(dir, "IMG_0001.JPG", "jpg")    // non-RAW -> unsupported
            val heic = photo(dir, "IMG_0002.HEIC", "heic")  // non-RAW -> unsupported

            val report = XmpSidecarPhotoExporter().exportSidecars(
                root = RootFolder(dir),
                photos = listOf(fav, rej, plainRaw, jpeg, heic),
                favouriteIds = setOf(PhotoId("fav")),
                rejectedIds = setOf(PhotoId("rej")),
                onProgress = { _, _ -> },
            )

            assertEquals(2, report.written)
            assertEquals(0, report.cleared)
            assertEquals(2, report.unsupported)
            assertTrue(report.failed.isEmpty())

            // Sidecar shares the basename, extension swapped to .xmp, in the same directory.
            assertTrue(Files.exists(dir.resolve("IMG_1234.xmp")))
            assertTrue(Files.exists(dir.resolve("IMG_5678.xmp")))
            // Undecided RAW with no prior sidecar writes nothing; non-RAW never gets a sidecar.
            assertFalse(Files.exists(dir.resolve("IMG_4321.xmp")))
            assertFalse(Files.exists(dir.resolve("IMG_0001.xmp")))
            assertFalse(Files.exists(dir.resolve("IMG_0002.xmp")))

            // Rating is set, and the wrong-field xmp:Label is never written.
            val favDesc = parseDescription(dir.resolve("IMG_1234.xmp").readText())
            assertEquals("5", favDesc.getAttribute("xmp:Rating"))
            assertFalse(favDesc.hasAttribute("xmp:Label"))
            val rejDesc = parseDescription(dir.resolve("IMG_5678.xmp").readText())
            assertEquals("-1", rejDesc.getAttribute("xmp:Rating"))
            assertFalse(rejDesc.hasAttribute("xmp:Label"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same-basename RAW and JPEG do not fold - RAW writes its own sidecar, JPEG is unsupported`() = runTest {
        val dir = createTempDirectory("xmp-no-fold")
        try {
            // A RAW+JPEG pair sharing a basename. The RAW is favourited, the JPEG rejected. The old
            // basename fold let the reject win over the whole pair; now each photo stands alone: the
            // RAW writes IMG_1234.xmp with its own decision (rating 5) and the JPEG is unsupported.
            val raw = photo(dir, "IMG_1234.CR2", "raw")
            val jpeg = photo(dir, "IMG_1234.JPG", "jpeg")

            val report = XmpSidecarPhotoExporter().exportSidecars(
                root = RootFolder(dir),
                photos = listOf(jpeg, raw),
                favouriteIds = setOf(PhotoId("raw")),
                rejectedIds = setOf(PhotoId("jpeg")),
                onProgress = { _, _ -> },
            )

            assertEquals(1, report.written)
            assertEquals(1, report.unsupported)
            assertEquals(0, report.cleared)
            assertTrue(report.failed.isEmpty())

            val sidecar = dir.resolve("IMG_1234.xmp")
            assertTrue(Files.exists(sidecar))
            // The RAW's decision (favourite, rating 5) wins - the JPEG's reject never touched it.
            assertEquals("5", parseDescription(sidecar.readText()).getAttribute("xmp:Rating"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a malformed existing sidecar fails that photo without overwriting the file or crashing`() = runTest {
        val dir = createTempDirectory("xmp-malformed")
        try {
            val raw = photo(dir, "IMG_7777.CR2", "raw")
            // A pre-existing, non-XML sidecar the parser will reject. The exporter must record a
            // per-photo failure (via its catch(Throwable)) and leave the file byte-for-byte intact.
            val sidecar = dir.resolve("IMG_7777.xmp")
            val garbage = "this is not <xml at all &&&"
            Files.write(sidecar, garbage.toByteArray(StandardCharsets.UTF_8))

            val report = XmpSidecarPhotoExporter().exportSidecars(
                root = RootFolder(dir),
                photos = listOf(raw),
                favouriteIds = setOf(PhotoId("raw")),
                rejectedIds = emptySet(),
                onProgress = { _, _ -> },
            )

            assertEquals(0, report.written)
            assertEquals(1, report.failed.size)
            assertEquals(PhotoId("raw"), report.failed.single().first.id)
            // The malformed file is untouched - never clobbered by a fresh packet.
            assertEquals(garbage, sidecar.readText())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun photo(dir: Path, name: String, id: String): Photo {
        val path = dir.resolve(name)
        Files.write(path, byteArrayOf(1, 2, 3))
        return Photo(
            id = PhotoId(id),
            absolutePath = path,
            relativePath = name,
            fileName = name,
            sizeBytes = 3,
            lastModifiedEpochMs = 0,
        )
    }

    private fun parseDescription(packet: String): Element {
        val doc: Document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(packet.toByteArray(StandardCharsets.UTF_8)))
        val nodes = doc.getElementsByTagName("rdf:Description")
        assertEquals(1, nodes.length, "expected exactly one rdf:Description")
        return nodes.item(0) as Element
    }
}
