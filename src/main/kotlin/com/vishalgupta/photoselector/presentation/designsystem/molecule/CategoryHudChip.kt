package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.designsystem.theme.PillShape

/**
 * One toggle in the browser's category HUD: a hotkey cap ([keyLabel], e.g. "F", "X" or "3"), an
 * optional [leadingIcon] slot (the favourite star, the reject flag), and the category [label]. Lit
 * when the current photo is a member; clicking toggles it. Colours are passed in by the
 * [com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserCategoryHud] so the
 * active accent (gold for Favourites, red for Rejects, neutral for custom) stays one decision.
 */
@Composable
fun CategoryHudChip(
    keyLabel: String?,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = PillShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
            modifier = Modifier.padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        ) {
            if (keyLabel != null) {
                Text(
                    keyLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            leadingIcon?.invoke()
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
