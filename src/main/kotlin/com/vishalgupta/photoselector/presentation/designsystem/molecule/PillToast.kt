package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.designsystem.theme.PillShape

/** Background/foreground pair for a [PillToast], kept together so they can't drift apart. */
@Immutable
data class PillToastColors(val container: Color, val content: Color)

/** Default color pairings for [PillToast], following the `XxxDefaults` convention. */
object PillToastDefaults {
    @Composable
    fun neutralColors() = PillToastColors(
        container = AppTheme.colors.toastBackground,
        content = AppTheme.colors.toastContent,
    )

    @Composable
    fun favouriteColors() = PillToastColors(
        container = AppTheme.colors.favouriteToastBackground,
        content = AppTheme.colors.favouriteToastContent,
    )

    /** A membership was added — a positive accent, shared across all categories. */
    @Composable
    fun addedColors() = PillToastColors(
        container = AppTheme.colors.toastAddedBackground,
        content = AppTheme.colors.toastAddedContent,
    )

    /** A membership was removed — a muted accent, shared across all categories. */
    @Composable
    fun removedColors() = PillToastColors(
        container = AppTheme.colors.toastRemovedBackground,
        content = AppTheme.colors.toastRemovedContent,
    )
}

/**
 * A rounded pill containing an optional [leadingIcon] slot, a [text] label and an optional
 * [trailingIcon] slot (an action the pill offers, e.g. stopping the pass it is reporting on).
 * Used for transient confirmations (e.g. favourited / unfavourited).
 */
@Composable
fun PillToast(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    colors: PillToastColors = PillToastDefaults.neutralColors(),
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = colors.container,
        contentColor = colors.content,
        shape = PillShape,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            modifier = Modifier.padding(horizontal = AppTheme.spacing.lg, vertical = 10.dp),
        ) {
            leadingIcon?.invoke()
            Text(text, style = MaterialTheme.typography.labelLarge)
            trailingIcon?.invoke()
        }
    }
}
