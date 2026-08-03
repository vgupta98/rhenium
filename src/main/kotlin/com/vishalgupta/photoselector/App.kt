package com.vishalgupta.photoselector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.di.AppContainer
import com.vishalgupta.photoselector.presentation.browser.BrowserScreen
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BackgroundPassChip
import com.vishalgupta.photoselector.presentation.designsystem.molecule.PillToast
import com.vishalgupta.photoselector.presentation.designsystem.molecule.UpdateAvailableBanner
import com.vishalgupta.photoselector.presentation.designsystem.organism.LibraryRail
import com.vishalgupta.photoselector.presentation.grid.GridScreen
import com.vishalgupta.photoselector.presentation.inspect.InspectScreen
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import com.vishalgupta.photoselector.presentation.navigation.GridRetentionKey
import com.vishalgupta.photoselector.presentation.navigation.InspectOrigin
import com.vishalgupta.photoselector.presentation.navigation.Screen
import com.vishalgupta.photoselector.presentation.people.PeopleScreen
import com.vishalgupta.photoselector.presentation.rootpicker.RootFolderPickerScreen
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(container: AppContainer) {
    val screen by container.currentScreen.collectAsState()
    // The background Similarity pass keeps running across navigation; this is its off-grid hint. On the
    // Grid the tab ring + banner carry it, so the chip is shown only on the other screens.
    val groupingActivity by container.groupingActivity.collectAsState()
    // The face scan keeps running across navigation too; this is its off-screen hint.
    val faceScanActivity by container.faceScanActivity.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // App-lifetime, notify-only update checker. Built once and kept across navigation; the launch check
    // fires after the first composition. Muted entirely for opted-out or Homebrew-managed installs.
    val updateViewModel = remember { container.updateViewModel() }
    val updateState by updateViewModel.state.collectAsState()
    LaunchedEffect(Unit) { updateViewModel.check() }

    // Scroll position retained per (root, scope) for the session, alongside the retained view models
    // in the container. Held here (not inside the Grid branch, which leaves composition on every
    // navigation away) so a Grid -> Browser -> Grid round trip returns to the exact same scroll.
    // Cleared on a root change, in lock-step with the container dropping its retained grids.
    val gridScrollStates = remember { mutableMapOf<GridRetentionKey, LazyGridState>() }

    // Library-rail collapse state, held at the navigation-shell level (not inside the Grid branch,
    // which leaves composition on every navigation away and re-keys per scope). Hoisting it here is
    // what lets the rail stay collapsed across a scope switch and a Grid -> Browser -> Grid round
    // trip, in lock-step with the retained scroll above.
    var railCollapsed by remember { mutableStateOf(false) }

    // One-shot result of a "Move rejects to Trash" sweep, surfaced as a transient pill in the
    // bottom-end overlay (the sweep is initiated from the rail, which lives only on the Grid).
    var railSweepMessage by remember { mutableStateOf<String?>(null) }
    // Auto-dismiss lives here, at App scope, NOT inside the Grid-branch collector that sets the
    // message: a sweep then Grid -> Browser within the window would otherwise cancel the collector
    // mid-delay and strand the pill on every screen. Keyed on the message so each new one re-arms.
    LaunchedEffect(railSweepMessage) {
        if (railSweepMessage != null) {
            delay(2500)
            railSweepMessage = null
        }
    }

    AppTheme {
        Surface(Modifier.fillMaxSize()) {
          Box(Modifier.fillMaxSize()) {
            when (val s = screen) {
                Screen.RootPicker -> {
                    val vm = remember { container.rootPickerViewModel() }
                    RootFolderPickerScreen(vm)
                }
                is Screen.Grid -> {
                    // The library rail is root-level chrome (the same for every scope), so it is mounted
                    // ABOVE the per-scope retention key: switching category re-keys only the grid below,
                    // leaving the rail mounted instead of tearing it down and rebuilding it (the flicker).
                    // It reads its own root-scoped view model, retained per root by the container.
                    val railVm = remember(s.root.path) { container.libraryRailViewModel(s.root) }
                    val railEntries by railVm.entries.collectAsState()
                    val xmpSyncState by railVm.xmpSyncState.collectAsState()
                    val peopleRailState by railVm.peopleState.collectAsState()
                    // Surface the reject-sweep result as a transient pill: only SET the message here.
                    // The auto-dismiss is an App-scoped effect (above) so it survives this Grid branch
                    // leaving composition (a sweep then Grid -> Browser within the timer).
                    LaunchedEffect(railVm) {
                        railVm.sweepEvents.collect { railSweepMessage = it }
                    }
                    // Shared by the rail and the grid's empty-state CTA: drop the root and return to the picker.
                    val changeFolder: () -> Unit = {
                        coroutineScope.launch {
                            container.resetForNewRoot()
                            gridScrollStates.clear()
                            container.goTo(Screen.RootPicker)
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                        // Show/hide animates the width (anchored at the start edge) so the grid slides
                        // open/closed rather than snapping. Rail rows are not keyboard-focusable, so the
                        // grid below keeps the keyboard ring.
                        AnimatedVisibility(
                            visible = !railCollapsed,
                            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                        ) {
                            LibraryRail(
                                rootName = s.root.path.fileName?.toString() ?: s.root.path.toString(),
                                scope = s.scope,
                                entries = railEntries,
                                // "All Photos" is usually warm (retained), so it restores its own scroll;
                                // the browse position is the cold fallback. The active row is inert (RailRow
                                // gates clicks on selection), so this only fires when switching in.
                                onSelectAllPhotos = {
                                    container.goTo(
                                        Screen.Grid(
                                            root = s.root,
                                            scope = CategoryScope.AllPhotos,
                                            initialScrollIndex = container.loadBrowsePosition(s.root).lastIndex,
                                        ),
                                    )
                                },
                                onSelectCategory = { id ->
                                    container.goTo(
                                        Screen.Grid(
                                            root = s.root,
                                            scope = CategoryScope.Category(id),
                                            // No lastViewedPhotoId: a category's underline marks what was
                                            // browsed *in that category*, not what was last opened from All
                                            // Photos. No returnScrollIndex either: backing out lands on the
                                            // (warm) All Photos grid, which restores its own retained scroll.
                                        ),
                                    )
                                },
                                onCreateCategory = railVm::create,
                                onRenameCategory = railVm::rename,
                                onDeleteCategory = railVm::delete,
                                onEmptyRejects = railVm::emptyRejectsToTrash,
                                xmpSyncEnabled = xmpSyncState.enabled,
                                xmpSyncSkippedNonRaw = xmpSyncState.skippedNonRaw,
                                onToggleXmpSync = { railVm.toggleXmpSync() },
                                people = peopleRailState,
                                // No returnScrollIndex, exactly like the rail's category rows: the
                                // grid's view model and scroll state are both retained across this
                                // round trip, so a bare Screen.Grid back is already a warm return.
                                onOpenPeople = {
                                    container.goTo(Screen.People(root = s.root, returnScope = s.scope))
                                },
                                onScanFaces = railVm::startScan,
                                onChangeFolder = changeFolder,
                            )
                        }
                        // weight(1f) is a RowScope modifier, so build it here and hand it to the grid: the
                        // key() block below is not a RowScope, but the grid's root is still Row's direct
                        // child (key emits no node), so the parent-data attaches correctly.
                        val gridModifier = Modifier.weight(1f).fillMaxHeight()
                        key(GridRetentionKey(s.root.path, s.scope)) {
                            val retentionKey = GridRetentionKey(s.root.path, s.scope)
                            // Warm if this (root, scope) grid was shown before this visit: it then reuses its
                            // retained scroll. Latched per visit (remember) so it can't flip mid-visit once the
                            // getOrPut below inserts the key. A cold first visit re-anchors to initialScrollIndex.
                            val anchorInitialScroll = remember { retentionKey !in gridScrollStates }
                            // Seed a cold first visit's scroll at the restored FLAT index (tile == flat while the
                            // grid is still ungrouped singles, which is exactly the loading state) rather than at 0
                            // and chasing it: that closes the window where the grid sits at 0 and persists 0 before
                            // the re-pin runs, wiping the saved resume point. The grid re-pins by identity once
                            // bursts form. A warm return reuses the already-correct retained state untouched.
                            val gridState = remember {
                                gridScrollStates.getOrPut(retentionKey) {
                                    LazyGridState(firstVisibleItemIndex = s.initialScrollIndex.coerceAtLeast(0))
                                }
                            }
                            val vm = remember {
                                container.gridViewModel(s.root, s.scope, s.lastViewedPhotoId)
                            }
                            // The deliberate "Show in All Photos" jump seats the keyboard ring on the revealed
                            // photo so it's unmistakable among thousands; the scroll-into-view itself is the
                            // one-shot reveal in GridScreen, which lives in its own Unit-keyed effect, so this
                            // focusedIndex change can't cancel it. A same-scope resume sets focusRevealedPhoto =
                            // false and so leaves the (possibly absent) ring alone. Keyed on vm: one-shot per visit.
                            LaunchedEffect(vm) {
                                if (s.focusRevealedPhoto) vm.focusPhoto(s.revealPhotoId)
                            }
                            GridScreen(
                                viewModel = vm,
                                initialScrollIndex = s.initialScrollIndex,
                                gridState = gridState,
                                anchorInitialScroll = anchorInitialScroll,
                                revealPhotoId = s.revealPhotoId,
                                railCollapsed = railCollapsed,
                                onToggleRail = { railCollapsed = !railCollapsed },
                                onTileClick = { index ->
                                    container.goTo(
                                        Screen.Browser(
                                            root = s.root,
                                            initialIndex = index,
                                            scope = vm.state.value.scope,
                                            returnScrollIndex = s.returnScrollIndex,
                                        ),
                                    )
                                },
                                onChangeFolder = changeFolder,
                                onInspectSelection = { indices, returnScrollIndex ->
                                    container.goTo(
                                        Screen.Inspect(
                                            root = s.root,
                                            scope = vm.state.value.scope,
                                            indices = indices,
                                            returnScrollIndex = returnScrollIndex,
                                            origin = InspectOrigin.Grid,
                                        ),
                                    )
                                },
                                onBack = when (s.scope) {
                                    CategoryScope.AllPhotos -> null
                                    is CategoryScope.Category -> {
                                        {
                                            container.goTo(
                                                Screen.Grid(
                                                    root = s.root,
                                                    scope = CategoryScope.AllPhotos,
                                                    initialScrollIndex = s.returnScrollIndex
                                                        ?: container.loadBrowsePosition(s.root).lastIndex,
                                                    // No lastViewedPhotoId / revealPhotoId: backing out of a
                                                    // category must NOT drag the category-browsed photo into All
                                                    // Photos and scroll there - categories are viewed separately.
                                                    // All Photos returns to its own retained scroll; use the
                                                    // browser's "Show in All Photos" for a deliberate jump.
                                                ),
                                            )
                                        }
                                    }
                                },
                                modifier = gridModifier,
                            )
                        }
                    }
                }
                is Screen.Browser -> {
                    val vm = remember(s.root.path, s.initialIndex, s.scope) {
                        container.browserViewModel(s.root, s.initialIndex, s.scope)
                    }
                    LaunchedEffect(vm) {
                        vm.state.collect { state ->
                            container.currentPhotoPath.value = state.currentPhoto?.absolutePath
                        }
                    }
                    BrowserScreen(
                        viewModel = vm,
                        systemActions = container.systemActions,
                        onBack = {
                            val idx = vm.state.value.currentIndex
                            val photoId = vm.state.value.currentPhoto?.id
                            container.goTo(
                                Screen.Grid(
                                    s.root,
                                    s.scope,
                                    initialScrollIndex = idx,
                                    lastViewedPhotoId = photoId,
                                    // Resume the grid on the photo just browsed, ring or no ring (mouse-only
                                    // users get the same resume keyboard users always did). Same scope, so
                                    // no ring is spawned - focusRevealedPhoto stays false.
                                    revealPhotoId = photoId,
                                    returnScrollIndex = s.returnScrollIndex,
                                ),
                            )
                        },
                        onCompare = {
                            val st = vm.state.value
                            if (st.photos.size >= 2) {
                                val cur = st.currentIndex
                                container.goTo(
                                    Screen.Inspect(
                                        root = s.root,
                                        scope = s.scope,
                                        indices = listOf(cur, (cur + 1) % st.photos.size),
                                        returnScrollIndex = s.returnScrollIndex,
                                        origin = InspectOrigin.Browser,
                                    ),
                                )
                            }
                        },
                        // Only offered when browsing a category: jump to this photo in the All Photos grid,
                        // ringing it there. Null in the All-Photos browser (it's already All Photos), which
                        // is what hides the button and disables the key. Relies on the All Photos grid being
                        // warm - it always is, since a category is reached through the All Photos dropdown,
                        // so its VM and scroll are retained. A cold All Photos would drop revealPhotoId in
                        // GridScreen (the reveal is gated to a warm return) and land at index 0 instead.
                        onShowInAllPhotos = (s.scope as? CategoryScope.Category)?.let {
                            {
                                container.goTo(
                                    Screen.Grid(
                                        root = s.root,
                                        scope = CategoryScope.AllPhotos,
                                        revealPhotoId = vm.state.value.currentPhoto?.id,
                                        focusRevealedPhoto = true,
                                    ),
                                )
                            }
                        },
                    )
                }
                is Screen.People -> {
                    // Full-screen with its own top bar: the library rail is mounted only in the Grid
                    // branch above, so it (and the whole key(GridRetentionKey) block) leaves
                    // composition here. Both the retained GridViewModel and the retained scroll state
                    // survive that, so the bare Screen.Grid below is a warm return.
                    val vm = remember(s.root.path) { container.peopleViewModel(s.root) }
                    PeopleScreen(
                        viewModel = vm,
                        rootName = s.root.path.fileName?.toString() ?: s.root.path.toString(),
                        imageLoader = container.imageLoader,
                        onBack = { container.goTo(Screen.Grid(root = s.root, scope = s.returnScope)) },
                        onPurgeFaceCache = container::clearFaceCache,
                    )
                }
                is Screen.Inspect -> key(s) {
                    val vm = remember { container.inspectViewModel(s.root, s.scope, s.indices) }
                    InspectScreen(
                        viewModel = vm,
                        systemActions = container.systemActions,
                        onExit = {
                            // A grid-originated inspect returns to the grid it came from; a
                            // browser-originated one drops back into the full-screen browser at the
                            // active photo (its scope index, via the set it was opened with).
                            when (s.origin) {
                                InspectOrigin.Grid -> container.goTo(
                                    Screen.Grid(
                                        root = s.root,
                                        scope = s.scope,
                                        initialScrollIndex = s.returnScrollIndex ?: 0,
                                    ),
                                )
                                InspectOrigin.Browser -> {
                                    val scopeIndex = s.indices.getOrElse(vm.activeSubsetIndex()) {
                                        s.indices.firstOrNull() ?: 0
                                    }
                                    container.goTo(
                                        Screen.Browser(
                                            root = s.root,
                                            initialIndex = scopeIndex,
                                            scope = s.scope,
                                            returnScrollIndex = s.returnScrollIndex,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // Bottom-end overlay stack, stacked so they never overlap when both show. The off-grid
            // background-grouping hint is suppressed on the Grid (its tab ring + banner carry it there);
            // the update banner shows on every screen.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppTheme.spacing.lg),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                railSweepMessage?.let { PillToast(text = it) }
                groupingActivity?.takeIf { screen !is Screen.Grid }?.let { activity ->
                    BackgroundPassChip(
                        label = "Grouping similar",
                        processed = activity.processed,
                        total = activity.total,
                    )
                }
                // The face scan's off-screen twin, suppressed on People (its banner carries it there)
                // the way the grouping chip is suppressed on the Grid.
                faceScanActivity?.takeIf { screen !is Screen.People }?.let { activity ->
                    BackgroundPassChip(
                        label = "Finding faces",
                        processed = activity.processed,
                        total = activity.total,
                        onStop = container::stopFaceScan,
                    )
                }
                updateState.available?.let { available ->
                    UpdateAvailableBanner(
                        versionLabel = available.versionLabel,
                        mandatory = available.mandatory,
                        onDownload = updateViewModel::onDownload,
                        onLater = updateViewModel::onLater,
                        onSkip = if (available.mandatory) null else updateViewModel::onSkip,
                        onViewNotes = if (available.notesUrl != null) updateViewModel::onViewNotes else null,
                    )
                }
            }
          }
        }
    }
}
