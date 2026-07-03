package com.vishalgupta.photoselector.presentation.grid

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast unit tests for the grid key dispatcher — the seam [handleGridKey] exists to provide. These
 * exercise the pure input->intent branches (digits, F/Space, Cmd+A, Cmd+Delete, C, G, Enter, the Esc
 * layering) with a recording fake, with no window or layout. The layout-coupled branches (vertical
 * nav + seed geometry) stay covered by the Compose `GridKeyboardTest`.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class GridKeyBindingsTest {

    private fun keyDown(key: Key, meta: Boolean = false): KeyEvent =
        KeyEvent(key = key, type = KeyEventType.KeyDown, isMetaPressed = meta)

    private fun ctx(
        focusedIndex: TileIndex = TileIndex(0),
        maxIndex: Int = 9,
        hasSelection: Boolean = false,
        selectionSize: Int = 0,
        expandedBurstId: PhotoId? = null,
        groupingMode: GroupingMode = GroupingMode.Time,
        focusedTileIsBurst: Boolean = false,
        canPop: Boolean = true,
    ) = GridKeyContext(
        focusedIndex = focusedIndex,
        maxIndex = maxIndex,
        hasSelection = hasSelection,
        selectionSize = selectionSize,
        expandedBurstId = expandedBurstId,
        groupingMode = groupingMode,
        focusedTileIsBurst = focusedTileIsBurst,
        canPop = canPop,
    )

    /** A [GridKeyActions] that records each call (and its argument, where relevant) into [log]. */
    private class Recorder {
        val log = mutableListOf<String>()
        val actions = GridKeyActions(
            selectAll = { log += "selectAll" },
            confirmDelete = { log += "confirmDelete" },
            inspectSelection = { log += "inspectSelection" },
            reviewFocused = { log += "reviewFocused" },
            selectLens = { log += "selectLens:$it" },
            seedFocus = { log += "seedFocus" },
            moveFocusHorizontal = { log += "moveH:$it" },
            moveFocusVertical = { log += "moveV:$it" },
            openFocused = { log += "openFocused" },
            fileSelectionIntoCustom = { log += "fileSelCustom:$it" },
            toggleCustomCategoryAtFocus = { log += "toggleCustom:$it" },
            fileSelectionIntoFavourites = { log += "fileSelFav" },
            toggleMembershipAtFocus = { log += "toggleFav" },
            fileSelectionIntoRejects = { log += "fileSelReject" },
            toggleRejectAtFocus = { log += "toggleReject" },
            clearSelection = { log += "clearSelection" },
            collapseBurst = { log += "collapseBurst" },
            back = { log += "back" },
        )
    }

    private fun dispatch(event: KeyEvent, ctx: GridKeyContext): Pair<Boolean, List<String>> {
        val rec = Recorder()
        val consumed = handleGridKey(event, ctx, rec.actions)
        return consumed to rec.log
    }

    // ---- Cmd+A / Cmd+Delete ---------------------------------------------------------------------

    @Test
    fun `cmd+A selects all when grid is non-empty`() {
        val (consumed, log) = dispatch(keyDown(Key.A, meta = true), ctx(maxIndex = 9))
        assertTrue(consumed)
        assertEquals(listOf("selectAll"), log)
    }

    @Test
    fun `cmd+A is consumed but no-ops on an empty grid`() {
        val (consumed, log) = dispatch(keyDown(Key.A, meta = true), ctx(maxIndex = -1))
        assertTrue(consumed)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `cmd+Delete over a selection arms the delete confirmation`() {
        val (consumed, log) = dispatch(keyDown(Key.Delete, meta = true), ctx(hasSelection = true))
        assertTrue(consumed)
        assertEquals(listOf("confirmDelete"), log)
    }

    @Test
    fun `cmd+Backspace over a selection arms the delete confirmation`() {
        val (consumed, log) = dispatch(keyDown(Key.Backspace, meta = true), ctx(hasSelection = true))
        assertTrue(consumed)
        assertEquals(listOf("confirmDelete"), log)
    }

    @Test
    fun `cmd+Delete with no selection is not consumed`() {
        val (consumed, log) = dispatch(keyDown(Key.Delete, meta = true), ctx(hasSelection = false))
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- C: inspect vs review -------------------------------------------------------------------

    @Test
    fun `C with a 2+ selection opens inspect`() {
        val (consumed, log) = dispatch(keyDown(Key.C), ctx(hasSelection = true, selectionSize = 2))
        assertTrue(consumed)
        assertEquals(listOf("inspectSelection"), log)
    }

    @Test
    fun `C on a focused burst with no selection reviews the group`() {
        val (consumed, log) = dispatch(keyDown(Key.C), ctx(focusedTileIsBurst = true))
        assertTrue(consumed)
        assertEquals(listOf("reviewFocused"), log)
    }

    @Test
    fun `C on a focused single with no selection does nothing`() {
        val (consumed, log) = dispatch(keyDown(Key.C), ctx(focusedTileIsBurst = false))
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- G: lens cycle --------------------------------------------------------------------------

    @Test
    fun `G cycles to the next lens`() {
        val order = GroupingMode.entries
        order.forEachIndexed { i, mode ->
            val (consumed, log) = dispatch(keyDown(Key.G), ctx(groupingMode = mode))
            val next = order[(i + 1) % order.size]
            assertTrue(consumed)
            assertEquals(listOf("selectLens:$next"), log)
        }
    }

    @Test
    fun `G during a selection is not consumed`() {
        val (consumed, log) = dispatch(keyDown(Key.G), ctx(hasSelection = true))
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- Arrows ---------------------------------------------------------------------------------

    @Test
    fun `an arrow with no cursor yet seeds focus`() {
        val (consumed, log) = dispatch(keyDown(Key.DirectionRight), ctx(focusedIndex = TileIndex.NONE))
        assertTrue(consumed)
        assertEquals(listOf("seedFocus"), log)
    }

    @Test
    fun `left and right move the cursor horizontally`() {
        assertEquals(listOf("moveH:-1"), dispatch(keyDown(Key.DirectionLeft), ctx()).second)
        assertEquals(listOf("moveH:1"), dispatch(keyDown(Key.DirectionRight), ctx()).second)
    }

    @Test
    fun `up and down move the cursor vertically`() {
        assertEquals(listOf("moveV:false"), dispatch(keyDown(Key.DirectionUp), ctx()).second)
        assertEquals(listOf("moveV:true"), dispatch(keyDown(Key.DirectionDown), ctx()).second)
    }

    // ---- Digits: selection wins over focus ------------------------------------------------------

    @Test
    fun `a digit with a selection files the selection into that custom slot`() {
        val (consumed, log) = dispatch(keyDown(Key.Two), ctx(hasSelection = true))
        assertTrue(consumed)
        assertEquals(listOf("fileSelCustom:1"), log)
    }

    @Test
    fun `a digit with no selection toggles the focused tile into that custom slot`() {
        val (consumed, log) = dispatch(keyDown(Key.One), ctx(hasSelection = false))
        assertTrue(consumed)
        assertEquals(listOf("toggleCustom:0"), log)
    }

    @Test
    fun `a cmd+digit is not a filing slot`() {
        val (consumed, log) = dispatch(keyDown(Key.One, meta = true), ctx())
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- Enter ----------------------------------------------------------------------------------

    @Test
    fun `Enter opens the focused tile when in range`() {
        val (consumed, log) = dispatch(keyDown(Key.Enter), ctx(focusedIndex = TileIndex(3), maxIndex = 9))
        assertTrue(consumed)
        assertEquals(listOf("openFocused"), log)
    }

    @Test
    fun `Enter is consumed but opens nothing when the cursor is out of range`() {
        val (consumed, log) = dispatch(keyDown(Key.Enter), ctx(focusedIndex = TileIndex.NONE))
        assertTrue(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- F / Space: favourite, selection wins over focus ----------------------------------------

    @Test
    fun `F and Space file the selection into favourites when one is armed`() {
        assertEquals(listOf("fileSelFav"), dispatch(keyDown(Key.F), ctx(hasSelection = true)).second)
        assertEquals(listOf("fileSelFav"), dispatch(keyDown(Key.Spacebar), ctx(hasSelection = true)).second)
    }

    @Test
    fun `F and Space toggle the focused tile's favourite with no selection`() {
        assertEquals(listOf("toggleFav"), dispatch(keyDown(Key.F), ctx(hasSelection = false)).second)
        assertEquals(listOf("toggleFav"), dispatch(keyDown(Key.Spacebar), ctx(hasSelection = false)).second)
    }

    @Test
    fun `cmd+F is not consumed (leaves the menu shortcut to the system)`() {
        val (consumed, log) = dispatch(keyDown(Key.F, meta = true), ctx())
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- X: reject, selection wins over focus ---------------------------------------------------

    @Test
    fun `X files the selection into rejects when one is armed`() {
        assertEquals(listOf("fileSelReject"), dispatch(keyDown(Key.X), ctx(hasSelection = true)).second)
    }

    @Test
    fun `X toggles the focused tile's reject with no selection`() {
        assertEquals(listOf("toggleReject"), dispatch(keyDown(Key.X), ctx(hasSelection = false)).second)
    }

    @Test
    fun `cmd+X is not consumed (leaves the system cut shortcut)`() {
        val (consumed, log) = dispatch(keyDown(Key.X, meta = true), ctx())
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- Esc: peels one layer at a time ---------------------------------------------------------

    @Test
    fun `Esc clears a selection first`() {
        val (consumed, log) = dispatch(
            keyDown(Key.Escape),
            ctx(hasSelection = true, expandedBurstId = PhotoId("b"), canPop = true),
        )
        assertTrue(consumed)
        assertEquals(listOf("clearSelection"), log)
    }

    @Test
    fun `Esc folds an open burst once the selection is gone`() {
        val (consumed, log) = dispatch(
            keyDown(Key.Escape),
            ctx(hasSelection = false, expandedBurstId = PhotoId("b"), canPop = true),
        )
        assertTrue(consumed)
        assertEquals(listOf("collapseBurst"), log)
    }

    @Test
    fun `Esc pops the screen once nothing is layered`() {
        val (consumed, log) = dispatch(
            keyDown(Key.Escape),
            ctx(hasSelection = false, expandedBurstId = null, canPop = true),
        )
        assertTrue(consumed)
        assertEquals(listOf("back"), log)
    }

    @Test
    fun `Esc with nothing to peel and no back target is not consumed`() {
        val (consumed, log) = dispatch(
            keyDown(Key.Escape),
            ctx(hasSelection = false, expandedBurstId = null, canPop = false),
        )
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }

    // ---- Unhandled keys -------------------------------------------------------------------------

    @Test
    fun `an unmapped key is not consumed`() {
        val (consumed, log) = dispatch(keyDown(Key.Z), ctx())
        assertFalse(consumed)
        assertEquals(emptyList<String>(), log)
    }
}
