package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import com.vishalgupta.photoselector.presentation.designsystem.theme.PillShape
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The browser's keyboard legend: the same discoverability the grid footer gives, restyled for
 * the dark, full-bleed photo backdrop. It matches the [com.vishalgupta.photoselector
 * .presentation.designsystem.organism.BrowserCategoryHud] — a translucent black pill with white
 * text — so the two read as one chrome surface, and the caller folds it into the HUD's existing
 * reveal/auto-hide rather than pinning a third always-on band over the image.
 *
 * The hints are truthful to the browser's key handler and verbs are platform-neutral ("Reveal",
 * "Open") so the strip stays short and reads correctly on a future Windows build. Filing
 * hints are dropped when they can't do anything: `F` and `1–9` are hidden in [readOnly], and
 * `1–9` only shows when [hasCustomCategories]; `C` is hidden when it can't [canCompare] (embedded
 * in Inspect, where `C` is inert), and a `Grid` hint appears instead when there's an overview to
 * return to ([canReturnToGrid]).
 */
@Composable
fun BrowserKeyboardLegend(
    hasCustomCategories: Boolean,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    // True only when browsing a category, where `A` jumps to the photo in the All Photos grid.
    canShowInAllPhotos: Boolean = false,
    // True in the standalone browser, where `C` opens the current photo + neighbour in Inspect.
    // False when embedded in Inspect's browse facet, where `C` is inert.
    canCompare: Boolean = true,
    // True when embedded in Inspect with an overview grid behind the toggle, where `G` / `Esc`
    // return to it.
    canReturnToGrid: Boolean = false,
) {
    KeyboardLegend(
        hints = browserHints(
            hasCustomCategories = hasCustomCategories,
            readOnly = readOnly,
            canShowInAllPhotos = canShowInAllPhotos,
            canCompare = canCompare,
            canReturnToGrid = canReturnToGrid,
        ),
        modifier = modifier,
        shape = PillShape,
        containerColor = AppTheme.colors.overlayChromeBackground,
        contentColor = AppTheme.colors.onOverlayChrome,
        keyCapContainerColor = AppTheme.colors.overlayChromeInactiveFill,
        keyCapContentColor = AppTheme.colors.onOverlayChrome,
    )
}

private fun browserHints(
    hasCustomCategories: Boolean,
    readOnly: Boolean,
    canShowInAllPhotos: Boolean,
    canCompare: Boolean,
    canReturnToGrid: Boolean,
): ImmutableList<KeyHint> = buildList {
    add(KeyHint("← →", "Move"))
    if (!readOnly) {
        add(KeyHint("F", "Favourite"))
        add(KeyHint("X", "Reject"))
        if (hasCustomCategories) add(KeyHint("1–9", "Categories"))
    }
    if (canCompare) add(KeyHint("C", "Compare"))
    if (canReturnToGrid) add(KeyHint("G", "Grid"))
    if (canShowInAllPhotos) add(KeyHint("A", "All Photos"))
    add(KeyHint("R", "Reveal"))
    add(KeyHint("O", "Open"))
    add(KeyHint("+ − 0", "Zoom"))
    add(KeyHint("Esc", "Back"))
}.toImmutableList()
