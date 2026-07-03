package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppTextButton
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/**
 * Top bar overlaid on the full-screen photo browser. Sits over a translucent
 * scrim; count + path are rendered in white for legibility against the photo.
 */
@Composable
fun BrowserTopBar(
    countLabel: String,
    relativePath: String,
    readOnly: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Non-null only when browsing a category: a "Show in All Photos" action that jumps to this photo in
    // the main grid. Hidden in the All-Photos browser, where it would be a no-op.
    onShowInAllPhotos: (() -> Unit)? = null,
    // True when the browser is *embedded* in Inspect's browse mode: suppresses the library-navigation
    // chrome (Show in All Photos), which does not apply to a fixed set.
    embedded: Boolean = false,
    // The "grid view" toggle back to Inspect's overview. Shown (when [embedded]) only if there is a
    // grid to return to — a browse-only set (past the grid cap) leaves it null.
    onSwitchToGrid: (() -> Unit)? = null,
) {
    TopBarScaffold(modifier, containerColor = AppTheme.colors.topBarScrim) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to grid",
                tint = Color.White,
            )
        }
        Text(countLabel, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "—  $relativePath",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (readOnly) {
            Text(
                "Read-only folder · selections in-memory only",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (embedded) {
            // Inspect's browse mode: the only trailing action is back to the overview grid — and only
            // when there is one (a browse-only set has no grid, so the toggle is absent).
            if (onSwitchToGrid != null) {
                IconButton(onClick = onSwitchToGrid) {
                    Icon(Icons.Outlined.GridView, contentDescription = "Grid view", tint = Color.White)
                }
            }
        } else {
            // Muted white so it recedes on the scrim. Shown only when the current photo was opened from
            // a category — the way back to where it lives in the full library.
            if (onShowInAllPhotos != null) {
                AppTextButton(
                    text = "Show in All Photos",
                    leadingIcon = Icons.Outlined.PhotoLibrary,
                    onClick = onShowInAllPhotos,
                    contentColor = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
