package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlin.math.roundToInt

/**
 * The off-screen half of a background pass's progress hint: a small pill telling the user a long
 * on-device pass ([label] names which one — "Grouping similar", "Finding faces") is still running
 * after they've navigated away from the screen that frames it. The pass keeps computing across
 * navigation; this is purely the signal that it is.
 *
 * Composed from [PillToast] (the house pill) with a determinate ring in its leading slot rather than
 * a new pill, so the styling stays shared. Determinate — not an indeterminate spinner — both to show
 * how far along the pass is and because an infinite animation would hang the screenshot harness.
 *
 * [onStop], when supplied, adds a stop affordance for a pass the user deliberately started and can
 * therefore deliberately abandon. The Similarity pass has none (nothing starts it but choosing a
 * lens, and choosing another already moves on), so it simply omits it.
 */
@Composable
fun BackgroundPassChip(
    label: String,
    processed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null,
) {
    val fraction = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
    PillToast(
        text = "$label… ${(fraction * 100).roundToInt()}%",
        modifier = modifier,
        leadingIcon = {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(AppTheme.dimens.iconSm),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
        },
        trailingIcon = onStop?.let {
            {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop",
                    modifier = Modifier.size(AppTheme.dimens.iconSm).clickable(onClick = it),
                )
            }
        },
    )
}
