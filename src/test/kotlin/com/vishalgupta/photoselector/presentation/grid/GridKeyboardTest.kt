package com.vishalgupta.photoselector.presentation.grid

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

class GridKeyboardTest {

    @get:Rule
    val rule = createComposeRule()

    private val testPhotos = listOf(
        Photo(
            id = PhotoId("a"),
            absolutePath = Path.of("/photos/sunset.jpg"),
            relativePath = "sunset.jpg",
            fileName = "sunset.jpg",
            sizeBytes = 1024,
            lastModifiedEpochMs = 0,
        ),
        Photo(
            id = PhotoId("b"),
            absolutePath = Path.of("/photos/mountain.jpg"),
            relativePath = "mountain.jpg",
            fileName = "mountain.jpg",
            sizeBytes = 2048,
            lastModifiedEpochMs = 0,
        ),
    )

    private val noOpImageLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap? = null
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun firstArrowPress_fromUnfocused_focusesFirstVisibleTile() {
        val captured = mutableListOf<TileIndex>()
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    GridScreen(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = TileIndex(-1),
                        ),
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = { captured += it },
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()

        // The shortcut path fires onSetFocusedIndex(0) and returns, so we expect
        // exactly one emission with index 0 — not a directional-math result.
        assertEquals(listOf(TileIndex(0)), captured)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bareDigitKey_togglesNthCustomCategoryAtFocusedTile() {
        val slots = mutableListOf<Int>()
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    GridScreen(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = TileIndex(0),
                        ),
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = { slots += it },
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        // Bare "2" (no Cmd) toggles the focused tile in the 2nd custom category — slot 1.
        rule.onRoot().performKeyInput { pressKey(Key.Two) }
        rule.waitForIdle()

        assertEquals(listOf(1), slots)
    }

    // --- Multi-select keyboard routing -----------------------------------------------------

    /** Renders the grid with selection callbacks captured; unrelated handlers are no-ops. */
    @Composable
    private fun SelectionGrid(
        state: GridUiState,
        onBack: (() -> Unit)? = null,
        onSelectAll: () -> Unit = {},
        onClearSelection: () -> Unit = {},
        onFileSelectionIntoFavourites: () -> Unit = {},
        onFileSelectionIntoCustom: (Int) -> Unit = {},
        onInspectSelection: (List<Int>, Int) -> Unit = { _, _ -> },
    ) {
        GridScreen(
            state = state,
            initialScrollIndex = 0,
            onTileClick = {},
            onChangeFolder = {},
            onBack = onBack,
            onSetFocusedIndex = {},
            onToggleMembershipAtFocus = {},
            onToggleCustomCategoryAtFocus = {},
            onExportTxt = {},
            onCopyToFolder = {},
            imageLoader = noOpImageLoader,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
            onFileSelectionIntoFavourites = onFileSelectionIntoFavourites,
            onFileSelectionIntoCustom = onFileSelectionIntoCustom,
            onInspectSelection = onInspectSelection,
        )
    }

    private val fourPhotos = (0 until 4).map { i ->
        Photo(
            id = PhotoId("p$i"),
            absolutePath = Path.of("/photos/img$i.jpg"),
            relativePath = "img$i.jpg",
            fileName = "img$i.jpg",
            sizeBytes = 1,
            lastModifiedEpochMs = 0,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cmdA_armsSelectAll() {
        var selectAllCalls = 0
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    SelectionGrid(
                        state = GridUiState(photos = testPhotos, scope = CategoryScope.AllPhotos),
                        onSelectAll = { selectAllCalls++ },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.A) } }
        rule.waitForIdle()

        assertEquals(1, selectAllCalls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun escWithSelection_clearsSelectionInsteadOfPoppingScreen() {
        var cleared = 0
        var backs = 0
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    SelectionGrid(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            selection = setOf(testPhotos[0].id),
                        ),
                        onBack = { backs++ },
                        onClearSelection = { cleared++ },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertEquals(1, cleared)
        assertEquals(0, backs)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fAndDigit_withSelection_fileTheWholeSelection() {
        var favouriteFiles = 0
        val customSlots = mutableListOf<Int>()
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    SelectionGrid(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            selection = setOf(testPhotos[0].id, testPhotos[1].id),
                        ),
                        onFileSelectionIntoFavourites = { favouriteFiles++ },
                        onFileSelectionIntoCustom = { customSlots += it },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.F) }
        rule.onRoot().performKeyInput { pressKey(Key.One) }
        rule.waitForIdle()

        assertEquals(1, favouriteFiles)
        assertTrue("digit files the selection into slot 0", customSlots == listOf(0))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cWithSelection_handsOffTheSelectedIndicesInScopeOrder() {
        var captured: List<Int>? = null
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    SelectionGrid(
                        // Select p1 + p3 (out of order on purpose) -> indices come back ascending.
                        state = GridUiState(
                            photos = fourPhotos,
                            scope = CategoryScope.AllPhotos,
                            selection = setOf(fourPhotos[3].id, fourPhotos[1].id),
                        ),
                        onInspectSelection = { indices, _ -> captured = indices },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.C) }
        rule.waitForIdle()

        assertEquals(listOf(1, 3), captured)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cWithFewerThanTwoSelected_doesNothing() {
        var calls = 0
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    SelectionGrid(
                        state = GridUiState(
                            photos = fourPhotos,
                            scope = CategoryScope.AllPhotos,
                            selection = setOf(fourPhotos[0].id),
                        ),
                        onInspectSelection = { _, _ -> calls++ },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.C) }
        rule.waitForIdle()

        assertEquals("C needs a 2+ selection to open Inspect", 0, calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cAboveTheGridCap_stillOpensInspect() {
        // A 13-photo selection no longer declines: it opens Inspect, which shows a set past the grid
        // cap browse-only. The grid still just hands off every selected index; the mode call is Inspect's.
        val thirteen = (0 until 13).map { i ->
            Photo(
                id = PhotoId("p$i"),
                absolutePath = Path.of("/photos/img$i.jpg"),
                relativePath = "img$i.jpg",
                fileName = "img$i.jpg",
                sizeBytes = 1,
                lastModifiedEpochMs = 0,
            )
        }
        var captured: List<Int>? = null
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    SelectionGrid(
                        state = GridUiState(
                            photos = thirteen,
                            scope = CategoryScope.AllPhotos,
                            selection = thirteen.map { it.id }.toSet(),
                        ),
                        onInspectSelection = { indices, _ -> captured = indices },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.C) }
        rule.waitForIdle()

        assertEquals("all 13 selected indices are handed to Inspect", (0 until 13).toList(), captured)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cOnAFocusedCollapsedBurst_reviewsItsFramesWithNoPriorSelection() {
        // a | [b c d] burst | e  -> the burst is tile 1, its frames are flat indices 1..3. C with the
        // burst focused (and NO multi-select) opens those frames for review.
        val photos = (0 until 5).map { photoNamed("q$it") }
        val groups = listOf(
            PhotoGroup.Single(photos[0]),
            PhotoGroup.Burst(photos.subList(1, 4)),
            PhotoGroup.Single(photos[4]),
        )
        var captured: List<Int>? = null
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    GridScreen(
                        state = GridUiState(
                            photos = photos,
                            groups = groups,
                            scope = CategoryScope.AllPhotos,
                            groupingMode = GroupingMode.Time,
                            focusedIndex = TileIndex(1), // the burst tile
                        ),
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                        onInspectSelection = { indices, _ -> captured = indices },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.C) }
        rule.waitForIdle()

        assertEquals("a focused burst's frames open for review", listOf(1, 2, 3), captured)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun gKey_cyclesTheGroupingLensToTheNextMode() {
        var picked: GroupingMode? = null
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    GridScreen(
                        state = GridUiState(
                            photos = testPhotos,
                            groups = testPhotos.map(PhotoGroup::Single),
                            scope = CategoryScope.AllPhotos,
                            groupingMode = GroupingMode.Off,
                        ),
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                        onSelectGroupingMode = { picked = it },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.G) }
        rule.waitForIdle()

        assertEquals("G advances Single -> Bursts", GroupingMode.Time, picked)
    }

    // --- Mouse-then-keyboard: Enter must not re-fire the mouse-focused tile -----------------------
    // Expanding a burst with the MOUSE leaves Compose focus on that tile. A tile's `clickable`
    // activates on the Enter/Space KEY-UP, so if the key-up leaks past the grid's handler the
    // mouse-focused tile re-opens itself - undoing the keyboard cursor's action. Concretely: open
    // burst A with the mouse, arrow the cursor to burst B, press Enter; without the key-up swallow
    // B opens and then A immediately re-opens ([a0, b0, a0]), so A always wins. The grid owns
    // Enter/Space, so it must swallow their key-ups too.

    // A loader that yields a real (1x1) bitmap, so each tile's Image carries a contentDescription
    // (the file name) and can be located + clicked like a real mouse interaction.
    private val bitmapImageLoader = object : ImageLoader {
        private val onePx = ImageBitmap(1, 1)
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap = onePx
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mouseExpandedBurst_thenArrowToAnother_enterOpensTheCursorTileNotTheMouseTile() {
        val burstA = PhotoGroup.Burst(listOf(photoNamed("a0"), photoNamed("a1"), photoNamed("a2")))
        val burstB = PhotoGroup.Burst(listOf(photoNamed("b0"), photoNamed("b1"), photoNamed("b2")))
        val groups = listOf(burstA, burstB)
        val toggles = mutableListOf<String>()

        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1000.dp, 800.dp)) {
                    // Stateful host mirroring GridViewModel.toggleBurstExpansion, so the expand actually
                    // reshapes the tiles the way the real screen does.
                    var state by remember {
                        mutableStateOf(
                            GridUiState(
                                photos = burstA.photos + burstB.photos,
                                groups = groups,
                                scope = CategoryScope.AllPhotos,
                                focusedIndex = TileIndex(-1),
                            ),
                        )
                    }
                    GridScreen(
                        state = state,
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = { idx ->
                            val max = state.displayGroups.size - 1
                            state = state.copy(focusedIndex = TileIndex(idx.value.coerceIn(-1, max.coerceAtLeast(-1))))
                        },
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = bitmapImageLoader,
                        onToggleBurstExpansion = { id ->
                            toggles += id.value
                            val open = if (state.expandedBurstId == id) null else id
                            val display = displayGroupsFor(state.groups, open)
                            val focus = display.indexOfFirst { it.groupId == id }.takeIf { it >= 0 }
                                ?: state.focusedIndex.value
                            state = state.copy(
                                expandedBurstId = open,
                                focusedIndex = TileIndex(focus.coerceIn(-1, (display.size - 1).coerceAtLeast(-1))),
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()

        // Mouse-click burst A's key frame (a1, the middle frame of a 3-burst) to expand it.
        rule.onAllNodesWithContentDescription("a1.jpg")[0].performClick()
        rule.waitForIdle()
        assertEquals("the mouse click expands burst A", listOf("a0"), toggles)

        // displayGroups is now [a0, a1, a2, (B)] - arrow the cursor right from a0 to the B tile (index 3).
        repeat(3) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitForIdle()
        }
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        // Exactly one further toggle - on B (the cursor tile). No spurious re-open of A from the
        // mouse-focused tile's key-up.
        assertEquals("Enter opens the cursor's burst B once, not the mouse-focused A", listOf("a0", "b0"), toggles)
    }

    // --- Delete must not orphan keyboard focus ---------------------------------------------------
    // A Cmd-click moves Compose's actual focus onto the clicked tile. Deleting that tile removes the
    // focused node, and Compose does not fall focus back to the grid - it orphans, so arrows/Enter
    // stop reaching the grid's key handler. The grid must reclaim focus when the selection clears.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun deletingTheMouseFocusedTile_keepsArrowKeysWorking() {
        val photos = (0 until 4).map { photoNamed("p$it") }
        val movedTo = mutableListOf<TileIndex>()
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1000.dp, 800.dp)) {
                    // Stateful host: p1 is pre-selected (the tile we mouse-focus and then delete), and
                    // onDeleteSelection mirrors the view model - drop the selected photo, clear the
                    // selection, reset the cursor.
                    var state by remember {
                        mutableStateOf(
                            GridUiState(
                                photos = photos,
                                groups = photos.map(PhotoGroup::Single),
                                scope = CategoryScope.AllPhotos,
                                focusedIndex = TileIndex(-1),
                                selection = setOf(photos[1].id),
                            ),
                        )
                    }
                    GridScreen(
                        state = state,
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = { idx ->
                            movedTo += idx
                            state = state.copy(focusedIndex = TileIndex(idx.value.coerceIn(-1, (state.displayGroups.size - 1).coerceAtLeast(-1))))
                        },
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = bitmapImageLoader,
                        onClearSelection = { state = state.copy(selection = emptySet()) },
                        onDeleteSelection = {
                            val remaining = state.photos.filterNot { it.id in state.selection }
                            state = state.copy(
                                photos = remaining,
                                groups = remaining.map(PhotoGroup::Single),
                                selection = emptySet(),
                                focusedIndex = TileIndex(-1),
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()

        // Mouse-click p1's tile so Compose focus lands on the tile that's about to be deleted.
        rule.onAllNodesWithContentDescription("p1.jpg")[0].performClick()
        rule.waitForIdle()

        // Cmd+Delete arms the move-to-Trash confirm; confirming performs the delete (removing p1).
        rule.onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Delete) } }
        rule.waitForIdle()
        rule.onNodeWithText("Move to Trash").performClick()
        rule.waitForIdle()

        // The mouse-focused tile is gone. Without reclaiming focus the grid would be deaf to keys;
        // a first arrow press must still reach the handler and seed the cursor.
        movedTo.clear()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()

        assertTrue("arrow key reached the grid after the focused tile was deleted", movedTo.isNotEmpty())
    }

    // --- Warm return must keep the scrolled position, not snap to the ring -----------------------
    // After arrowing the ring to a tile, then trackpad-scrolling away and opening a tile, returning
    // to the grid (a warm re-entry) must honour the retained scroll - NOT yank the viewport back to
    // the (off-screen) ring. The focus-into-view scroll reacts to cursor moves, not to mounting.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun warmReturnWithOffscreenRing_keepsRetainedScrollInsteadOfSnappingToRing() {
        val manyPhotos = (0 until 60).map { photoNamed("p$it") }
        lateinit var gridStateRef: LazyGridState
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(400.dp, 400.dp)) {
                    // A retained scroll parked well down the grid (the position the user opened from),
                    // hosted exactly as the real screen does on a warm return.
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 36)
                    gridStateRef = gridState
                    GridScreen(
                        state = GridUiState(
                            photos = manyPhotos,
                            groups = manyPhotos.map(PhotoGroup::Single),
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = TileIndex(0), // the ring sits at the top, far above the retained scroll
                        ),
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false, // warm return: the retained state owns the position
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        // The retained scroll stands: it must not have snapped up to the ring at index 0.
        assertTrue(
            "warm return snapped to the ring (index ${gridStateRef.firstVisibleItemIndex}) instead of keeping the scroll",
            gridStateRef.firstVisibleItemIndex > 10,
        )
    }

    // --- Returning from the viewer resumes at the photo you left on - RING OR NOT -----------------
    // Open a photo and come back: the grid scrolls to that photo so you resume there. Browser.onBack
    // carries it as revealPhotoId, and the resume is driven by THAT, not by the keyboard ring - so it
    // fires even for a pure-mouse user with no ring (focusedIndex == -1), the bug the old ring-gated
    // resume had. This drives the UI half: an off-screen revealPhotoId must scroll on-screen with no ring.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun warmReturnWithRevealPhoto_scrollsItIntoViewEvenWithNoRing() {
        val photos = (0 until 80).map { photoNamed("p$it") }
        lateinit var gridStateRef: LazyGridState
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(400.dp, 400.dp)) {
                    // Retained scroll parked at the top; the reveal target is far below, off-screen.
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 0)
                    gridStateRef = gridState
                    GridScreen(
                        state = GridUiState(
                            photos = photos,
                            groups = photos.map(PhotoGroup::Single),
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = TileIndex(-1), // pure-mouse user, NO ring - resume must still fire
                        ),
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false, // warm return
                        revealPhotoId = photos[60].id, // the photo the browser left on
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        // The reveal target (tile 60) was below the fold; the resume must have scrolled it into view.
        assertTrue(
            "the off-screen reveal target p60 should have scrolled into view, got ${gridStateRef.firstVisibleItemIndex}",
            gridStateRef.firstVisibleItemIndex > 30,
        )
    }

    // The "Show in All Photos" jump: it both reveals the photo AND rings it. Seating the ring flips
    // focusedIndex, which re-keys the reconcile effect and cancels the reveal scroll mid-flight - so the
    // reveal must survive that cancellation and complete on the relaunch, or the photo never scrolls in
    // (the reported "Show in All Photos doesn't work" bug). This drives that exact sequence: mount with no
    // ring, then flip focusedIndex onto the reveal target a frame later.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun revealWithRingSeatedAfterMount_stillScrollsTheTargetIn() {
        val photos = (0 until 80).map { photoNamed("p$it") }
        lateinit var gridStateRef: LazyGridState
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(400.dp, 400.dp)) {
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 0)
                    gridStateRef = gridState
                    // focusedIndex flips from -1 to the reveal target shortly after mount, exactly as
                    // seating the ring for the jump does - the change that used to cancel the scroll.
                    var focused by remember { mutableStateOf(TileIndex(-1)) }
                    LaunchedEffect(Unit) { focused = TileIndex(60) }
                    GridScreen(
                        state = GridUiState(
                            photos = photos,
                            groups = photos.map(PhotoGroup::Single),
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = focused,
                        ),
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false,
                        revealPhotoId = photos[60].id,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        assertTrue(
            "the reveal scroll was cancelled by the ring seat and never completed, got ${gridStateRef.firstVisibleItemIndex}",
            gridStateRef.firstVisibleItemIndex > 30,
        )
    }

