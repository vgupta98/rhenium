package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import com.vishalgupta.photoselector.presentation.designsystem.atom.LoadingIndicator
import com.vishalgupta.photoselector.presentation.designsystem.atom.TileSignal
import com.vishalgupta.photoselector.presentation.designsystem.atom.TileSignalChip
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ComparePaneHeader
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ErrorPlaceholder
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.survey.SurveyTile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * One cell of the Inspect overview grid: the photo fit (not cropped) into the cell, a
 * [ComparePaneHeader] floating at the top, and a focus-ring border when [isActive] so it's obvious
 * which tile the keyboard is filing into. Pressing anywhere in the tile makes it active
 * ([onActivate]). The overview never zooms (that's what Inspect's browse mode is for), so it draws a
 * plain fitted [Image] rather than a `ZoomableImage`, reusing the same header/placeholder chrome.
 *
 * The caller sizes the tile (e.g. `Modifier.weight(1f).fillMaxHeight()`); the active/inactive border
 * is always laid out at the same width so activating a tile never nudges the survey layout.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SurveyTileView(
    tile: SurveyTile,
    isActive: Boolean,
    totalInScope: Int,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    // Feature-agnostic bottom-center signal lane, matching PhotoThumbnail's. Empty in the shipped
    // path; immutable so the tile stays strong-skippable.
    signals: ImmutableList<TileSignal> = persistentListOf(),
) {
    Box(
        modifier
            .background(Color.Black)
            .border(
                width = AppTheme.dimens.focusBorderWidth,
                color = if (isActive) AppTheme.colors.focusRing else Color.Transparent,
            )
            .onPointerEvent(PointerEventType.Press) { onActivate() },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = tile.bitmap
        when {
            tile.isLoading && bitmap == null -> LoadingIndicator()
            bitmap == null -> ErrorPlaceholder("Cannot decode this photo.")
            else -> Image(
                bitmap = bitmap,
                contentDescription = tile.photo?.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val photo = tile.photo
        if (photo != null) {
            ComparePaneHeader(
                fileName = photo.fileName,
                positionLabel = "${tile.index + 1} / $totalInScope",
                isFavourite = tile.isFavourite,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(AppTheme.spacing.sm),
            )
        }

        if (signals.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(AppTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                signals.forEach { signal -> TileSignalChip(signal = signal) }
            }
        }
    }
}
