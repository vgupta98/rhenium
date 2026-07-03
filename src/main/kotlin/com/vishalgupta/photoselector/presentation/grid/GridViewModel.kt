package com.vishalgupta.photoselector.presentation.grid

import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.grouping.CaptureMetadataSource
import com.vishalgupta.photoselector.domain.grouping.PhotoGrouper
import com.vishalgupta.photoselector.domain.grouping.burstGrouper
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.ConflictPolicy
import com.vishalgupta.photoselector.domain.repository.CopyReport
import com.vishalgupta.photoselector.domain.repository.TrashReport
import com.vishalgupta.photoselector.domain.usecase.CopyPhotosToFolderUseCase
import com.vishalgupta.photoselector.domain.usecase.ExportPhotosTxtUseCase
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.presentation.StateHolder
import com.vishalgupta.photoselector.presentation.common.CategoryToggle
import com.vishalgupta.photoselector.presentation.common.GroupingCoordinator
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.common.customCategories
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import com.vishalgupta.photoselector.presentation.navigation.activeCategoryId
import com.vishalgupta.photoselector.presentation.navigation.slice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.nio.file.Path

data class GridUiState(
    val photos: List<Photo> = emptyList(),
    // The grid's tiles: bursts collapse into one [PhotoGroup.Burst] tile, everything else is a
    // [PhotoGroup.Single]. Derived from [photos] off-thread (EXIF reads); until grouping resolves
    // it mirrors [photos] one-to-one as singles. [photos] stays the flat source of truth for
    // selection -> index mapping and browser/Compare/Survey navigation.
    val groups: List<PhotoGroup> = emptyList(),
    val scope: CategoryScope = CategoryScope.AllPhotos,
    val categories: List<Category> = emptyList(),
    val memberships: Map<CategoryId, Set<PhotoId>> = emptyMap(),
    val lastViewedPhotoId: PhotoId? = null,
    val focusedIndex: TileIndex = TileIndex.NONE,
    // Transient multi-select: the tiles the mouse (Cmd/Shift-click, Cmd+A) has picked out for a
    // bulk action. Never persisted; cleared on Esc, scope change, or screen exit. [anchorIndex]
    // is the pivot a Shift-click range extends from.
    val selection: Set<PhotoId> = emptySet(),
    val anchorIndex: TileIndex? = null,
    // Which grouping lens collapses tiles: Off (one tile per photo), Time (bursts, the default),
    // or Similarity (visual). In-memory per grid instance (not persisted). The grid toolbar picks
    // one of the three.
    val groupingMode: GroupingMode = GroupingMode.Time,
    // The burst (by tile id) currently expanded in place, or null. Clicking a burst tile unfolds it
    // into its frames inline; at most one is open at a time. Pure presentation state.
    val expandedBurstId: PhotoId? = null,
    // True while the first-run Similarity coachmark should show (set when the user first picks the
    // Similarity lens; cleared on dismiss or switching away). Pure presentation state.
    val showSimilarityCoachmark: Boolean = false,
    val isBusy: Boolean = false,
    val progressLabel: String? = null,
    // Live progress of an in-flight *displayed-lens* regroup (the Time pass), or null when none is
    // running. Drives a non-blocking progress bar — unlike [isBusy], it does not lock the toolbar, so
    // the user can keep scrolling while the model works.
    val grouping: GroupingStatus? = null,
    // Live progress of the background Similarity pass (owned by the GroupingCoordinator, not this
    // grid's display lens), or null when none is running. Distinct from [grouping] because the pass
    // keeps running — and keeps reporting — even while the grid shows a different lens, so it drives
    // the always-visible "still grouping" cues (the determinate ring on the Similar tab here, and the
    // off-grid chip in the navigation host) regardless of [groupingMode].
    val similarityProgress: GroupingStatus? = null,
) {
    /** True when any grouping lens is active — drives the off-thread regroup and tile collapse. */
    val groupBursts: Boolean get() = groupingMode != GroupingMode.Off

    /** Photos in the built-in Favourites — what the tile star indicates, in any scope. */
    val markedIds: Set<PhotoId> get() = memberships[Category.FAVOURITES_ID].orEmpty()

    /** Photos in the built-in Rejects — what the tile's reject flag indicates, in any scope. */
    val rejectedIds: Set<PhotoId> get() = memberships[Category.REJECTS_ID].orEmpty()

    /** True while a multi-select is active — drives the top-bar swap and bulk key routing. */
    val hasSelection: Boolean get() = selection.isNotEmpty()

    /**
     * The tiles focus/selection/filing act on: [groups] with the expanded burst (if any) exploded
     * into one tile per frame. A collapsed burst is one tile here (files all its frames); an open
     * burst's frames are individual tiles (file just that frame). The grid renders the same space.
     */
    val displayGroups: List<PhotoGroup> get() = displayGroupsFor(groups, expandedBurstId)
}

/** Progress of an in-flight regroup: [processed] of [total] photos handled so far. */
data class GroupingStatus(val processed: Int, val total: Int)

/**
 * The result of a completed grouping pass, emitted once per *user lens pick* (not per background
 * re-slice) so the grid can announce the payoff — "$photosInBursts photos → $burstCount stacks" — or,
 * when nothing collapsed ([burstCount] == 0), explain the empty result instead of a silently flat grid.
 * Counts are derived from the applied groups; nothing here is persisted.
 */
data class GroupingOutcome(
    val mode: GroupingMode,
    val burstCount: Int,
    val photosInBursts: Int,
)

