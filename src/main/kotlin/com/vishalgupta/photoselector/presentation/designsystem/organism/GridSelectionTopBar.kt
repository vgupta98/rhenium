package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.repository.ConflictPolicy
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppOutlinedButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppTextButton
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ExportMenu
import com.vishalgupta.photoselector.presentation.designsystem.molecule.SelectionFileMenu

/**
 * The grid top bar while a multi-select is active: the same chrome height and shape as
 * [GridTopBar], swapped in so the selection's bulk actions replace per-photo controls rather
 * than crowding them. Leads with a live "[n] selected" count, then the bulk file/export actions
 * (Favourite and Reject — the two sides of the cull — get their own buttons to match the rest of
 * the app, custom categories sit behind an "Add to category" menu), and trails with **Clear**.
 * **Reject** only flags for the later "Move rejects to Trash" sweep; **Delete** trashes now. The
 * output paths reuse the same
 * [ExportMenu] the per-scope [GridTopBar] carries — saving a .txt list or copying to a folder — so a
 * selection exports identically to a whole category rather than through a separate copy-only button.
 *
 * [customCategories] is the slot-ordered custom list (Favourites excluded); the menu hides
 * entirely when it is empty so there's no dead control. **Delete** sits in the trailing group
 * beside Clear, tinted with the error colour so the one destructive action reads apart from the
 * filing actions; the caller is expected to gate it behind a confirmation.
 */
@Composable
fun GridSelectionTopBar(
    selectedCount: Int,
    customCategories: List<Category>,
    onFileIntoFavourites: () -> Unit,
    onFileIntoRejects: () -> Unit,
    onFileIntoCustom: (slot: Int) -> Unit,
    onExportSelectionTxt: () -> Unit,
    onCopySelection: (ConflictPolicy) -> Unit,
    onDeleteSelection: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopBarScaffold(modifier) {
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleLarge,
        )

        AppOutlinedButton(
            text = "Favourite",
            onClick = onFileIntoFavourites,
            leadingIcon = Icons.Filled.Star,
        )
        // The reject half of the cull: flags the selection for the later "Move rejects to Trash"
        // sweep (distinct from Delete, which trashes now). Filing action, so it stays neutral.
        AppOutlinedButton(
            text = "Reject",
            onClick = onFileIntoRejects,
            leadingIcon = Icons.Outlined.Flag,
        )
        if (customCategories.isNotEmpty()) {
            SelectionFileMenu(customCategories = customCategories, onSelectSlot = onFileIntoCustom)
        }
        ExportMenu(
            enabled = true,
            onExportTxt = onExportSelectionTxt,
            onCopyToFolder = onCopySelection,
        )

        Spacer(Modifier.weight(1f))

        AppTextButton(
            text = "Delete",
            onClick = onDeleteSelection,
            leadingIcon = Icons.Outlined.DeleteOutline,
            contentColor = MaterialTheme.colorScheme.error,
        )
        AppTextButton(
            text = "Clear",
            onClick = onClearSelection,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
