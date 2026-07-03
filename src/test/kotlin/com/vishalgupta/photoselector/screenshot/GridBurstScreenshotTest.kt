package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.grid.GridScreen
import com.vishalgupta.photoselector.presentation.grid.GridUiState
import com.vishalgupta.photoselector.presentation.grid.GroupingStatus
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

/**
 * Renders the grid with a burst collapsed to a single tile (its key frame + a "N" burst badge)
 * sitting among ordinary single-photo tiles, through the real [GridScreen]. Eyeball
 * `build/screenshots/grid-burst-collapsed.png`: the third tile should carry the stacked-frames
 * badge with the count, and the singles around it should look unchanged.
 */
class GridBurstScreenshotTest {

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

    // a | [b c d] burst | e | f  -> four tiles, the middle frame (c) represents the burst.
    private val groups = listOf(
        PhotoGroup.Single(photos[0]),
        PhotoGroup.Burst(listOf(photos[1], photos[2], photos[3])),
        PhotoGroup.Single(photos[4]),
        PhotoGroup.Single(photos[5]),
    )

    private val colors = mapOf(
        "a" to Color(0xFF6FCF97), "b" to Color(0xFF56CCF2), "c" to Color(0xFF2F80ED),
        "d" to Color(0xFF9B51E0), "e" to Color(0xFFF2994A), "f" to Color(0xFFEB5757),
    )

    private val colorLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap =
            solid(colors[photo.id.value] ?: Color.Gray)

        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @Test fun `a burst collapses to one badged tile among singles`() {
        // Grouping on (the toolbar chip is selected): six photos -> four tiles, the middle frame
        // standing in for the burst with a stacked-frames "3" badge.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = groups,
                groupingMode = GroupingMode.Time,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-burst-collapsed")
    }

    @Test fun `an expanded burst unfolds inline under a full-width header`() {
        // Clicking the burst opens it in place: a full-width header, then its three frames as
        // individual tiles (dim bracket ring), with the surrounding singles untouched.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = groups,
                groupingMode = GroupingMode.Time,
                expandedBurstId = groups[1].groupId, // the burst tile
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-burst-expanded")
    }

    @Test fun `a collapsed burst shows the last-viewed marker for any frame, not just its key`() {
        // Open a burst, view its first frame (b, not the middle key c), come back: the burst
        // collapses again and must still carry the last-viewed underline. Eyeball
        // build/screenshots/grid-burst-lastviewed-nonkey-frame.png - the burst tile (2nd) shows the
        // marker even though its key frame is c, because b is one of its frames.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = groups,
                groupingMode = GroupingMode.Time,
                lastViewedPhotoId = photos[1].id, // "b": a non-key frame of the burst
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-burst-lastviewed-nonkey-frame")
    }

    @Test fun `grouping off shows every frame as its own tile`() {
        // Grouping off (chip cleared): the same six photos render as six plain tiles, no badge.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Off,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-burst-ungrouped")
    }

    @Test fun `the similarity lens shows the sparkle glyph on the collapsed stack`() {
        // Toolbar set to "Similar": the same b|c|d run collapses, keyIndex 0 marks b (the
        // suggested-sharpest) as the cover. Eyeball build/screenshots/grid-similarity-collapsed.png -
        // the 2nd tile shows b's cyan, its count pill carries the SPARKLE glyph (not stacked frames),
        // and the segmented toolbar control reads "Similar".
        val similarityGroups = listOf(
            PhotoGroup.Single(photos[0]),
            PhotoGroup.Burst(listOf(photos[1], photos[2], photos[3]), keyIndex = 0),
            PhotoGroup.Single(photos[4]),
            PhotoGroup.Single(photos[5]),
        )
        renderGrid(
            GridUiState(
                photos = photos,
                groups = similarityGroups,
                groupingMode = GroupingMode.Similarity,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-similarity-collapsed")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `hovering a collapsed group reveals the Review CTA`() {
        // The "Review N →" chip is hover-gated, so the headless suite has to drive a mouse-enter to
        // see it. Hover the burst tile (via its "Group of 3" badge, which sits inside the tile's
        // hoverable), then assert the chip is actually up BEFORE dumping — so a hover that fails to
        // register fails loudly here instead of silently producing a chip-less screenshot. Eyeball
        // build/screenshots/grid-review-cta-hover.png - the 2nd tile shows "Review 3 →" top-start,
        // clear of the count pill (bottom-start) and the deck.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = groups,
                groupingMode = GroupingMode.Similarity,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.onNodeWithContentDescription("Group of 3", useUnmergedTree = true)
            .performMouseInput { moveTo(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Review 3 →").assertIsDisplayed()
        rule.dumpScreenshot("grid-review-cta-hover")
    }

    @Test fun `the cold similarity pass shows the framing banner with the privacy line`() {
        // The AI lens is mid-cold-pass: the singles grid stays interactive while the framing banner
        // under the toolbar names the work ("Finding similar shots — analysing 60 photos.")
        // and carries the privacy line ("Everything stays on your device.") plus the 18 / 60 fraction.
        // Eyeball build/screenshots/grid-grouping-progress.png.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Similarity,
                grouping = GroupingStatus(processed = 18, total = 60),
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-grouping-progress")
    }

    @Test fun `a productive pass surfaces the N photos to M stacks summary pill`() {
        // After a Similar pass that grouped: a one-line payoff pill near the bottom. Eyeball
        // build/screenshots/grid-grouping-summary.png - "3 photos -> 1 stack. Review to cut duplicates."
        renderGrid(
            GridUiState(
                photos = photos,
                groups = groups,
                groupingMode = GroupingMode.Similarity,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
            groupingNotice = "3 photos → 1 stack. Review to cut duplicates.",
        )
        rule.dumpScreenshot("grid-grouping-summary")
    }

    @Test fun `an empty pass explains itself instead of a silent flat grid`() {
        // A Similar pass that grouped nothing: the flat photos stay browsable, with a notice explaining
        // why. Eyeball build/screenshots/grid-grouping-empty.png - "No similar shots found ...".
        renderGrid(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Similarity,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
            groupingNotice = "No similar shots found — these all look unique.",
        )
        rule.dumpScreenshot("grid-grouping-empty")
    }

    @Test fun `the first Similar pick shows the on-device coachmark`() {
        // First time the Similar lens is picked: a dismissible callout under the toolbar explains the
        // on-device pass. Eyeball build/screenshots/grid-similarity-coachmark.png.
        renderGrid(
            GridUiState(
                photos = photos,
                groups = photos.map(PhotoGroup::Single),
                groupingMode = GroupingMode.Similarity,
                showSimilarityCoachmark = true,
                scope = CategoryScope.AllPhotos,
                categories = listOf(Category.favourites()),
            ),
        )
        rule.dumpScreenshot("grid-similarity-coachmark")
    }

    private fun renderGrid(state: GridUiState, groupingNotice: String? = null) {
        rule.setContent {
            AppTheme {
                // A realistic toolbar width so the labeled grouping toggle and "Change folder" both
                // sit without crowding (the default app window is wider still, 1280dp).
                Surface(Modifier.size(1100.dp, 560.dp)) {
                    GridScreen(
                        state = state,
                        initialScrollIndex = 0,
                        onTileClick = {},
                        onChangeFolder = {},
                        onBack = null,
                        onSetFocusedIndex = {},
                        onToggleMembershipAtFocus = {},
                        onToggleCustomCategoryAtFocus = {},
                        onExportTxt = {},
                        onCopyToFolder = {},
                        imageLoader = colorLoader,
                        groupingNotice = groupingNotice,
                    )
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
