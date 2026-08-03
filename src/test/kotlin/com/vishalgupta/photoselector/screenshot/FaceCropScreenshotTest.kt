package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.toImageBitmap
import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.model.DecodedImage
import com.vishalgupta.photoselector.presentation.designsystem.atom.FaceCrop
import com.vishalgupta.photoselector.presentation.designsystem.atom.faceCropRect
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.testing.ImageFixtures
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FaceCrop] over a real `testing/ImageFixtures` buffer, converted through the *same*
 * `DecodedImage.toImageBitmap()` the app's loader uses.
 *
 * This is precisely the class of bug a pixel assertion on an intermediate buffer cannot catch: the
 * crop is drawn with an explicit source rect through Compose's draw scope, so a wrong rect, a wrong
 * `dstSize`, or a bitmap-conversion mistake shows up only in the rendered image. Eyeball
 * `build/screenshots/face-crop.png`:
 *  - **top-left ramp**: a horizontal black→white ramp cropped around a box on the *left* third —
 *    the tile must be noticeably darker than the whole image's average, proving the crop tracked
 *    the box rather than centre-cropping the photo.
 *  - **top-right ramp**: the same image cropped on the *right* third — noticeably lighter. The two
 *    tiles differing is the assertion the eye makes.
 *  - **checker**: a fine checkerboard cropped near an edge, filling the tile squarely (no letterbox
 *    bars, no stretched aspect).
 *  - **no box / no image**: the neutral person-glyph fallback, for a sidecar written before boxes.
 */
class FaceCropScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private fun bitmap(image: DecodedImage) = image.toImageBitmap()

    @Test
    fun `face crop follows the box rather than centre-cropping the photo`() {
        val ramp = bitmap(ImageFixtures.ramp(400, 200, horizontal = true))
        val checker = bitmap(ImageFixtures.checker(240, 240))
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(560.dp, 300.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(AppTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                    ) {
                        Text("Face crops", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                            FaceCrop(
                                image = ramp,
                                box = FaceBox(x = 0.10f, y = 0.30f, width = 0.14f, height = 0.28f),
                                modifier = Modifier.size(96.dp),
                            )
                            FaceCrop(
                                image = ramp,
                                box = FaceBox(x = 0.76f, y = 0.30f, width = 0.14f, height = 0.28f),
                                modifier = Modifier.size(96.dp),
                            )
                            FaceCrop(
                                image = checker,
                                // Hard against the top-left corner: the clamp must slide the square
                                // back inside rather than shrink it, so the tile still fills.
                                box = FaceBox(x = 0.0f, y = 0.0f, width = 0.20f, height = 0.20f),
                                modifier = Modifier.size(96.dp),
                            )
                            FaceCrop(image = ramp, box = null, modifier = Modifier.size(96.dp))
                            FaceCrop(image = null, box = null, modifier = Modifier.size(96.dp))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val file = rule.dumpScreenshot("face-crop")
        assertTrue(file.exists() && file.length() > 0)
    }

    @Test
    fun `the crop rect is padded, squared and clamped into the image`() {
        // A tight box grows by the padding fraction of its longer edge and is squared on the face's
        // centre: 0.2 * 400 = 80px wide, 0.2 * 200 = 40px tall -> long edge 80 -> 80 * 1.9 = 152.
        val rect = faceCropRect(400, 200, FaceBox(0.4f, 0.4f, 0.2f, 0.2f), padding = 0.45f)
        assertEquals(152, rect.width)
        assertEquals(rect.width, rect.height, "a face card is square, so the source rect must be too")

        // A box at the very edge slides back inside rather than shrinking, so the card still fills.
        val edge = faceCropRect(400, 200, FaceBox(0f, 0f, 0.2f, 0.2f), padding = 0.45f)
        assertEquals(0, edge.left)
        assertEquals(0, edge.top)
        assertEquals(152, edge.width)

        // A square larger than the image is capped at the shorter side, never past the bounds.
        val huge = faceCropRect(400, 200, FaceBox(0.1f, 0.1f, 0.9f, 0.9f), padding = 0.45f)
        assertEquals(200, huge.width)
        assertTrue(huge.left + huge.width <= 400 && huge.top + huge.height <= 200)

        // Degenerate input must never produce an empty srcSize (Skia treats that as a no-draw).
        val degenerate = faceCropRect(400, 200, FaceBox(0.5f, 0.5f, 0f, 0f), padding = 0.45f)
        assertTrue(degenerate.width >= 1 && degenerate.height >= 1)
    }
}
