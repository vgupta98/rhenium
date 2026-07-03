package com.vishalgupta.photoselector.presentation.grid

import androidx.compose.ui.graphics.ImageBitmap
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.grouping.CaptureMetadata
import com.vishalgupta.photoselector.domain.grouping.CaptureMetadataSource
import com.vishalgupta.photoselector.domain.grouping.GroupingProgress
import com.vishalgupta.photoselector.domain.grouping.PhotoGrouper
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.ConflictPolicy
import com.vishalgupta.photoselector.domain.repository.CopyReport
import com.vishalgupta.photoselector.domain.repository.PhotoExporter
import com.vishalgupta.photoselector.domain.repository.PhotoTrash
import com.vishalgupta.photoselector.domain.repository.TrashReport
import com.vishalgupta.photoselector.domain.usecase.CopyPhotosToFolderUseCase
import com.vishalgupta.photoselector.domain.usecase.ExportPhotosTxtUseCase
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.presentation.common.GroupingCoordinator
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import com.vishalgupta.photoselector.testing.FakeCategoriesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Pure selection-model behaviour on [GridViewModel]: the Cmd/Shift/Cmd-A interactions resolve to
 * the right id sets, and a bulk file batches the whole selection into one repository call.
 *
 * Most tests run on a [StandardTestDispatcher] (injected via the GridViewModel dispatcher seam) and
 * drive the scope's coroutines with [advanceUntilIdle], so the off-thread regroup settles in virtual
 * time rather than on a real background thread under a wall-clock `withTimeout` (which was flaky on a
 * loaded CI runner). The two tests that gate the metadata read on a blocking [java.util.concurrent.CountDownLatch]
 * deliberately need a real background thread, so they keep the real Swing/IO dispatchers and `runBlocking`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GridSelectionTest {

    private val photos = (0 until 6).map { i ->
        Photo(
            id = PhotoId("p$i"),
            absolutePath = Path.of("/photos/img$i.jpg"),
            relativePath = "img$i.jpg",
            fileName = "img$i.jpg",
            sizeBytes = 1,
            lastModifiedEpochMs = 0,
        )
    }
    private val selectsId = CategoryId("selects")
    private val categories = listOf(Category.favourites(), Category(selectsId, "Selects", builtIn = false))

    private val noOpImageLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap? = null
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    private val noOpExporter = object : PhotoExporter {
        override suspend fun exportTxt(root: RootFolder, favourites: List<Photo>, destinationTxt: Path) {}
        override suspend fun copyToFolder(
            root: RootFolder,
            favourites: List<Photo>,
            destDir: Path,
            policy: ConflictPolicy,
            onProgress: (copied: Int, total: Int) -> Unit,
        ): CopyReport = CopyReport(0, 0, emptyList())
    }

    private val noOpTrash = object : PhotoTrash {
        override suspend fun moveToTrash(photos: List<Photo>): TrashReport =
            TrashReport(trashed = photos.size, failed = emptyList())
    }

    private val deletedRoots = mutableListOf<Set<PhotoId>>()

    // A distinct camera per photo keeps every tile a [PhotoGroup.Single], so a tile index equals a
    // photo index and these selection tests read against the flat list — burst collapsing has its
    // own coverage in BurstGrouperTest and the grid screenshot test.
    private val perPhotoCameraMetadata = CaptureMetadataSource { photo ->
        CaptureMetadata(takenAtEpochMs = null, cameraId = photo.id.value, orientation = null)
    }

    // Same camera + capture times one second apart, so all six photos collapse into one burst when
    // grouping is on - the fixture the toggle test flips on and off.
    private val oneBurstMetadata = CaptureMetadataSource { photo ->
        val i = photo.id.value.removePrefix("p").toInt()
        CaptureMetadata(takenAtEpochMs = i * 1_000L, cameraId = "cam", orientation = 1)
    }

    // single | burst[p1..p4] | single : p0 and p5 sit on their own cameras, p1..p4 share one and
    // shoot a second apart. The burst is tile 1, so collapsing it is a meaningful focus target
    // (distinct from the first/last tile) - the fixture for the collapse-returns-focus test.
    private val singleBurstSingleMetadata = CaptureMetadataSource { photo ->
        when (val i = photo.id.value.removePrefix("p").toInt()) {
            0 -> CaptureMetadata(takenAtEpochMs = 0L, cameraId = "head", orientation = 1)
            5 -> CaptureMetadata(takenAtEpochMs = 0L, cameraId = "tail", orientation = 1)
            else -> CaptureMetadata(takenAtEpochMs = i * 1_000L, cameraId = "burst", orientation = 1)
        }
    }

    // p0|p1 are a two-frame burst (shared camera, a second apart); p2..p5 sit on their own cameras.
    // Deleting one of the pair drops the burst below two frames, so the survivor must regroup to a
    // Single - the fixture for the below-two-frames regroup test.
    private val twoFrameBurstMetadata = CaptureMetadataSource { photo ->
        when (val i = photo.id.value.removePrefix("p").toInt()) {
            0 -> CaptureMetadata(takenAtEpochMs = 0L, cameraId = "pair", orientation = 1)
            1 -> CaptureMetadata(takenAtEpochMs = 1_000L, cameraId = "pair", orientation = 1)
            else -> CaptureMetadata(takenAtEpochMs = 0L, cameraId = "solo$i", orientation = 1)
        }
    }

    private fun viewModel(
        repo: CategoriesRepository,
        trash: PhotoTrash = noOpTrash,
        metadata: CaptureMetadataSource = perPhotoCameraMetadata,
        similarityGrouper: PhotoGrouper? = null,
        initialGroupingMode: GroupingMode = GroupingMode.Time,
        onGroupingModeChanged: ((GroupingMode) -> Unit)? = null,
        hasSeenSimilarityCoachmark: Boolean = true,
        onSimilarityCoachmarkSeen: () -> Unit = {},
        // When supplied (the common case), this single TestDispatcher drives both the scope and the
        // off-thread regroup, so a test settles the grid with advanceUntilIdle() in virtual time.
        // Left null only by the blocking-gate tests, which need the real Swing/IO dispatchers.
        dispatcher: CoroutineDispatcher? = null,
    ): GridViewModel = GridViewModel(
        root = RootFolder(Path.of("/photos")),
        allPhotos = photos,
        categoryScope = CategoryScope.AllPhotos,
        lastViewedPhotoId = null,
        categories = repo,
        exportTxt = ExportPhotosTxtUseCase(noOpExporter),
        copyToFolder = CopyPhotosToFolderUseCase(noOpExporter),
        moveToTrash = MovePhotosToTrashUseCase(trash),
        imageLoader = noOpImageLoader,
        captureMetadataSource = metadata,
        // Wrap the fake grouper in a coordinator on the same test dispatcher so the background
        // Similarity pass runs in virtual time and settles under advanceUntilIdle, just like the
        // inline regroup. Null grouper -> null coordinator (Similarity degrades to singles).
        groupingCoordinator = similarityGrouper?.let {
            GroupingCoordinator(it, parentJob = null, dispatcher = dispatcher ?: Dispatchers.IO)
        },
        initialGroupingMode = initialGroupingMode,
        hasSeenSimilarityCoachmark = hasSeenSimilarityCoachmark,
        onSimilarityCoachmarkSeen = onSimilarityCoachmarkSeen,
        onGroupingModeChanged = onGroupingModeChanged,
        onPhotosDeleted = { ids -> deletedRoots += ids },
        dispatcher = dispatcher ?: Dispatchers.Swing,
        groupingDispatcher = dispatcher ?: Dispatchers.IO,
    )

    // Real-time wait for the two blocking-gate tests, which can't use a virtual-time dispatcher.
    private suspend fun GridViewModel.awaitPhotos() {
        withTimeout(2_000) { state.first { it.photos.size == photos.size } }
    }

    @Test
    fun cmdClick_togglesTileAndSetsAnchor() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.toggleSelection(TileIndex(2))
        assertEquals(setOf(photos[2].id), vm.state.value.selection)
        assertEquals(TileIndex(2), vm.state.value.anchorIndex)

        vm.toggleSelection(TileIndex(2))
        assertTrue(vm.state.value.selection.isEmpty())

        vm.onClear()
    }

    @Test
    fun setLastViewed_reseatsAnExistingRingOntoThatPhoto() = runTest {
        // Off keeps one tile per photo, so a tile index equals a photo index here.
        val vm = viewModel(FakeCategoriesRepository(categories), initialGroupingMode = GroupingMode.Off, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.setFocusedIndex(TileIndex(1)) // a ring is showing at tile 1

        vm.setLastViewed(photos[4].id) // returned from the viewer on p4

        assertEquals("the ring follows the mouse-driven open to p4", TileIndex(4), vm.state.value.focusedIndex)
        assertEquals(photos[4].id, vm.state.value.lastViewedPhotoId)
        vm.onClear()
    }

    @Test
    fun setLastViewed_doesNotSpawnARingForAPureMouseUser() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), initialGroupingMode = GroupingMode.Off, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle() // no ring: focusedIndex stays at its -1 default

        vm.setLastViewed(photos[4].id)

        assertEquals("no ring present, so none is spawned", TileIndex(-1), vm.state.value.focusedIndex)
        assertEquals("the underline marker still moves", photos[4].id, vm.state.value.lastViewedPhotoId)
        vm.onClear()
    }

    @Test
    fun shiftClick_selectsContiguousRunFromAnchor() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.toggleSelection(TileIndex(1))   // anchor = 1
        vm.selectRange(TileIndex(4))       // 1..4 inclusive
        assertEquals(setOf(photos[1].id, photos[2].id, photos[3].id, photos[4].id), vm.state.value.selection)
        // Anchor is unchanged, so a second shift-click re-derives the run from the same pivot.
        assertEquals(TileIndex(1), vm.state.value.anchorIndex)
        vm.selectRange(TileIndex(0))       // 0..1
        assertEquals(setOf(photos[0].id, photos[1].id), vm.state.value.selection)

        vm.onClear()
    }

    @Test
    fun selectAll_thenClear() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.selectAll()
        assertEquals(photos.map { it.id }.toSet(), vm.state.value.selection)

        vm.clearSelection()
        assertTrue(vm.state.value.selection.isEmpty())
        assertNull(vm.state.value.anchorIndex)

        vm.onClear()
    }

    @Test
    fun toggleGroupBursts_collapsesAndExpandsTiles() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = oneBurstMetadata, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        // Grouping is on by default: the six frames settle into a single burst tile.
        assertEquals(1, vm.state.value.groups.size)
        assertTrue(vm.state.value.groupBursts)

        // Turning it off drops straight back to one tile per photo.
        vm.toggleGroupBursts()
        assertTrue(!vm.state.value.groupBursts)
        assertEquals(photos.size, vm.state.value.groups.size)

        // Turning it back on re-collapses the burst off-thread.
        vm.toggleGroupBursts()
        assertTrue(vm.state.value.groupBursts)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.groups.size)

        vm.onClear()
    }

    @Test
    fun groupingMode_opensInTheSeededLens() = runTest {
        // A rebuilt grid (Grid -> Browser -> Grid) is constructed with the session's last lens.
        val vm = viewModel(FakeCategoriesRepository(categories), initialGroupingMode = GroupingMode.Similarity, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(GroupingMode.Similarity, vm.state.value.groupingMode)
        vm.onClear()
    }

    @Test
    fun setGroupingMode_reportsTheChangeUpwards() = runTest {
        // The container listens here to remember the choice, so the next rebuilt grid reopens in it.
        var reported: GroupingMode? = null
        val vm = viewModel(FakeCategoriesRepository(categories), onGroupingModeChanged = { reported = it }, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.setGroupingMode(GroupingMode.Off)
        assertEquals(GroupingMode.Off, reported)
        assertEquals(GroupingMode.Off, vm.state.value.groupingMode)

        // A no-op re-select of the current lens reports nothing new.
        reported = null
        vm.setGroupingMode(GroupingMode.Off)
        assertEquals(null, reported)

        vm.onClear()
    }

    @Test
    fun expandBurst_thenFocusFilesOneFrame_whileCollapsedFilesWholeBurst() = runTest {
        val repo = FakeCategoriesRepository(categories)
        val vm = viewModel(repo, metadata = oneBurstMetadata, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(1, vm.state.value.groups.size) // one burst of six

        val burstId = vm.state.value.groups.single().groupId

        // Collapsed: F at the burst tile files all six frames in one additive batch (addMemberships).
        vm.setFocusedIndex(TileIndex(0))
        vm.toggleMembershipAtFocus()
        advanceUntilIdle()
        assertEquals(Category.FAVOURITES_ID, repo.addCalls.single().first)
        assertEquals(photos.map { it.id }.toSet(), repo.addCalls.single().second)

        // Expand: now six per-frame tiles, focus on the suggested keeper (keyIndex = middle = 3 for a
        // time burst), not the first frame - see suggestedKeeperTest for the Similarity-pick coverage.
        vm.toggleBurstExpansion(burstId)
        assertEquals(burstId, vm.state.value.expandedBurstId)
        assertEquals(photos.size, vm.state.value.displayGroups.size)
        assertEquals(TileIndex(photos.size / 2), vm.state.value.focusedIndex)

        // Expanded: filing the 3rd frame into "Selects" toggles exactly that one photo (not a batch).
        vm.setFocusedIndex(TileIndex(2))
        vm.toggleCustomCategoryAtFocus(0) // slot 0 == "Selects"
        advanceUntilIdle()
        assertEquals(selectsId to photos[2].id, repo.toggleCalls.single())

        // Collapse folds back to the single burst tile.
        vm.collapseBurst()
        assertNull(vm.state.value.expandedBurstId)
        assertEquals(1, vm.state.value.displayGroups.size)

        vm.onClear()
    }

    @Test
    fun collapseBurst_returnsFocusToTheBurstTile() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = singleBurstSingleMetadata, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        // single | burst[p1..p4] | single -> three tiles, the burst in the middle (tile 1).
        assertEquals(3, vm.state.value.groups.size)
        val burstId = vm.state.value.groups[1].groupId

        // Expand and arrow onto a later frame, away from the burst's leading edge.
        vm.toggleBurstExpansion(burstId)
        assertEquals(burstId, vm.state.value.expandedBurstId)
        vm.setFocusedIndex(TileIndex(3)) // p3, deep inside the unfolded run

        // Collapsing returns focus to the burst tile (index 1), not whatever now sits at the old
        // index - symmetric with expansion jumping focus to the first frame.
        vm.collapseBurst()
        assertNull(vm.state.value.expandedBurstId)
        assertEquals(3, vm.state.value.displayGroups.size)
        assertEquals(TileIndex(1), vm.state.value.focusedIndex)
        assertEquals(burstId, vm.state.value.displayGroups[1].groupId)

        vm.onClear()
    }

    @Test
    fun expandingASimilarityBurst_landsFocusOnTheSuggestedKeeperAndFFilesIt() = runTest {
        // A Similarity grouper that clusters all six frames and nominates frame 1 as the sharpest -
        // deliberately neither the first frame (the old expand target) nor the middle (a time burst's
        // neutral keyIndex), so the focus landing on it proves it tracks the suggested keeper.
        val suggested = 1
        val similarity = object : PhotoGrouper {
            override suspend fun group(photos: List<Photo>, onProgress: GroupingProgress) =
                listOf(PhotoGroup.Burst(photos, keyIndex = suggested))
        }
        val repo = FakeCategoriesRepository(categories)
        val vm = viewModel(
            repo,
            similarityGrouper = similarity,
            initialGroupingMode = GroupingMode.Similarity,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertEquals(1, vm.state.value.groups.size) // one burst of six
        val burstId = vm.state.value.groups.single().groupId

        // Expanding lands the keyboard ring on the suggested keeper, not the run's first frame.
        vm.toggleBurstExpansion(burstId)
        assertEquals(burstId, vm.state.value.expandedBurstId)
        assertEquals(photos.size, vm.state.value.displayGroups.size)
        assertEquals(TileIndex(suggested), vm.state.value.focusedIndex)

        // F immediately after expand files exactly the suggested frame (one toggle, not a batch).
        vm.toggleMembershipAtFocus()
        advanceUntilIdle()
        assertEquals(Category.FAVOURITES_ID to photos[suggested].id, repo.toggleCalls.single())

        vm.onClear()
    }

    @Test
    fun similarityPass_keepsRunningAcrossALensSwitchAndIsReusedOnReturn() = runTest {
        // The bug this guards: switching the lens off Similarity used to cancel the in-flight pass. Now
        // the GroupingCoordinator owns it, so it survives a detour through Time (and a return) as ONE
        // pass. A gate keeps group() suspended so we can switch lenses while it is mid-flight; the call
        // counter proves it is neither cancelled (which would force a fresh call on return) nor restarted.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val similarity = object : PhotoGrouper {
            override suspend fun group(photos: List<Photo>, onProgress: GroupingProgress): List<PhotoGroup> {
                calls.incrementAndGet()
                gate.await()
                return listOf(PhotoGroup.Burst(photos))
            }
        }
        val vm = viewModel(
            FakeCategoriesRepository(categories),
            similarityGrouper = similarity,
            initialGroupingMode = GroupingMode.Similarity,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        // The pass is running (one call, gated) and reporting progress for the always-visible cues.
        assertEquals(1, calls.get())
        assertNotNull("the running pass reports progress", vm.state.value.similarityProgress)
        assertEquals(photos.size, vm.state.value.groups.size) // still singles: group() hasn't returned

        // Detour through Time: the displayed lens changes, but the background pass must not be cancelled.
        vm.setGroupingMode(GroupingMode.Time)
        advanceUntilIdle()
        assertEquals("switching lens must not restart or cancel the pass", 1, calls.get())
        assertNotNull("the pass keeps reporting while another lens is shown", vm.state.value.similarityProgress)

        // Return to Similarity: re-attaches to the SAME in-flight pass rather than starting another.
        vm.setGroupingMode(GroupingMode.Similarity)
        advanceUntilIdle()
        assertEquals("returning to the lens re-attaches, not re-runs", 1, calls.get())

        // Let the one pass finish: its groups land and the progress cue clears.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.groups.size) // the single burst
        assertNull("progress clears when the pass completes", vm.state.value.similarityProgress)
        assertEquals(1, calls.get())
        vm.onClear()
    }

    @Test
    fun regroupCompletingAfterGroupingToggledOff_doesNotReapplyBursts() = runBlocking {
        // Same-camera burst metadata, but the first read blocks on a gate, so the off-thread grouping
        // pass is guaranteed in-flight when we toggle grouping off - the race that finding 2 describes.
        // The blocking gate needs a real background thread, so this test keeps the real IO dispatcher.
        val gate = java.util.concurrent.CountDownLatch(1)
        val gatedBurstMetadata = CaptureMetadataSource { photo ->
            gate.await()
            val i = photo.id.value.removePrefix("p").toInt()
            CaptureMetadata(takenAtEpochMs = i * 1_000L, cameraId = "cam", orientation = 1)
        }
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = gatedBurstMetadata)
        vm.awaitPhotos()

        // Grouping is on by default, so a regroup is already launched and now blocked on the gate.
        // Toggle off before releasing it: singles stand, and cancel() can't stop the blocked job.
        vm.toggleGroupBursts()
        assertTrue(!vm.state.value.groupBursts)
        assertEquals(photos.size, vm.state.value.groups.size)

        gate.countDown() // let the now-stale regroup finish and attempt its _state.update

        // Give that update a window to (wrongly) collapse the tiles; it must not.
        var collapsed = false
        try {
            withTimeout(500) { vm.state.first { it.groups.size < photos.size } }
            collapsed = true
        } catch (_: TimeoutCancellationException) {
            // No collapse within the window - the in-flight regroup correctly bailed on groupBursts.
        }
        assertTrue("a stale regroup re-applied bursts after grouping was toggled off", !collapsed)
        assertTrue(!vm.state.value.groupBursts)
        assertEquals(photos.size, vm.state.value.groups.size)

        vm.onClear()
    }

    @Test
    fun deleteSelection_doesNotRegroupWhenGroupingIsOff() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = oneBurstMetadata, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(1, vm.state.value.groups.size) // one burst of six

        // User turns grouping off -> one tile per photo.
        vm.toggleGroupBursts()
        advanceUntilIdle()
        assertEquals(photos.size, vm.state.value.groups.size)
        assertTrue(!vm.state.value.groupBursts)

        // Delete one frame. With grouping off the delete must leave the tiles flat - it must not
        // re-collapse the remaining frames behind the user's "off" choice.
        vm.toggleSelection(TileIndex(5))
        vm.deleteSelection()
        advanceUntilIdle()
        assertNotNull(vm.messages.first())
        assertTrue(!vm.state.value.isBusy)

        // Virtual time has run every launched coroutine to completion, so a buggy off-thread regroup
        // would already have collapsed the tiles. It didn't: the frames stay flat.
        assertEquals(photos.size - 1, vm.state.value.groups.size)
        assertTrue(!vm.state.value.groupBursts)

        vm.onClear()
    }

    @Test
    fun deleteSelection_regroupsABurstDroppedBelowTwoFramesToASingle() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = twoFrameBurstMetadata, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        // p0|p1 burst + p2..p5 singles -> 5 tiles, exactly one of them a burst.
        assertEquals(1, vm.state.value.groups.count { it is PhotoGroup.Burst })
        val burstId = vm.state.value.groups.first { it is PhotoGroup.Burst }.groupId

        // Expand so the two frames are individually addressable, then delete just the second one (p1).
        vm.toggleBurstExpansion(burstId)
        assertEquals(burstId, vm.state.value.expandedBurstId)
        vm.toggleSelection(TileIndex(1)) // display tile 1 == p1, the burst's second frame
        vm.deleteSelection()
        advanceUntilIdle()
        assertNotNull(vm.messages.first())
        assertTrue(!vm.state.value.isBusy)

        // p1 gone, p0 survives alone: the burst falls below two frames, so it must regroup to a Single
        // (BurstGrouper requires >= 2). No burst remains, and p0 + the four solos are five tiles.
        assertEquals(photos.size - 1, vm.state.value.photos.size)
        assertTrue(vm.state.value.groups.none { it is PhotoGroup.Burst })
        assertTrue(vm.state.value.photos.any { it.id == photos[0].id })
        assertEquals(photos.size - 1, vm.state.value.groups.size)

        vm.onClear()
    }

    @Test
    fun fileSelectionIntoCustom_batchesWholeSelectionIntoOneCall() = runTest {
        val repo = FakeCategoriesRepository(categories)
        val vm = viewModel(repo, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.toggleSelection(TileIndex(0))
        vm.toggleSelection(TileIndex(3))
        vm.toggleSelection(TileIndex(5))
        vm.fileSelectionIntoCustom(0) // slot 0 == "Selects"

        // The bulk file confirms via a one-shot message; settle it, then assert the single batched call.
        advanceUntilIdle()
        assertNotNull(vm.messages.first())
        assertEquals(1, repo.addCalls.size)
        assertEquals(selectsId, repo.addCalls.single().first)
        assertEquals(setOf(photos[0].id, photos[3].id, photos[5].id), repo.addCalls.single().second)

        vm.onClear()
    }

    @Test
    fun messages_areConsumeOnceAndDoNotReplayToALaterCollector() = runTest {
        // The result feedback is a one-shot channel event, not persistent state: it fires exactly once
        // and is gone. This pins the fix for the "navigate away mid-toast, come back to a resurrected
        // stale toast" bug — a consumed message can never replay.
        val vm = viewModel(FakeCategoriesRepository(categories), dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.toggleSelection(TileIndex(0))
        vm.fileSelectionIntoFavourites()
        advanceUntilIdle()

        // The action surfaced exactly one message.
        assertNotNull(vm.messages.first())

        // It was consumed once: a fresh collector over a settled window sees nothing (no replay).
        var replayed: String? = null
        val watcher = launch { vm.messages.collect { replayed = it } }
        advanceUntilIdle()
        watcher.cancel()
        assertNull("a consumed message must not replay to a later collector", replayed)

        vm.onClear()
    }

    @Test
    fun deleteSelection_trashesDropsFromListPurgesCategoriesAndNotifiesContainer() = runTest {
        val root = RootFolder(Path.of("/photos"))
        val repo = FakeCategoriesRepository(categories)
        // Pre-file two of the about-to-be-deleted photos so we can prove they get purged.
        repo.addMemberships(root, selectsId, setOf(photos[0].id, photos[3].id))
        val vm = viewModel(repo, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.toggleSelection(TileIndex(0))
        vm.toggleSelection(TileIndex(3))
        vm.deleteSelection()

        advanceUntilIdle()
        val message = vm.messages.first()
        val st = vm.state.value
        assertTrue(!st.isBusy)
        assertEquals("two photos left the visible list", photos.size - 2, st.photos.size)
        assertTrue(st.photos.none { it.id == photos[0].id || it.id == photos[3].id })
        assertTrue("selection cleared after delete", st.selection.isEmpty())
        assertTrue("message names the Trash, got: $message", message.contains("Trash"))

        // Purged from every category, and the container was told which ids went.
        val members = repo.observeMemberships(root).value[selectsId].orEmpty()
        assertTrue(photos[0].id !in members && photos[3].id !in members)
        assertEquals(setOf(photos[0].id, photos[3].id), deletedRoots.flatten().toSet())

        vm.onClear()
    }

    @Test
    fun deleteSelection_keepsPhotosThatFailedToTrash() = runTest {
        // Trash everything except the first target, which "fails" (e.g. locked file).
        val failingFirst = object : PhotoTrash {
            override suspend fun moveToTrash(photos: List<Photo>): TrashReport {
                val failed = photos.take(1).map { it to RuntimeException("locked") }
                return TrashReport(trashed = photos.size - failed.size, failed = failed)
            }
        }
        val vm = viewModel(FakeCategoriesRepository(categories), failingFirst, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.toggleSelection(TileIndex(0))
        vm.toggleSelection(TileIndex(1))
        vm.deleteSelection()

        advanceUntilIdle()
        val message = vm.messages.first()
        val st = vm.state.value
        assertTrue(!st.isBusy)
        assertEquals("only the successfully trashed photo left", photos.size - 1, st.photos.size)
        assertTrue("the failed photo stays put", st.photos.any { it.id == photos[0].id })
        assertTrue("message reports the failure, got: $message", message.contains("failed"))

        vm.onClear()
    }

    @Test
    fun deleteSelection_keepsFocusOnTheSamePhotoByIdentity() = runTest {
        // Lens Off, so no regroup runs after the delete to fix up a bare-index focus. Deleting a photo
        // *before* the cursor renumbers the tiles, and focus must follow the photo it was on - not stay
        // a coerced index that now points at a neighbour (the identity-refocus invariant removePhotos
        // already honours; the in-grid delete must match it).
        val vm = viewModel(FakeCategoriesRepository(categories), initialGroupingMode = GroupingMode.Off, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(photos.size, vm.state.value.groups.size) // six singles

        vm.setFocusedIndex(TileIndex(4)) // cursor on p4
        assertEquals(photos[4].id, vm.state.value.displayGroups[4].keyPhoto.id)

        vm.toggleSelection(TileIndex(1)) // select p1, an earlier tile
        vm.deleteSelection()
        advanceUntilIdle()
        assertNotNull(vm.messages.first())
        assertTrue(!vm.state.value.isBusy)

        val st = vm.state.value
        assertEquals(listOf("p0", "p2", "p3", "p4", "p5"), st.photos.map { it.id.value })
        assertEquals("focus stays on p4, now one tile earlier", photos[4].id, st.displayGroups[st.focusedIndex.value].keyPhoto.id)

        vm.onClear()
    }

    @Test
    fun removePhotos_dropsExternallyTrashedFramesAndKeepsFocusByIdentity() = runTest {
        // The grid is now retained across navigation, so a delete made in the browser (which used to
        // be picked up by rebuilding the grid) is instead pushed in via removePhotos. The trashed
        // frame must leave the list and focus must stay on the *photo* it was on, not a bare index.
        val vm = viewModel(FakeCategoriesRepository(categories), dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.setFocusedIndex(TileIndex(3)) // every photo is its own tile, so focus sits on p3
        assertEquals(photos[3].id, vm.state.value.displayGroups[vm.state.value.focusedIndex.value].keyPhoto.id)

        vm.removePhotos(setOf(photos[1].id)) // a browser delete of p1, propagated to this retained grid

        val st = vm.state.value
        assertEquals(listOf("p0", "p2", "p3", "p4", "p5"), st.photos.map { it.id.value })
        assertEquals("focus stays on p3, now one tile earlier", photos[3].id, st.displayGroups[st.focusedIndex.value].keyPhoto.id)

        // A no-op when the ids aren't present (the grid that performed the delete already pruned itself).
        vm.removePhotos(setOf(photos[1].id))
        assertEquals(5, vm.state.value.photos.size)

        vm.onClear()
    }

    @Test
    fun regroupCollapsing_keepsFocusOnTheSamePhotosTile() = runTest {
        // The user's bug: focus a photo, switch to a grouping lens, and when grouping lands ~a beat
        // (or a minute) later the tiles renumber under the cursor. Focus must follow the *photo*, not
        // stay a bare index that now points at a different burst (which made Enter expand the wrong one).
        val vm = viewModel(
            FakeCategoriesRepository(categories),
            metadata = singleBurstSingleMetadata,
            initialGroupingMode = GroupingMode.Off,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        // Off: one tile per photo. Focus p2 (tile 2), which will fold *into* the burst.
        vm.setFocusedIndex(TileIndex(2))
        assertEquals(photos[2].id, vm.state.value.displayGroups[2].keyPhoto.id)

        // Switch to Time: p1..p4 collapse to one burst -> [p0, burst(p1..p4), p5] = 3 tiles.
        vm.setGroupingMode(GroupingMode.Time)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.groups.size)

        // Focus rode the reshape onto the burst tile (index 1) that now contains p2 - not the bare,
        // coerced old index 2 (which would be p5, a different tile entirely).
        assertEquals(TileIndex(1), vm.state.value.focusedIndex)
        assertTrue(vm.state.value.displayGroups[1].photos.any { it.id == photos[2].id })

        vm.onClear()
    }

    @Test
    fun switchingLensOff_keepsFocusOnTheSamePhotosTile() = runTest {
        // The other direction: from a collapsed view, turning grouping off unfolds bursts back to
        // singles and renumbers tiles *upward*. Focus on a tile past the burst must track its photo.
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = singleBurstSingleMetadata, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        // Time (default): [p0, burst(p1..p4), p5] -> 3 tiles. Focus p5 (the last tile, index 2).
        assertEquals(3, vm.state.value.groups.size)
        vm.setFocusedIndex(TileIndex(2))
        assertEquals(photos[5].id, vm.state.value.displayGroups[2].keyPhoto.id)

        // Off: back to six singles. p5 is now tile 5, not the coerced old index 2 (which would be p2).
        vm.setGroupingMode(GroupingMode.Off)
        assertEquals(photos.size, vm.state.value.displayGroups.size)
        assertEquals(TileIndex(5), vm.state.value.focusedIndex)
        assertEquals(photos[5].id, vm.state.value.displayGroups[5].keyPhoto.id)

        vm.onClear()
    }

    @Test
    fun grouping_seedsThenClearsProgress() = runBlocking {
        // A grouping pass exposes determinate progress that the grid surfaces as a non-blocking bar,
        // and clears it when done. Gate the metadata read so the pass is provably in flight first.
        // The blocking gate needs a real background thread, so this test keeps the real IO dispatcher.
        val gate = java.util.concurrent.CountDownLatch(1)
        val gatedMetadata = CaptureMetadataSource { photo ->
            gate.await()
            val i = photo.id.value.removePrefix("p").toInt()
            CaptureMetadata(takenAtEpochMs = i * 1_000L, cameraId = "cam", orientation = 1)
        }
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = gatedMetadata)
        vm.awaitPhotos()

        // Grouping is on by default, so a pass is launched and blocked on the gate: progress is seeded.
        withTimeout(2_000) { vm.state.first { it.grouping != null } }
        assertEquals(photos.size, vm.state.value.grouping!!.total)

        // Let it finish: the bursts land and progress clears.
        gate.countDown()
        withTimeout(2_000) { vm.state.first { it.groups.size == 1 } }
        withTimeout(2_000) { vm.state.first { it.grouping == null } }

        vm.onClear()
    }

    @Test
    fun grouping_warmPassDoesNotFlashTheProgressBar() = runTest {
        // A warm pass (instant metadata, e.g. a memoized Time re-slice) finishes inside the grace
        // window, so the determinate bar must never appear - a flashed-then-gone bar is just noise.
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = oneBurstMetadata, initialGroupingMode = GroupingMode.Off, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(photos.size, vm.state.value.groups.size) // Off: six singles

        var flashed = false
        val watcher = launch { vm.state.collect { if (it.grouping != null) flashed = true } }

        vm.setGroupingMode(GroupingMode.Time) // instant metadata -> a sub-grace pass
        advanceUntilIdle() // the warm regroup lands before the grace-delayed bar ever arms
        assertEquals(1, vm.state.value.groups.size) // the burst landed
        watcher.cancel()

        assertTrue("a warm regroup flashed the progress bar", !flashed)
        vm.onClear()
    }

    @Test
    fun groupingOutcome_firesOnceOnAUserLensPickWithPayoffCounts() = runTest {
        // oneBurstMetadata collapses all six frames into one burst. A deliberate lens pick must announce
        // the result exactly once, with counts derived from the applied groups.
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = oneBurstMetadata, initialGroupingMode = GroupingMode.Off, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.setGroupingMode(GroupingMode.Time)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.groups.size) // the burst landed (state applied first)
        val outcome = vm.groupingOutcomes.first()

        assertEquals(GroupingMode.Time, outcome.mode)
        assertEquals(1, outcome.burstCount)
        assertEquals(photos.size, outcome.photosInBursts)
        vm.onClear()
    }

    @Test
    fun groupingOutcome_isEmptyWhenALensPickProducesNoBursts() = runTest {
        // perPhotoCameraMetadata never groups, so picking a lens yields zero stacks: the outcome still
        // fires (burstCount == 0) so the grid can explain the empty result rather than going silent.
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = perPhotoCameraMetadata, initialGroupingMode = GroupingMode.Off, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.setGroupingMode(GroupingMode.Time)
        advanceUntilIdle()
        val outcome = vm.groupingOutcomes.first()

        assertEquals(0, outcome.burstCount)
        assertEquals(0, outcome.photosInBursts)
        vm.onClear()
    }

    @Test
    fun groupingOutcome_doesNotFireOnTheSeededFirstLoadPass() = runTest {
        // A grid seeded into a lens regroups on init (announce = false): a background pass, not a user
        // pick, so it must stay silent — otherwise every folder-open would pop a summary.
        val vm = viewModel(FakeCategoriesRepository(categories), metadata = oneBurstMetadata, initialGroupingMode = GroupingMode.Time, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle() // init pass collapsed the burst
        assertEquals(1, vm.state.value.groups.size)

        // The init pass already ran; a buggy announce would have buffered to the channel, so a
        // collector started now would still see it. It must find nothing.
        var fired = false
        val watcher = launch { vm.groupingOutcomes.collect { fired = true } }
        advanceUntilIdle()
        watcher.cancel()

        assertTrue("the seeded first-load pass must not announce", !fired)
        vm.onClear()
    }

    @Test
    fun similarityCoachmark_showsOnFirstPickThenDismissPersistsAndNeverReturns() = runTest {
        var seen = 0
        val vm = viewModel(
            FakeCategoriesRepository(categories),
            hasSeenSimilarityCoachmark = false,
            onSimilarityCoachmarkSeen = { seen++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertTrue("no coachmark before the lens is picked", !vm.state.value.showSimilarityCoachmark)

        vm.setGroupingMode(GroupingMode.Similarity)
        assertTrue("coachmark shows on the first Similar pick", vm.state.value.showSimilarityCoachmark)

        vm.dismissSimilarityCoachmark()
        assertTrue("dismissing hides it", !vm.state.value.showSimilarityCoachmark)
        assertEquals("dismissal is persisted exactly once", 1, seen)

        // Re-picking Similar (after switching away) never shows it again.
        vm.setGroupingMode(GroupingMode.Off)
        vm.setGroupingMode(GroupingMode.Similarity)
        assertTrue("coachmark never returns once dismissed", !vm.state.value.showSimilarityCoachmark)
        assertEquals("no second persist call", 1, seen)
        vm.onClear()
    }

    @Test
    fun similarityCoachmark_neverShowsWhenAlreadySeen() = runTest {
        val vm = viewModel(FakeCategoriesRepository(categories), hasSeenSimilarityCoachmark = true, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.setGroupingMode(GroupingMode.Similarity)
        assertTrue("a returning user is not re-coached", !vm.state.value.showSimilarityCoachmark)
        vm.onClear()
    }

}