// How long a regroup must run before its progress bar appears. A warm pass (memoized Time metadata)
// finishes well inside this window and shows nothing; a cold Similarity pass blows past it and surfaces
// the bar. Tuned to swallow sub-perceptible flicker without delaying a genuinely long pass noticeably.
internal const val GROUPING_BAR_GRACE_MS = 200L

class GridViewModel(
    private val root: RootFolder,
    // Mutable: a delete drops the trashed photos here so the All Photos slice (which ignores
    // membership) stops showing them without waiting for a rescan.
    private var allPhotos: List<Photo>,
    private val categoryScope: CategoryScope,
    lastViewedPhotoId: PhotoId? = null,
    private val categories: CategoriesRepository,
    private val exportTxt: ExportPhotosTxtUseCase,
    private val copyToFolder: CopyPhotosToFolderUseCase,
    private val moveToTrash: MovePhotosToTrashUseCase,
    val imageLoader: ImageLoader,
    // Reads capture time + camera per photo for burst grouping; memoized across re-groups. Backs
    // the [GroupingMode.Time] lens.
    private val captureMetadataSource: CaptureMetadataSource,
    // Owns the background [GroupingMode.Similarity] pass, decoupled from this grid's displayed lens so
    // it survives a lens switch or navigation. Null when no embedding model is wired (e.g. tests, or a
    // platform without one): the Similarity option then degrades to ungrouped singles.
    private val groupingCoordinator: GroupingCoordinator? = null,
    // The lens this grid opens in. A grid is retained across navigation, so its lens already survives
    // a Grid -> Browser -> Grid round trip; this seed (the session's last choice) only sets the lens
    // for a *freshly built* grid - the first visit to a given (root, scope).
    initialGroupingMode: GroupingMode = GroupingMode.Time,
    // The first-run Similarity coachmark shows the first time the user actively picks the Similarity
    // lens, unless already dismissed. Defaults to "seen" so tests and other callers never trigger it.
    hasSeenSimilarityCoachmark: Boolean = true,
    private val onSimilarityCoachmarkSeen: () -> Unit = {},
    parentJob: Job? = null,
    // The scope (UI) dispatcher - the Swing EDT in the running app; tests inject a TestDispatcher to
    // drive the scope's coroutines deterministically. See StateHolder.
    dispatcher: CoroutineDispatcher = Dispatchers.Swing,
    // Where the off-thread regroup runs (EXIF reads for Time, decode + embedding for Similarity) -
    // IO in the app, kept off the scope's EDT. Tests pass the same TestDispatcher as [dispatcher] so
    // the pass runs in virtual time instead of on a real background thread (which is what made the
    // grid's grouping tests flaky under CI load).
    private val groupingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Flat photo index of the resume point. Crosses to the container (which persists it), so it stays a
    // plain Int on the wire; the grid converts at this single seam.
    private val onScrollIndexChanged: ((Int) -> Unit)? = null,
    // Reports a lens change up to the container so the next rebuilt grid opens in the same lens.
    private val onGroupingModeChanged: ((GroupingMode) -> Unit)? = null,
    // Lets the container drop the trashed photos from its scan snapshot, so any screen opened
    // after the delete is built without them too.
    private val onPhotosDeleted: ((Set<PhotoId>) -> Unit)? = null,
) : StateHolder(parentJob, dispatcher) {

    private val _state = MutableStateFlow(
        GridUiState(scope = categoryScope, lastViewedPhotoId = lastViewedPhotoId, groupingMode = initialGroupingMode),
    )
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    // One-shot confirmation that a keyboard toggle landed — the persistent star/badge on the
    // tile shows the resulting state, this names the action. Same event the browser emits.
    private val _toggleEvents = Channel<CategoryToggle>(Channel.BUFFERED)
    val toggleEvents: Flow<CategoryToggle> = _toggleEvents.receiveAsFlow()

    // Fires once when a user-initiated lens pick (toolbar or the G key) finishes grouping, carrying the
    // payoff counts (or burstCount == 0 for the empty-result notice). Deliberately NOT fired on a
    // membership re-slice, delete, or the seeded first-load pass — those regroup with announce = false —
    // so the summary never spams on background reshapes.
    private val _groupingOutcomes = Channel<GroupingOutcome>(Channel.BUFFERED)
    val groupingOutcomes: Flow<GroupingOutcome> = _groupingOutcomes.receiveAsFlow()

    // Result of a completed bulk/library action (export, copy, bulk file, delete) — a consume-once
    // one-shot, same handoff shape as [toggleEvents]/[groupingOutcomes] and the browser's delete
    // events. Modelled as a channel (not persistent state) so navigating away mid-toast can't cancel
    // its dismissal and leave a stale message to resurrect on return. The grid VM is retained per
    // scope, so a message emitted while the user is on another scope buffers and fires once on return
    // — a fresh result surfacing, not a resurrected one.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var scrollSaveJob: Job? = null
    private var lastKnownIndex: FlatIndex? = null

    // The id-list the current [groups] were computed for; guards against re-grouping (and the
    // singles flicker) when a membership change re-slices to the same set of photos.
    private var lastGroupedIds: List<PhotoId>? = null
    // The inline (Time) regroup pass. Cancelled freely on a re-slice or lens switch — it is cheap and
    // bound to the displayed lens.
    private var groupingJob: Job? = null
    // The job that *applies* the background Similarity result to this grid's display (and announces it).
    // Cancelled when the grid stops showing Similarity — but that only stops the display wiring, never
    // the pass itself, which the GroupingCoordinator owns and runs to completion regardless.
    private var similarityApplyJob: Job? = null
    // Generation of the background pass this grid last requested (captured from the coordinator). The
    // progress collector shows this grid's "still grouping" cues only while the coordinator's *active*
    // generation still matches — i.e. the one running pass is grouping this grid's slice, not another
    // scope's that has since superseded it. Null until this grid first requests Similarity.
    private var similarityPassGeneration: Int? = null

    // Mirrors [GridUiState.groupingMode]; [GroupingMode.Off] keeps one tile per photo and skips the
    // off-thread regroup entirely. The grouper for a mode comes from [grouperFor].
    private var groupingMode: GroupingMode = initialGroupingMode

    // Bumped on every regroup so a cancelled pass's late progress callback can't overwrite the
    // current pass's progress (cooperative cancellation lets one more callback slip through).
    private var groupingGeneration = 0

    // True until the first-run Similarity coachmark is dismissed; flips the state flag on when the user
    // first picks the Similarity lens. Persisted once via [onSimilarityCoachmarkSeen] on dismissal.
    private var similarityCoachmarkPending = !hasSeenSimilarityCoachmark

    /**
     * The inline grouper backing [mode], or null for the modes that don't run one inline: [Off] (one
     * tile per photo) and [Similarity] (run off the display lens by the [GroupingCoordinator], not
     * here). Only [Time] groups synchronously on the grid's own scope.
     */
    private fun grouperFor(mode: GroupingMode): PhotoGrouper? = when (mode) {
        GroupingMode.Off -> null
        GroupingMode.Time -> timeGrouper
        GroupingMode.Similarity -> null
    }

    private val timeGrouper: PhotoGrouper = burstGrouper(captureMetadataSource)

    init {
        combine(
            categories.observeCategories(root),
            categories.observeMemberships(root),
        ) { cats, members -> cats to members }
            .onEach { (cats, members) ->
                val photos = slicedPhotos(members)
                val validIds = photos.mapTo(HashSet()) { it.id }
                val ids = photos.map { it.id }
                val sliceChanged = ids != lastGroupedIds
                _state.update {
                    val anchorId = it.focusedAnchorId()
                    val newGroups = if (sliceChanged) photos.map(PhotoGroup::Single) else it.groups
                    // A new slice has different tiles, so any expanded burst no longer applies.
                    val newExpanded = if (sliceChanged) null else it.expandedBurstId
                    it.copy(
                        photos = photos,
                        // Show tiles immediately as ungrouped singles when the slice changes;
                        // regroup() refines them off-thread. An unchanged slice keeps its groups.
                        groups = newGroups,
                        expandedBurstId = newExpanded,
                        categories = cats,
                        memberships = members,
                        // Keep focus on the same frame across the reshape rather than clamping a bare
                        // tile index onto whatever now sits there.
                        focusedIndex = refocus(displayGroupsFor(newGroups, newExpanded), anchorId, it.focusedIndex),
                        // Drop any selected tiles the new slice no longer contains, so a bulk
                        // action can never touch a photo that has scrolled out of scope.
                        selection = if (it.selection.isEmpty()) it.selection else it.selection.intersect(validIds),
                    )
                }
                // Only collapse when a lens is active; otherwise the singles set above stands and we
                // record the slice so a membership re-slice doesn't needlessly re-run.
                if (sliceChanged) triggerGrouping(groupingMode, photos, ids)
            }
            .launchIn(scope)

        // Mirror the coordinator's background Similarity progress into state, but only while the running
        // pass is grouping *this* grid's slice — i.e. the coordinator's active generation still matches
        // the one we captured when this grid requested it. The coordinator runs a single global pass, so
        // without this gate every retained grid would mirror it and light a determinate ring on the
        // Similar tab of a scope whose Similarity would group an entirely different set: a precise
        // percentage that is wrong for that tab. This field drives every "still grouping" cue for this
        // grid — the Similar-tab ring (in any lens) and the banner (when Similarity is shown); the
        // off-grid chip is fed separately and globally by the host. The coordinator already applies the
        // grace window, so a warm (cache-hit) pass never flashes here. [grouping] stays untouched: it is
        // the inline Time bar's alone. Comparing generations is O(1), so it is safe per progress tick.
        val coordinator = groupingCoordinator
        if (coordinator != null) {
            coordinator.progress
                .onEach { progress ->
                    val status = progress
                        ?.takeIf { coordinator.activeGeneration == similarityPassGeneration }
                        ?.let { GroupingStatus(it.processed, it.total) }
                    _state.update { it.copy(similarityProgress = status) }
                }
                .launchIn(scope)
        }
    }

    /**
     * Picks the grouping lens for this grid. [GroupingMode.Off] drops straight back to one tile per
     * photo; a lens shows singles immediately and refines groups off-thread, exactly like a re-slice.
     * Switching lens cancels any in-flight regroup so a stale result can't land over the new mode.
     */
    fun setGroupingMode(mode: GroupingMode) {
        if (mode == groupingMode) return
        groupingMode = mode
        onGroupingModeChanged?.invoke(mode)
        val photos = _state.value.photos
        val ids = photos.map { it.id }
        val singles = photos.map(PhotoGroup::Single)
        _state.update {
            it.copy(
                groupingMode = mode,
                groups = singles,
                expandedBurstId = null,
                // The tile space renumbers, so a stored Shift+click anchor (a tile index) is stale.
                anchorIndex = null,
                // Switching lens collapses bursts back to singles (or vice-versa) under the cursor;
                // keep focus on the same frame rather than on whatever tile inherits the old index.
                focusedIndex = refocus(singles, it.focusedAnchorId(), it.focusedIndex),
                // Reset the inline Time bar; the Similarity banner reads [similarityProgress] (the live
                // background pass) directly, so arriving on Similarity mid-pass shows it without seeding.
                grouping = null,
                // Show the one-time coachmark the first time Similarity is actively picked; switching to
                // any other lens hides it (it never resurfaces once dismissed — pending guards that).
                showSimilarityCoachmark = mode == GroupingMode.Similarity && similarityCoachmarkPending,
            )
        }
        // announce = true: this is the deliberate lens pick, the one pass whose result the grid
        // surfaces (a summary, or the empty-result notice). Background re-slices stay silent.
        triggerGrouping(mode, photos, ids, announce = true)
    }

    /**
     * Routes a (re)grouping for [mode] to the right machinery, cancelling whatever the *previous* lens
     * was displaying. [Time] regroups inline on this grid's scope; [Similarity] delegates to the
     * [GroupingCoordinator] so the pass outlives the displayed lens; [Off] just clears the bar. The
     * key invariant: cancelling [similarityApplyJob] here stops only the *display* wiring — the
     * coordinator's background pass keeps running and caching, so a lens switch never wastes it.
     */
    private fun triggerGrouping(mode: GroupingMode, photos: List<Photo>, ids: List<PhotoId>, announce: Boolean = false) {
        groupingJob?.cancel()
        similarityApplyJob?.cancel()
        when (mode) {
            GroupingMode.Off -> {
                groupingGeneration++
                lastGroupedIds = ids
                _state.update { it.copy(grouping = null) }
            }
            GroupingMode.Time -> regroup(photos, ids, mode, announce)
            GroupingMode.Similarity -> requestBackgroundGrouping(photos, ids, announce)
        }
    }

    /**
     * Hands the Similarity pass to the [GroupingCoordinator] and wires its result into *this* grid's
     * display. The pass is started (or re-attached to) immediately, in the coordinator's own scope, so
     * it is unaffected by this job's lifetime; [similarityApplyJob] only awaits it to apply the groups
     * and announce. A null coordinator (no model wired) degrades Similarity to the singles already shown.
     */
    private fun requestBackgroundGrouping(photos: List<Photo>, ids: List<PhotoId>, announce: Boolean) {
        lastGroupedIds = ids
        val coordinator = groupingCoordinator ?: run {
            _state.update { it.copy(grouping = null) }
            return
        }
        val deferred = coordinator.groupingFor(photos)
        // Capture which pass is ours (same thread, right after the request) so the progress collector can
        // tell this grid's pass from one a different scope later supersedes.
        similarityPassGeneration = coordinator.activeGeneration
        similarityApplyJob = scope.launch {
            // The display bar is owned by the coordinator-progress collector (it clears on completion),
            // so the apply must not also null it out — pass clearDisplayBar = false.
            applyGroupedResult(deferred.await(), ids, GroupingMode.Similarity, announce, clearDisplayBar = false)
        }
    }

    /** Off <-> Time convenience for callers (and tests) that just want grouping flipped on/off. */
    fun toggleGroupBursts() {
        setGroupingMode(if (groupingMode == GroupingMode.Off) GroupingMode.Time else GroupingMode.Off)
    }

    /** Dismisses the first-run Similarity coachmark and persists the dismissal so it never returns. */
    fun dismissSimilarityCoachmark() {
        if (similarityCoachmarkPending) {
            similarityCoachmarkPending = false
            onSimilarityCoachmarkSeen()
        }
        _state.update { if (it.showSimilarityCoachmark) it.copy(showSimilarityCoachmark = false) else it }
    }

    /**
     * Recomputes [groups] off-thread (EXIF reads for Time; decode + embedding for Similarity) using
     * [mode]'s grouper, and applies them only if the slice still matches what was grouped and the
     * lens is still [mode]. [ids] is the slice fingerprint captured by the caller.
     */
    private fun regroup(photos: List<Photo>, ids: List<PhotoId>, mode: GroupingMode, announce: Boolean = false) {
        lastGroupedIds = ids
        groupingJob?.cancel()
        val grouper = grouperFor(mode) ?: run {
            _state.update { it.copy(grouping = null) }
            return
        }
        val generation = ++groupingGeneration
        // Clear any stale bar from a just-cancelled pass, but do NOT seed a fresh one synchronously:
        // a warm pass (memoized Time metadata on a re-slice) finishes within a frame, so an eagerly
        // seeded bar would flash on and off as noise. The bar is armed after a grace delay instead.
        _state.update { it.copy(grouping = null) }
        val total = photos.size
        groupingJob = scope.launch(groupingDispatcher) {
            // Only surface the bar once a pass has run longer than the grace window - long enough that
            // a cold Similarity pass (or a first Time pass over a big folder) shows it, while a warm
            // regroup completes and cancels this before it ever arms.
            val barJob = launch {
                if (total > 0) {
                    delay(GROUPING_BAR_GRACE_MS)
                    _state.update { if (generation == groupingGeneration) it.copy(grouping = GroupingStatus(0, total)) else it }
                }
            }
            // Coalesce per-photo callbacks to whole-percent ticks: ~100 state updates over a minute,
            // not one per photo, while the count still reads as live. The Similarity pass now extracts
            // features in parallel, so this callback arrives concurrently from several threads and out
            // of order — a straggler can report a lower count than one already shown. Do the coalesce +
            // monotonic guard *inside* the atomic update, comparing against the percent currently on the
            // bar, so it can only ever move forward and never races a plain `var`.
            val groups = grouper.group(photos) { processed, t ->
                val percent = if (t <= 0) 100 else processed * 100 / t
                // Refresh only once the grace-delayed bar is actually showing; before then the pass is
                // still inside the grace window and must stay invisible.
                _state.update { st ->
                    val current = st.grouping
                    if (generation != groupingGeneration || current == null) return@update st
                    val currentPercent = if (current.total <= 0) 100 else current.processed * 100 / current.total
                    if (percent <= currentPercent) return@update st
                    st.copy(grouping = GroupingStatus(processed, t))
                }
            }
            // Stop the timer before clearing, so a bar can't arm after the pass has already finished.
            barJob.cancelAndJoin()
            // The Time bar is owned by this pass, so clear it as the groups land (clearDisplayBar).
            applyGroupedResult(groups, ids, mode, announce, clearDisplayBar = true)
        }
    }

    /**
     * Applies a completed grouping pass's [groups] to the live state and announces it — shared by the
     * inline Time [regroup] and the background-Similarity apply ([requestBackgroundGrouping]).
     *
     * It bails if the slice moved under it, OR the lens changed while the pass ran: cancellation is
     * cooperative, so a pass that finished computing just as the user switched modes would otherwise
     * re-apply its groups over a toolbar that now reads a different lens. Leaving the state untouched
     * keeps whatever the newer pass set. [clearDisplayBar] nulls the displayed-lens bar as the groups
     * land — true for the Time pass, which owns its bar; false for Similarity, whose bar is owned by
     * the coordinator-progress collector. Announcing is gated to a deliberate lens pick that actually
     * applied ([announce] && applied), so a stale or background pass stays silent.
     */
    private fun applyGroupedResult(
        groups: List<PhotoGroup>,
        ids: List<PhotoId>,
        mode: GroupingMode,
        announce: Boolean,
        clearDisplayBar: Boolean,
    ) {
        // Whether this pass's groups became the live state — set inside the update so it tracks the
        // bail-vs-apply decision exactly (a referential check would miss the all-singles case, where
        // StateFlow dedups the value-equal result and keeps the prior reference).
        var applied = false
        _state.update { st ->
            if (st.photos.map { it.id } != ids || groupingMode != mode) {
                applied = false
                st
            } else {
                applied = true
                val anchorId = st.focusedAnchorId()
                // Keep an expanded burst open only if it survived the re-group as a burst.
                val stillExpanded = st.expandedBurstId
                    ?.takeIf { id -> groups.any { it is PhotoGroup.Burst && it.groupId == id } }
                val display = displayGroupsFor(groups, stillExpanded)
                // Only when this async pass actually renumbers the tile space (singles -> collapsed
                // bursts) is a stored Shift+click anchor (a tile index) stale. If the pass yields the
                // same tiles - the common all-singles case - keep the anchor, or a mid-grouping
                // Cmd+click would lose its pivot. Matches the synchronous reshapes, which null the
                // anchor precisely because they always change the tile shape.
                val reshaped = groups != st.groups || stillExpanded != st.expandedBurstId
                st.copy(
                    groups = groups,
                    expandedBurstId = stillExpanded,
                    anchorIndex = if (reshaped) null else st.anchorIndex,
                    // Re-find the focused frame: the tiles just renumbered (singles -> bursts).
                    focusedIndex = refocus(display, anchorId, st.focusedIndex),
                    grouping = if (clearDisplayBar) null else st.grouping,
                )
            }
        }
        // Announce the result only for a deliberate lens pick whose groups were actually applied
        // (a stale pass that bailed must stay silent). Counts come from the applied groups.
        if (announce && applied) {
            val burstCount = groups.count { it is PhotoGroup.Burst }
            val photosInBursts = groups.sumOf { (it as? PhotoGroup.Burst)?.photos?.size ?: 0 }
            _groupingOutcomes.trySend(
                GroupingOutcome(mode = mode, burstCount = burstCount, photosInBursts = photosInBursts),
            )
        }
    }

    /** The scope's visible photos for the current [allPhotos] and [members] — the single slice rule. */
    private fun slicedPhotos(members: Map<CategoryId, Set<PhotoId>>): List<Photo> =
        categoryScope.slice(allPhotos, members[categoryScope.activeCategoryId].orEmpty())

    fun onFirstVisibleItemChanged(index: FlatIndex) {
        if (categoryScope != CategoryScope.AllPhotos) return
        lastKnownIndex = index
        onScrollIndexChanged?.let { save ->
            scrollSaveJob?.cancel()
            scrollSaveJob = scope.launch {
                delay(500)
                save(index.value)
                lastKnownIndex = null
            }
        }
    }

    override fun onClear() {
        val pending = lastKnownIndex
        if (pending != null) {
            onScrollIndexChanged?.invoke(pending.value)
        }
        super.onClear()
    }

    fun setFocusedIndex(index: TileIndex) {
        _state.update { it.copy(focusedIndex = it.clampTile(index)) }
    }

    /**
     * Updates the last-viewed underline marker. A Grid -> Browser -> Grid round trip now reuses this
     * retained view model rather than rebuilding it, so the marker would otherwise stay on whatever
     * photo the grid was first built around; this re-points it at the frame the browser left on.
     *
     * Also re-seats the keyboard ring onto that frame so a mouse-driven open moves the cursor the way an
     * arrow does - keeping ring, scroll and marker on the same photo when the user mixes trackpad and
     * keyboard. Only an *existing* ring is moved (a pure-mouse user has `focusedIndex == -1`, and we
     * never spawn a ring); it re-finds the frame by identity via [refocus], so the cursor lands on
     * whatever tile now holds it (and stays put if that photo is outside the current slice).
     */
    fun setLastViewed(id: PhotoId?) {
        if (id == null) return
        _state.update { st ->
            st.copy(
                lastViewedPhotoId = id,
                focusedIndex = if (st.focusedIndex.isSet) refocus(st.displayGroups, id, st.focusedIndex) else st.focusedIndex,
            )
        }
    }

    /**
     * Seats the keyboard ring on [id]'s tile — the deliberate "Show in All Photos" jump, which lands the
     * cursor on the revealed photo so it's unmistakable among thousands (the scroll-into-view itself is the
     * grid's job, via `Screen.Grid.revealPhotoId`). Unlike [setLastViewed] this *spawns* a ring where none
     * existed, which is why only the explicit jump calls it; a passive same-scope resume must not. No-op if
     * the photo isn't in the current slice.
     */
    fun focusPhoto(id: PhotoId?) {
        if (id == null) return
        _state.update { st ->
            val idx = st.displayGroups.indexOfFirst { group -> group.photos.any { it.id == id } }
            if (idx >= 0) st.copy(focusedIndex = TileIndex(idx)) else st
        }
    }

    /** The frame the focused tile currently represents, the anchor [refocus] re-finds after a reshape. */
    private fun GridUiState.focusedAnchorId(): PhotoId? =
        displayGroups.getOrNull(focusedIndex)?.keyPhoto?.id

    /** Clamp a tile index into the focusable range for these display tiles ([TileIndex.NONE] = no focus). */
    private fun GridUiState.clampTile(index: TileIndex): TileIndex =
        TileIndex(index.value.coerceIn(-1, (displayGroups.size - 1).coerceAtLeast(-1)))

    /**
     * The tile index in [display] that still represents [anchorId]'s frame, or [fallback] clamped to
     * bounds when that frame is gone. A regroup or lens switch renumbers tiles *under the cursor*
     * (singles collapse into fewer burst tiles, and back), so re-finding the focused frame by identity
     * keeps the cursor on the photo the user was looking at — clamping a bare index instead would
     * silently slide focus onto a different burst, which made Enter expand the wrong tile.
     */
    private fun refocus(display: List<PhotoGroup>, anchorId: PhotoId?, fallback: TileIndex): TileIndex {
        if (anchorId != null) {
            val i = display.indexOfFirst { group -> group.photos.any { it.id == anchorId } }
            if (i >= 0) return TileIndex(i)
        }
        return TileIndex(fallback.value.coerceIn(-1, (display.size - 1).coerceAtLeast(-1)))
    }

    /**
     * Clicking / Enter on a collapsed burst tile: unfold it in place into its frames (or fold it
     * back if it is already open). At most one burst is expanded at a time. Focus tracks the burst:
     * on expand it lands on the suggested keeper - the frame [PhotoGroup.Burst.keyIndex] points at,
     * which is the sharpest frame under the Similarity lens and the neutral middle otherwise - so the
     * keyboard cull starts on the AI's pick and `F`/`Space` files it immediately (arrowing away
     * overrides it; the suggestion never forces anything). On fold-back it returns to the collapsed
     * burst tile. (The collapsed lookup is by [PhotoGroup.groupId] - the first frame's id - while the
     * expanded lookup is by the keyer frame's identity, since each open frame is its own tile.)
     */
    fun toggleBurstExpansion(groupId: PhotoId) {
        _state.update { st ->
            val open = if (st.expandedBurstId == groupId) null else groupId
            val display = displayGroupsFor(st.groups, open)
            val burst = st.groups.firstOrNull { it.groupId == groupId } as? PhotoGroup.Burst
            val match = if (open != null && burst != null) {
                display.indexOfFirst { it.keyPhoto.id == burst.keyPhoto.id }
            } else {
                display.indexOfFirst { it.groupId == groupId }
            }
            val focus = match.takeIf { it >= 0 }?.let(::TileIndex) ?: st.focusedIndex
            // Expanding/folding renumbers the tile space, so any stored Shift+click anchor is stale.
            st.copy(
                expandedBurstId = open,
                anchorIndex = null,
                focusedIndex = TileIndex(focus.value.coerceIn(-1, (display.size - 1).coerceAtLeast(-1))),
            )
        }
    }

    /**
     * Esc (after clearing any selection): fold the open burst back into one tile, returning focus to
     * that burst tile so the cursor lands where the user opened from rather than on whatever tile now
     * occupies the shrunken index (symmetric with [toggleBurstExpansion]'s jump to the suggested keeper).
     */
    fun collapseBurst() {
        _state.update { st ->
            val burstId = st.expandedBurstId ?: return@update st
            val display = displayGroupsFor(st.groups, null)
            val focus = display.indexOfFirst { it.groupId == burstId }.takeIf { it >= 0 }?.let(::TileIndex)
                ?: st.focusedIndex
            // Folding renumbers the tile space, so any stored Shift+click anchor is stale.
            st.copy(
                expandedBurstId = null,
                anchorIndex = null,
                focusedIndex = TileIndex(focus.value.coerceIn(-1, (display.size - 1).coerceAtLeast(-1))),
            )
        }
    }

    /** F / Space at the focused tile: file it into Favourites — see [fileAtFocus] for the single-vs-burst rule. */
    fun toggleMembershipAtFocus() =
        fileAtFocus(Category.FAVOURITES_ID, Category.FAVOURITES_NAME, isFavourite = true)

    /** X at the focused tile: file it into Rejects — the cull's reject half, same single-vs-burst rule as F. */
    fun toggleRejectAtFocus() =
        fileAtFocus(Category.REJECTS_ID, Category.REJECTS_NAME, isFavourite = false)

    /** Bare digit 1..9 at the focused tile: toggle the single, or file the whole burst, into the Nth custom category. */
    fun toggleCustomCategoryAtFocus(slot: Int) {
        val category = _state.value.categories.customCategories().getOrNull(slot) ?: return
        fileAtFocus(category.id, category.name, isFavourite = false)
    }

    /**
     * The single-vs-burst filing rule at the focused tile: a single toggles its membership; a
     * collapsed burst additively files all its frames (toggling a representative you can't fully
     * see would be ambiguous, so a burst always *adds*). Shared by [toggleMembershipAtFocus] and
     * [toggleCustomCategoryAtFocus] so the rule lives in one place.
     */
    private fun fileAtFocus(id: CategoryId, name: String, isFavourite: Boolean) {
        when (val group = _state.value.displayGroups.getOrNull(_state.value.focusedIndex)) {
            is PhotoGroup.Single -> toggleSingleMembership(group.photo, id, name, isFavourite = isFavourite)
            is PhotoGroup.Burst -> fileIdsInto(group.photos.mapTo(HashSet()) { it.id }, id, name)
            null -> Unit
        }
    }

    private fun toggleSingleMembership(photo: Photo, id: CategoryId, name: String, isFavourite: Boolean) {
        scope.launch {
            val added = categories.toggleMembership(root, id, photo.id)
            _toggleEvents.send(CategoryToggle(name, isFavourite = isFavourite, added = added))
        }
    }

    // ---- Multi-select ----------------------------------------------------------------------
    // Mouse-driven (Cmd/Shift-click, Cmd+A) so the established plain-click-opens-the-browser
    // gesture is preserved. The set lives in state and is screenshot-testable directly.

    // Selection operates at tile granularity over [displayGroups]: [index] is a tile index, and a
    // collapsed burst contributes all its frames so a whole burst is picked or dropped as one, while
    // an expanded burst's frames select individually. The set itself stays per-photo, so downstream
    // (bulk file, copy, the C entry's flat-index mapping) is unchanged.

    /** Cmd+Click: flip the tile's photos in the selection; that tile becomes the range anchor. */
    fun toggleSelection(index: TileIndex) {
        val group = _state.value.displayGroups.getOrNull(index) ?: return
        val ids = group.photos.mapTo(HashSet()) { it.id }
        _state.update {
            val allSelected = ids.all { id -> id in it.selection }
            val next = if (allSelected) it.selection - ids else it.selection + ids
            it.copy(selection = next, anchorIndex = index)
        }
    }

    /** Shift+Click: select every photo in the contiguous run of tiles from the anchor to [index]. */
    fun selectRange(index: TileIndex) {
        _state.update { st ->
            val display = st.displayGroups
            if (index.value !in display.indices) return@update st
            val anchor = TileIndex((st.anchorIndex ?: index).value.coerceIn(display.indices))
            val lo = minOf(anchor.value, index.value)
            val hi = maxOf(anchor.value, index.value)
            val ids = (lo..hi).flatMap { display.getOrNull(it)?.photos.orEmpty() }.mapTo(HashSet()) { it.id }
            st.copy(selection = ids, anchorIndex = anchor)
        }
    }

    /** Cmd+A: select every photo in the current scope. */
    fun selectAll() {
        _state.update { it.copy(selection = it.photos.mapTo(HashSet()) { p -> p.id }) }
    }

    /** Esc / Clear: drop the selection. */
    fun clearSelection() {
        _state.update { it.copy(selection = emptySet(), anchorIndex = null) }
    }

    /** Files the whole selection into Favourites (the selection bar's star, or F when active). */
    fun fileSelectionIntoFavourites() =
        fileSelectionInto(Category.FAVOURITES_ID, Category.FAVOURITES_NAME)

    /** Files the whole selection into Rejects (the selection bar's Reject action, or X when active). */
    fun fileSelectionIntoRejects() =
        fileSelectionInto(Category.REJECTS_ID, Category.REJECTS_NAME)

    /** Files the whole selection into the Nth custom category (a digit while a selection is active). */
    fun fileSelectionIntoCustom(slot: Int) {
        val category = _state.value.categories.customCategories().getOrNull(slot) ?: return
        fileSelectionInto(category.id, category.name)
    }

    private fun fileSelectionInto(id: CategoryId, name: String) = fileIdsInto(_state.value.selection, id, name)

    /** Additive bulk file of [ids] into a category, with a result toast. Shared by selection filing and focused-burst filing. */
    private fun fileIdsInto(ids: Set<PhotoId>, id: CategoryId, name: String) {
        if (ids.isEmpty()) return
        scope.launch {
            val added = categories.addMemberships(root, id, ids)
            _messages.trySend(bulkFileToast(name, requested = ids.size, added = added))
        }
    }

    /** Copies just the selected photos into [destination] (the selection bar's "Copy selected…"). */
    fun copySelectionTo(destination: Path, policy: ConflictPolicy) {
        val ids = _state.value.selection
        copyPhotos(_state.value.photos.filter { it.id in ids }, destination, policy)
    }

    /**
     * Moves the selected photos to the Trash, then drops them from the in-memory list, purges
     * them from every category, and tells the container so later screens are built without them.
     * Best-effort: photos that couldn't be trashed stay put and are named in the toast. The
     * caller (the screen) is responsible for confirming first — this performs the delete.
     */
    fun deleteSelection() {
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        val targets = _state.value.photos.filter { it.id in ids }
        if (targets.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isBusy = true, progressLabel = "Moving ${targets.size} to Trash…") }
            val report = try {
                moveToTrash.invoke(targets)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(isBusy = false, progressLabel = null) }
                _messages.trySend("Delete failed: ${t.message}")
                return@launch
            }
            val trashedIds = targets
                .filter { p -> report.failed.none { it.first.id == p.id } }
                .mapTo(HashSet()) { it.id }
            if (trashedIds.isNotEmpty()) {
                allPhotos = allPhotos.filterNot { it.id in trashedIds }
                categories.removeMemberships(root, trashedIds)
                onPhotosDeleted?.invoke(trashedIds)
            }
            // Recompute directly off the reduced [allPhotos]: removeMemberships only re-emits
            // when something was actually filed, so the slice can't rely on that callback.
            val photos = slicedPhotos(_state.value.memberships)
            val ids = photos.map { it.id }
            _state.update { st ->
                val validIds = photos.mapTo(HashSet()) { it.id }
                val anchorId = st.focusedAnchorId()
                val singles = photos.map(PhotoGroup::Single)
                st.copy(
                    isBusy = false,
                    progressLabel = null,
                    photos = photos,
                    groups = singles,
                    expandedBurstId = null,
                    selection = st.selection.intersect(validIds),
                    anchorIndex = null,
                    // Re-anchor by identity, not a bare index: deleting a photo before the cursor
                    // renumbers the tiles, and with the lens Off no regroup runs afterward to fix a
                    // coerced index, so focus would silently slide onto a different photo. Mirrors
                    // removePhotos exactly (its docstring promises this path behaves the same).
                    focusedIndex = refocus(singles, anchorId, st.focusedIndex),
                )
            }
            _messages.trySend(deleteToast(report))
            // Respect the toolbar lens: re-collapse only when one is active, otherwise the grid
            // would silently regroup behind a control that reads "Off".
            if (groupingMode != GroupingMode.Off) regroup(photos, ids, groupingMode) else lastGroupedIds = ids
        }
    }

    /**
     * Drops photos trashed elsewhere (e.g. a delete made in the browser while this retained grid sat
     * off-screen) so a warm return doesn't resurrect them — the rebuild that used to refresh the list
     * no longer happens now that the grid is retained. Idempotent: the grid that performed the delete
     * has already pruned its own [allPhotos], so the early-out makes the self-notification a no-op.
     * Focus is re-anchored by identity and the lens re-collapses, mirroring the in-grid delete tail.
     */
    fun removePhotos(ids: Set<PhotoId>) {
        if (ids.isEmpty() || allPhotos.none { it.id in ids }) return
        allPhotos = allPhotos.filterNot { it.id in ids }
        val photos = slicedPhotos(_state.value.memberships)
        val sliceIds = photos.map { it.id }
        _state.update { st ->
            val validIds = photos.mapTo(HashSet()) { it.id }
            val anchorId = st.focusedAnchorId()
            val singles = photos.map(PhotoGroup::Single)
            st.copy(
                photos = photos,
                groups = singles,
                expandedBurstId = null,
                selection = if (st.selection.isEmpty()) st.selection else st.selection.intersect(validIds),
                anchorIndex = null,
                focusedIndex = refocus(singles, anchorId, st.focusedIndex),
            )
        }
        if (groupingMode != GroupingMode.Off) regroup(photos, sliceIds, groupingMode) else lastGroupedIds = sliceIds
    }

    private fun deleteToast(report: TrashReport): String = when {
        report.failed.isEmpty() && report.trashed == 1 -> "Moved 1 photo to Trash"
        report.failed.isEmpty() -> "Moved ${report.trashed} photos to Trash"
        report.trashed == 0 -> "Couldn't move ${report.failed.size} to Trash"
        else -> "Moved ${report.trashed}, ${report.failed.size} failed"
    }

    private fun bulkFileToast(category: String, requested: Int, added: Int): String = when {
        added == 0 -> "All $requested already in $category"
        added == requested -> "Added $requested to $category"
        else -> "Added $added to $category ($requested selected)"
    }

    fun exportTxt(destination: Path) {
        exportPhotosTxt(_state.value.photos, destination)
    }

    /** Writes just the selected photos to a .txt list (the selection bar's Export menu). */
    fun exportSelectionTxt(destination: Path) {
        val ids = _state.value.selection
        exportPhotosTxt(_state.value.photos.filter { it.id in ids }, destination)
    }

    private fun exportPhotosTxt(photos: List<Photo>, destination: Path) {
        if (photos.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isBusy = true, progressLabel = "Writing list…") }
            try {
                exportTxt.invoke(root, photos, destination)
                _state.update { it.copy(isBusy = false, progressLabel = null) }
                _messages.trySend("Saved ${photos.size} entries to ${destination.fileName}")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(isBusy = false, progressLabel = null) }
                _messages.trySend("Export failed: ${t.message}")
            }
        }
    }

    fun copyTo(destination: Path, policy: ConflictPolicy) {
        copyPhotos(_state.value.photos, destination, policy)
    }

    private fun copyPhotos(photos: List<Photo>, destination: Path, policy: ConflictPolicy) {
        if (photos.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isBusy = true, progressLabel = "0 / ${photos.size}") }
            try {
                val report: CopyReport = copyToFolder.invoke(
                    root = root,
                    photos = photos,
                    destDir = destination,
                    policy = policy,
                ) { done, total ->
                    _state.update { it.copy(progressLabel = "$done / $total") }
                }
                _state.update { it.copy(isBusy = false, progressLabel = null) }
                _messages.trySend(buildReportToast(report))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(isBusy = false, progressLabel = null) }
                _messages.trySend("Copy failed: ${t.message}")
            }
        }
    }

    private fun buildReportToast(report: CopyReport): String {
        val parts = mutableListOf("Copied ${report.copied}")
        if (report.skipped > 0) parts += "skipped ${report.skipped}"
        if (report.failed.isNotEmpty()) parts += "${report.failed.size} failed"
        return parts.joinToString(", ")
    }
}
