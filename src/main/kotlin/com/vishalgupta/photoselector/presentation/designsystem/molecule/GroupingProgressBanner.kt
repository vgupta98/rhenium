package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppTextButton
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/**
 * The cold-pass framing banner for a long on-device pass: the Similarity grouping run (and now the
 * face scan) is a ~minute-long wait over the whole folder, so unlike the bare [BusyBar] used for the
 * instant Time regroup this names what's happening ([label]), sets the expectation, and carries the
 * privacy line that makes the on-device story legible — *"Everything stays on your device."* It keeps
 * the determinate fraction the pass already reports. The caller gates visibility; the Time lens stays
 * on the plain bar because it finishes inside the progress grace window.
 *
 * [onStop] adds a Stop action for a pass the user deliberately started (the face scan). The
 * Similarity pass omits it — choosing another lens is already the way out of it.
 */
@Composable
fun GroupingProgressBanner(
    processed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    label: String = "Finding similar shots",
    onStop: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimens.iconSm),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "$label — analysing $total photos.",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "Everything stays on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = "$processed / $total", style = MaterialTheme.typography.labelMedium)
            onStop?.let { AppTextButton(text = "Stop", onClick = it) }
        }
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
