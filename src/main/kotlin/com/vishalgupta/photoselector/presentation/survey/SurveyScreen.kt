package com.vishalgupta.photoselector.presentation.survey

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.presentation.common.SystemActions
import com.vishalgupta.photoselector.presentation.common.customCategories
import com.vishalgupta.photoselector.presentation.common.digitSlot
import com.vishalgupta.photoselector.presentation.common.insightTileSignals
import com.vishalgupta.photoselector.presentation.designsystem.molecule.SurveyKeyboardLegend
import com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserCategoryHud
import com.vishalgupta.photoselector.presentation.designsystem.organism.SurveyTileView
import com.vishalgupta.photoselector.presentation.designsystem.organism.TopBarScaffold
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

@Composable
fun SurveyScreen(
    viewModel: SurveyViewModel,
    systemActions: SystemActions,
    onExit: () -> Unit,
    // Title and the "open the active tile in browse mode" toggle are set by Inspect, which hosts this
    // grid as one of its two facets; [manageLifecycle] is false there so Inspect owns the VM's clear.
    title: String = "Survey",
    onOpenActive: (() -> Unit)? = null,
    manageLifecycle: Boolean = true,
) {
    DisposableEffect(viewModel, manageLifecycle) { onDispose { if (manageLifecycle) viewModel.onClear() } }
    val state by viewModel.state.collectAsState()

    // Decoding is driven entirely by the reported viewport (onViewportSizeChanged) so tiles decode
    // once, at their real cell size — no eager decode at a guessed default that gets thrown away.
    SurveyScreen(
        state = state,
        systemActions = systemActions,
        onSetActive = viewModel::setActive,
        onMoveActive = viewModel::moveActive,
        onToggleCategory = viewModel::toggleCategory,
        onViewportSizeChanged = viewModel::setViewportLongEdgePx,
        onExit = onExit,
        title = title,
        onOpenActive = onOpenActive,
    )
}

/**
 * Stateless survey overview: the selected tiles laid out in an even, non-scrolling grid (every tile
 * stays on screen — it's an overview), one of them active. Keyboard model: `Tab` and `← → ↑ ↓` move
 * the active tile, `F`/`1-9` file it, `Space`/`R`/`O` act on it via the OS (when [systemActions] is
 * wired), `Esc` returns to the grid. No zoom — survey is for picking between frames at a glance, not
 * pixel-peeping (that's compare).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SurveyScreen(
    state: SurveyUiState,
    systemActions: SystemActions? = null,
    onSetActive: (Int) -> Unit,
    onMoveActive: (Int) -> Unit,
    onToggleCategory: (CategoryId) -> Unit,
    onViewportSizeChanged: (Int) -> Unit,
    onExit: () -> Unit,
    title: String = "Survey",
    // Non-null when this grid is a facet of Inspect: `Enter` and a top-bar button open the active
    // tile in browse mode. Null elsewhere, where the key and button are simply absent.
    onOpenActive: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val cols = surveyColumns(state.tiles.size)

    var viewportPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(viewportPx) {
        if (viewportPx > 0) onViewportSizeChanged(viewportPx)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val meta = event.isMetaPressed
                val slot = if (meta) null else digitSlot(event.key)
                if (slot != null) {
                    state.categories.customCategories().getOrNull(slot)?.let { onToggleCategory(it.id) }
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    // Tab cycles (wraps); arrows move within the grid and clamp at the edges.
                    Key.Tab -> {
                        if (state.tiles.isNotEmpty()) onSetActive((state.activeTile + 1) % state.tiles.size)
                        true
                    }
                    Key.DirectionLeft -> { onMoveActive(-1); true }
                    Key.DirectionRight -> { onMoveActive(1); true }
                    Key.DirectionUp -> { onMoveActive(-cols); true }
                    Key.DirectionDown -> { onMoveActive(cols); true }
                    Key.F -> if (meta) false else { onToggleCategory(Category.FAVOURITES_ID); true }
                    Key.R -> if (meta) false else {
                        state.active?.photo?.absolutePath?.let { systemActions?.revealInFileManager(it) }
                        true
                    }
                    Key.O -> if (meta) false else {
                        state.active?.photo?.absolutePath?.let { systemActions?.openWithDefaultApp(it) }
                        true
                    }
                    // Enter opens the active tile in Inspect's browse mode (absent outside Inspect).
                    Key.Enter -> if (onOpenActive != null) { onOpenActive(); true } else false
                    Key.Escape -> { onExit(); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBarScaffold(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppTheme.colors.topBarScrim,
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (onOpenActive != null) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenActive) {
                        Icon(Icons.Outlined.Fullscreen, contentDescription = "Browse", tint = Color.White)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(AppTheme.spacing.xs)
                        .onSizeChanged { size ->
                            // Each tile gets ~1/cols of the width; size the decode to that edge.
                            val perTile = size.width / cols
                            if (perTile > 0) viewportPx = perTile
                        },
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                ) {
                    // Carry each tile's flat position alongside it so the active highlight and
                    // onActivate target never depend on tile value-equality (indexOf).
                    state.tiles.withIndex().chunked(cols).forEach { rowTiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                        ) {
                            rowTiles.forEach { (pos, tile) ->
                                SurveyTileView(
                                    tile = tile,
                                    isActive = pos == state.activeTile,
                                    totalInScope = state.totalInScope,
                                    onActivate = { onSetActive(pos) },
                                    signals = insightTileSignals(tile.memberships, state.categories),
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            // Pad a short last row so its tiles keep the same width as full rows.
                            repeat(cols - rowTiles.size) { Spacer(Modifier.weight(1f).fillMaxHeight()) }
                        }
                    }
                }

                // Bottom chrome reflects the ACTIVE tile, mirroring compare: the HUD lights and
                // toggles its memberships, the legend names the keys. Persistent (survey is a short,
                // deliberate mode) rather than auto-hidden like the browser.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = AppTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    BrowserCategoryHud(
                        categories = state.categories,
                        currentMemberships = state.active?.memberships ?: emptySet(),
                        onToggle = onToggleCategory,
                    )
                    SurveyKeyboardLegend(
                        hasCustomCategories = state.categories.customCategories().isNotEmpty(),
                        readOnly = state.readOnly,
                        canBrowse = onOpenActive != null,
                    )
                }
            }
        }
    }
}

/**
 * Column count for an [n]-tile survey: keep small sets on a single row (3 across), 4 as a tidy 2x2,
 * and cap at 4 columns so a large accidental selection (e.g. Cmd+A then `C`) still tiles evenly
 * rather than producing one absurdly wide row.
 */
internal fun surveyColumns(n: Int): Int = when {
    n <= 3 -> n.coerceAtLeast(1)
    n == 4 -> 2
    n <= 9 -> 3
    else -> 4
}
