package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.domain.repository.ConflictPolicy
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppOutlinedButton
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/**
 * The single **Export** entry point, consolidating the two output paths that used to sit as separate
 * top-bar controls — "Export list (.txt)" and "Copy photos to folder…" — into one dropdown so the
 * bar carries one export affordance rather than two competing ones. Shared by the per-scope
 * [GridTopBar] (exporting the whole category) and the [GridSelectionTopBar] (exporting just the
 * current multi-selection), so both export through identical chrome.
 *
 * The menu lists the text export, then the copy-to-folder choices grouped under the same
 * [ConflictPolicyHeader] / [ConflictPolicyOptions] the standalone [ConflictPolicyButton] uses, so a
 * collision choice reads identically wherever it appears. This menu is also the intended home for a
 * future plugin exporter: an extra contributed format appends as one more item after a divider,
 * with no change to the surrounding bar. Owns its own visibility — purely local UI state.
 */
@Composable
fun ExportMenu(
    enabled: Boolean,
    onExportTxt: () -> Unit,
    onCopyToFolder: (ConflictPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AppOutlinedButton(
            text = "Export",
            enabled = enabled,
            onClick = { expanded = true },
            trailingIcon = Icons.Filled.ArrowDropDown,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Save photo list (.txt)") },
                onClick = {
                    expanded = false
                    onExportTxt()
                },
            )
            HorizontalDivider()
            // Copy-to-folder, with the collision choice inlined as the next level so the whole
            // export surface is one flat menu rather than a button-plus-popup. The action and the
            // "what if it clashes" question stack on two lines — a section title over a caption —
            // rather than running together into one long em-dashed sentence.
            Column(
                Modifier.padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.sm),
            ) {
                Text(
                    text = "Copy photos to a folder",
                    style = AppTheme.typography.labelLarge,
                    color = AppTheme.colorScheme.onSurface,
                )
                Text(
                    text = ConflictPolicyHeader,
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConflictPolicyOptions.forEach { (label, policy) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onCopyToFolder(policy)
                    },
                )
            }
        }
    }
}
