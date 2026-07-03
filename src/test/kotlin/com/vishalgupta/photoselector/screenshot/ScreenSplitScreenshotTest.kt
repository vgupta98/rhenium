package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.browser.BrowserScreen
import com.vishalgupta.photoselector.presentation.browser.BrowserUiState
import com.vishalgupta.photoselector.presentation.common.CategoryToggle
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BrowserKeyboardLegend
import com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserCategoryHud
import com.vishalgupta.photoselector.presentation.designsystem.organism.LibraryRail
import com.vishalgupta.photoselector.presentation.grid.GridScreen
import com.vishalgupta.photoselector.presentation.grid.GridUiState
import com.vishalgupta.photoselector.presentation.grid.TileIndex
import com.vishalgupta.photoselector.presentation.survey.SurveyScreen
import com.vishalgupta.photoselector.presentation.survey.SurveyTile
import com.vishalgupta.photoselector.presentation.survey.SurveyUiState
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import com.vishalgupta.photoselector.presentation.rootpicker.RootFolderPickerScreen
import com.vishalgupta.photoselector.presentation.rootpicker.RootPickerUiState
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

class ScreenSplitScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val selectsId = CategoryId("selects")
    private val categories = listOf(Category.favourites(), Category(selectsId, "Selects", builtIn = false))

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

    // A fuller library so a density screenshot actually shows the column count and gutters,
    // not just two tiles in a corner.
    private val manyPhotos = (0 until 18).map { i ->
        Photo(
            id = PhotoId("p$i"),
            absolutePath = Path.of("/photos/img$i.jpg"),
            relativePath = "img$i.jpg",
            fileName = "img$i.jpg",
            sizeBytes = 1024,
            lastModifiedEpochMs = 0,
        )
    }

    private val noOpImageLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap? = null
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @androidx.compose.runtime.Composable
    private fun Grid(
        state: GridUiState,
        onBack: (() -> Unit)?,
        categoryToast: CategoryToggle? = null,
        resultToast: String? = null,
        modifier: Modifier = Modifier,
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
            categoryToast = categoryToast,
            resultToast = resultToast,
            modifier = modifier,
        )
    }

    // The rail beside the grid, assembled as the navigation host (App) does it — for the tests that
    // exercise rail affordances (its rows, the "⋯" actions menu, "Change folder"). Other grid tests
    // host the bare [Grid] since the rail is no longer part of GridScreen.
    @androidx.compose.runtime.Composable
    private fun RailAndGrid(state: GridUiState, onBack: (() -> Unit)?) {
        Row(Modifier.fillMaxSize()) {
            LibraryRail(
                rootName = "Photos",
                scope = state.scope,
                entries = state.categories.map { it to (state.memberships[it.id]?.size ?: 0) },
                onSelectAllPhotos = {},
                onSelectCategory = {},
                onCreateCategory = {},
                onRenameCategory = { _, _ -> },
                onDeleteCategory = {},
                onChangeFolder = {},
            )
            Grid(state = state, onBack = onBack, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }

    // --- RootFolderPickerScreen ---

    @Test
    fun rootPicker_idle() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(600.dp, 400.dp)) {
                    RootFolderPickerScreen(
                        state = RootPickerUiState(),
                        onPickFolder = {},
                        onCancelScan = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("root-picker-idle")
    }

    @Test
    fun rootPicker_scanning() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(600.dp, 400.dp)) {
                    RootFolderPickerScreen(
                        state = RootPickerUiState(
                            phase = RootPickerUiState.Phase.Scanning,
                            scanned = 142,
                            found = 37,
                        ),
                        onPickFolder = {},
                        onCancelScan = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("root-picker-scanning")
    }

    @Test
    fun rootPicker_failed() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(600.dp, 400.dp)) {
                    RootFolderPickerScreen(
                        state = RootPickerUiState(
                            phase = RootPickerUiState.Phase.Failed,
                            errorMessage = "Permission denied",
                        ),
                        onPickFolder = {},
                        onCancelScan = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("root-picker-failed")
    }

    // --- GridScreen ---

    @Test
    fun grid_allPhotos() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(Category.FAVOURITES_ID to setOf(PhotoId("a"))),
                            lastViewedPhotoId = PhotoId("b"),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-all-photos")
    }

    @Test
    fun grid_density() {
        // A filled grid at two widths to verify the contact-sheet density (column count +
        // tight gutters) reads well and isn't tuned to one test-window size.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1100.dp, 700.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = manyPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(
                                Category.FAVOURITES_ID to setOf(PhotoId("p1"), PhotoId("p4")),
                                selectsId to setOf(PhotoId("p2")),
                            ),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-density-wide")
    }

    @Test
    fun grid_multiSelect() {
        // A live multi-select: three tiles picked out (accent ring + check + scale-down), one of
        // them also favourited, with the selection top bar swapped in over the normal chrome.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1100.dp, 700.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = manyPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(
                                Category.FAVOURITES_ID to setOf(PhotoId("p2")),
                                selectsId to setOf(PhotoId("p7")),
                            ),
                            selection = setOf(PhotoId("p2"), PhotoId("p3"), PhotoId("p6")),
                            anchorIndex = TileIndex(6),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-multi-select")
    }

    @Test
    fun grid_deleteConfirm() {
        // The Delete button in the selection bar is a destructive action, so it opens an
        // error-tinted confirm naming the count before anything is moved to the Trash. Capture
        // the dialog so the copy and the destructive styling stay honest.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1100.dp, 700.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = manyPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            selection = setOf(PhotoId("p2"), PhotoId("p3"), PhotoId("p6")),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Move 3 photos to Trash?").assertIsDisplayed()
        // The dialog renders in its own popup root; capture that, not the base screen.
        rule.dumpScreenshot("grid-delete-confirm", rule.onAllNodes(isRoot()).onLast())
    }

    @Test
    fun grid_densityNarrow() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(640.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = manyPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(Category.FAVOURITES_ID to setOf(PhotoId("p1"))),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-density-narrow")
    }

    @Test
    fun grid_tileFeedback() {
        // Photo "a" is favourited (star) AND filed in the custom "Selects" category (badge "1",
        // its 1-key slot); the pill names the action that just landed. Exercises both the
        // persistent on-tile cues and the transient confirmation together.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(
                                Category.FAVOURITES_ID to setOf(PhotoId("a")),
                                selectsId to setOf(PhotoId("a")),
                            ),
                            focusedIndex = TileIndex(0),
                        ),
                        onBack = null,
                        categoryToast = CategoryToggle(
                            categoryName = "Selects",
                            isFavourite = false,
                            added = true,
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-tile-feedback")
    }

    @Test
    fun grid_messagePill() {
        // Bulk / library-level notices (export, copy, the survey cap) now render in the app's pill
        // chrome instead of a stock Material snackbar. Captured with the survey cap message.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = manyPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                        ),
                        resultToast = "Select up to 12 photos to compare side by side",
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-message-pill")
    }

    @Test
    fun grid_changeFolderConfirm() {
        // Clicking "Change folder" must not tear the session down on a stray click — it opens
        // a confirm dialog first. Capture the dialog so the guard's copy stays honest (it
        // promises favourites/categories are saved, which they are).
        // Use the real app window width (1280dp): this test clicks "Change folder", the rightmost
        // top-bar control. At the cramped 800dp other tests use, the bar overflows under CI's wider
        // Linux font metrics — the weight(1f) spacer collapses and the button slides off-screen, so
        // the click misses and no dialog opens. A realistic width keeps the interaction reliable.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1280.dp, 600.dp)) {
                    RailAndGrid(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(Category.FAVOURITES_ID to setOf(PhotoId("a"))),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Change folder").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Change folder?").assertIsDisplayed()
        // The dialog renders in its own popup root; capture that, not the base screen.
        rule.dumpScreenshot("grid-change-folder-confirm", rule.onAllNodes(isRoot()).onLast())
    }

    @Test
    fun grid_favouritesCategory() {
        val favPhotos = testPhotos.take(1)
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = favPhotos,
                            scope = CategoryScope.Category(Category.FAVOURITES_ID),
                            categories = categories,
                            memberships = mapOf(Category.FAVOURITES_ID to setOf(PhotoId("a"))),
                        ),
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-favourites-category")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun grid_customCategoryActionsMenuOpen() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    RailAndGrid(
                        state = GridUiState(
                            photos = testPhotos.take(1),
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            memberships = mapOf(selectsId to setOf(PhotoId("a"))),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        // The footer legend's F hint is always "Favourite": F files into Favourites in every
        // scope (GridViewModel.toggleMembershipAtFocus), so it must never claim to toggle a
        // custom category.
        rule.onNodeWithText("Favourite").assertIsDisplayed()
        rule.onNodeWithText("Toggle Selects").assertDoesNotExist()
        // Rename / delete moved to the rail's per-category "⋯". The clickable row merges the button's
        // semantics, so reach it through the unmerged tree, then open it.
        rule.onNodeWithContentDescription("Category actions", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Rename…").assertIsDisplayed()
        rule.onNodeWithText("Delete…").assertIsDisplayed()
        rule.dumpScreenshot("grid-custom-category-actions-menu", rule.onAllNodes(isRoot()).onLast())
    }

    @Test
    fun grid_focused() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            photos = testPhotos,
                            scope = CategoryScope.AllPhotos,
                            categories = categories,
                            focusedIndex = TileIndex(0),
                        ),
                        onBack = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-focused")
    }

    @Test
    fun grid_empty() {
        // All Photos with nothing in the folder: a fitting icon and a way forward, not an error.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(state = GridUiState(categories = categories), onBack = null)
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-empty")
    }

    @Test
    fun grid_favouritesEmpty() {
        // Empty Favourites teaches the F key that fills it.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            scope = CategoryScope.Category(Category.FAVOURITES_ID),
                            categories = categories,
                        ),
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-favourites-empty")
    }

    @Test
    fun grid_customCategoryEmpty() {
        // Empty custom category teaches its own digit key (Selects is the 1st custom slot -> "1").
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    Grid(
                        state = GridUiState(
                            scope = CategoryScope.Category(selectsId),
                            categories = categories,
                        ),
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("grid-custom-category-empty")
    }

    // --- BrowserScreen ---

    @Test
    fun browser_loading() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = testPhotos,
                            currentIndex = 0,
                            currentPhoto = testPhotos[0],
                            currentBitmap = null,
                            isLoadingBitmap = true,
                            isCurrentFavourite = false,
                            readOnly = false,
                        ),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("browser-loading")
    }

    @Test
    fun browser_empty() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState.initial(emptyList()),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("browser-empty")
    }

    @Test
    fun browser_withToast() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = testPhotos,
                            currentIndex = 0,
                            currentPhoto = testPhotos[0],
                            currentBitmap = ImageBitmap(200, 150),
                            isLoadingBitmap = false,
                            isCurrentFavourite = true,
                            readOnly = false,
                        ),
                        toast = CategoryToggle("Favourites", isFavourite = true, added = true),
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("browser-with-toast")
    }

    @Test
    fun browser_withRemoveToast() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = testPhotos,
                            currentIndex = 0,
                            currentPhoto = testPhotos[0],
                            currentBitmap = ImageBitmap(200, 150),
                            isLoadingBitmap = false,
                            isCurrentFavourite = false,
                            readOnly = false,
                            categories = categories,
                        ),
                        toast = CategoryToggle("Selects", isFavourite = false, added = false),
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("browser-with-remove-toast")
    }

    @Test
    fun browser_showInAllPhotos() {
        // Browsing a category photo: the top bar gains a muted "Show in All Photos" action that jumps to
        // the photo in the main grid. Wired (non-null) only in a category browser, so capture it there.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(900.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = testPhotos,
                            currentIndex = 0,
                            currentPhoto = testPhotos[0],
                            currentBitmap = ImageBitmap(200, 150),
                            isLoadingBitmap = false,
                            isCurrentFavourite = true,
                            readOnly = false,
                        ),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                        onShowInAllPhotos = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Show in All Photos").assertIsDisplayed()
        rule.dumpScreenshot("browser-show-in-all-photos")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun browser_deleteConfirm() {
        // Cmd+Delete in the browser arms the same destructive move-to-Trash confirm as the grid,
        // scoped to the single photo on screen. Driven by the keyboard since there's no button.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = testPhotos,
                            currentIndex = 0,
                            currentPhoto = testPhotos[0],
                            currentBitmap = ImageBitmap(200, 150),
                            isLoadingBitmap = false,
                            isCurrentFavourite = false,
                            readOnly = false,
                        ),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Backspace) } }
        rule.waitForIdle()
        rule.onNodeWithText("Move this photo to Trash?").assertIsDisplayed()
        rule.dumpScreenshot("browser-delete-confirm", rule.onAllNodes(isRoot()).onLast())
    }

    // --- Inspect: grid mode (the overview-pick grid facet) ---

    private fun surveyTile(
        index: Int,
        photo: Photo,
        loaded: Boolean = true,
        isFavourite: Boolean = false,
        memberships: Set<CategoryId> = emptySet(),
    ) = SurveyTile(
        index = index,
        photo = photo,
        bitmap = if (loaded) ImageBitmap(200, 150) else null,
        isLoading = !loaded,
        isFavourite = isFavourite,
        memberships = memberships,
    )

    // The grid is always hosted as Inspect's grid facet, so it carries Inspect's title and the
    // browse toggle (the Fullscreen button + the `Enter` legend hint).
    @androidx.compose.runtime.Composable
    private fun Survey(state: SurveyUiState) {
        SurveyScreen(
            state = state,
            onSetActive = {},
            onMoveActive = {},
            onToggleCategory = {},
            onViewportSizeChanged = {},
            onExit = {},
            title = "Inspect",
            onOpenActive = {},
        )
    }

    @Test
    fun inspectGrid_threeUp() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Survey(
                        SurveyUiState(
                            tiles = listOf(
                                surveyTile(0, manyPhotos[0], isFavourite = true, memberships = setOf(Category.FAVOURITES_ID)),
                                surveyTile(1, manyPhotos[1]),
                                surveyTile(2, manyPhotos[2], memberships = setOf(selectsId)),
                            ),
                            activeTile = 1,
                            totalInScope = manyPhotos.size,
                            readOnly = false,
                            categories = categories,
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("inspect-grid-three-up")
    }

    @Test
    fun inspectGrid_fourUpWithLoadingTile() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Survey(
                        SurveyUiState(
                            tiles = listOf(
                                surveyTile(0, manyPhotos[0]),
                                surveyTile(1, manyPhotos[1], isFavourite = true, memberships = setOf(Category.FAVOURITES_ID)),
                                surveyTile(2, manyPhotos[2], loaded = false),
                                surveyTile(3, manyPhotos[3]),
                            ),
                            activeTile = 0,
                            totalInScope = manyPhotos.size,
                            readOnly = false,
                            categories = categories,
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("inspect-grid-four-up")
    }

    @Test
    fun inspectGrid_twelveUp() {
        // The dense end of the grid range (the MAX_INSPECT_GRID_PHOTOS cap) lays out 4x3. This is where
        // tiles get smallest, so capture it to confirm the chrome and fitted tiles still read at that
        // size. Past this a set opens browse-only instead (see inspectBrowse_* below).
        rule.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Survey(
                        SurveyUiState(
                            tiles = (0 until 12).map { i ->
                                surveyTile(
                                    index = i,
                                    photo = manyPhotos[i],
                                    isFavourite = i == 0,
                                    memberships = if (i == 0) setOf(Category.FAVOURITES_ID) else emptySet(),
                                )
                            },
                            activeTile = 5,
                            totalInScope = manyPhotos.size,
                            readOnly = false,
                            categories = categories,
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("inspect-grid-twelve-up")
    }

    // --- Inspect: browse mode (the embedded full-screen browser facet) ---

    @Test
    fun inspectBrowse_withGridToggle() {
        // A grid-sized set in browse mode: the top bar drops the library chrome (Favourites / Change
        // folder) and shows only the "grid view" toggle back to the overview. "2 / 5" reads as the
        // position within the inspected set, not the whole folder.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(900.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = manyPhotos.take(5),
                            currentIndex = 1,
                            currentPhoto = manyPhotos[1],
                            currentBitmap = ImageBitmap(200, 150),
                            isLoadingBitmap = false,
                            isCurrentFavourite = false,
                            readOnly = false,
                            categories = categories,
                        ),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                        embedded = true,
                        onSwitchToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Grid view").assertIsDisplayed()
        rule.dumpScreenshot("inspect-browse-with-grid-toggle")
    }

    @Test
    fun inspectBrowse_browseOnly() {
        // A set past the grid cap is browse-only: still embedded (no library chrome) but with no grid
        // to return to, so the grid-view toggle is absent too — only Back remains.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(900.dp, 600.dp)) {
                    BrowserScreen(
                        state = BrowserUiState(
                            photos = manyPhotos,
                            currentIndex = 0,
                            currentPhoto = manyPhotos[0],
                            currentBitmap = ImageBitmap(200, 150),
                            isLoadingBitmap = false,
                            isCurrentFavourite = false,
                            readOnly = false,
                            categories = categories,
                        ),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                        embedded = true,
                        onSwitchToGrid = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("inspect-browse-only")
    }

    // --- BrowserCategoryHud ---
    // The HUD auto-hides inside the live browser (reveal on key/mouse), so its appearance
    // is captured by rendering the organism directly over a photo-like dark backdrop.

    @Test
    fun browser_categoryHud() {
        val maybesId = CategoryId("maybes")
        val hudCategories = listOf(
            Category.favourites(),
            Category(selectsId, "Selects", builtIn = false),
            Category(maybesId, "Maybes", builtIn = false),
        )
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(800.dp, 160.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserCategoryHud(
                        categories = hudCategories,
                        currentMemberships = setOf(Category.FAVOURITES_ID, selectsId),
                        onToggle = {},
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Favourites").assertIsDisplayed()
        rule.onNodeWithText("Selects").assertIsDisplayed()
        rule.onNodeWithText("Maybes").assertIsDisplayed()
        rule.dumpScreenshot("browser-category-hud")
    }

    @Test
    fun browser_categoryHudEmpty() {
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(800.dp, 160.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserCategoryHud(
                        categories = listOf(Category.favourites()),
                        currentMemberships = emptySet(),
                        onToggle = {},
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Favourites").assertIsDisplayed()
        rule.dumpScreenshot("browser-category-hud-empty")
    }

    // --- BrowserKeyboardLegend ---
    // Like the HUD, the legend folds into the browser's auto-hiding bottom stack, so its
    // appearance is captured by rendering it directly over a photo-like dark backdrop.

    @Test
    fun browser_keyboardLegend() {
        // Rendered at the browser's full-bleed window width (it defaults to 1280dp) so the full
        // hint set reads as one non-wrapping strip, the way it does over a real photo.
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(1100.dp, 120.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserKeyboardLegend(
                        hasCustomCategories = true,
                        readOnly = false,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        // Neutral verbs (no "Finder"/"Quick Look") plus the filing keys when categories exist.
        rule.onNodeWithText("Favourite").assertIsDisplayed()
        rule.onNodeWithText("Categories").assertIsDisplayed()
        rule.onNodeWithText("Reveal").assertIsDisplayed()
        // Standalone browser: `C` opens Inspect, so the hint shows and the grid-return hint doesn't.
        rule.onNodeWithText("Compare").assertIsDisplayed()
        rule.onNodeWithText("Grid").assertDoesNotExist()
        rule.dumpScreenshot("browser-keyboard-legend")
    }

    @Test
    fun browser_keyboardLegendEmbedded() {
        // Embedded in Inspect's browse facet: `C` is inert, so the "Compare" hint drops out, and a
        // "Grid" hint takes its place pointing back to the overview behind the toggle.
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(1100.dp, 120.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserKeyboardLegend(
                        hasCustomCategories = true,
                        readOnly = false,
                        canCompare = false,
                        canReturnToGrid = true,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Compare").assertDoesNotExist()
        rule.onNodeWithText("Grid").assertIsDisplayed()
        rule.dumpScreenshot("browser-keyboard-legend-embedded")
    }

    @Test
    fun browser_keyboardLegendInCategory() {
        // Browsing a category adds the "A All Photos" hint (the jump to the main grid). Captured at the
        // browser's real full-bleed width (1280dp) so the now-longer strip reads as one non-wrapping line.
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(1280.dp, 120.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserKeyboardLegend(
                        hasCustomCategories = true,
                        readOnly = false,
                        canShowInAllPhotos = true,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("All Photos").assertIsDisplayed()
        rule.dumpScreenshot("browser-keyboard-legend-in-category")
    }

    @Test
    fun browser_keyboardLegendReadOnly() {
        // A read-only folder can't file: the F / 1-9 filing hints drop out, leaving navigation
        // and the (still-valid) system actions.
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(800.dp, 120.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserKeyboardLegend(
                        hasCustomCategories = true,
                        readOnly = true,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Move").assertIsDisplayed()
        rule.onNodeWithText("Reveal").assertIsDisplayed()
        rule.dumpScreenshot("browser-keyboard-legend-read-only")
    }

    @Test
    fun browser_bottomStack() {
        // The HUD chips and the legend share the browser's auto-hiding bottom-center stack
        // (a Column, as BrowserScreen arranges them). Capture them together to confirm they
        // read as one chrome surface and don't fight each other.
        val maybesId = CategoryId("maybes")
        val hudCategories = listOf(
            Category.favourites(),
            Category(selectsId, "Selects", builtIn = false),
            Category(maybesId, "Maybes", builtIn = false),
        )
        rule.setContent {
            AppTheme {
                Box(
                    Modifier.size(1100.dp, 200.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                    ) {
                        BrowserCategoryHud(
                            categories = hudCategories,
                            currentMemberships = setOf(Category.FAVOURITES_ID, selectsId),
                            onToggle = {},
                        )
                        BrowserKeyboardLegend(hasCustomCategories = true, readOnly = false)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Maybes").assertIsDisplayed()
        rule.onNodeWithText("Categories").assertIsDisplayed()
        rule.dumpScreenshot("browser-bottom-stack")
    }
}
