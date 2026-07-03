package com.vishalgupta.photoselector.presentation.inspect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vishalgupta.photoselector.presentation.browser.BrowserScreen
import com.vishalgupta.photoselector.presentation.common.SystemActions
import com.vishalgupta.photoselector.presentation.survey.SurveyScreen

/**
 * Inspect: a fixed set of photos viewed either as the overview grid or in full-screen browse, behind
 * one toggle. This is the state-holder seam — it collects [InspectViewModel.mode] and hands the live
 * facet to the corresponding screen, which already renders that mode in full. The facets run with
 * `manageLifecycle = false` because Inspect, not their own dispose, owns the clear (so a toggle keeps
 * the hidden facet's decoded frames); the one [DisposableEffect] here cascades that clear.
 *
 * `Enter` / the grid's top-bar toggle opens the active tile in browse; the browser's grid toggle (or
 * `Esc`) returns to the overview when one exists, otherwise `Esc` exits. [onExit] returns to the
 * source (grid or browser) — the caller decides where, this only signals "leave Inspect".
 */
@Composable
fun InspectScreen(
    viewModel: InspectViewModel,
    systemActions: SystemActions,
    onExit: () -> Unit,
) {
    DisposableEffect(viewModel) { onDispose { viewModel.onClear() } }
    val mode by viewModel.mode.collectAsState()

    when (mode) {
        InspectMode.Grid -> SurveyScreen(
            viewModel = viewModel.gridViewModel(),
            systemActions = systemActions,
            onExit = onExit,
            title = "Inspect",
            onOpenActive = viewModel::openBrowse,
            manageLifecycle = false,
        )
        InspectMode.Browse -> BrowserScreen(
            viewModel = viewModel.browseViewModel(),
            systemActions = systemActions,
            // Esc / back: to the overview when there is one, otherwise out of Inspect entirely.
            onBack = { if (viewModel.gridAvailable) viewModel.openGrid() else onExit() },
            onCompare = {},
            embedded = true,
            onSwitchToGrid = if (viewModel.gridAvailable) viewModel::openGrid else null,
            manageLifecycle = false,
        )
    }
}
