package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.designsystem.organism.LibraryRail
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.grid.GridScreen
import com.vishalgupta.photoselector.presentation.grid.GridUiState
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

/**
 * Renders the redesigned grid shell — the left [LibraryRail] beside the slimmed top bar — by
 * assembling the rail next to the real [GridScreen] exactly as the navigation host (App) does (the
 * rail is hoisted out of the grid so it survives scope switches). Eyeball the PNGs under
 * `build/screenshots/`:
 *  - `library-rail-all-photos`: rail expanded, "All Photos" active, Favourites + two custom
 *    categories with counts, the top bar carrying only identity + the grouping toggle (no Export
 *    in All Photos), and the far-left collapse toggle.
 *  - `library-rail-category`: a custom category active (highlighted row), with the top bar's
 *    consolidated **Export** menu showing for the exportable scope.
 *  - `library-rail-collapsed`: rail hidden, grid full-bleed, the top bar showing the "Show sidebar"
 *    toggle on the far left.
 */
class LibraryRailScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private val photos = listOf("a", "b", "c", "d", "e", "f").map { id ->
        Photo(
            id = PhotoId(id),
            absolutePath = Path.of("/photos/$id.jpg"),
            relativePath = "$id.jpg",
            fileName = "$id.jpg",
            sizeBytes = 1024,
            lastModifiedEpochMs = 0,
        )
    }

    private val keepers = Category(CategoryId("keepers"), "Keepers", builtIn = false)
    private val portfolio = Category(CategoryId("portfolio"), "Portfolio", builtIn = false)
    private val categories = listOf(Category.favourites(), Category.rejects(), keepers, portfolio)

    private val memberships = mapOf(
        Category.FAVOURITES_ID to setOf(photos[0].id, photos[2].id),
        // Rejects: drives the rail's Rejects row + its "Move rejects to Trash" sweep action, and the
        // dimmed + flagged reject state on tiles b and f in the grid.
        Category.REJECTS_ID to setOf(photos[1].id, photos[5].id),
        keepers.id to setOf(photos[0].id, photos[1].id, photos[3].id),
        portfolio.id to setOf(photos[4].id),
    )

    private val palette = mapOf(
        "a" to Color(0xFF6FCF97), "b" to Color(0xFF56CCF2), "c" to Color(0xFF2F80ED),
        "d" to Color(0xFF9B51E0), "e" to Color(0xFFF2994A), "f" to Color(0xFFEB5757),
    )

    private val colorLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap =
            solid(palette[photo.id.value] ?: Color.Gray)

        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @Test fun `rail expanded over all photos`() {
        renderShell(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.AllPhotos,
                categories = categories,
                memberships = memberships,
            ),
        )
        rule.dumpScreenshot("library-rail-all-photos")
    }

    @Test fun `rail highlights the active category and the bar shows export`() {
        renderShell(
            GridUiState(
                // A category scope shows just its members; the rail still lists the whole library.
                photos = listOf(photos[0], photos[1], photos[3]),
                groups = listOf(photos[0], photos[1], photos[3]).map(PhotoGroup::Single),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.Category(keepers.id),
                categories = categories,
                memberships = memberships,
            ),
        )
        rule.dumpScreenshot("library-rail-category")
    }

    @Test fun `a long category name ellipsises and keeps the top-bar cluster intact`() {
        // Regression: an over-long scope name must not push the Export menu / lens toggle off the
        // right edge. Eyeball build/screenshots/library-rail-long-name.png — the title ends in "…",
        // and the Export + grouping controls stay full-width on the right.
        val verbose = Category(CategoryId("verbose"), "MyCategory11WithReallyLongNameeeeeeeeeeeeeeee", builtIn = false)
        renderShell(
            GridUiState(
                photos = emptyList(),
                groups = emptyList(),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.Category(verbose.id),
                categories = listOf(Category.favourites(), verbose),
                memberships = emptyMap(),
            ),
        )
        rule.dumpScreenshot("library-rail-long-name")
    }

    @Test fun `selection bar shows favourite and reject, grid dims rejected tiles`() {
        // Eyeball build/screenshots/library-rail-selection-reject.png: the selection top bar carries
        // both a Favourite and a Reject button (the two sides of the cull) plus Delete; tiles b and f
        // (rejected) are dimmed under a scrim with a red flag top-end.
        renderShell(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.AllPhotos,
                categories = categories,
                memberships = memberships,
                selection = setOf(photos[0].id, photos[2].id),
            ),
        )
        rule.dumpScreenshot("library-rail-selection-reject")
    }

    @Test fun `rail collapsed leaves the grid full-bleed`() {
        renderShell(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.AllPhotos,
                categories = categories,
                memberships = memberships,
            ),
            railCollapsed = true,
        )
        rule.dumpScreenshot("library-rail-collapsed")
    }

    @Test fun `a long category list scrolls while the new-category action stays pinned`() {
        // Regression: only the custom-category list scrolls. With more categories than fit, the
        // "Categories" label above and the "New category" action below must stay pinned — eyeball
        // build/screenshots/library-rail-scrolling-list.png: "New category" sits at the bottom and
        // the list is clipped between it and the label, not run off the rail.
        val many = (1..20).map { Category(CategoryId("c$it"), "Category $it", builtIn = false) }
        renderShell(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()) + many,
                memberships = emptyMap(),
            ),
        )
        rule.dumpScreenshot("library-rail-scrolling-list")
    }

    private fun renderShell(state: GridUiState, railCollapsed: Boolean = false) {
        // Mirror the host: the rail sits beside the grid in a Row (and is simply absent when
        // collapsed). entries are derived from the same categories+memberships the grid carries, so a
        // rail count matches a tile badge — exactly the production invariant (now via a shared flow).
        val entries = state.categories.map { it to (state.memberships[it.id]?.size ?: 0) }
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(1100.dp, 560.dp)) {
                    Row(Modifier.fillMaxSize()) {
                        if (!railCollapsed) {
                            LibraryRail(
                                rootName = "Iceland 2026",
                                scope = state.scope,
                                entries = entries,
                                onSelectAllPhotos = {},
                                onSelectCategory = {},
                                onCreateCategory = {},
                                onRenameCategory = { _, _ -> },
                                onDeleteCategory = {},
                                onEmptyRejects = {},
                                onChangeFolder = {},
                            )
                        }
                        GridScreen(
                            state = state,
                            initialScrollIndex = 0,
                            railCollapsed = railCollapsed,
                            onToggleRail = {},
                            onTileClick = {},
                            onChangeFolder = {},
                            onBack = if (state.scope is CategoryScope.Category) ({}) else null,
                            onSetFocusedIndex = {},
                            onToggleMembershipAtFocus = {},
                            onToggleCustomCategoryAtFocus = {},
                            onExportTxt = {},
                            onCopyToFolder = {},
                            imageLoader = colorLoader,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun solid(color: Color): ImageBitmap {
        val bmp = ImageBitmap(96, 96)
        Canvas(bmp).drawRect(0f, 0f, 96f, 96f, Paint().apply { this.color = color })
        return bmp
    }
}
