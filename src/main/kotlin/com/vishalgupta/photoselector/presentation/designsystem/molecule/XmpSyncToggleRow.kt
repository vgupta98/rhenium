package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/**
 * The library-rail footer control for live XMP sidecar sync: a status line ("XMP sync · On" / "· Off")
 * with, while on, a muted count of the non-RAW photos that won't get a sidecar (Phase 1 is RAW-only),
 * and a Material3 [Switch] on the trailing edge.
 *
 * Like every rail row it is kept **out of the keyboard focus order** ([Modifier.focusProperties]
 * `canFocus = false`): the grid owns the keyboard ring, and a focusable Switch here would steal it and
 * break arrow-key navigation. All tokens are read through [AppTheme]; nothing is inlined.
 */
@Composable
fun XmpSyncToggleRow(
    enabled: Boolean,
    skippedNonRaw: Int,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "XMP sync · " + if (enabled) "On" else "Off",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.onSurface,
            )
            if (enabled && skippedNonRaw > 0) {
                Text(
                    text = if (skippedNonRaw == 1) "1 non-RAW skipped" else "$skippedNonRaw non-RAW skipped",
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            // Material3's Switch has no size params and renders large for a rail footer, so scale it
            // down; kept off the keyboard ring so it never steals arrow-key focus from the grid.
            modifier = Modifier
                .scale(0.8f)
                .focusProperties { canFocus = false },
        )
    }
}
