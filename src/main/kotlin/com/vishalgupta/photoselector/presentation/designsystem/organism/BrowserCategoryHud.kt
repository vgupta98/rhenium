package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.presentation.designsystem.atom.FavouriteStar
import com.vishalgupta.photoselector.presentation.designsystem.atom.RejectFlag
import com.vishalgupta.photoselector.presentation.designsystem.molecule.CategoryHudChip
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.designsystem.theme.PillShape

/**
 * The heads-up legend overlaid on the full-screen browser: one [CategoryHudChip] per
 * category showing its hotkey and whether the current photo (whose memberships are
 * [currentMemberships]) belongs to it. Tapping a chip toggles membership — the same effect
 * as the keyboard. The built-in scopes come first — Favourites (`F`, gold star) then Rejects
 * (`X`, red flag), the two sides of the cull — and custom categories follow with bare digits
 * `1`..`9` (the 10th-plus get no digit).
 *
 * Visibility (auto-hide / reveal-on-interaction) is the caller's concern — this just draws
 * the strip.
 */
@Composable
fun BrowserCategoryHud(
    categories: List<Category>,
    currentMemberships: Set<CategoryId>,
    onToggle: (CategoryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return
    val favourites = categories.firstOrNull { it.id == Category.FAVOURITES_ID }
    val rejects = categories.firstOrNull { it.id == Category.REJECTS_ID }
    val customs = categories.filter { !it.builtIn }
    val inactiveContainer = AppTheme.colors.overlayChromeInactiveFill
    val inactiveContent = AppTheme.colors.onOverlayChrome
    val iconSize = Modifier.size(AppTheme.dimens.iconSm)

    Surface(
        shape = PillShape,
        color = AppTheme.colors.overlayChromeBackground,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        ) {
            if (favourites != null) {
                val active = Category.FAVOURITES_ID in currentMemberships
                CategoryHudChip(
                    keyLabel = "F",
                    label = favourites.name,
                    leadingIcon = { FavouriteStar(filled = active, modifier = iconSize) },
                    containerColor = if (active) AppTheme.colors.favourite else inactiveContainer,
                    contentColor = if (active) AppTheme.colors.favouriteToastContent else inactiveContent,
                    onClick = { onToggle(Category.FAVOURITES_ID) },
                )
            }
            if (rejects != null) {
                val active = Category.REJECTS_ID in currentMemberships
                CategoryHudChip(
                    keyLabel = "X",
                    label = rejects.name,
                    leadingIcon = { RejectFlag(filled = active, modifier = iconSize) },
                    containerColor = if (active) AppTheme.colors.reject else inactiveContainer,
                    contentColor = if (active) AppTheme.colors.onOverlayChrome else inactiveContent,
                    onClick = { onToggle(Category.REJECTS_ID) },
                )
            }
            customs.forEachIndexed { slot, category ->
                val active = category.id in currentMemberships
                CategoryHudChip(
                    keyLabel = if (slot < 9) "${slot + 1}" else null,
                    label = category.name,
                    containerColor = if (active) AppTheme.colors.categoryMemberContainer else inactiveContainer,
                    contentColor = if (active) AppTheme.colors.categoryMemberContent else inactiveContent,
                    onClick = { onToggle(category.id) },
                )
            }
        }
    }
}
