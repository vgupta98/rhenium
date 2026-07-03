package com.vishalgupta.photoselector.presentation.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.repository.ConflictPolicy
import com.vishalgupta.photoselector.presentation.common.CategoryToggle
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.common.NativeFileDialogs
import com.vishalgupta.photoselector.presentation.common.customCategories
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppOutlinedButton
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BurstExpandedFooter
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BurstExpandedHeader
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BusyBar
import com.vishalgupta.photoselector.presentation.designsystem.molecule.CategoryTogglePill
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ConfirmDialog
import com.vishalgupta.photoselector.presentation.designsystem.molecule.EmptyState
import com.vishalgupta.photoselector.presentation.designsystem.molecule.GridKeyboardLegend
import com.vishalgupta.photoselector.presentation.designsystem.molecule.GroupingProgressBanner
import com.vishalgupta.photoselector.presentation.designsystem.molecule.KeyHint
import com.vishalgupta.photoselector.presentation.designsystem.molecule.LatchedPill
import com.vishalgupta.photoselector.presentation.designsystem.molecule.PillToast
import com.vishalgupta.photoselector.presentation.designsystem.molecule.SimilarityCoachmark
import com.vishalgupta.photoselector.presentation.designsystem.organism.GridSelectionTopBar
import com.vishalgupta.photoselector.presentation.designsystem.organism.GridTopBar
import com.vishalgupta.photoselector.presentation.designsystem.organism.PhotoThumbnail
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun GridScreen(
    viewModel: GridViewModel,
    initialScrollIndex: Int,
    onTileClick: (index: Int) -> Unit,
    onChangeFolder: () -> Unit,
    onInspectSelection: (indices: List<Int>, returnScrollIndex: Int) -> Unit,
    onBack: (() -> Unit)?,
    // Whether the library rail (hoisted to the host, beside this grid) is collapsed. Drives only the
    // top-bar toggle's icon state here; the rail itself is the host's concern. Hoisted so it survives
    // a scope switch and a Grid -> Browser -> Grid round trip.
    railCollapsed: Boolean,
    onToggleRail: () -> Unit,
    // The scroll state retained for this (root, scope) across the session, supplied by the host so it
    // survives a Grid -> Browser -> Grid round trip. [anchorInitialScroll] is true only on the first
    // (cold) visit, where [initialScrollIndex] still needs to be applied as grouping settles; on a
    // warm return the retained state already holds the exact position, so re-anchoring is skipped.
    // The view model is retained too (it is NOT cleared here on navigate-away — the host evicts it on
    // a root change), so the grid returns with its groups, focus and scroll intact.
    gridState: LazyGridState,
    anchorInitialScroll: Boolean,
    // A photo to scroll into view on a warm return, regardless of the keyboard ring (resume / "Show in
    // All Photos"). See [Screen.Grid.revealPhotoId].
    revealPhotoId: PhotoId? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Surface the one-shot toggle confirmation as a transient pill (mirrors the browser).
    var categoryToast by remember { mutableStateOf<CategoryToggle?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.toggleEvents.collectLatest { event ->
            categoryToast = event
            delay(1200)
            categoryToast = null
        }
    }

    // The "what the lens found" notice, fired once per user lens pick (see GroupingOutcome). collectLatest
    // so a quick second lens pick replaces the previous notice rather than queueing it behind the timer.
    var groupingNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.groupingOutcomes.collectLatest { outcome ->
            groupingNotice = groupingNoticeText(outcome)
            delay(GROUPING_NOTICE_MS)
            groupingNotice = null
        }
    }

    // The bulk/library action result (export, copy, bulk file, delete) — a consume-once one-shot,
    // collected here and shown as a transient pill. Modelled off [messages] (a channel) rather than
    // persistent state, so navigating away mid-toast can't strand a stale message that re-shows on return.
    var resultToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            resultToast = message
            delay(TOAST_DURATION_MS)
            resultToast = null
        }
    }

    GridScreen(
        state = state,
        initialScrollIndex = initialScrollIndex,
        retainedGridState = gridState,
        anchorInitialScroll = anchorInitialScroll,
        revealPhotoId = revealPhotoId,
        categoryToast = categoryToast,
        groupingNotice = groupingNotice,
        resultToast = resultToast,
        railCollapsed = railCollapsed,
        onToggleRail = onToggleRail,
        onTileClick = onTileClick,
        onChangeFolder = onChangeFolder,
        onInspectSelection = onInspectSelection,
        onBack = onBack,
        onSetFocusedIndex = viewModel::setFocusedIndex,
        modifier = modifier,
        onToggleMembershipAtFocus = viewModel::toggleMembershipAtFocus,
        onToggleRejectAtFocus = viewModel::toggleRejectAtFocus,
        onToggleCustomCategoryAtFocus = viewModel::toggleCustomCategoryAtFocus,
        onExportTxt = {
            coroutineScope.launch {
                val target = NativeFileDialogs.pickSaveFile(
                    title = "Export list",
                    defaultName = "photos.txt",
                ) ?: return@launch
                viewModel.exportTxt(target)
            }
        },
        onCopyToFolder = { policy ->
            coroutineScope.launch {
                val dir = NativeFileDialogs.pickDirectory("Choose destination folder")
                    ?: return@launch
                viewModel.copyTo(dir, policy)
            }
        },
        onFirstVisibleItemChanged = viewModel::onFirstVisibleItemChanged,
        onSelectGroupingMode = viewModel::setGroupingMode,
        onToggleBurstExpansion = viewModel::toggleBurstExpansion,
        onCollapseBurst = viewModel::collapseBurst,
        onDismissSimilarityCoachmark = viewModel::dismissSimilarityCoachmark,
        imageLoader = viewModel.imageLoader,
        onToggleSelection = viewModel::toggleSelection,
        onSelectRange = viewModel::selectRange,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onFileSelectionIntoFavourites = viewModel::fileSelectionIntoFavourites,
        onFileSelectionIntoRejects = viewModel::fileSelectionIntoRejects,
        onFileSelectionIntoCustom = viewModel::fileSelectionIntoCustom,
        onDeleteSelection = viewModel::deleteSelection,
        onExportSelectionTxt = {
            coroutineScope.launch {
                val target = NativeFileDialogs.pickSaveFile(
                    title = "Export list",
                    defaultName = "photos.txt",
                ) ?: return@launch
                viewModel.exportSelectionTxt(target)
            }
        },
        onCopySelection = { policy ->
            coroutineScope.launch {
                val dir = NativeFileDialogs.pickDirectory("Copy selected photos to…")
                    ?: return@launch
                viewModel.copySelectionTo(dir, policy)
            }
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GridScreen(
    state: GridUiState,
    initialScrollIndex: Int,
    // The host-retained scroll state for this (root, scope), or null for the stateless hosting (tests,
    // previews) that owns no retention — then the grid makes its own, seeded from [initialScrollIndex].
    retainedGridState: LazyGridState? = null,
    // True only on a cold first visit, where [initialScrollIndex] is re-anchored as grouping settles;
    // false on a warm return, where [retainedGridState] already holds the exact position.
    anchorInitialScroll: Boolean = true,
    // A photo to scroll into view once on a warm return, regardless of the keyboard ring. See
    // [Screen.Grid.revealPhotoId]; ignored on a cold visit (initialScrollIndex places the photo there).
    revealPhotoId: PhotoId? = null,
    // Library rail collapse state + toggle, for the top bar's collapse control. The rail itself lives
    // in the host beside this grid; defaulted so the stateless screen renders without the host wiring.
    railCollapsed: Boolean = false,
    onToggleRail: () -> Unit = {},
    onTileClick: (index: Int) -> Unit,
    onChangeFolder: () -> Unit,
    onBack: (() -> Unit)?,
    onSetFocusedIndex: (TileIndex) -> Unit,
    onToggleMembershipAtFocus: () -> Unit,
    onToggleRejectAtFocus: () -> Unit = {},
    onToggleCustomCategoryAtFocus: (slot: Int) -> Unit,
    onExportTxt: () -> Unit,
    onCopyToFolder: (ConflictPolicy) -> Unit,
    onFirstVisibleItemChanged: (FlatIndex) -> Unit = {},
    onSelectGroupingMode: (GroupingMode) -> Unit = {},
    onToggleBurstExpansion: (PhotoId) -> Unit = {},
    onCollapseBurst: () -> Unit = {},
    onDismissSimilarityCoachmark: () -> Unit = {},
    imageLoader: ImageLoader,
    categoryToast: CategoryToggle? = null,
    // A one-shot "what the lens found" notice (summary or empty result), already rendered to copy by
    // the stateful host from a [GroupingOutcome]. Null when there's nothing to announce.
    groupingNotice: String? = null,
    // The consume-once result of a bulk/library action (export, copy, bulk file, delete), collected
    // from [GridViewModel.messages] and cleared on a timer by the stateful host. Null when idle.
    resultToast: String? = null,
    // Multi-select plumbing. Defaulted so the stateless screen can be hosted (tests, previews)
    // without wiring selection — a grid with no selection handlers simply never selects.
    onToggleSelection: (TileIndex) -> Unit = {},
    onSelectRange: (TileIndex) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onFileSelectionIntoFavourites: () -> Unit = {},
    onFileSelectionIntoRejects: () -> Unit = {},
    onFileSelectionIntoCustom: (slot: Int) -> Unit = {},
    onDeleteSelection: () -> Unit = {},
    onExportSelectionTxt: () -> Unit = {},
    onCopySelection: (ConflictPolicy) -> Unit = {},
    onInspectSelection: (indices: List<Int>, returnScrollIndex: Int) -> Unit = { _, _ -> },
    // The scrollbar's drag interactions, hoisted so a test can drive a scrollbar-drag-during-settle
    // (emit DragInteraction.Start) without a real thin-scrollbar gesture; production uses the default.
    scrollbarInteraction: MutableInteractionSource = remember { MutableInteractionSource() },
    modifier: Modifier = Modifier,
) {
    // The collapsed grouping. The view model populates [GridUiState.groups] (singles, then bursts
    // once grouping resolves); a state with no groups computed yet (e.g. a test or a static preview)
    // falls back to one tile per photo so the grid still renders and navigates.
    val baseGroups = remember(state.groups, state.photos) {
        state.groups.ifEmpty { state.photos.map(PhotoGroup::Single) }
    }
    // The renderable items: headers + tiles, with the expanded burst (if any) unfolded in place.
    val renderItems = remember(baseGroups, state.expandedBurstId) {
        buildRenderItems(baseGroups, state.expandedBurstId)
    }
    // The tiles-only index space focus / selection / clicks address, in lock-step with the view
    // model's [GridUiState.displayGroups]. A collapsed burst is one tile; an open burst's frames are
    // individual tiles.
    val tiles = remember(baseGroups, state.expandedBurstId) {
        displayGroupsFor(baseGroups, state.expandedBurstId)
    }
    // Flat index in [state.photos] where each display tile's frames begin - the bridge between the
    // flat photo index the rest of the app speaks (browser, Compare, Survey, persisted scroll) and
    // the grid's own tile index.
    val tileFlatStart = remember(tiles) {
        var acc = 0
        tiles.map { group -> FlatIndex(acc).also { acc += group.photos.size } }
    }
    // initialScrollIndex is a FLAT photo index (which photo to reveal); convert to its tile, since a
    // burst makes the two diverge. Grouping settles asynchronously, so a later effect re-anchors.
    // Prefer the host-retained scroll state (so a warm return keeps the exact position); fall back to
    // a self-owned one seeded from initialScrollIndex for the stateless hosting that has no retention.
    val ownGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = tileIndexForFlat(tileFlatStart, FlatIndex(initialScrollIndex)).value,
    )
    val gridState = retainedGridState ?: ownGridState
    // What we hand back to the browser / Compare / Survey / persistence is a FLAT photo index, so no
    // caller needs to know tiles exist. firstVisibleItemIndex is a renderItems index (header/footer
    // included when a burst is open), so map it through the tile space before the flat lookup.
    fun firstVisibleFlat() = flatIndexForRenderItem(renderItems, tileFlatStart, gridState.firstVisibleItemIndex)
    val focusRequester = remember { FocusRequester() }
    // Open/closed state of the destructive delete confirmation. The Delete button and Cmd+Delete
    // both arm it; confirming calls [onDeleteSelection], which performs the move to Trash.
    var confirmingDelete by remember { mutableStateOf(false) }

    // These three derive from a slice of state that only the toolbar reads, but the screen body
    // recomposes on every cursor move (state.focusedIndex). Rebuilt unmemoized, each pass minted a
    // fresh List identity, and a bare List is unstable - so the top bars (which compare these by
    // ===) re-ran in full on every arrow key. Key each on exactly what it derives from so the
    // toolbar identities hold steady across an unrelated state flip and the bars stay skippable.
    val currentCategory: Category? = remember(state.scope, state.categories) {
        (state.scope as? CategoryScope.Category)
            ?.let { sc -> state.categories.firstOrNull { it.id == sc.id } }
    }

    // Custom categories in slot order — drives both the per-tile numbered badges and the
    // 1..9 digit mapping, so a tile's "2" chip always matches the key that toggled it.
    val customCategories = remember(state.categories) { state.categories.customCategories() }

    // Opening a tile: a collapsed burst unfolds in place into its frames; a single photo - including
    // an open burst's frame - opens the browser at that photo. There is no frame-count cap: even a
    // huge burst is culled inline. (Inspect stays reachable by selecting frames and pressing C, exactly
    // as for any multi-select.)
    // Remembered, not a bare val: this resolver captures the unstable tiles / tileFlatStart lists, so
    // rebuilding it every recomposition would hand every tile a fresh { openTile(index) } onClick and
    // defeat per-tile skipping on any unrelated state flip (favourite, focus, selection). tiles and
    // tileFlatStart only change on a regroup/expansion, which is exactly when the resolver *should*
    // be rebuilt - so key on them (and the two stable callbacks) and the onClick identity holds steady
    // through the hot path.
    val openTile: (TileIndex) -> Unit = remember(tiles, tileFlatStart, onTileClick, onToggleBurstExpansion) {
        open@{ tileIndex ->
            val group = tiles.getOrNull(tileIndex) ?: return@open
            when (group) {
                is PhotoGroup.Single -> onTileClick(tileFlatStart[tileIndex.value].value)
                is PhotoGroup.Burst -> onToggleBurstExpansion(group.groupId)
            }
        }
    }

    // "Review" a collapsed group: open its frames straight into Inspect, the "decide now" path next to
    // expand-in-place. The group is a contiguous run of the flat photo list, so its flat indices are
    // [tileFlatStart] .. +frameCount — resolved HERE, the sole tile->flat translator (never put a tile
    // index on the nav wire). Any size opens: Inspect itself shows a long burst (past the grid cap) in
    // browse mode rather than declining. Remembered like [openTile] so the hover CTA and the
    // focused-group `C` share one stable resolver and the tiles keep skipping.
    val openReview: (TileIndex) -> Unit =
        remember(tiles, tileFlatStart, renderItems, gridState, onInspectSelection) {
            review@{ tileIndex ->
                val group = tiles.getOrNull(tileIndex) as? PhotoGroup.Burst ?: return@review
                val start = tileFlatStart[tileIndex.value].value
                onInspectSelection((start until start + group.photos.size).toList(), firstVisibleFlat().value)
            }
        }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // The viewport re-pin across a grouping reshape - the cold settle and a lens switch - lives in one
    // holder (see [GridViewportAnchor]). It keeps the viewport on one photo by IDENTITY, re-read from the
    // live top only when the user has actually scrolled (re-deriving it every reshape degrades a collapsed
    // burst to its first frame and walks the viewport upward across switches). Cold seed: the returned
    // photo's id *if the photos are already loaded* - on a real cold launch they arrive after mount, so
    // this is null and [coldFlatFallback] (the flat index) carries the restore until an identity anchor
    // exists; on a warm return both are null so the retained pixel position is left untouched.
    // A warm return (resume) or a "Show in All Photos" jump carries [revealPhotoId]: the anchor scrolls
    // that photo on-screen by IDENTITY on the first reconcile, ring or no ring (the old resume rode the
    // keyboard ring's focus-into-view, so a mouse-only user - focusedIndex == -1 - silently got no resume).
    // Gated to a warm return; a cold first visit places the photo via initialAnchor / coldFlatFallback.
    val anchor = rememberGridViewportAnchor(
        gridState = gridState,
        initialAnchor = if (anchorInitialScroll) state.photos.getOrNull(initialScrollIndex)?.id else null,
        coldFlatFallback = FlatIndex(initialScrollIndex).takeIf { anchorInitialScroll },
        revealPhotoId = revealPhotoId.takeIf { !anchorInitialScroll },
    )
    // Moves the keyboard cursor: tells the anchor the user took the viewport over (releases the re-pin) and,
    // on an actual change, arms the focus-into-view scroll. The no-op-at-edge guard lives here because the
    // change is relative to the current focus; the holder owns the flag and the scroll itself.
    val moveFocus: (TileIndex) -> Unit = { target ->
        anchor.onCursorMove(focusChanged = target != state.focusedIndex)
        onSetFocusedIndex(target)
    }
    // A scrollbar drag reaches [gridState] through the scrollable, NOT the Column's pointer modifier
    // below, so observe the scrollbar's own drag interactions to release the re-pin too (the gap that
    // used to let a scrollbar-drag-during-settle get yanked back). Distinct from gridState.isScrollInProgress,
    // which a programmatic re-pin also trips - this fires only on a real user drag.
    LaunchedEffect(scrollbarInteraction) {
        scrollbarInteraction.interactions.collect { if (it is DragInteraction.Start) anchor.onUserScroll() }
    }

    // Persisted scroll position is a FLAT photo index - the 50k-photo resume point, written on every
    // scroll. The subscription is start-once (gridState is stable), but renderItems / tileFlatStart
    // change on every regroup/expansion - so read the latest via rememberUpdatedState rather than
    // capture a stale snapshot that would persist a wrong position after a burst expands.
    val latestRenderItems by rememberUpdatedState(renderItems)
    val latestTileFlatStart by rememberUpdatedState(tileFlatStart)
    val latestTiles by rememberUpdatedState(tiles)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .collect { index -> onFirstVisibleItemChanged(flatIndexForRenderItem(latestRenderItems, latestTileFlatStart, index)) }
    }

    // The one-shot reveal (warm-return resume / "Show in All Photos") scrolls a photo on-screen by identity,
    // ring or not. It lives in its OWN mount effect, keyed on Unit, NOT the focusedIndex-keyed reconcile
    // below: the jump seats the ring on arrival (flipping focusedIndex), which would re-key reconcile and
    // cancel a reveal scroll running there mid-animation. Reads the latest tiles/renderItems so a regroup
    // that lands first still resolves the photo.
    LaunchedEffect(Unit) {
        anchor.scrollRevealIntoView(latestRenderItems, latestTiles)
    }

    // Every programmatic viewport move goes through the anchor's single [reconcile], keyed on BOTH the
    // collapsed grouping (a reshape) and the focused tile (a cursor move) so it is the lone decision point:
    // exactly one of re-pin / focus-into-view runs per change, and the two can no longer race. Keyed on
    // baseGroups (not the display tiles), so merely expanding a burst - which changes neither key - doesn't
    // reconcile; that case is left to the LazyGrid's own key retention.
    LaunchedEffect(baseGroups, state.focusedIndex) {
        anchor.reconcile(renderItems, tiles, tileFlatStart, state.focusedIndex, baseGroups)
    }

    // A toolbar lens switch reshapes the whole grid: capture the photo at the live top before the reshape,
    // then re-pin to it after. Remembered (not a bare lambda), keyed on the unstable renderItems / tiles it
    // reads plus the wrapped callback, so the toolbar keeps skipping through the hot path - like [openTile].
    val onSelectGroupingModeAnchored: (GroupingMode) -> Unit =
        remember(renderItems, tiles, onSelectGroupingMode) {
            { mode ->
                anchor.captureTop(renderItems, tiles)
                onSelectGroupingMode(mode)
            }
        }

    // The library rail (navigation + category management) is hoisted to the navigation host and sits
    // left of this grid; only the grid column lives here, so it keeps the keyboard ring. The host
    // sizes us via [modifier] (it gives the grid the row's remaining width beside the rail).
    Column(
        modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // A two-finger / wheel scroll (the app's scroll gestures) releases the re-pin: a programmatic
            // scroll or a grouping reshape doesn't emit this, so neither is mistaken for the user taking
            // over. (Scrollbar drags reach the grid through the scrollable, not here - those are caught via
            // the scrollbar's interactionSource above.)
            .onPointerEvent(PointerEventType.Scroll) { anchor.onUserScroll() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    // A tile's `Modifier.clickable` activates on the Enter / Space KEY-UP, and a mouse
                    // click leaves Compose focus on that tile - which differs from our keyboard cursor.
                    // If we let the key-up through, the focused tile re-fires its onClick and re-opens
                    // the mouse-clicked tile, undoing the cursor's action: the "Enter always re-expands
                    // the burst I opened with the mouse" bug. The grid owns Enter/Space (handled on the
                    // key-down below), so swallow their key-ups too and the tile clickable never sees them.
                    return@onPreviewKeyEvent event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.Spacebar
                }
                // Keyboard focus moves over tiles (groups), not the flat photo list — a collapsed
                // burst is one stop, so the last addressable tile is tiles.size - 1. Bound once here so
                // the dispatcher's bound and the move actions' clamps can't drift apart.
                val maxIndex = tiles.size - 1
                // Pure input->intent dispatch lives in [handleGridKey]; the layout/anchor-coupled
                // effects (vertical nav + seed read the laid-out geometry; horizontal/G re-pin the
                // viewport) are bound here, where the grid owns that state.
                handleGridKey(
                    event = event,
                    ctx = GridKeyContext(
                        focusedIndex = state.focusedIndex,
                        maxIndex = maxIndex,
                        hasSelection = state.hasSelection,
                        selectionSize = state.selection.size,
                        expandedBurstId = state.expandedBurstId,
                        groupingMode = state.groupingMode,
                        focusedTileIsBurst = tiles.getOrNull(state.focusedIndex) is PhotoGroup.Burst,
                        canPop = onBack != null,
                    ),
                    actions = GridKeyActions(
                        selectAll = onSelectAll,
                        confirmDelete = { confirmingDelete = true },
                        inspectSelection = {
                            // Indices taken in scope (reading) order; the flat return position is the
                            // grid's, so the round-trip lands back here.
                            val indices = state.photos.indices.filter { state.photos[it].id in state.selection }
                            onInspectSelection(indices, firstVisibleFlat().value)
                        },
                        reviewFocused = { openReview(state.focusedIndex) },
                        selectLens = onSelectGroupingModeAnchored,
                        seedFocus = {
                            // firstVisibleItemIndex is render-item space (header/footer included when a
                            // burst is open), so map it into tile space first.
                            val seed = tileDisplayIndexForRenderItem(renderItems, gridState.firstVisibleItemIndex)?.value ?: 0
                            moveFocus(TileIndex(seed.coerceIn(0, maxIndex)))
                        },
                        moveFocusHorizontal = { delta ->
                            val raw = state.focusedIndex.value + delta
                            val target = if (delta < 0) raw.coerceAtLeast(0) else raw.coerceAtMost(maxIndex)
                            moveFocus(TileIndex(target))
                        },
                        moveFocusVertical = { down ->
                            moveFocus(verticalNavTarget(gridState, renderItems, state.focusedIndex, maxIndex, down = down))
                        },
                        openFocused = { openTile(state.focusedIndex) },
                        fileSelectionIntoCustom = onFileSelectionIntoCustom,
                        toggleCustomCategoryAtFocus = onToggleCustomCategoryAtFocus,
                        fileSelectionIntoFavourites = onFileSelectionIntoFavourites,
                        toggleMembershipAtFocus = onToggleMembershipAtFocus,
                        fileSelectionIntoRejects = onFileSelectionIntoRejects,
                        toggleRejectAtFocus = onToggleRejectAtFocus,
                        clearSelection = onClearSelection,
                        collapseBurst = onCollapseBurst,
                        back = { onBack?.invoke() },
                    ),
                )
            },
    ) {
        if (state.hasSelection) {
            GridSelectionTopBar(
                selectedCount = state.selection.size,
                customCategories = customCategories,
                onFileIntoFavourites = onFileSelectionIntoFavourites,
                onFileIntoRejects = onFileSelectionIntoRejects,
                onFileIntoCustom = onFileSelectionIntoCustom,
                onExportSelectionTxt = onExportSelectionTxt,
                onCopySelection = onCopySelection,
                onDeleteSelection = { confirmingDelete = true },
                onClearSelection = onClearSelection,
            )
        } else {
            GridTopBar(
                scope = state.scope,
                currentCategory = currentCategory,
                photoCount = state.photos.size,
                isBusy = state.isBusy,
                railCollapsed = railCollapsed,
                onToggleRail = onToggleRail,
                onExportTxt = onExportTxt,
                onCopyToFolder = onCopyToFolder,
                groupingMode = state.groupingMode,
                onSelectGroupingMode = onSelectGroupingModeAnchored,
                similarityProgress = state.similarityProgress
                    ?.takeIf { it.total > 0 }
                    ?.let { it.processed.toFloat() / it.total },
            )
        }

        if (state.isBusy) {
            BusyBar(label = state.progressLabel ?: "Working…")
        }

        // Non-blocking determinate progress while a grouping lens computes. Unlike the busy bar above
        // it doesn't lock the toolbar — the user can keep scrolling the singles grid while the model
        // works. Two independent sources: the inline Time regroup (sub-second, the bare bar) reads
        // [grouping]; the background Similarity pass reads [similarityProgress]. The Similarity pass is
        // a ~minute-long on-device run, so it gets the framing banner (what's happening + the privacy
        // line) while it is the displayed lens; in any other lens its progress shows only on the tab.
        state.grouping?.takeIf { it.total > 0 }?.let { g ->
            BusyBar(
                label = "Grouping ${g.processed} / ${g.total}",
                progress = g.processed.toFloat() / g.total,
            )
        }
        state.similarityProgress
            ?.takeIf { it.total > 0 && state.groupingMode == GroupingMode.Similarity }
            ?.let { g -> GroupingProgressBanner(processed = g.processed, total = g.total) }

        // First-run callout for the Similarity lens — a dismissible card under the toolbar (near the
        // lens toggle), not a modal: the user can ignore it and keep culling. Shown once, then never.
        // Eases in (and out on dismiss) so it doesn't snap the grid down beneath it.
        AnimatedVisibility(
            visible = state.showSimilarityCoachmark,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SimilarityCoachmark(onDismiss = onDismissSimilarityCoachmark)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.photos.isEmpty()) {
                GridEmptyState(
                    scope = state.scope,
                    currentCategory = currentCategory,
                    customCategories = customCategories,
                    onChangeFolder = onChangeFolder,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(AppTheme.dimens.thumbnailMinCell),
                    // Tight contact-sheet gutters. `end` stays wider than the others to leave a
                    // lane for the overlaid scrollbar (xs pad + thickness ~= 12dp) without the
                    // last column running under it.
                    contentPadding = PaddingValues(
                        start = AppTheme.spacing.sm,
                        end = AppTheme.spacing.lg,
                        top = AppTheme.spacing.sm,
                        bottom = AppTheme.spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                ) {
                    items(
                        items = renderItems,
                        // The burst header spans the full row so the frames beneath it read as one
                        // section; tiles take a single cell.
                        span = { item ->
                            when (item) {
                                is GridRenderItem.BurstHeader -> GridItemSpan(maxLineSpan)
                                is GridRenderItem.BurstFooter -> GridItemSpan(maxLineSpan)
                                is GridRenderItem.Tile -> GridItemSpan(1)
                            }
                        },
                        key = { item ->
                            when (item) {
                                is GridRenderItem.BurstHeader -> "burst-header:" + item.burst.groupId.value
                                is GridRenderItem.BurstFooter -> "burst-footer:" + item.burst.groupId.value
                                is GridRenderItem.Tile -> item.group.groupId.value
                            }
                        },
                    ) { item ->
                        // Placement-only reflow shared by every render item, so the whole row set
                        // slides as one when a burst unfolds/folds or the lens regroups. The fade is
                        // off (null specs); an unfolding burst's frames pop in instead (below).
                        val itemMotion = Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = GRID_ITEM_PLACEMENT_SPEC,
                            fadeOutSpec = null,
                        )
                        when (item) {
                            is GridRenderItem.BurstHeader -> BurstExpandedHeader(
                                frameCount = item.burst.photos.size,
                                onCollapse = onCollapseBurst,
                                modifier = itemMotion,
                            )
                            is GridRenderItem.BurstFooter -> BurstExpandedFooter(modifier = itemMotion)
                            is GridRenderItem.Tile -> {
                                val group = item.group
                                val index = item.displayIndex
                                val keyPhoto = group.keyPhoto
                                PhotoThumbnail(
                                    // An unfolded burst frame pops in (scale); every other tile just
                                    // slides via the shared placement spring.
                                    modifier = if (item.expandedFrame) itemMotion.gridAppearPop() else itemMotion,
                                    photo = keyPhoto,
                                    loader = imageLoader,
                                    isMarked = keyPhoto.id in state.markedIds,
                                    isRejected = keyPhoto.id in state.rejectedIds,
                                    isFocused = index == state.focusedIndex,
                                    // Any frame of the run counts: a collapsed burst shows the
                                    // middle frame as its key, but you may have opened (and last
                                    // viewed) a different frame, so match against the whole run.
                                    isLastViewed = group.photos.any { it.id == state.lastViewedPhotoId },
                                    // A collapsed burst reads as selected only when its whole run is
                                    // selected, matching the whole-burst pick in toggleSelection.
                                    isSelected = group.photos.all { it.id in state.selection },
                                    onClick = { openTile(index) },
                                    onToggleSelect = { onToggleSelection(index) },
                                    onRangeSelect = { onSelectRange(index) },
                                    categoryBadges = categoryBadgesFor(keyPhoto, customCategories, state.memberships),
                                    burstCount = (group as? PhotoGroup.Burst)?.photos?.size,
                                    // The glyph echoes the active lens, and onReview opens the run
                                    // side by side. Both null for singles and for an expanded burst's
                                    // individual frames (those open the browser, not a review).
                                    groupGlyph = if (group is PhotoGroup.Burst) groupGlyphFor(state.groupingMode) else null,
                                    onReview = if (group is PhotoGroup.Burst) {
                                        { openReview(index) }
                                    } else {
                                        null
                                    },
                                    withinBurst = item.expandedFrame,
                                )
                            }
                        }
                    }
                }
                val scrollbarAdapter = rememberScrollbarAdapter(gridState)
                VerticalScrollbar(
                    // Wrapped to swallow the transient NaN the lazy-grid adapter emits while the grid
                    // reshapes under animateItem (a lens regroup or burst expand/collapse) - see
                    // [NanSafeScrollbarAdapter]. Without it the scrollbar crashes mid-measure.
                    adapter = remember(scrollbarAdapter) { NanSafeScrollbarAdapter(scrollbarAdapter) },
                    interactionSource = scrollbarInteraction,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = AppTheme.spacing.xs),
                    style = ScrollbarStyle(
                        minimalHeight = AppTheme.dimens.scrollbarMinHeight,
                        thickness = AppTheme.dimens.scrollbarThickness,
                        shape = MaterialTheme.shapes.small,
                        hoverDurationMillis = 300,
                        unhoverColor = AppTheme.colors.scrollbarIdle,
                        hoverColor = AppTheme.colors.scrollbarHover,
                    ),
                )
            }

            // Result/notice for bulk and library-level actions (export, copy, bulk file, the survey
            // cap notice) — rendered in the app's pill chrome, not a stock Material snackbar, so all
            // of the grid's transient feedback reads as one family.
            GridMessagePill(
                message = resultToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = AppTheme.spacing.lg),
            )

            // Transient confirmation that the last F / 1..9 toggle landed and what it did. The
            // tile's star/badge shows the resulting state; this names the action, the way the
            // browser's pill does.
            GridTogglePill(
                toast = categoryToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = AppTheme.spacing.lg),
            )

            // The grouping payoff / empty-result notice, fired once per user lens pick. Sits a row
            // higher than the action pills above so a coincident toggle/result pill doesn't collide.
            GridMessagePill(
                message = groupingNotice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = GROUPING_NOTICE_BOTTOM_PADDING),
            )
        }

        if (state.photos.isNotEmpty()) {
            GridKeyboardLegend(
                hints = rememberLegendHints(
                    scope = state.scope,
                    canGoBack = onBack != null,
                    // A focused *collapsed* burst is a Burst in tile space (an open burst's frames are
                    // Singles), so Enter expands it rather than opening the browser.
                    focusedBurstCollapsed = tiles.getOrNull(state.focusedIndex) is PhotoGroup.Burst,
                    burstExpanded = state.expandedBurstId != null,
                ),
            )
        }
    }

    // When a selection clears, two cleanups: drop the delete-confirm latch so it can't hang over
    // nothing, and reclaim keyboard focus for the grid. A Cmd-click moves Compose's *actual* focus
    // onto the clicked tile (distinct from our keyboard cursor); deleting that tile removes the
    // focused node, and Compose does NOT fall focus back to the grid - it orphans, so arrows and
    // Enter stop reaching onPreviewKeyEvent until something re-grabs focus. Clearing a selection is
    // the discrete moment after which the grid should own focus again, so re-request it here. Done
    // in an effect (not composition) to avoid a back-write.
    LaunchedEffect(state.hasSelection) {
        if (!state.hasSelection) {
            confirmingDelete = false
            focusRequester.requestFocus()
        }
    }

    // Destructive speed bump in front of the move-to-Trash.
    if (confirmingDelete && state.hasSelection) {
        val count = state.selection.size
        ConfirmDialog(
            title = if (count == 1) "Move 1 photo to Trash?" else "Move $count photos to Trash?",
            message = "The selected " +
                (if (count == 1) "photo" else "photos") +
                " will be moved to the macOS Trash. You can restore " +
                (if (count == 1) "it" else "them") +
                " from there.",
            confirmLabel = "Move to Trash",
            confirmDestructive = true,
            onConfirm = {
                confirmingDelete = false
                onDeleteSelection()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * The truthful set of grid shortcuts for the current [scope]. `F` always toggles Favourites
 * membership and `X` always toggles Rejects (see `GridViewModel.toggleMembershipAtFocus` /
 * `toggleRejectAtFocus`) — the two sides of the cull, available in every scope, not "toggle this
 * category" keys — while the `1..9` filing keys only do anything from All Photos, so they're only
 * advertised there.
 *
 * The grouping keys stay honest too: `G` (cycle lens) always applies, but Enter is labelled
 * **Expand** only when a collapsed burst is focused ([focusedBurstCollapsed]) and `Esc` advertises
 * **Collapse** only while a burst is open ([burstExpanded]) — where Esc peels the burst before backing out.
 */
@Composable
private fun rememberLegendHints(
    scope: CategoryScope,
    canGoBack: Boolean,
    focusedBurstCollapsed: Boolean,
    burstExpanded: Boolean,
): ImmutableList<KeyHint> = remember(scope, canGoBack, focusedBurstCollapsed, burstExpanded) {
    buildList {
        add(KeyHint("← → ↑ ↓", "Move"))
        add(KeyHint("↵", if (focusedBurstCollapsed) "Expand" else "Open"))
        add(KeyHint(keys = "F", label = "Favourite"))
        add(KeyHint(keys = "X", label = "Reject"))
        if (scope == CategoryScope.AllPhotos) add(KeyHint("1–9", "Categories"))
        add(KeyHint("G", "Group"))
        if (burstExpanded) add(KeyHint("Esc", "Collapse"))
        if (canGoBack) add(KeyHint("Esc", "Back"))
    }.toImmutableList()
}

/**
 * The empty-grid guidance, varying by [scope]. All Photos points at the only useful next step
 * (change folder); a category teaches the exact key that files into it — `F` for Favourites,
 * its `1..9` digit for a custom category — so the empty state reinforces the keyboard model
 * rather than dead-ending.
 */
@Composable
private fun GridEmptyState(
    scope: CategoryScope,
    currentCategory: Category?,
    customCategories: List<Category>,
    onChangeFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (scope) {
        CategoryScope.AllPhotos -> EmptyState(
            title = "No photos here",
            description = "This folder has no JPEG or PNG images.",
            icon = Icons.Outlined.PhotoLibrary,
            action = {
                AppOutlinedButton(
                    text = "Change folder",
                    leadingIcon = Icons.Default.Folder,
                    onClick = onChangeFolder,
                )
            },
            modifier = modifier,
        )
        is CategoryScope.Category -> {
            val isFavourites = currentCategory?.id == Category.FAVOURITES_ID
            val slot = customCategories.indexOfFirst { it.id == currentCategory?.id }
            val keyHint = when {
                isFavourites -> "F"
                slot in 0..8 -> "${slot + 1}"
                else -> null
            }
            EmptyState(
                title = if (isFavourites) {
                    "No favourites yet"
                } else {
                    "Nothing in ${currentCategory?.name ?: "this category"} yet"
                },
                description = if (keyHint != null) {
                    "In All Photos, focus a photo and press $keyHint to add it here."
                } else {
                    "In All Photos, focus a photo to add it here."
                },
                icon = if (isFavourites) Icons.Outlined.StarOutline else Icons.Outlined.Collections,
                modifier = modifier,
            )
        }
    }
}

/**
 * The fading confirmation pill for a keyboard membership toggle. Lives in its own composable
 * (not under the screen's Column/Row receiver) so the plain `AnimatedVisibility` overload
 * resolves, and holds the last non-null [toast] so its text survives the exit fade. Colour
 * encodes added vs removed; favourites also keep their star — matching the browser's pill.
 */
// Motion for grid item reflow — the burst expand/collapse and the lens regroup. A no-bounce
// placement spring slides each surviving tile to its new home; the fade-on-appear/disappear is
// switched off (null specs on `animateItem`) so the only built-in motion is that calm slide. The
// grid only reshapes after its initial layout, so this animates the *transition*, never the cold
// first paint. Hoisted as a constant (not rebuilt per recomposition) so `Modifier.animateItem`
// yields a structurally-equal modifier each pass and the tiles stay skippable on the hot
// scroll/selection path.
private val GRID_ITEM_PLACEMENT_SPEC = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

// A burst's unfolded frames "pop" in — scaling up from [APPEAR_INITIAL_SCALE] to full size instead
// of fading — so opening a burst reads as the frames springing out of the collapsed stack. A calm
// (no-bounce) medium spring keeps it crisp rather than springy. Applied only to the expanded frame
// tiles (see [gridAppearPop]); scale is a draw-phase transform, so it never reflows neighbours.
private val APPEAR_SCALE_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)
private const val APPEAR_INITIAL_SCALE = 0.85f

/**
 * Guards [VerticalScrollbar] against a Compose-desktop crash on the regrouping grid.
 *
 * While the grid reshapes under [Modifier.animateItem] - a lens regroup (e.g. switching to the
 * Similarity lens, which actually collapses singles into burst tiles) or a burst expand/collapse -
 * the lazy-grid scrollbar adapter can momentarily see a visible window whose line count is zero or
 * negative and divides by it (foundation 1.7.3,
 * `LazyGridScrollbarAdapter.averageVisibleLineSize`). That feeds a NaN content size /scroll offset
 * to the scrollbar, which rounds it while measuring and throws
 * `IllegalArgumentException: Cannot round NaN value`. (The Time/Burst lens dodges it only because
 * a folder with no JPEG capture times - e.g. HEIC/screenshots - forms no groups, so the grid never
 * reshapes.)
 *
 * Sanitising the three doubles the scrollbar reads back to finite values turns that one bad frame
 * into a harmless full-height thumb; the next layout pass recovers the real geometry. Remove once
 * the upstream adapter no longer emits NaN.
 */
internal class NanSafeScrollbarAdapter(
    private val delegate: ScrollbarAdapter,
) : ScrollbarAdapter {
    override val scrollOffset: Double
        get() = delegate.scrollOffset.takeIf(Double::isFinite) ?: 0.0
    override val contentSize: Double
        get() = delegate.contentSize.takeIf(Double::isFinite) ?: 0.0
    override val viewportSize: Double
        get() = delegate.viewportSize.takeIf(Double::isFinite) ?: 0.0

    override suspend fun scrollTo(scrollOffset: Double) = delegate.scrollTo(scrollOffset)
}

/**
 * Entrance "pop" for a freshly unfolded burst frame: scales from [APPEAR_INITIAL_SCALE] to 1 on first
 * composition (a one-shot, hence the `Unit` key — there is no input to restart on). The scale is read
 * inside [graphicsLayer]'s lambda — a deferred draw-phase read — so the spring runs without recomposing
 * the tile each frame.
 *
 * The caller gates this to expanded frame tiles only ([GridRenderItem.Tile.expandedFrame]). Those
 * render items exist *only* while a burst is open, so a tile's first composition coincides with the
 * expand: an ordinary tile scrolling into view is never an expanded frame and so never pops.
 */
@Composable
private fun Modifier.gridAppearPop(): Modifier {
    val scale = remember { Animatable(APPEAR_INITIAL_SCALE) }
    LaunchedEffect(Unit) { scale.animateTo(targetValue = 1f, animationSpec = APPEAR_SCALE_SPEC) }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** How long a [GridMessagePill] result/notice stays up before it fades out. */
private const val TOAST_DURATION_MS = 2500L

/** How long the grouping summary / empty-result notice stays up — a touch longer, it's the payoff line. */
private const val GROUPING_NOTICE_MS = 4000L

/** Bottom offset that lifts the grouping notice above the action/toggle pills so they never collide. */
private val GROUPING_NOTICE_BOTTOM_PADDING = 64.dp

/**
 * The user-facing copy for a completed grouping pass. An empty result (no stacks) explains why nothing
 * collapsed rather than leaving a silently flat grid; a productive pass names the payoff. Wording
 * follows the lens — "stacks" for Similar, "bursts" for Time.
 */
internal fun groupingNoticeText(outcome: GroupingOutcome): String {
    if (outcome.burstCount == 0) {
        return if (outcome.mode == GroupingMode.Similarity) {
            "No similar shots found — these all look unique."
        } else {
            "No bursts here — nothing was shot rapid-fire."
        }
    }
    val stack = if (outcome.mode == GroupingMode.Similarity) "stack" else "burst"
    val noun = if (outcome.burstCount == 1) stack else "${stack}s"
    return "${outcome.photosInBursts} photos → ${outcome.burstCount} $noun. Review to cut duplicates."
}

/**
 * Result/notice pill for bulk and library-level actions (export saved, copy report, the survey
 * cap notice). Uses the shared [PillToast] chrome and the same latch + fade as [GridTogglePill], so
 * the grid's transient feedback is one consistent family rather than a stock Material snackbar.
 * The caller drives [message] from the one-shot [GridViewModel.messages] flow and clears it on a timer.
 */
@Composable
private fun GridMessagePill(message: String?, modifier: Modifier = Modifier) {
    LatchedPill(message, modifier) { PillToast(text = it) }
}

@Composable
private fun GridTogglePill(toast: CategoryToggle?, modifier: Modifier = Modifier) {
    LatchedPill(toast, modifier) { dt -> CategoryTogglePill(dt) }
}

/**
 * The count-pill glyph for a collapsed group under [mode]: a sparkle for the Similarity lens (an
 * on-device-AI cluster), the stacked-frames glyph otherwise (a time burst). So a grouped tile
 * silently says *why* it grouped, echoing the lens the toolbar names. A returned Material icon is a
 * cached singleton, so the param stays stable and the tile skippable.
 */
private fun groupGlyphFor(mode: GroupingMode): ImageVector =
    if (mode == GroupingMode.Similarity) Icons.Filled.AutoAwesome else Icons.Filled.BurstMode

/**
 * The digit slots (1..9) of the custom categories [photo] belongs to, for its tile badges.
 * Slot `i+1` matches the key that toggles the i-th custom category. Returns an immutable list
 * so an unchanged result compares equal and the tile stays skippable across membership churn.
 */
private fun categoryBadgesFor(
    photo: Photo,
    customCategories: List<Category>,
    memberships: Map<CategoryId, Set<PhotoId>>,
): ImmutableList<Int> {
    if (customCategories.isEmpty()) return persistentListOf()
    return customCategories.mapIndexedNotNull { i, cat ->
        if (photo.id in memberships[cat.id].orEmpty()) i + 1 else null
    }.toImmutableList()
}

// Maps a flat photo index (the navigation / persistence currency) onto the tile that
// contains it. Tiles are contiguous runs of the flat list, so [tileFlatStart] - each
// tile's first flat index, ascending - is binary-searchable: an exact hit is that tile,
// otherwise the insertion point minus one is the tile whose run straddles the index.
// The grid is the sole translator between flat photo space and its own tile space.
internal fun tileIndexForFlat(tileFlatStart: List<FlatIndex>, flatIndex: FlatIndex): TileIndex {
    if (tileFlatStart.isEmpty()) return TileIndex(0)
    val i = tileFlatStart.binarySearch { it.value.compareTo(flatIndex.value) }
    return if (i >= 0) TileIndex(i) else TileIndex((-i - 2).coerceIn(0, tileFlatStart.lastIndex))
}

/**
 * Vertical (up/down) keyboard move for the grid cursor, resolved against the *actual* laid-out tile
 * positions rather than `focusedIndex ± columns`. An expanded burst inserts full-width header/footer
 * rows and the partial rows they create, which the arithmetic model can't represent (it made down
 * behave like right). So from the focused tile we pick the nearest tile - by horizontal offset - in
 * the closest tile row in the chosen direction, skipping the non-focusable header/footer items.
 *
 * Falls back to a column-count step only when the focused tile isn't currently laid out (e.g. it was
 * scrolled off after a non-keyboard scroll): the step nudges [focusedIndex] so the focus effect can
 * scroll it back into view, after which geometry resumes. [renderItems] is the LazyGrid's item list
 * (headers/footers included), so an item's `index` there maps to a tile's `displayIndex`.
 */
private fun verticalNavTarget(
    gridState: LazyGridState,
    renderItems: List<GridRenderItem>,
    focusedIndex: TileIndex,
    maxIndex: Int,
    down: Boolean,
): TileIndex {
    // The laid-out tile cells (headers/footers excluded), mapped to the tile index space.
    val tilePositions = gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        (renderItems.getOrNull(info.index) as? GridRenderItem.Tile)
            ?.let { TilePosition(it.displayIndex, info.offset.x, info.offset.y) }
    }
    pickVerticalTarget(tilePositions, focusedIndex, down)?.let { return it }
    // No on-screen tile beyond the cursor (a true edge, or the next row is just off-screen): step by
    // a row so the focus effect can scroll a fresh target into view. Clamps to a no-op at the very
    // top/bottom.
    val columns = computeColumnCount(gridState)
    return if (down) TileIndex((focusedIndex.value + columns).coerceAtMost(maxIndex))
    else TileIndex((focusedIndex.value - columns).coerceAtLeast(0))
}

// The grid's column count, read from the *widest* laid-out row so a full-width burst header/footer
// row (one spanning item) or a partial row never undercounts it. Used only as the fallback step when
// geometry-based [verticalNavTarget] has no on-screen anchor. Matches what GridCells.Adaptive
// actually laid out (recomputing from viewport width drifts once padding/spacing are accounted for).
// Returns 1 before first layout (visibleItemsInfo empty), which is safe: no arrow can have fired yet.
private fun computeColumnCount(gridState: LazyGridState): Int {
    val visible = gridState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 1
    return visible.groupingBy { it.offset.y }.eachCount().values.maxOrNull()?.coerceAtLeast(1) ?: 1
}
