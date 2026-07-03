package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.presentation.common.CategoryToggle
import com.vishalgupta.photoselector.presentation.designsystem.atom.FavouriteStar
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/**
 * The one-shot confirmation pill for a [CategoryToggle], shared by the grid and the browser so the
 * two screens describe the same action the same way instead of drifting apart: the label
 * ("Favourited" / "Unfavourited" / "Added to <category>" / "Removed from <category>"), the
 * [FavouriteStar] leading icon for the Favourites toggle, and the colour that encodes the action
 * (added vs removed) — a fast peripheral cue when flipping through a cull. Callers wrap it in their
 * own reveal chrome (the grid's `LatchedPill`, the browser's `AnimatedVisibility`).
 */
@Composable
fun CategoryTogglePill(toggle: CategoryToggle, modifier: Modifier = Modifier) {
    PillToast(
        modifier = modifier,
        text = when {
            toggle.isFavourite && toggle.added -> "Favourited"
            toggle.isFavourite -> "Unfavourited"
            toggle.added -> "Added to ${toggle.categoryName}"
            else -> "Removed from ${toggle.categoryName}"
        },
        leadingIcon = if (toggle.isFavourite) {
            { FavouriteStar(filled = toggle.added, modifier = Modifier.size(AppTheme.dimens.iconSm)) }
        } else {
            null
        },
        colors = if (toggle.added) {
            PillToastDefaults.addedColors()
        } else {
            PillToastDefaults.removedColors()
        },
    )
}
