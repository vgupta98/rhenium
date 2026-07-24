package com.vishalgupta.photoselector.presentation.grid

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.CategoryAnalysisState
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.presentation.StateHolder
import com.vishalgupta.photoselector.presentation.common.InsightCoordinator
import com.vishalgupta.photoselector.presentation.common.XmpSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Backs the [com.vishalgupta.photoselector.presentation.designsystem.organism.LibraryRail], which
 * is root-level chrome: it lists every scope (Favourites + custom categories with their counts) and
 * owns category create / rename / delete. Deliberately scoped to the **root**, not the grid's
 * (root, scope): the rail is the same for every scope, so the navigation host ([App]) mounts it
 * *outside* the per-scope `key(GridRetentionKey)` and it survives a category switch untouched (a
 * grid-scoped rail used to tear down and rebuild on every switch, flickering).
 *
 * [entries] mirrors what the grid derives for its tile badges because both read the *same* two
 * repository flows ([CategoriesRepository.observeCategories] / [observeMemberships]) — so a rail
 * count and a tile badge can never disagree. CRUD just forwards to the repository on [scope]; the
 * grid's own [GridViewModel] keeps observing categories independently for badges and 1..9 filing.
 *
 * It also owns the one library-level destructive action: [emptyRejectsToTrash] sweeps the whole
 * Rejects bucket to the Trash, reusing the same [MovePhotosToTrashUseCase] the grid/browser delete
 * paths use and then dropping the trashed photos from every category and the scan (via
 * [onPhotosDeleted], the same hook the per-photo deletes call).
 */
class LibraryRailViewModel(
    private val root: RootFolder,
    private val categories: CategoriesRepository,
    private val moveToTrash: MovePhotosToTrashUseCase,
    // The current scan for [root], so the rejected [PhotoId]s can be resolved to [Photo]s to trash.
    private val photosForRoot: () -> List<Photo>,
    // Live RAW XMP-sidecar sync, owned per root by the container (like the grouping coordinator). The
    // rail footer re-exposes its state + toggle. Defaulted so tests that don't exercise sync omit it.
    private val xmpSync: XmpSyncCoordinator? = null,
    // The gated insight (folder-analysis) pass, owned per process by the container. The rail's Smart-section
    // header drives it and mirrors its progress; defaulted so tests that don't exercise insights omit it.
    private val insights: InsightCoordinator? = null,
    // Drops the trashed photos from the container's scan snapshot + every retained grid, so screens
    // built or returned to after the sweep are without them. Same hook the per-photo deletes use.
    private val onPhotosDeleted: (Set<PhotoId>) -> Unit = {},
    parentJob: Job? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing,
) : StateHolder(parentJob, dispatcher) {

    /** Per-category analysis state, for the rail's tri-state count/`—` rendering on insight categories. */
    val analysisStates: StateFlow<Map<CategoryId, CategoryAnalysisState>> =
        categories.observeAnalysisStates(root)

    /** Live progress of the gated folder-analysis pass (null when idle), for the Smart header ring. */
    val insightProgress: StateFlow<InsightCoordinator.Progress?> =
        insights?.progress ?: MutableStateFlow<InsightCoordinator.Progress?>(null).asStateFlow()

    /** Kicks off the explicit whole-folder insight pass over the current scan. No-op if not wired. */
    fun analyzeFolder() {
        insights?.analyze(photosForRoot())
    }

    /** Live XMP-sync footer state (enabled + non-RAW skip count), mirrored from the coordinator. */
    val xmpSyncState: StateFlow<XmpSyncCoordinator.State> =
        xmpSync?.state ?: MutableStateFlow(XmpSyncCoordinator.State(enabled = false)).asStateFlow()

    /** Toggles live XMP sidecar sync for this root (footer switch). No-op if sync isn't wired. */
    fun toggleXmpSync() {
        xmpSync?.toggle()
    }

    // One-shot result of a reject sweep, surfaced by [App] as a transient pill.
    private val _sweepEvents = Channel<String>(Channel.BUFFERED)
    val sweepEvents: Flow<String> = _sweepEvents.receiveAsFlow()

    /** Category + member count, in display order (built-ins first), for the rail's rows. */
    val entries: StateFlow<List<Pair<Category, Int>>> =
        combine(
            categories.observeCategories(root),
            categories.observeMemberships(root),
        ) { cats, members -> cats.map { it to (members[it.id]?.size ?: 0) } }
            .stateIn(scope, SharingStarted.Eagerly, currentEntries())

    // Seed the StateFlow synchronously so the rail's first frame already has the categories the
    // repository holds (both upstreams are StateFlows with a current value), rather than flashing an
    // empty rail for a frame before the combine emits.
    private fun currentEntries(): List<Pair<Category, Int>> {
        val members = categories.observeMemberships(root).value
        return categories.observeCategories(root).value.map { it to (members[it.id]?.size ?: 0) }
    }

    fun create(name: String) {
        if (name.isBlank()) return
        scope.launch { categories.create(root, name) }
    }

    fun rename(id: CategoryId, newName: String) {
        if (newName.isBlank()) return
        scope.launch { categories.rename(root, id, newName) }
    }

    fun delete(id: CategoryId) {
        scope.launch { categories.delete(root, id) }
    }

    /**
     * Moves every photo flagged as a reject to the Trash, then empties the bucket: purges the
     * trashed ids from every category and tells the container so later/returned-to screens are
     * built without them. Best-effort — photos that couldn't be trashed stay flagged and are
     * reported in the result pill. The rail confirms first; this performs the sweep.
     */
    fun emptyRejectsToTrash() {
        scope.launch {
            val rejectIds = categories.observeMemberships(root).value[Category.REJECTS_ID].orEmpty()
            if (rejectIds.isEmpty()) return@launch
            val targets = photosForRoot().filter { it.id in rejectIds }
            if (targets.isEmpty()) {
                // The bucket references photos no longer in the scan; just clear the flags.
                categories.removeMemberships(root, rejectIds)
                return@launch
            }
            val report = try {
                moveToTrash.invoke(targets)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _sweepEvents.trySend("Couldn't empty rejects: ${t.message}")
                return@launch
            }
            val trashedIds = targets
                .filter { p -> report.failed.none { it.first.id == p.id } }
                .mapTo(HashSet()) { it.id }
            if (trashedIds.isNotEmpty()) {
                categories.removeMemberships(root, trashedIds)
                onPhotosDeleted(trashedIds)
            }
            _sweepEvents.trySend(sweepToast(report.trashed, report.failed.size))
        }
    }

    private fun sweepToast(trashed: Int, failed: Int): String = when {
        failed == 0 && trashed == 1 -> "Moved 1 reject to Trash"
        failed == 0 -> "Moved $trashed rejects to Trash"
        trashed == 0 -> "Couldn't move $failed rejects to Trash"
        else -> "Moved $trashed, $failed failed"
    }
}
