package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.designsystem.organism.PhotoThumbnail
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

/**
 * A grid tile whose decode fails must read as a broken tile, not an eternal spinner. The loader
 * returns null both while in flight AND on a genuine decode failure, so this drives the failure path
 * (a loader that always returns null) and asserts the tile settles to the [ErrorPlaceholder] rather
 * than the [LoadingIndicator]. Eyeball `build/screenshots/grid-tile-decode-failed.png`: the broken-image
 * glyph and "Can't open this photo." over the tile background, no spinner.
 */
class PhotoThumbnailScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private val photo = Photo(
        id = PhotoId("broken"),
        absolutePath = Path.of("/photos/broken.jpg"),
        relativePath = "broken.jpg",
        fileName = "broken.jpg",
        sizeBytes = 1,
        lastModifiedEpochMs = 0,
    )

    // load() always returns null — the loader's contract for both "still in flight" and "decode
    // failed". Once produceState settles, a null result is a failure, so the tile shows the error.
    private val failingLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap? = null
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @Test fun `a tile whose decode fails shows the error placeholder, not a spinner`() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(240.dp)) {
                    PhotoThumbnail(
                        photo = photo,
                        loader = failingLoader,
                        isMarked = false,
                        isFocused = false,
                        onClick = {},
                        modifier = Modifier.size(240.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        // Fail loudly here if the placeholder didn't render (e.g. the spinner regressed back in)
        // rather than silently dumping a spinner PNG.
        rule.onNodeWithText("Can't open this photo.").assertIsDisplayed()
        rule.dumpScreenshot("grid-tile-decode-failed")
    }
}
