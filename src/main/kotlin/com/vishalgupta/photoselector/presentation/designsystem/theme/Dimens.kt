package com.vishalgupta.photoselector.presentation.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Component-level fixed dimensions shared across screens. Values preserved
 * verbatim from the pre-token UI.
 *
 * Read through `AppTheme.dimens`.
 */
@Immutable
data class Dimens(
    val topBarHeight: Dp = 56.dp,
    // Fixed width of the left library rail (scopes: All Photos, Favourites, custom categories).
    // Wide enough for a category name plus its count without truncating common names; the rail
    // collapses to zero width behind the top-bar toggle when the user wants the grid full-bleed.
    val libraryRailWidth: Dp = 248.dp,
    // The adaptive grid's minimum tile width. Tuned down from 160 toward a contact-sheet
    // density: more frames per row for a culler scanning fast, still large enough to judge a
    // shot at a glance. The grid's gutters and content padding are tightened to match.
    val thumbnailMinCell: Dp = 132.dp,
    val focusBorderWidth: Dp = 3.dp,
    val lastViewedIndicatorHeight: Dp = 3.dp,
    val iconSm: Dp = 18.dp,
    val iconLg: Dp = 48.dp,
    // Bottom inset for the browser's confirmation toast. Lifts it clear of the bottom-center
    // HUD + keyboard-legend stack so a membership toast and the revealed HUD don't collide.
    val browserToastBottomInset: Dp = 128.dp,
    val scrollbarThickness: Dp = 8.dp,
    val scrollbarMinHeight: Dp = 48.dp,
    val progressIndicatorLg: Dp = 48.dp,
    // Thickness of the little drag-style handle that closes an expanded burst (one of the 3dp
    // hairline family alongside focusBorderWidth / lastViewedIndicatorHeight).
    val burstHandleHeight: Dp = 3.dp,
    // Horizontal inset inside the small overlay badges (burst count, category chip). Tighter than
    // the spacing scale by design, shared so both badges stay visually identical.
    val badgeInset: Dp = 5.dp,
    // Vertical counterpart to [badgeInset]: a 1dp hairline pad so the badge text isn't flush to the
    // chip edge. Off the spacing scale (even xxs reads too tall here), shared by every overlay badge.
    val badgeVerticalInset: Dp = 1.dp,
    // How far the stacked-deck cards behind a collapsed group tile peek out toward the top-right.
    // The whole deck is drawn *inside* the cell (the cover photo is inset by this much on its top
    // and end), so the peeking edges never bleed into the grid gutters or the neighbouring tile.
    val burstStackInset: Dp = 10.dp,
    // Max width of a centered supporting-copy line (the root picker's guidance/count text), so the
    // prose wraps to a comfortable measure rather than stretching across a wide window.
    val supportingTextMaxWidth: Dp = 360.dp,
)

val LocalDimens = staticCompositionLocalOf { Dimens() }
