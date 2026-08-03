package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.data.image.toImageBitmap
import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.common.BackgroundPassCoordinator
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.people.PeopleScreen
import com.vishalgupta.photoselector.presentation.people.PeopleUiState
import com.vishalgupta.photoselector.presentation.people.PersonCard
import com.vishalgupta.photoselector.testing.ImageFixtures
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * The People screen in every branch it has. Eyeball the PNGs under `build/screenshots/`:
 *  - `people-unnamed`: a "TO NAME" section of cluster cards — a face crop, a photo count, an inline
 *    name field, and the Skip / "Not a person" verdicts. The top bar shows the naming backlog.
 *  - `people-mixed`: TO NAME, NAMED (the field pre-filled, the action reading "Rename") and SKIPPED
 *    (with "Bring back") together, so the three sections' cards line up across one grid.
 *  - `people-empty`: nothing scanned yet — the CTA, not an error.
 *  - `people-unavailable`: the **hard-requirement** state. A packaged build with a stripped runtime
 *    or a missing model resource must read as "face scanning unavailable", never as "no faces here".
 *  - `people-scanning`: the determinate cold-pass banner with its Stop action.
 *  - `people-purge-confirm`: the destructive confirm, which must name the count *and* say plainly
 *    that the shared face cache is cleared for every folder.
 */
class PeopleScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private fun photo(id: String) = Photo(
        id = PhotoId(id),
        absolutePath = Path.of("/photos/$id.jpg"),
        relativePath = "$id.jpg",
        fileName = "$id.jpg",
        sizeBytes = 1024,
        lastModifiedEpochMs = 0,
    )

    // Distinct synthetic content per photo, so each card's crop is visibly its own rather than a
    // shared grey square that would hide a crop bug.
    private val bitmaps: Map<String, ImageBitmap> = mapOf(
        "a" to ImageFixtures.ramp(400, 300, horizontal = true).toImageBitmap(),
        "b" to ImageFixtures.ramp(400, 300, horizontal = false).toImageBitmap(),
        "c" to ImageFixtures.checker(240, 240).toImageBitmap(),
        "d" to ImageFixtures.solid(240, 240, r = 90, g = 140, b = 200).toImageBitmap(),
    )

    private val loader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap? = bitmaps[photo.id.value]
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    private fun card(id: String, name: String?, count: Int, photoId: String) = PersonCard(
        id = PersonId(id),
        name = name,
        photoCount = count,
        coverPhoto = photo(photoId),
        coverBox = FaceBox(x = 0.30f, y = 0.20f, width = 0.22f, height = 0.30f),
    )

    @Test fun `unnamed clusters offer a crop, a name field and both verdicts`() {
        render(
            PeopleUiState(
                toName = listOf(
                    card("p1", null, 42, "a"),
                    card("p2", null, 17, "b"),
                    card("p3", null, 3, "c"),
                ),
            ),
        )
        rule.dumpScreenshot("people-unnamed")
    }

    @Test fun `named, unnamed and skipped clusters share one grid`() {
        render(
            PeopleUiState(
                toName = listOf(card("p1", null, 42, "a")),
                named = listOf(card("p2", "Alice", 88, "b"), card("p3", "Bob", 12, "c")),
                skipped = listOf(card("p4", null, 5, "d")),
            ),
        )
        rule.dumpScreenshot("people-mixed")
    }

    @Test fun `nothing scanned yet reads as a call to action, not an error`() {
        render(PeopleUiState())
        rule.dumpScreenshot("people-empty")
    }

    @Test fun `models that could not load read as unavailable, never as no faces`() {
        // The hard requirement: this is what makes a packaged-DMG regression distinguishable from a
        // folder that genuinely has nobody in it. If this screenshot ever renders the empty state
        // instead, the smoke test has lost its only signal.
        render(PeopleUiState(available = false))
        rule.dumpScreenshot("people-unavailable")
    }

    @Test fun `a scan in progress shows a determinate banner with a stop`() {
        render(
            PeopleUiState(
                scanning = BackgroundPassCoordinator.Progress(processed = 37, total = 120),
                toName = listOf(card("p1", null, 9, "a")),
            ),
        )
        rule.dumpScreenshot("people-scanning")
    }

    @Test fun `an unreadable people file is called out rather than shown as empty`() {
        render(PeopleUiState(unreadable = true, toName = listOf(card("p1", null, 9, "a"))))
        rule.dumpScreenshot("people-unreadable")
    }

    @Test fun `the purge confirm names the count and the cross-folder cache cost`() {
        render(PeopleUiState(named = listOf(card("p2", "Alice", 88, "b"))), personCategoryCount = 3)
        rule.onNodeWithContentDescription("People actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete all face data…").performClick()
        rule.waitForIdle()
        // The dialog renders in its own popup root, so onRoot() would only capture the screen behind it.
        val file = rule.dumpScreenshot("people-purge-confirm", rule.onAllNodes(isRoot()).onLast())
        assertTrue(file.exists() && file.length() > 0)
    }

    private fun render(state: PeopleUiState, personCategoryCount: Int = 1) {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(900.dp, 780.dp)) {
                    PeopleScreen(
                        state = state,
                        rootName = "Iceland 2026",
                        imageLoader = loader,
                        personCategoryCount = { personCategoryCount },
                        onBack = {},
                        onScan = {},
                        onStopScan = {},
                        onName = { _, _ -> },
                        onNameEdited = {},
                        onDismiss = {},
                        onRestore = {},
                        onPurge = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
    }
}
