package com.vishalgupta.photoselector.presentation.grid

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.common.digitSlot

/**
 * The grid's keyboard model, lifted out of the `GridScreen` composable so the input->intent mapping
 * is testable without a live window. The composable keeps only the genuinely Compose-coupled pieces
 * (the KeyUp-swallow guard, the laid-out geometry the vertical nav and seed read), and hands the
 * pure branching here.
 *
 * The split has a real seam, not a clean lift: arrow *vertical* nav and seeding focus resolve against
 * the laid-out tile geometry, and the viewport anchor must be told about cursor moves and reshapes.
 * Those stay on the screen and arrive as callbacks on [GridKeyActions]; everything that is pure
 * input->intent (digits, F/Space filing, Cmd+A, Cmd+Delete, C, G, Enter, the Esc layering) lives in
 * [handleGridKey] and is exercised by fast unit tests with a recording fake.
 *
 * This is the grid's most bug-prone surface (the three index spaces — see [GridIndex] and the
 * `project_burst_grid_index_and_identity_traps` note), so giving the branching a checked seam directly
 * attacks where those bugs keep landing.
 */
internal data class GridKeyContext(
    /** The keyboard cursor, in tile-index space. [TileIndex.NONE] when nothing is focused yet. */
    val focusedIndex: TileIndex,
    /** Last addressable tile (`tiles.size - 1`); negative when the grid is empty. */
    val maxIndex: Int,
    val hasSelection: Boolean,
    val selectionSize: Int,
    val expandedBurstId: PhotoId?,
    val groupingMode: GroupingMode,
    /** Whether the focused tile is a collapsed burst — drives the keyboard "Review" fallback. */
    val focusedTileIsBurst: Boolean,
    /** Whether `Esc` has a screen to pop once selection / burst layers are peeled (`onBack != null`). */
    val canPop: Boolean,
) {
    /** True when the cursor addresses a real, in-bounds tile. */
    val focusedInRange: Boolean get() = focusedIndex.value in 0..maxIndex
}

/**
 * The effects the dispatcher fires. The layout-coupled ones ([seedFocus], [moveFocusVertical]) and the
 * anchor-coupled ones ([moveFocusHorizontal] re-pins, [cycleLensForward] captures the viewport top) are
 * bound by `GridScreen` to the geometry/anchor it owns; the rest forward to the view model callbacks.
 */
internal class GridKeyActions(
    val selectAll: () -> Unit,
    val confirmDelete: () -> Unit,
    val inspectSelection: () -> Unit,
    val reviewFocused: () -> Unit,
    /** Switch to [mode] through the anchored selector (re-pins the viewport across the reshape). */
    val selectLens: (mode: GroupingMode) -> Unit,
    /** Seed the cursor on the first visible tile (reads laid-out geometry). */
    val seedFocus: () -> Unit,
    /** Move the cursor one tile left (-1) / right (+1), coerced to bounds and re-pinned. */
    val moveFocusHorizontal: (delta: Int) -> Unit,
    /** Move the cursor one row up/down, resolved against the laid-out tile rows. */
    val moveFocusVertical: (down: Boolean) -> Unit,
    val openFocused: () -> Unit,
    val fileSelectionIntoCustom: (slot: Int) -> Unit,
    val toggleCustomCategoryAtFocus: (slot: Int) -> Unit,
    val fileSelectionIntoFavourites: () -> Unit,
    val toggleMembershipAtFocus: () -> Unit,
    val fileSelectionIntoRejects: () -> Unit,
    val toggleRejectAtFocus: () -> Unit,
    val clearSelection: () -> Unit,
    val collapseBurst: () -> Unit,
    val back: () -> Unit,
)

/**
 * The "selection wins over focus" rule, in one place: a bulk action when a multi-select is armed,
 * otherwise the focused-tile action. Both focus actions no-op internally on an out-of-range cursor
 * (`GridViewModel.fileAtFocus` resolves `displayGroups.getOrNull` to nothing), so this needs no
 * separate in-range guard — the digit path's old explicit check was redundant.
 */
private inline fun GridKeyContext.fileOrToggle(onSelection: () -> Unit, onFocus: () -> Unit) {
    if (hasSelection) onSelection() else onFocus()
}

