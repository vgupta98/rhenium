package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.presentation.common.categorySlotDigit
import com.vishalgupta.photoselector.presentation.designsystem.atom.FavouriteStar
import com.vishalgupta.photoselector.presentation.designsystem.atom.RejectFlag
import com.vishalgupta.photoselector.presentation.designsystem.molecule.CategoryActionsMenu
import com.vishalgupta.photoselector.presentation.designsystem.molecule.CategoryNameDialog
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ChangeFolderButton
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ConfirmDialog
import com.vishalgupta.photoselector.presentation.designsystem.molecule.XmpSyncToggleRow
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope

/**
 * The left navigation rail: the library's scopes laid out as a vertical list rather than crammed
 * into the top bar. It owns folder identity (the root name + "Change folder"), the All Photos and
 * the built-in Favourites / Rejects scopes, every custom category with its membership count, and
 * category management (create / rename / delete) plus the one-shot "Move rejects to Trash" sweep.
 * The active scope is highlighted; selecting a row navigates to that scope's own grid.
 *
 * This consolidates affordances that used to be split across the top bar (Favourites button, the
 * "Categories" dropdown, the per-category "⋯" menu, "Change folder") into one stable, scannable
 * column — leaving the top bar to carry only the current scope's identity and the view/export
 * controls. It is composed entirely from existing pieces ([FavouriteStar], [CategoryActionsMenu],
 * [CategoryNameDialog], [ChangeFolderButton]) so the affordances look identical to before.
 *
 * Rows are mouse-clickable but deliberately **not** keyboard-focusable
 * ([Modifier.focusProperties] `canFocus = false`): the grid owns the keyboard ring, and a rail row
 * grabbing focus would silently break arrow-key navigation.
 */
