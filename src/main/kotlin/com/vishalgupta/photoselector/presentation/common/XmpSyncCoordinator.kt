package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.XmpSyncPreferences
import com.vishalgupta.photoselector.domain.usecase.ExportPhotosXmpUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps RAW XMP sidecars continuously in step with the cull's Favourites / Rejects while the user has
 * turned sync **on** for this root. Mirrors [GroupingCoordinator]: a root-scoped, longer-lived owner of
 * one background job, parented to the folder job with [reset] on a root change, exposing one stable
 * [state] flow the rail footer reads. All the file IO lives behind the [ExportPhotosXmpUseCase] seam
 * (the same use case the manual Export menu drives) and persistence behind [XmpSyncPreferences], so the
 * coordinator itself touches no disk.
 *
 * Two write paths, both through the exporter:
 *  - **Enable** runs a FULL whole-root reconcile over *every* photo in the root ([photosForRoot]) —
 *    not the current grid scope — writing Rating 5 for favourites, -1 for rejects, and clearing our own
 *    stamped rating on RAW photos now in neither bucket. The full walk is a hard invariant: it is what
 *    lets a photo un-favourited *while sync was off* have its stale sidecar rating cleared on re-enable,
 *    because the reconcile visits it even though its membership didn't change this session.
 *  - **While enabled**, each membership change diffs current vs last-seen verdicts to the set of changed
 *    [PhotoId]s and feeds just those to the exporter (a delta write), regardless of which grid scope is
 *    open.
 *
 * Authority is app-wins: the exporter's [com.vishalgupta.photoselector.data.export.XmpDocument.merge]
 * overwrites a head-on rating conflict but leaves a rating on a photo we have no verdict for, and its
 * guarded clear only fires when the on-disk rating still equals our ownership stamp — so a foreign edit
 * made while sync was off is never clobbered. The coordinator relies on that; it never reimplements it.
 *
 * Thread-safety: [setEnabled] / [toggle] / [reset] mutate the job under [lock] and are safe from any
 * thread (in the app they arrive on the EDT). The sync body runs on the injected dispatcher.
 */
class XmpSyncCoordinator(
    private val root: RootFolder,
    private val categories: CategoriesRepository,
    private val exportXmp: ExportPhotosXmpUseCase,
    private val preferences: XmpSyncPreferences,
    private val photosForRoot: () -> List<Photo>,
    parentJob: Job?,
    dispatcher: CoroutineDispatcher,
) {
    /**
     * What the footer renders: whether sync is [enabled] and, once a reconcile has run, how many
     * non-RAW photos in the root are [skippedNonRaw] (they carry no sidecar — Phase 1 is RAW-only).
     */
    data class State(val enabled: Boolean, val skippedNonRaw: Int = 0)

    private val scope = CoroutineScope(SupervisorJob(parentJob) + dispatcher)

    private val _state = MutableStateFlow(State(enabled = preferences.isEnabled(root)))
    val state: StateFlow<State> = _state.asStateFlow()

    // Guards [syncJob]. Cold (toggled by hand), so effectively uncontended; here so the class is
    // genuinely thread-safe rather than correct only because every caller happens to be on the EDT.
    private val lock = Any()
    // The in-flight reconcile+observe job while enabled; cancelled on disable / reset. Null while off.
    private var syncJob: Job? = null

    init {
        // Remembered across sessions: if this root was left with sync on, resume it (which re-runs the
        // full reconcile) as soon as the coordinator is built for the root.
        if (_state.value.enabled) start()
    }

    /** Flips the toggle and persists it. On -> full reconcile + live sync; off -> stop, sidecars left. */
    fun toggle() = setEnabled(!_state.value.enabled)

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (enabled == _state.value.enabled && (syncJob != null) == enabled) return
            preferences.setEnabled(root, enabled)
            _state.update { it.copy(enabled = enabled) }
            if (enabled) start() else stop()
        }
    }

    /** Drops any in-flight sync and clears state — called on a root change (like [GroupingCoordinator.reset]). */
    fun reset() {
        synchronized(lock) {
            stop()
            _state.update { State(enabled = false, skippedNonRaw = 0) }
        }
    }

    // Must be called under [lock]. Launches the reconcile-then-observe job on the coordinator's scope.
    private fun start() {
        syncJob?.cancel()
        syncJob = scope.launch {
            val memberFlow = categories.observeMemberships(root)
            // Snapshot the verdicts the reconcile is about to write, so the observe loop below diffs
            // against them: the StateFlow re-emits this same value first, yielding an empty delta.
            var lastSeen = memberFlow.value

            // Full whole-root reconcile: write over EVERY root photo, not just the changed set.
            val all = photosForRoot()
            val report = exportXmp(
                root = root,
                photos = all,
                favouriteIds = lastSeen[Category.FAVOURITES_ID].orEmpty(),
                rejectedIds = lastSeen[Category.REJECTS_ID].orEmpty(),
            )
            _state.update { it.copy(skippedNonRaw = report.unsupported) }

            // Live delta writes: on each membership change, feed only the photos whose favourite/reject
            // verdict flipped. Reacts regardless of the open grid scope (this observes the root's flow).
            memberFlow.collect { members ->
                val changed = changedVerdicts(lastSeen, members)
                lastSeen = members
                if (changed.isEmpty()) return@collect
                val targets = photosForRoot().filter { it.id in changed }
                if (targets.isEmpty()) return@collect
                exportXmp(
                    root = root,
                    photos = targets,
                    favouriteIds = members[Category.FAVOURITES_ID].orEmpty(),
                    rejectedIds = members[Category.REJECTS_ID].orEmpty(),
                )
            }
        }
    }

    // Must be called under [lock].
    private fun stop() {
        syncJob?.cancel()
        syncJob = null
    }

    private companion object {
        /**
         * The ids whose sidecar-affecting verdict changed between two membership snapshots: a photo
         * whose Favourites *or* Rejects membership flipped. Custom-category churn is ignored — only the
         * two built-in buckets drive a sidecar rating. Symmetric difference of each bucket, unioned.
         */
        fun changedVerdicts(
            old: Map<CategoryId, Set<PhotoId>>,
            new: Map<CategoryId, Set<PhotoId>>,
        ): Set<PhotoId> = buildSet {
            addAll(symmetricDiff(old[Category.FAVOURITES_ID].orEmpty(), new[Category.FAVOURITES_ID].orEmpty()))
            addAll(symmetricDiff(old[Category.REJECTS_ID].orEmpty(), new[Category.REJECTS_ID].orEmpty()))
        }

        fun symmetricDiff(a: Set<PhotoId>, b: Set<PhotoId>): Set<PhotoId> = (a - b) + (b - a)
    }
}
