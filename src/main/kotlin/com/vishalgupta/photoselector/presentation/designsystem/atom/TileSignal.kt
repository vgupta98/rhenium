package com.vishalgupta.photoselector.presentation.designsystem.atom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/**
 * The one small-chip style every on-tile overlay badge shares: a [Surface] in the translucent-dark
 * overlay-chrome palette (`overlayChromeBackground` / `onOverlayChrome`), rounded `shapes.small`, with
 * the tight `badgeInset` interior padding — an optional leading icon plus an optional short label, so
 * it reads over both bright and dark photos.
 *
 * This is the base atom that the tile's burst-count pill, review CTA and category chips all build on
 * (each just passes its own icon/label/colours), and that the [TileSignal] overload renders through.
 * Callers that need a different palette (e.g. the neutral category chip) override [color] /
 * [contentColor]; everything else keeps the overlay-chrome defaults so the family stays identical.
 */
@Composable
fun TileSignalChip(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    label: String? = null,
    color: Color = AppTheme.colors.overlayChromeBackground,
    contentColor: Color = AppTheme.colors.onOverlayChrome,
    shape: Shape = AppTheme.shapes.small,
    textStyle: TextStyle = AppTheme.typography.labelMedium,
    iconSize: Dp = AppTheme.dimens.iconSm,
    horizontalPadding: Dp = AppTheme.dimens.badgeInset,
    verticalPadding: Dp = AppTheme.dimens.badgeVerticalInset,
    iconLabelGap: Dp = AppTheme.spacing.xxs,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = contentColor,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(iconLabelGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(iconSize),
                )
            }
            if (label != null) {
                Text(text = label, style = textStyle)
            }
        }
    }
}

/**
 * How a [TileSignal] tints its glyph/label over the shared overlay-chrome chip. Feature-agnostic:
 * a future signal picks a role by what it *means* (a positive cue, a caution), never a specific
 * feature. The chip background always stays the neutral overlay chrome; only the content colour moves.
 */
enum class TileSignalTint { Neutral, Positive, Caution }

/**
 * A single, feature-agnostic presentation signal shown on a photo tile's bottom-center lane — an
 * [icon], an optional short [label], and an optional [tint] role. Deliberately carries no AI (or any
 * other) semantics: it is a pure display token that a future on-device signal (eyes-open, sharpness,
 * a smart-category hint, …) populates. Immutable and equality-comparable so a lane of these keeps the
 * host tile ([PhotoThumbnail] / `SurveyTileView`) strong-skippable.
 */
@Immutable
data class TileSignal(
    val icon: ImageVector,
    val label: String? = null,
    /** Spoken description; falls back to [label] when null. */
    val contentDescription: String? = null,
    val tint: TileSignalTint = TileSignalTint.Neutral,
)

/**
 * Renders one [TileSignal] as an overlay-chrome [TileSignalChip], resolving its [TileSignal.tint] to
 * a content colour through [AppTheme]. The background stays the neutral overlay chrome so a lane of
 * mixed signals reads as one band.
 */
@Composable
fun TileSignalChip(signal: TileSignal, modifier: Modifier = Modifier) {
    val contentColor = when (signal.tint) {
        TileSignalTint.Neutral -> AppTheme.colors.onOverlayChrome
        TileSignalTint.Positive -> AppTheme.colors.favourite
        TileSignalTint.Caution -> AppTheme.colors.reject
    }
    TileSignalChip(
        modifier = modifier,
        icon = signal.icon,
        iconContentDescription = signal.contentDescription ?: signal.label,
        label = signal.label,
        contentColor = contentColor,
        verticalPadding = AppTheme.spacing.xxs,
    )
}