/**
 * Maps a key-down [event] to its grid intent, returning true when the key is consumed. Callers must
 * pass only `KeyDown` events — the `KeyUp`-swallow guard (which stops a focused tile's `clickable`
 * re-firing on Enter/Space key-up) is Compose-specific and stays in `GridScreen`.
 */
internal fun handleGridKey(event: KeyEvent, ctx: GridKeyContext, actions: GridKeyActions): Boolean {
    val meta = event.isMetaPressed

    // Cmd+A arms a multi-select over the whole scope.
    if (meta && event.key == Key.A) {
        if (ctx.maxIndex >= 0) actions.selectAll()
        return true
    }
    // Cmd+Delete (Cmd+Backspace on a Mac keyboard) over a selection arms the move-to-Trash
    // confirmation — the macOS "move to trash" chord, applied to the whole pick.
    if (meta && (event.key == Key.Backspace || event.key == Key.Delete) && ctx.hasSelection) {
        actions.confirmDelete()
        return true
    }
    // C opens a 2+ multi-selection in Inspect (a 2-tile selection opens its overview grid like any
    // larger one; a large selection isn't declined — Inspect opens it browse-only).
    if (!meta && event.key == Key.C && ctx.selectionSize >= 2) {
        actions.inspectSelection()
        return true
    }
    // C with no multi-select but a collapsed group focused: review that group's run — the group's
    // frames ARE the selection (the keyboard fallback for the hover "Review" CTA). Singles no-op.
    if (!meta && event.key == Key.C && !ctx.hasSelection && ctx.focusedTileIsBurst) {
        actions.reviewFocused()
        return true
    }
    // G cycles the lens Single -> Bursts -> Similar -> Single without the mouse, through the anchored
    // selector so the viewport re-pins across the reshape. Suppressed during a multi-select.
    if (!meta && event.key == Key.G && !ctx.hasSelection) {
        val modes = GroupingMode.entries
        actions.selectLens(modes[(ctx.groupingMode.ordinal + 1) % modes.size])
        return true
    }
    // An arrow with no cursor yet seeds focus on the first visible tile.
    val isArrow = event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
        event.key == Key.DirectionUp || event.key == Key.DirectionDown
    if (isArrow && !ctx.focusedIndex.isSet && ctx.maxIndex >= 0) {
        actions.seedFocus()
        return true
    }
    // Bare 1..9 files into the Nth custom category: the whole selection when one is armed, otherwise
    // the focused tile (the keyboard path for filing from All Photos without leaving it).
    val slot = if (meta) null else digitSlot(event.key)
    if (slot != null) {
        ctx.fileOrToggle({ actions.fileSelectionIntoCustom(slot) }, { actions.toggleCustomCategoryAtFocus(slot) })
        return true
    }
    return when (event.key) {
        Key.DirectionLeft -> {
            actions.moveFocusHorizontal(-1)
            true
        }
        Key.DirectionRight -> {
            actions.moveFocusHorizontal(+1)
            true
        }
        Key.DirectionUp -> {
            actions.moveFocusVertical(/* down = */ false)
            true
        }
        Key.DirectionDown -> {
            actions.moveFocusVertical(/* down = */ true)
            true
        }
        Key.Enter -> {
            if (ctx.focusedInRange) actions.openFocused()
            true
        }
        // F and Space both file into Favourites: the selection when armed, else the focused tile.
        Key.F, Key.Spacebar -> if (meta) false else {
            ctx.fileOrToggle(actions.fileSelectionIntoFavourites, actions.toggleMembershipAtFocus)
            true
        }
        // X files into Rejects — the cull's reject half, mirroring F: the selection when armed, else
        // the focused tile. Cmd+X stays the system cut, so it falls through.
        Key.X -> if (meta) false else {
            ctx.fileOrToggle(actions.fileSelectionIntoRejects, actions.toggleRejectAtFocus)
            true
        }
        // Esc peels one layer at a time: clear a selection, then fold an open burst, then pop the
        // screen. Each press undoes the most recent thing the user did.
        Key.Escape -> when {
            ctx.hasSelection -> {
                actions.clearSelection()
                true
            }
            ctx.expandedBurstId != null -> {
                actions.collapseBurst()
                true
            }
            ctx.canPop -> {
                actions.back()
                true
            }
            else -> false
        }
        else -> false
    }
}
