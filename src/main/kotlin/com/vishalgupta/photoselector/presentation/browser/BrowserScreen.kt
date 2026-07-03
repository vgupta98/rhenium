package com.vishalgupta.photoselector.presentation.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.presentation.common.CategoryToggle
import com.vishalgupta.photoselector.presentation.common.HoverOverlay
import com.vishalgupta.photoselector.presentation.common.SystemActions
import com.vishalgupta.photoselector.presentation.common.customCategories
import com.vishalgupta.photoselector.presentation.common.digitSlot
import com.vishalgupta.photoselector.presentation.designsystem.atom.LoadingIndicator
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BrowserKeyboardLegend
import com.vishalgupta.photoselector.presentation.designsystem.molecule.CategoryTogglePill
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ConfirmDialog
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ErrorPlaceholder
import com.vishalgupta.photoselector.presentation.designsystem.molecule.PillToast
import com.vishalgupta.photoselector.presentation.designsystem.molecule.PillToastDefaults
import com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserCategoryHud
import com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserTopBar
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    systemActions: SystemActions,
    onBack: () -> Unit,
    onCompare: () -> Unit,
    // Non-null only when browsing a category: jumps to this photo in the All Photos grid. Null hides
    // the affordance (and disables its key) in the All-Photos browser, which is already All Photos.
    onShowInAllPhotos: (() -> Unit)? = null,
    // True when embedded in Inspect's browse mode: trims the browser to the fixed set (no library
    // chrome, no delete). [onSwitchToGrid] is the toggle back to the overview (null for a browse-only
    // set). [manageLifecycle] is false there so Inspect, not a per-toggle dispose, clears the VM.
    embedded: Boolean = false,
    onSwitchToGrid: (() -> Unit)? = null,
    manageLifecycle: Boolean = true,
) {
    DisposableEffect(viewModel, manageLifecycle) { onDispose { if (manageLifecycle) viewModel.onClear() } }
    val state by viewModel.state.collectAsState()
    var toast by remember { mutableStateOf<CategoryToggle?>(null) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
    }

    LaunchedEffect(viewModel) {
        viewModel.toggleEvents.collectLatest { event ->
            toast = event
            delay(1200)
            toast = null
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.deleteEvents.collectLatest { message ->
            deleteMessage = message
            delay(1600)
            deleteMessage = null
        }
    }

    LaunchedEffect(state.currentPhoto?.id) {
        toast = null
    }

    BrowserScreen(
        state = state,
        toast = toast,
        deleteMessage = deleteMessage,
        systemActions = systemActions,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onToggleCategory = viewModel::toggleCategory,
        onDeleteCurrent = viewModel::deleteCurrent,
        onViewportSizeChanged = viewModel::setViewportLongEdgePx,
        onBackToGrid = onBack,
        onCompare = onCompare,
        onShowInAllPhotos = onShowInAllPhotos,
        embedded = embedded,
        onSwitchToGrid = onSwitchToGrid,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    toast: CategoryToggle?,
    systemActions: SystemActions? = null,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleCategory: (CategoryId) -> Unit,
    onViewportSizeChanged: (Int) -> Unit,
    onBackToGrid: () -> Unit,
    onCompare: () -> Unit = {},
    onShowInAllPhotos: (() -> Unit)? = null,
    // True when embedded in Inspect's browse mode: hides the library chrome and disables move-to-Trash
    // (a fixed inspect set isn't where you cull files). [onSwitchToGrid] is the toggle back to the
    // overview, shown only when there is a grid to return to.
    embedded: Boolean = false,
    onSwitchToGrid: (() -> Unit)? = null,
    deleteMessage: String? = null,
    onDeleteCurrent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val zoom = rememberZoomState()
    // Open/closed state of the move-to-Trash confirmation; Cmd+Delete arms it.
    var confirmingDelete by remember { mutableStateOf(false) }

    // The HUD auto-hides; any handled keystroke (and, via HoverOverlay, mouse movement)
    // reveals it. Bumping this token restarts the hide timer.
    var revealHud by remember { mutableIntStateOf(0) }
    var keyRevealActive by remember { mutableStateOf(false) }
    LaunchedEffect(revealHud) {
        if (revealHud == 0) return@LaunchedEffect
        keyRevealActive = true
        delay(2500)
        keyRevealActive = false
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.currentPhoto?.id) {
        zoom.reset()
    }

    var viewportPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(viewportPx) {
        if (viewportPx > 0) onViewportSizeChanged(viewportPx)
    }

    // Hold last non-null toast so AnimatedVisibility content renders during exit fade.
    var displayedToast by remember { mutableStateOf<CategoryToggle?>(null) }
    if (toast != null) displayedToast = toast

    // Same latch for the delete confirmation/failure message.
    var displayedDeleteMessage by remember { mutableStateOf<String?>(null) }
    if (deleteMessage != null) displayedDeleteMessage = deleteMessage

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val meta = event.isMetaPressed
                // Bare 1..9 toggles the current photo in the Nth custom category.
                val slot = if (meta) null else digitSlot(event.key)
                if (slot != null) {
                    state.categories.customCategories().getOrNull(slot)?.let { onToggleCategory(it.id) }
                    revealHud++
                    return@onPreviewKeyEvent true
                }
                // Cmd+Delete (Cmd+Backspace on a Mac keyboard) arms the move-to-Trash confirmation
                // for the photo on screen — the macOS "move to trash" chord. Disabled when embedded
                // in Inspect: the grid facet has no delete, so a delete there would desync the two.
                if (!embedded && meta && (event.key == Key.Backspace || event.key == Key.Delete)) {
                    if (state.currentPhoto != null) confirmingDelete = true
                    revealHud++
                    return@onPreviewKeyEvent true
                }
                val handled = when (event.key) {
                    Key.DirectionLeft -> { onPrevious(); true }
                    Key.DirectionRight -> { onNext(); true }
                    Key.F -> if (meta) false else { onToggleCategory(Category.FAVOURITES_ID); true }
                    // X flags the current photo as a reject — the cull's reject half, mirroring F.
                    Key.X -> if (meta) false else { onToggleCategory(Category.REJECTS_ID); true }
                    Key.R -> if (meta) false else {
                        state.currentPhoto?.absolutePath?.let { systemActions?.revealInFileManager(it) }
                        true
                    }
                    Key.O -> if (meta) false else {
                        state.currentPhoto?.absolutePath?.let { systemActions?.openWithDefaultApp(it) }
                        true
                    }
                    Key.G -> if (meta) false else { onBackToGrid(); true }
                    // Embedded in Inspect, `C` is inert (no nested Inspect), so fall through rather
                    // than silently swallow it — the legend hides the hint to match.
                    Key.C -> if (meta || embedded) false else { onCompare(); true }
                    // A: reveal this photo in the All Photos grid. Only when browsing a category (the
                    // handler is null in the All-Photos browser), so plain A is inert there.
                    Key.A -> if (meta || onShowInAllPhotos == null) false else { onShowInAllPhotos(); true }
                    Key.Escape -> { onBackToGrid(); true }
                    Key.Equals, Key.Plus -> { zoom.zoomIn(); true }
                    Key.Minus -> { zoom.zoomOut(); true }
                    Key.Zero -> { zoom.reset(); true }
                    else -> false
                }
                if (handled) revealHud++
                handled
            },
    ) {
        BrowserTopBar(
            countLabel = if (state.photos.isEmpty()) "0 / 0"
            else "${state.currentIndex + 1} / ${state.photos.size}",
            relativePath = state.currentPhoto?.relativePath.orEmpty(),
            readOnly = state.readOnly,
            onBack = onBackToGrid,
            onShowInAllPhotos = onShowInAllPhotos,
            embedded = embedded,
            onSwitchToGrid = onSwitchToGrid,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.photos.isEmpty()) {
            ErrorPlaceholder("No JPEG / PNG photos found in this folder.", Modifier.fillMaxSize())
            return@Box
        }

        HoverOverlay(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = AppTheme.dimens.topBarHeight)
                .onSizeChanged { size ->
                    val px = maxOf(size.width, size.height)
                    if (px > 0) viewportPx = px
                },
        ) { controlsVisible ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bmp = state.currentBitmap
                when {
                    state.isLoadingBitmap && bmp == null -> LoadingIndicator()
                    bmp == null -> ErrorPlaceholder("Cannot decode this photo. Press → to continue.")
                    else -> ZoomableImage(
                        bitmap = bmp,
                        contentDescription = state.currentPhoto?.fileName,
                        zoom = zoom,
                    )
                }

                val alpha by animateFloatAsState(if (controlsVisible) 1f else 0f)
                // The HUD also reveals on keystrokes, so it tracks its own visibility.
                val hudVisible = controlsVisible || keyRevealActive

                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = AppTheme.spacing.md)
                        .graphicsLayer { this.alpha = alpha },
                ) {
                    FilledTonalIconButton(onClick = onPrevious) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Previous")
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = AppTheme.spacing.md)
                        .graphicsLayer { this.alpha = alpha },
                ) {
                    FilledTonalIconButton(onClick = onNext) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Next")
                    }
                }

                AnimatedVisibility(
                    visible = hudVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = AppTheme.spacing.xl),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                    ) {
                        BrowserCategoryHud(
                            categories = state.categories,
                            currentMemberships = state.currentMemberships,
                            onToggle = onToggleCategory,
                        )
                        // Always-on discoverability, folded into the HUD's reveal/auto-hide so the
                        // photo stays unobstructed when idle. Truthful to the key handler above.
                        BrowserKeyboardLegend(
                            hasCustomCategories = state.categories.customCategories().isNotEmpty(),
                            readOnly = state.readOnly,
                            canShowInAllPhotos = onShowInAllPhotos != null,
                            canCompare = !embedded,
                            canReturnToGrid = embedded && onSwitchToGrid != null,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AppTheme.dimens.browserToastBottomInset),
        ) {
            displayedToast?.let { dt -> CategoryTogglePill(dt) }
        }

        AnimatedVisibility(
            visible = deleteMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AppTheme.dimens.browserToastBottomInset),
        ) {
            displayedDeleteMessage?.let { PillToast(text = it, colors = PillToastDefaults.removedColors()) }
        }

        if (confirmingDelete) {
            ConfirmDialog(
                title = "Move this photo to Trash?",
                message = "It will be moved to the macOS Trash. You can restore it from there.",
                confirmLabel = "Move to Trash",
                confirmDestructive = true,
                onConfirm = {
                    confirmingDelete = false
                    onDeleteCurrent()
                },
                onDismiss = { confirmingDelete = false },
            )
        }
    }
}
