package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlin.math.roundToInt

/**
 * The off-grid half of the background-grouping hint: a small pill telling the user the Similarity pass
 * is still running after they've navigated away from the grid (where the tab ring and banner carry it).
 * The pass keeps computing across navigation — this is purely the signal that it is.
 *
 * Composed from [PillToast] (the house pill) with a determinate ring in its leading slot rather than a
 * new pill, so the styling stays shared. Determinate — not an indeterminate spinner — both to show how
 * far along the pass is and because an infinite animation would hang the screenshot harness.
 */
@Composable
fun BackgroundGroupingChip(
    processed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    // The pill's leading text; the percentage is appended. Defaulted for the Similarity pass, overridden
    // (e.g. "Analyzing folder…") to reuse the exact same off-grid cue for the gated insight pass.
    label: String = "Grouping similar…",
) {
    val fraction = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
    PillToast(
        text = "$label ${(fraction * 100).roundToInt()}%",
        modifier = modifier,
        leadingIcon = {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(AppTheme.dimens.iconSm),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
        },
    )
}