@Composable
fun LibraryRail(
    rootName: String,
    scope: CategoryScope,
    // Category + member count, Favourites first then custom in slot order. Derived from the same
    // CategoriesRepository membership flow the grid reads for its tile badges, so a rail count and a
    // tile badge can never disagree (the rail is fed by its own root-scoped LibraryRailViewModel now,
    // not the per-scope grid).
    entries: List<Pair<Category, Int>>,
    onSelectAllPhotos: () -> Unit,
    onSelectCategory: (CategoryId) -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (CategoryId, String) -> Unit,
    onDeleteCategory: (CategoryId) -> Unit,
    onChangeFolder: () -> Unit,
    // Sweeps the whole Rejects bucket to the Trash (the rail confirms first). The caller performs
    // the move and empties the bucket; defaulted so the stateless rail renders without the wiring.
    onEmptyRejects: () -> Unit = {},
    // Live XMP-sidecar sync footer state (whole-root, persisted per root). [xmpSyncSkippedNonRaw] is
    // the count of non-RAW photos that won't get a sidecar, shown only while on. Defaulted so the
    // stateless rail (and older callers/tests) render without the wiring.
    xmpSyncEnabled: Boolean = false,
    xmpSyncSkippedNonRaw: Int = 0,
    onToggleXmpSync: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    // The custom category currently being renamed (the rail owns one shared dialog rather than one
    // per row), or null when no rename is in flight.
    var renaming by remember { mutableStateOf<Category?>(null) }
    // Destructive speed bump in front of the Rejects -> Trash sweep.
    var confirmingEmptyRejects by remember { mutableStateOf(false) }

    // Built-in scopes (Favourites, Rejects) render first, in canonical order, each with its own
    // glyph; custom categories follow with their slot digit. Generalised over `builtIn` rather than
    // special-casing Favourites, so a new built-in is one entry in Category.builtIns.
    val builtInEntries = entries.filter { it.first.builtIn }
    val customEntries = entries.filter { !it.first.builtIn }
    val rejectsCount = entries.firstOrNull { it.first.id == Category.REJECTS_ID }?.second ?: 0

    Column(
        modifier
            .width(AppTheme.dimens.libraryRailWidth)
            .fillMaxHeight()
            .background(AppTheme.colorScheme.surface)
            .padding(vertical = AppTheme.spacing.sm),
    ) {
        RailHeader(rootName = rootName, onChangeFolder = onChangeFolder)

        Spacer(Modifier.height(AppTheme.spacing.sm))

        RailRow(
            label = "All Photos",
            selected = scope == CategoryScope.AllPhotos,
            onClick = onSelectAllPhotos,
            leading = {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(AppTheme.dimens.iconSm),
                )
            },
        )

        builtInEntries.forEach { (category, count) ->
            RailRow(
                label = category.name,
                selected = scope.isCategory(category.id),
                count = count,
                onClick = { onSelectCategory(category.id) },
                leading = { BuiltInLeadingIcon(category.id) },
                // Rejects carries the one library-level action: empty the bucket to Trash. Shown
                // only when there is something to sweep, so it's never a dead control.
                actions = if (category.id == Category.REJECTS_ID && count > 0) {
                    {
                        IconButton(onClick = { confirmingEmptyRejects = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = "Move rejects to Trash",
                                modifier = Modifier.size(AppTheme.dimens.iconSm),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }

        RailSectionLabel("Categories")

        // Only the custom-category list scrolls; the header, the All Photos / Favourites scopes, the
        // "Categories" label, and the "New category" action stay pinned. weight(1f, fill = false)
        // caps the list at the leftover height (so it scrolls once the categories overflow) but lets
        // it shrink to its content, keeping "New category" directly under a short list rather than
        // floating at the bottom.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            customEntries.forEachIndexed { slot, (category, count) ->
                RailRow(
                    label = category.name,
                    selected = scope.isCategory(category.id),
                    count = count,
                    onClick = { onSelectCategory(category.id) },
                    leading = { RailSlotBadge(slot) },
                    // Rename / delete ride a "⋯" actions menu; the count yields to it while shown.
                    // Built-in Favourites never gets one.
                    actions = {
                        CategoryActionsMenu(
                            categoryName = category.name,
                            onRenameRequested = { renaming = category },
                            onDeleteConfirmed = { onDeleteCategory(category.id) },
                        )
                    },
                )
            }
        }

        RailRow(
            label = "New category",
            selected = false,
            onClick = { showCreateDialog = true },
            contentColor = AppTheme.colorScheme.onSurfaceVariant,
            leading = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AppTheme.dimens.iconSm),
                )
            },
        )

        // Pinned footer, below every scope: the live RAW XMP-sidecar sync toggle. Fenced off by a
        // divider so it reads as root-level chrome, not another category row.
        HorizontalDivider(
            Modifier.padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
        )
        XmpSyncToggleRow(
            enabled = xmpSyncEnabled,
            skippedNonRaw = xmpSyncSkippedNonRaw,
            onToggle = onToggleXmpSync,
        )
    }

    if (showCreateDialog) {
        CategoryNameDialog(
            title = "New category",
            confirmLabel = "Create",
            // Every existing name is off-limits for a brand-new category.
            takenNames = entries.mapTo(mutableSetOf()) { it.first.name },
            onConfirm = {
                showCreateDialog = false
                onCreateCategory(it)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renaming?.let { category ->
        CategoryNameDialog(
            title = "Rename category",
            confirmLabel = "Rename",
            initialName = category.name,
            // Every other name is off-limits; the category keeps its own (re-confirming it is fine).
            takenNames = entries.filter { it.first.id != category.id }
                .mapTo(mutableSetOf()) { it.first.name },
            onConfirm = {
                renaming = null
                onRenameCategory(category.id, it)
            },
            onDismiss = { renaming = null },
        )
    }

    if (confirmingEmptyRejects) {
        ConfirmDialog(
            title = if (rejectsCount == 1) "Move 1 reject to Trash?" else "Move $rejectsCount rejects to Trash?",
            message = "Every photo flagged as a reject will be moved to the macOS Trash. You can " +
                "restore " + (if (rejectsCount == 1) "it" else "them") + " from there.",
            confirmLabel = "Move to Trash",
            confirmDestructive = true,
            onConfirm = {
                confirmingEmptyRejects = false
                onEmptyRejects()
            },
            onDismiss = { confirmingEmptyRejects = false },
        )
    }
}

/** The leading glyph for a built-in scope row: the favourite star or the reject flag. */
@Composable
private fun BuiltInLeadingIcon(id: CategoryId) {
    val size = Modifier.size(AppTheme.dimens.iconSm)
    when (id) {
        Category.REJECTS_ID -> RejectFlag(filled = true, tint = AppTheme.colors.reject, modifier = size)
        else -> FavouriteStar(filled = true, tint = AppTheme.colors.favourite, modifier = size)
    }
}

/** Folder identity at the top of the rail: the open folder's name, with the "Change folder" action. */
@Composable
private fun RailHeader(rootName: String, onChangeFolder: () -> Unit) {
    Column(Modifier.padding(horizontal = AppTheme.spacing.md)) {
        Text(
            text = rootName,
            style = AppTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChangeFolderButton(
            onChangeFolder = onChangeFolder,
            contentColor = AppTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Muted group label between rail sections (e.g. "Categories"). */
@Composable
private fun RailSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = AppTheme.typography.labelSmall,
        color = AppTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = AppTheme.spacing.md,
            top = AppTheme.spacing.md,
            bottom = AppTheme.spacing.xs,
        ),
    )
}

/** The "1".."9" slot hint for a custom category, echoing its bare-digit filing key. Blank past 9. */
@Composable
private fun RailSlotBadge(slot: Int) {
    Box(
        Modifier.size(AppTheme.dimens.iconSm),
        contentAlignment = Alignment.Center,
    ) {
        // Derived from the same helper the filing-key prefixes use, so the badge and the keys
        // can't disagree on the 1..9 cap.
        categorySlotDigit(slot)?.let { digit ->
            Text(
                text = digit,
                style = AppTheme.typography.labelMedium,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One rail entry: a clickable, full-width row with a leading glyph, an ellipsised label, a member
 * [count], and an optional trailing [actions] slot (the "⋯" rename/delete menu, custom categories
 * only). The active scope's row reads with a neutral fill (state is hueless here, the warm accent
 * stays reserved for the favourite star and primary actions). The row is given a uniform minimum
 * height so a row carrying the taller "⋯" icon button doesn't stand proud of its neighbours. Not
 * keyboard-focusable, by design — see [LibraryRail].
 */
@Composable
private fun RailRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    count: Int? = null,
    actions: (@Composable () -> Unit)? = null,
    contentColor: Color = AppTheme.colorScheme.onSurface,
) {
    val fill = if (selected) {
        AppTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }
    Row(
        Modifier
            .padding(horizontal = AppTheme.spacing.sm, vertical = 1.dp)
            .fillMaxWidth()
            .heightIn(min = RAIL_ROW_MIN_HEIGHT)
            .clip(AppTheme.shapes.small)
            .background(fill)
            // Announce the scope as a selectable tab so assistive tech reports the active row,
            // even though the row stays out of the keyboard focus order (below).
            .semantics {
                this.selected = selected
                role = Role.Tab
            }
            // The grid owns the keyboard ring; a focusable rail row would steal it and kill arrows.
            .focusProperties { canFocus = false }
            // The active row is inert: re-selecting the scope you're already in would push a
            // redundant navigation and recompute the cold scroll index, so swallow the click when
            // selected. The row stays an *enabled* tab (not gated via clickable's `enabled`, which
            // would stamp a misleading `disabled` onto the selected tab) so assistive tech reads it
            // as the selected tab, not a dead control.
            .clickable(onClick = { if (!selected) onClick() })
            .padding(start = AppTheme.spacing.sm, end = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        leading()
        Text(
            text = label,
            style = AppTheme.typography.bodyMedium,
            color = if (selected) AppTheme.colorScheme.onSurface else contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = "$count",
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions?.invoke()
    }
}

/** Uniform minimum height for a rail row, so the "⋯"-bearing custom rows align with the rest. */
private val RAIL_ROW_MIN_HEIGHT = 40.dp

/** True when this scope is the given category — the rail's active-row test. */
private fun CategoryScope.isCategory(id: CategoryId): Boolean =
    this is CategoryScope.Category && this.id == id