    // The other half: a warm return with NO revealPhotoId (e.g. backing out of a category to All Photos,
    // or a background grouping settle) must NOT resume-scroll - it keeps the retained scroll, even though
    // the underline marker may point at an off-screen photo. Only an explicit reveal moves the viewport,
    // which is what keeps categories "viewed separately" from the main grid.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun warmReturnWithoutReveal_keepsTheScrollInsteadOfResuming() {
        val photos = (0 until 80).map { photoNamed("p$it") }
        lateinit var gridStateRef: LazyGridState
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(400.dp, 400.dp)) {
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 0)
                    gridStateRef = gridState
                    GridScreen(
                        state = GridUiState(
                            photos = photos,
                            groups = photos.map(PhotoGroup::Single),
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = TileIndex(-1),
                            lastViewedPhotoId = photos[60].id, // marker points off-screen, but must not scroll
                        ),
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false,
                        // No revealPhotoId: the retained scroll must stand.
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        // No reveal -> no resume; the retained scroll at the top stands.
        assertTrue(
            "without a reveal the warm return must keep its scroll, got ${gridStateRef.firstVisibleItemIndex}",
            gridStateRef.firstVisibleItemIndex < 10,
        )
    }

    // --- Cold start restores the persisted scroll even though photos load AFTER mount ------------
    // On a real cold launch the host hands the grid a bare LazyGridState (index 0) and the view model
    // loads photos asynchronously, so at first composition state.photos is EMPTY - the identity anchor
    // can't be seeded from initialScrollIndex. The grid must still scroll to the restored position once
    // the photos arrive (via the flat fallback), or it sits at index 0 and then PERSISTS 0, wiping the
    // saved resume point. The earlier tests all pre-loaded photos in the initial state, hiding this.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coldStartWithPhotosLoadingAfterMount_scrollsToTheRestoredIndex() {
        val photos = (0 until 80).map { photoNamed("p$it") }
        lateinit var gridStateRef: LazyGridState
        val reported = mutableListOf<FlatIndex>()
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    // The host's retained state starts at 0 (exactly App.kt's `LazyGridState()`).
                    val gridState = rememberLazyGridState()
                    gridStateRef = gridState
                    // Photos arrive on a later frame, mirroring the async view-model load.
                    var photosLoaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { photosLoaded = true }
                    GridScreen(
                        state = GridUiState(
                            photos = if (photosLoaded) photos else emptyList(),
                            groups = if (photosLoaded) photos.map(PhotoGroup::Single) else emptyList(),
                            scope = CategoryScope.AllPhotos,
                            focusedIndex = TileIndex(-1),
                        ),
                        initialScrollIndex = 50, // the persisted resume point
                        retainedGridState = gridState,
                        anchorInitialScroll = true, // cold first visit
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        onFirstVisibleItemChanged = { reported += it },
                        imageLoader = noOpImageLoader,
                    )
                }
            }
        }
        rule.waitForIdle()

        // The grid landed on the restored region, not index 0.
        assertTrue(
            "cold start stayed at the top (index ${gridStateRef.firstVisibleItemIndex}) instead of restoring ~50",
            gridStateRef.firstVisibleItemIndex in 44..56,
        )
        // And it must NOT have persisted 0 as its final reported position (the resume-point wipe).
        assertTrue(
            "cold start persisted 0, wiping the saved resume point (reported=$reported)",
            reported.lastOrNull()?.let { it.value in 44..56 } == true,
        )
    }

    // --- Switching the grouping lens must keep the scroll position ------------------------------
    // The LazyGrid keys tiles by photo/group id, so a burst that EXPLODES back into singles keeps its
    // top frame pinned for free (the key survives). The jump happens the other way: the top photo gets
    // ABSORBED into a burst whose representative is a different frame, so the top tile's key VANISHES -
    // the LazyGrid can't retain it and clamps to a random spot. The screen must pin the photo that was
    // at the viewport top by identity, landing on whatever tile now contains it.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun switchingGroupingLens_keepsTheTopPhotoPinnedInsteadOfJumping() {
        val photos = (0 until 100).map { photoNamed("p$it") }
        // Time grouping collapses two runs into bursts: p0..p49 (one big leading burst) and p55..p64.
        // The top photo we park on, p60, is absorbed into the SECOND burst as a non-representative
        // frame, so its single-tile key (p60) disappears - exactly the case the LazyGrid can't retain.
        val timeGroups: List<PhotoGroup> = buildList {
            add(PhotoGroup.Burst(photos.subList(0, 50)))
            addAll(photos.subList(50, 55).map(PhotoGroup::Single))
            add(PhotoGroup.Burst(photos.subList(55, 65)))
            addAll(photos.subList(65, 100).map(PhotoGroup::Single))
        }
        val modeCalls = mutableListOf<GroupingMode>()
        // The flat photo index reported at the viewport top (column-independent, unlike a tile index).
        var reportedTopFlat = -1
        rule.setContent {
            AppTheme {
                // Wide enough that the toolbar's segmented lens control lays out un-clipped and is hittable.
                Surface(Modifier.size(800.dp, 600.dp)) {
                    // Open in the Off lens (flat singles), parked at p60 - tile 60 == flat 60 here.
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 60)
                    var state by remember {
                        mutableStateOf(
                            GridUiState(
                                photos = photos,
                                groups = photos.map(PhotoGroup::Single),
                                scope = CategoryScope.AllPhotos,
                                groupingMode = GroupingMode.Off,
                                focusedIndex = TileIndex(-1),
                            ),
                        )
                    }
                    GridScreen(
                        state = state,
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false, // already open: the retained scroll owns the position
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        onFirstVisibleItemChanged = { reportedTopFlat = it.value },
                        imageLoader = noOpImageLoader,
                        // Mirror the view model: the Time lens collapses the two bursts, Off is flat singles.
                        onSelectGroupingMode = { mode ->
                            modeCalls += mode
                            state = state.copy(
                                groupingMode = mode,
                                groups = if (mode == GroupingMode.Time) timeGroups else photos.map(PhotoGroup::Single),
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("retained scroll starts parked on ~p60", reportedTopFlat in 56..64)

        // Flip the lens to Time (label "Bursts") via the toolbar's segmented control.
        rule.onNodeWithContentDescription("Bursts").performClick()
        rule.waitForIdle()
        assertEquals("toolbar click switched the lens to Time", listOf(GroupingMode.Time), modeCalls)

        // p60 is now inside the p55..p64 burst (reported by its first frame, p55). Pinning the top
        // photo must keep the viewport on that run - NOT clamp to the far end of the shorter tile list.
        assertTrue(
            "lens switch jumped the grid (top flat $reportedTopFlat) instead of pinning the top photo's burst",
            reportedTopFlat in 50..64,
        )
    }

    // --- A reshape must NOT let the focus ring yank the viewport (the intermittent jump) -----------
    // The subtle case behind the lens-switch jump: the viewport top photo is in a region the reshape
    // leaves UNCHANGED (so the re-pin is a no-op and can't counteract anything), while a focus ring
    // sits far OFF-SCREEN on a photo the reshape moves a long way (the user arrowed there earlier, then
    // mouse-scrolled away). The old focus-into-view effect, keyed on the tile index, fired when the view
    // model re-anchored that ring to its new index and animated the viewport down to it - a jump nothing
    // counteracts. The fix gates focus-into-view on an actual user key move, so a reshape never moves
    // the viewport via focus. This isolates that gate: the re-pin is deliberately inert here.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lensSwitchWithOffscreenRing_doesNotScrollToTheReanchoredRing() {
        val photos = (0 until 100).map { photoNamed("p$it") }
        // Time grouping leaves the leading singles p0..p49 untouched (the viewport top lives here, so
        // the re-pin no-ops), collapses p50..p89 into ONE burst tile, and keeps p90..p99 as singles -
        // so the ring's photo p95 leaps from tile 95 up to tile 56.
        val timeGroups: List<PhotoGroup> = buildList {
            addAll(photos.subList(0, 50).map(PhotoGroup::Single))
            add(PhotoGroup.Burst(photos.subList(50, 90)))
            addAll(photos.subList(90, 100).map(PhotoGroup::Single))
        }
        var reportedTopFlat = -1
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    // Parked well inside the unchanged leading singles; the ring is far below, off-screen.
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 20)
                    var state by remember {
                        mutableStateOf(
                            GridUiState(
                                photos = photos,
                                groups = photos.map(PhotoGroup::Single),
                                scope = CategoryScope.AllPhotos,
                                groupingMode = GroupingMode.Off,
                                focusedIndex = TileIndex(95),
                            ),
                        )
                    }
                    GridScreen(
                        state = state,
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false, // already open: the retained scroll owns the position
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = { state = state.copy(focusedIndex = it) },
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        onFirstVisibleItemChanged = { reportedTopFlat = it.value },
                        imageLoader = noOpImageLoader,
                        onSelectGroupingMode = { mode ->
                            val newGroups = if (mode == GroupingMode.Time) timeGroups else photos.map(PhotoGroup::Single)
                            // Mimic GridViewModel.refocus: keep the cursor on the same photo's frame,
                            // whose tile index leaps once p50..p89 collapse into one burst.
                            val anchorId = state.displayGroups.getOrNull(state.focusedIndex.value)?.keyPhoto?.id
                            val newFocus = newGroups.indexOfFirst { g -> g.photos.any { it.id == anchorId } }
                            state = state.copy(
                                groupingMode = mode,
                                groups = newGroups,
                                focusedIndex = if (newFocus >= 0) TileIndex(newFocus) else state.focusedIndex,
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("retained scroll starts parked in the leading singles", reportedTopFlat in 10..30)

        // Flip the lens to Time. The ring (p95) is re-anchored to a tile far up the list; the old code
        // animated the viewport down to it. The viewport must stay put in the leading singles instead.
        rule.onNodeWithContentDescription("Bursts").performClick()
        rule.waitForIdle()
        assertTrue(
            "the focus ring yanked the viewport (top flat $reportedTopFlat) off the unchanged top region",
            reportedTopFlat < 40,
        )
    }

    // --- A SEQUENCE of lens switches must not let the viewport drift -----------------------------
    // The single-switch case pins fine, but repeated switches used to walk the viewport upward: each
    // switch re-derived the anchor from the live top, and a collapsed burst reports only its FIRST
    // frame, so a precise anchor (p60) degraded to a burst's first frame (p55, then p53...) one step
    // per switch. Going Off -> Time -> Similarity -> Off, the user lands well above where they started.
    // The fix holds the anchor by identity and KEEPS it whenever it is still inside the top tile, so a
    // round trip through grouping lenses returns to the same photo.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cyclingGroupingLenses_returnsToTheSameTopPhotoInsteadOfDrifting() {
        val photos = (0 until 100).map { photoNamed("p$it") }
        // Time absorbs p60 into a burst as a non-first frame (its first frame is p55).
        val timeGroups: List<PhotoGroup> = buildList {
            addAll(photos.subList(0, 55).map(PhotoGroup::Single))
            add(PhotoGroup.Burst(photos.subList(55, 65)))
            addAll(photos.subList(65, 100).map(PhotoGroup::Single))
        }
        // Similarity absorbs p60 into a DIFFERENT burst (first frame p53) - so a re-derived anchor would
        // degrade a second time, compounding the drift; an identity anchor that is kept does not.
        val similarityGroups: List<PhotoGroup> = buildList {
            addAll(photos.subList(0, 53).map(PhotoGroup::Single))
            add(PhotoGroup.Burst(photos.subList(53, 63)))
            addAll(photos.subList(63, 100).map(PhotoGroup::Single))
        }
        var reportedTopFlat = -1
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    // Open in Off (flat singles), parked at p60 - tile 60 == flat 60 here.
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 60)
                    var state by remember {
                        mutableStateOf(
                            GridUiState(
                                photos = photos,
                                groups = photos.map(PhotoGroup::Single),
                                scope = CategoryScope.AllPhotos,
                                groupingMode = GroupingMode.Off,
                                focusedIndex = TileIndex(-1),
                            ),
                        )
                    }
                    GridScreen(
                        state = state,
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false, // already open: the retained scroll owns the position
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        onFirstVisibleItemChanged = { reportedTopFlat = it.value },
                        imageLoader = noOpImageLoader,
                        onSelectGroupingMode = { mode ->
                            state = state.copy(
                                groupingMode = mode,
                                groups = when (mode) {
                                    GroupingMode.Time -> timeGroups
                                    GroupingMode.Similarity -> similarityGroups
                                    GroupingMode.Off -> photos.map(PhotoGroup::Single)
                                },
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("retained scroll starts parked on ~p60", reportedTopFlat in 56..64)

        // Cycle through every lens and back to Single without ever touching the scroll.
        rule.onNodeWithContentDescription("Bursts").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Similar").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Single").performClick()
        rule.waitForIdle()

        // Back in Off, the viewport must be on p60's run again - not drifted up to ~p53 by the
        // burst-first-frame degradation the old re-derive-every-switch path produced.
        assertTrue(
            "cycling the lenses drifted the viewport (top flat $reportedTopFlat) off the start photo",
            reportedTopFlat in 56..64,
        )
    }

    // --- A scrollbar drag DURING a settle must win, not get re-pinned away -------------------------
    // A scrollbar drag reaches the grid through the scrollable, NOT the Column's pointer modifier, so it
    // emits no PointerEventType.Scroll - the only signal that the user grabbed the scrollbar is the
    // scrollbar's own DragInteraction. If a grouping settle (the cold similarity pass completing) lands
    // while the user is mid-drag, the re-pin must stand down and leave them where they dragged - the gap
    // that used to yank a scrollbar-drag-during-settle back to the anchor. A real thin-scrollbar drag
    // isn't hittable headlessly, so this drives that interaction source directly and asserts the drag holds.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun scrollbarDragDuringSettle_keepsTheUsersScrollInsteadOfRepinning() {
        val photos = (0 until 100).map { photoNamed("p$it") }
        // Two settle arrangements that both absorb p60 into a burst, so a live re-pin (anchor = p60)
        // would pull the viewport back toward p60's tile if it ever fired.
        val timeGroups: List<PhotoGroup> = buildList {
            addAll(photos.subList(0, 55).map(PhotoGroup::Single))
            add(PhotoGroup.Burst(photos.subList(55, 65)))
            addAll(photos.subList(65, 100).map(PhotoGroup::Single))
        }
        val similarityGroups: List<PhotoGroup> = buildList {
            addAll(photos.subList(0, 53).map(PhotoGroup::Single))
            add(PhotoGroup.Burst(photos.subList(53, 63)))
            addAll(photos.subList(63, 100).map(PhotoGroup::Single))
        }
        lateinit var scrollbarSource: MutableInteractionSource
        lateinit var dragScrollAway: () -> Unit
        lateinit var landSettle: () -> Unit
        var reportedTopFlat = -1
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    // Open in Off (flat singles), parked at p60 - tile 60 == flat 60 here.
                    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 60)
                    val source = remember { MutableInteractionSource() }
                    scrollbarSource = source
                    var groups by remember { mutableStateOf<List<PhotoGroup>>(photos.map(PhotoGroup::Single)) }
                    var mode by remember { mutableStateOf(GroupingMode.Off) }
                    // A programmatic scroll stands in for where the user's scrollbar drag LANDS; the drag
                    // INTENT is the DragInteraction emitted separately. Like a real scrollToItem it emits
                    // no user-scroll signal of its own - so only the scrollbar wiring can mark user-owned.
                    var scrollAwayTo by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(scrollAwayTo) { scrollAwayTo?.let { gridState.scrollToItem(it) } }
                    dragScrollAway = { scrollAwayTo = 5 }
                    // A background settle (the cold similarity pass completing) reshapes the grid WITHOUT a
                    // toolbar switch - so captureTop never runs and the held anchor stays p60.
                    landSettle = { groups = similarityGroups }
                    GridScreen(
                        state = GridUiState(
                            photos = photos,
                            groups = groups,
                            scope = CategoryScope.AllPhotos,
                            groupingMode = mode,
                            focusedIndex = TileIndex(-1),
                        ),
                        initialScrollIndex = 0,
                        retainedGridState = gridState,
                        anchorInitialScroll = false, // already open: the retained scroll owns the position
                        scrollbarInteraction = source,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        onFirstVisibleItemChanged = { reportedTopFlat = it.value },
                        imageLoader = noOpImageLoader,
                        onSelectGroupingMode = { m ->
                            mode = m
                            groups = if (m == GroupingMode.Time) timeGroups else photos.map(PhotoGroup::Single)
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue("retained scroll starts parked on ~p60", reportedTopFlat in 56..64)

        // Establish the identity anchor on p60 the real way: a toolbar lens switch captures the live top
        // (p60) and re-pins to it. After this the held anchor is p60.
        rule.onNodeWithContentDescription("Bursts").performClick()
        rule.waitForIdle()

        // The user grabs the scrollbar (DragInteraction.Start) and drags up to the top of the list.
        rule.runOnIdle { scrollbarSource.tryEmit(DragInteraction.Start()) }
        rule.runOnIdle { dragScrollAway() }
        rule.waitForIdle()
        assertTrue("the scrollbar drag moved the viewport up the list", reportedTopFlat < 30)

        // The settle lands while the drag is in progress. The re-pin must stand down: the viewport stays
        // where the user dragged, NOT yanked back down to p60's burst (~tile 53) the way it would without
        // the scrollbar's DragInteraction releasing the re-pin.
        rule.runOnIdle { landSettle() }
        rule.waitForIdle()
        assertTrue(
            "a settle during a scrollbar drag yanked the viewport back to the anchor (top flat $reportedTopFlat)",
            reportedTopFlat < 30,
        )
    }

    private fun photoNamed(id: String) = Photo(
        id = PhotoId(id),
        absolutePath = Path.of("/photos/$id.jpg"),
        relativePath = "$id.jpg",
        fileName = "$id.jpg",
        sizeBytes = 1,
        lastModifiedEpochMs = 0,
    )
}
