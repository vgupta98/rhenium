package com.vishalgupta.photoselector.presentation.designsystem.atom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.vishalgupta.photoselector.data.image.DiskThumbnailCache
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlin.math.roundToInt

/**
 * Edge the source photo is decoded at for a face crop.
 *
 * Pinned to [DiskThumbnailCache.MAX_EDGE_PX], not chosen for sharpness: `SkikoImageLoader` only
 * consults the on-disk cache at or below that edge, so one pixel over turns every cover crop into a
 * full source decode — through the ImageIO bridge for HEIC/RAW — on every visit to the People screen,
 * with nothing persisted for the next one. Thirty people would be thirty cold decodes a visit.
 *
 * Note this is *not* the same entry as the grid's 320px thumbnails (the edge is part of the cache
 * key), so the first People visit still pays a decode per person — it is simply paid once, ever,
 * rather than once per visit.
 */
private const val FACE_CROP_VIEWPORT_PX = DiskThumbnailCache.MAX_EDGE_PX

/**
 * How much context to keep around the detector's box, as a fraction of the box's longer edge. A face
 * box is tight to the features; without headroom the crop reads as a disembodied nose and mouth, which
 * is exactly the thing a user cannot name someone from.
 */
private const val FACE_CROP_PADDING = 0.45f

/**
 * One person's face, cropped out of the photo it was found in.
 *
 * Decodes [photo] through the shared [loader] — **no second cache**, and at an edge the loader will
 * actually persist (see [FACE_CROP_VIEWPORT_PX]) — then blits the region [box] describes, padded and
 * squared, into whatever size the caller gives it. Falls back to a neutral person glyph while the
 * decode is in flight, when it fails, or when the face has no stored box (a people sidecar written
 * before boxes were persisted).
 *
 * The crop is drawn with an explicit source rect rather than an `Image` + `ContentScale`, because the
 * region is not the whole bitmap: `ContentScale.Crop` would centre-crop the *photo*, not the face.
 * The rect maths lives in [faceCropRect], pure and unit-testable — but the pixel result is only
 * really proven by the screenshot test, which is why one exists.
 *
 * PII: a face crop is user data. Never write one to disk or log it.
 */
@Composable
fun FaceCrop(
    photo: Photo,
    loader: ImageLoader,
    box: FaceBox?,
    modifier: Modifier = Modifier,
) {
    val image by produceState<ImageBitmap?>(null, photo.id) {
        value = loader.load(photo, viewportLongEdgePx = FACE_CROP_VIEWPORT_PX)
    }
    FaceCrop(image = image, box = box, modifier = modifier)
}

/**
 * The stateless half of [FaceCrop]: draws an already-decoded [image] cropped to [box]. Split out so a
 * screenshot test can render a deterministic fixture without a loader, and so a caller that already
 * holds the bitmap doesn't decode twice.
 */
@Composable
fun FaceCrop(
    image: ImageBitmap?,
    box: FaceBox?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(AppTheme.shapes.small)
            .background(AppTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null || box == null) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = AppTheme.colorScheme.onSurfaceVariant,
            )
            return@Box
        }
        val source = faceCropRect(image.width, image.height, box, FACE_CROP_PADDING)
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image = image,
                srcOffset = IntOffset(source.left, source.top),
                srcSize = IntSize(source.width, source.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }
}

/** A crop rectangle in source-image pixels. Plain ints so the maths is trivially assertable. */
data class FaceCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The pixel rect to blit for [box] within a `imageWidth` x `imageHeight` image: the box grown by
 * [padding] (a fraction of its longer edge), squared up around the face's centre, then **clamped**
 * into the image.
 *
 * Clamping shifts the square back inside rather than shrinking it, so a face at the very edge of a
 * frame still fills the card instead of rendering as a sliver; only a square larger than the image
 * itself is shrunk. The result is always at least 1x1, so a degenerate box can never produce an empty
 * `srcSize` (Skia treats that as a no-draw, which would silently blank the card).
 */
fun faceCropRect(imageWidth: Int, imageHeight: Int, box: FaceBox, padding: Float): FaceCropRect {
    if (imageWidth <= 0 || imageHeight <= 0) return FaceCropRect(0, 0, 1, 1)
    val centerX = box.centerX * imageWidth
    val centerY = box.centerY * imageHeight
    val longEdge = maxOf(box.width * imageWidth, box.height * imageHeight)
    val side = (longEdge * (1f + 2f * padding))
        .roundToInt()
        .coerceIn(1, minOf(imageWidth, imageHeight))
    val left = (centerX - side / 2f).roundToInt().coerceIn(0, imageWidth - side)
    val top = (centerY - side / 2f).roundToInt().coerceIn(0, imageHeight - side)
    return FaceCropRect(left = left, top = top, width = side, height = side)
}
