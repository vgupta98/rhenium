package com.vishalgupta.photoselector.presentation.designsystem.atom

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The reject indicator: a filled flag when [filled], an outline otherwise — the cull's "reject"
 * counterpart to [FavouriteStar]. Size is controlled by the caller via [modifier]; [tint] defaults
 * to the ambient content colour so it adapts inside chips, the rail and toasts.
 */
@Composable
fun RejectFlag(
    filled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = if (filled) Icons.Filled.Flag else Icons.Outlined.Flag,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}
