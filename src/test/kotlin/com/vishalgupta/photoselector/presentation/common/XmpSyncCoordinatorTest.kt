package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.data.export.RatingDecision
import com.vishalgupta.photoselector.data.export.XmpDocument
import com.vishalgupta.photoselector.data.export.XmpMergeOutcome
import com.vishalgupta.photoselector.data.export.decisionFor
import com.vishalgupta.photoselector.data.format.RawDecoder.Companion.RawFormat
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.PhotoExporter
import com.vishalgupta.photoselector.domain.repository.XmpReport
import com.vishalgupta.photoselector.domain.repository.XmpSyncPreferences
import com.vishalgupta.photoselector.domain.usecase.ExportPhotosXmpUseCase
import com.vishalgupta.photoselector.testing.FakeCategoriesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Behaviour of the [XmpSyncCoordinator]: it drives the RAW-only XMP-sidecar sync from the cull's
 * Favourites / Rejects. Enable is a FULL whole-root reconcile; while enabled each membership change is
 * a delta write; disable stops writing. The [FakeXmpExporter] below applies the *production*
 * [XmpDocument.merge] over an in-memory sidecar store, so the invariant-sequence and guarded-clear
 * tests prove the real merge is driven correctly (they don't re-test XmpDocument's own cases).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XmpSyncCoordinatorTest {

    private val root = RootFolder(Path.of("/root"))

    private fun raw(id: String) = Photo(
        id = PhotoId(id),
        absolutePath = Path.of("/root/$id.cr2"),
        relativePath = "$id.cr2",
        fileName = "$id.cr2",
        sizeBytes = 1,
        lastModifiedEpochMs = 0,
    )

    private fun jpeg(id: String) = Photo(
        id = PhotoId(id),
        absolutePath = Path.of("/root/$id.jpg"),
        relativePath = "$id.jpg",
        fileName = "$id.jpg",
        sizeBytes = 1,
        lastModifiedEpochMs = 0,
    )

    private fun coordinator(
        exporter: FakeXmpExporter,
        repo: FakeCategoriesRepository,
        prefs: XmpSyncPreferences,
        photos: () -> List<Photo>,
        dispatcher: TestDispatcher,
    ) = XmpSyncCoordinator(
        root = root,
        categories = repo,
        exportXmp = ExportPhotosXmpUseCase(exporter),
        preferences = prefs,
        photosForRoot = photos,
        parentJob = null,
        dispatcher = dispatcher,
    )

    @Test
    fun `enable runs a full reconcile over every root photo and skips non-RAW`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exporter = FakeXmpExporter()
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(raw("p1"), raw("p2"), raw("p3"), jpeg("p4"))
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p1")))
        repo.addMemberships(root, Category.REJECTS_ID, listOf(PhotoId("p2")))
        val vm = coordinator(exporter, repo, FakeXmpSyncPreferences(), { all }, dispatcher)

        vm.setEnabled(true)
        advanceUntilIdle()

        // Exactly one call — the reconcile — and it visited ALL root photos, not just the flagged ones.
        assertEquals(1, exporter.calls.size)
        assertEquals(all.map { it.id }, exporter.calls.single())
        assertEquals("5", exporter.rating("p1"))
        assertEquals("-1", exporter.rating("p2"))
        assertNull(exporter.rating("p3")) // undecided, no prior sidecar -> nothing written
        // The non-RAW jpeg is skipped and surfaced as the footer's count.
        assertEquals(1, vm.state.value.skippedNonRaw)
    }

    @Test
    fun `a membership change while enabled writes only the changed photo`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exporter = FakeXmpExporter()
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(raw("p1"), raw("p2"), raw("p3"))
        val vm = coordinator(exporter, repo, FakeXmpSyncPreferences(), { all }, dispatcher)

        vm.setEnabled(true)
        advanceUntilIdle()
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p2")))
        advanceUntilIdle()

        assertEquals(2, exporter.calls.size)
        // The delta write carries only p2, not the whole root.
        assertEquals(listOf(PhotoId("p2")), exporter.calls[1])
        assertEquals("5", exporter.rating("p2"))
    }

    @Test
    fun `a fav-to-reject swap while enabled clears then rewrites via the live delta path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exporter = FakeXmpExporter()
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(raw("p1"), raw("p2"))
        // p1 starts favourited; enable writes p1 = 5 (stamp = 5).
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p1")))
        val vm = coordinator(exporter, repo, FakeXmpSyncPreferences(), { all }, dispatcher)
        vm.setEnabled(true)
        advanceUntilIdle()
        assertEquals("5", exporter.rating("p1"))

        // Remove-only delta: un-favourite p1 -> it is now undecided, so the live write feeds just p1 and the
        // guarded clear fires (our stamp still matches the on-disk 5).
        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("p1"))
        advanceUntilIdle()
        assertEquals(listOf(PhotoId("p1")), exporter.calls[1])
        assertNull(exporter.rating("p1"))

        // Then reject p1 -> the swap's second half rewrites the same photo to -1 through the delta path.
        repo.addMemberships(root, Category.REJECTS_ID, listOf(PhotoId("p1")))
        advanceUntilIdle()
        assertEquals(listOf(PhotoId("p1")), exporter.calls[2])
        assertEquals("-1", exporter.rating("p1"))
    }

    @Test
    fun `disable stops further writes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exporter = FakeXmpExporter()
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(raw("p1"), raw("p2"))
        val vm = coordinator(exporter, repo, FakeXmpSyncPreferences(), { all }, dispatcher)

        vm.setEnabled(true)
        advanceUntilIdle()
        vm.setEnabled(false)
        advanceUntilIdle()
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p1")))
        advanceUntilIdle()

        // Only the enable reconcile ran; the post-disable membership change wrote nothing.
        assertEquals(1, exporter.calls.size)
        assertNull(exporter.rating("p1"))
    }

    @Test
    fun `re-enable clears a stale rating on a photo un-favourited while off and writes the new one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exporter = FakeXmpExporter()
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(raw("p1"), raw("p2"))
        val prefs = FakeXmpSyncPreferences()
        val vm = coordinator(exporter, repo, prefs, { all }, dispatcher)

        // favourite p1 -> enable (sync writes p1 = 5)
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p1")))
        vm.setEnabled(true)
        advanceUntilIdle()
        assertEquals("5", exporter.rating("p1"))

        // disable -> un-favourite p1 + favourite p2 (no writes while off)
        vm.setEnabled(false)
        advanceUntilIdle()
        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("p1"))
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p2")))
        advanceUntilIdle()
        assertEquals(1, exporter.calls.size) // still just the first reconcile

        // re-enable => FULL reconcile visits p1 (now undecided, our stamp still matches) -> cleared,
        // and p2 -> 5. This only works because enable walks every root photo, not the changed set.
        vm.setEnabled(true)
        advanceUntilIdle()
        assertNull(exporter.rating("p1"))
        assertEquals("5", exporter.rating("p2"))
    }

    @Test
    fun `re-enable leaves a foreign rating changed while off untouched`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exporter = FakeXmpExporter()
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(raw("p1"))
        val vm = coordinator(exporter, repo, FakeXmpSyncPreferences(), { all }, dispatcher)

        // favourite p1 -> enable (writes p1 = 5, stamp = 5)
        repo.addMemberships(root, Category.FAVOURITES_ID, listOf(PhotoId("p1")))
        vm.setEnabled(true)
        advanceUntilIdle()

        // disable, then a foreign tool re-rates p1 to 3 on disk (stamp stays 5 -> no longer ours),
        // and the photo is un-favourited in the app.
        vm.setEnabled(false)
        advanceUntilIdle()
        exporter.overrideRating("p1", from = "5", to = "3")
        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("p1"))
        advanceUntilIdle()

        // re-enable => reconcile visits p1 (undecided) but the guarded clear does NOT fire, because the
        // on-disk rating no longer equals our stamp — the foreign 3 is preserved.
        vm.setEnabled(true)
        advanceUntilIdle()
        assertEquals("3", exporter.rating("p1"))
    }

    /** In-memory [XmpSyncPreferences]; defaults off so the coordinator's init doesn't auto-start. */
    private class FakeXmpSyncPreferences(private var enabled: Boolean = false) : XmpSyncPreferences {
        override fun isEnabled(root: RootFolder): Boolean = enabled
        override fun setEnabled(root: RootFolder, enabled: Boolean) { this.enabled = enabled }
    }

    /**
     * A [PhotoExporter] whose `exportXmpSidecars` applies the real [XmpDocument.merge] over an in-memory
     * sidecar store (keyed by [PhotoId]) — so tests observe genuine rating/clear outcomes — while
     * recording the id list of every call so the reconcile-vs-delta contract can be asserted.
     */
    private class FakeXmpExporter : PhotoExporter {
        val calls = mutableListOf<List<PhotoId>>()
        private val sidecars = mutableMapOf<PhotoId, ByteArray>()

        override suspend fun exportXmpSidecars(
            root: RootFolder,
            photos: List<Photo>,
            favouriteIds: Set<PhotoId>,
            rejectedIds: Set<PhotoId>,
            onProgress: (written: Int, total: Int) -> Unit,
        ): XmpReport {
            calls += photos.map { it.id }
            var written = 0
            var cleared = 0
            var unsupported = 0
            for (photo in photos) {
                if (!RawFormat.matches(photo.absolutePath)) {
                    unsupported++
                    continue
                }
                val decision = decisionFor(
                    isRejected = photo.id in rejectedIds,
                    isFavourite = photo.id in favouriteIds,
                )
                when (val outcome = XmpDocument.merge(sidecars[photo.id], decision)) {
                    is XmpMergeOutcome.Write -> {
                        sidecars[photo.id] = outcome.bytes
                        if (outcome.cleared) cleared++ else written++
                    }
                    XmpMergeOutcome.Skip -> Unit
                }
            }
            return XmpReport(written = written, cleared = cleared, unsupported = unsupported, failed = emptyList())
        }

        /** The current on-disk xmp:Rating for [id], or null if there is no sidecar / no rating. */
        fun rating(id: String): String? {
            val bytes = sidecars[PhotoId(id)] ?: return null
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val blocks = doc.getElementsByTagNameNS(RDF_NS, "Description")
            for (b in 0 until blocks.length) {
                val el = blocks.item(b) as Element
                el.getAttributeNodeNS(XMP_NS, "Rating")?.let { return it.value }
                val kids = el.childNodes
                for (i in 0 until kids.length) {
                    val n = kids.item(i)
                    if (n is Element && XMP_NS == n.namespaceURI && "Rating" == n.localName) return n.textContent
                }
            }
            return null
        }

        /** Simulate a foreign tool re-rating [id]'s sidecar (leaving our stamp mismatched). */
        fun overrideRating(id: String, from: String, to: String) {
            val text = String(sidecars.getValue(PhotoId(id)), StandardCharsets.UTF_8)
                .replace("xmp:Rating=\"$from\"", "xmp:Rating=\"$to\"")
            sidecars[PhotoId(id)] = text.toByteArray(StandardCharsets.UTF_8)
        }

        override suspend fun exportTxt(root: RootFolder, favourites: List<Photo>, destinationTxt: Path) = error("unused")
        override suspend fun copyToFolder(
            root: RootFolder,
            favourites: List<Photo>,
            destDir: Path,
            policy: com.vishalgupta.photoselector.domain.repository.ConflictPolicy,
            onProgress: (copied: Int, total: Int) -> Unit,
        ) = error("unused")

        private companion object {
            const val XMP_NS = "http://ns.adobe.com/xap/1.0/"
            const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        }
    }
}
