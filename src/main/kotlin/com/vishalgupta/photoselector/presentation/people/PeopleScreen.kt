package com.vishalgupta.photoselector.presentation.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppOutlinedButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppTextButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.FaceCrop
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ConfirmDialog
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ErrorPlaceholder
import com.vishalgupta.photoselector.presentation.designsystem.molecule.GroupingProgressBanner
import com.vishalgupta.photoselector.presentation.designsystem.organism.TopBarScaffold
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme

/** Minimum width of a person card, so the grid reflows from a couple of columns to many. */
private val PERSON_CARD_MIN_WIDTH = 168.dp

/**
 * People: name the clusters the face scan found.
 *
 * The stateful half — collects [viewModel] and forwards intents. A person named here becomes a smart
 * category, so they appear in the library rail and open as their own grid; this screen exists purely
 * to turn anonymous clusters into that.
 *
 * Rendered full-screen with its own top bar (the library rail lives only beside the grid), so [onBack]
 * is the single way out — Esc, the back arrow, both land on the grid scope the user came from.
 *
 * PII: face crops and names are user data. Nothing here writes either anywhere but the sidecar.
 */
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    rootName: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onPurgeFaceCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    PeopleScreen(
        state = state,
        rootName = rootName,
        imageLoader = imageLoader,
        personCategoryCount = viewModel::personCategoryCount,
        onBack = onBack,
        onScan = viewModel::scan,
        onStopScan = viewModel::cancelScan,
        onName = viewModel::name,
        onNameEdited = viewModel::clearNameError,
        onDismiss = viewModel::dismiss,
        onRestore = viewModel::restore,
        onPurge = { viewModel.purgeAllFaceData(onPurgeFaceCache) },
        modifier = modifier,
    )
}

/**
 * The stateless half of [PeopleScreen] — everything it draws comes from [state]. Split so the
 * screenshot suite can render every branch (unnamed, mixed, empty, models-unavailable, scanning)
 * without a view model or a live scan.
 */
@Composable
fun PeopleScreen(
    state: PeopleUiState,
    rootName: String,
    imageLoader: ImageLoader,
    personCategoryCount: () -> Int,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onName: (PersonId, String) -> Unit,
    onNameEdited: () -> Unit,
    onDismiss: (PersonId) -> Unit,
    onRestore: (PersonId) -> Unit,
    onPurge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingPurge by remember { mutableStateOf(false) }

    // Esc leaves the screen, matching every other full-screen surface. Focus is seated once on
    // arrival so the key works before the user has clicked anything; clicking a name field moves
    // focus there, and the *preview* handler still sees Esc first, so it stays the way out.
    val escapeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { escapeFocus.requestFocus() }

    Column(
        modifier
            .fillMaxSize()
            .focusRequester(escapeFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        PeopleTopBar(
            rootName = rootName,
            toNameCount = state.toName.size,
            scanning = state.scanning != null,
            available = state.available,
            hasPeople = !state.isEmpty,
            onBack = onBack,
            onScan = onScan,
            onPurgeRequested = { confirmingPurge = true },
        )

        state.nameError?.let { NoticeRow(it) }
        if (state.unreadable) {
            // The sidecar exists but cannot be decoded, so every write is discarded. Saying so is the
            // whole point of the signal: otherwise a scan reports "found 12 people" over nothing.
            NoticeRow(
                "This folder's people file can't be read, so names can't be saved. It was written by " +
                    "a newer version, or it's damaged — move or repair it and reopen the folder.",
            )
        }
        state.scanning?.let { progress ->
            GroupingProgressBanner(
                processed = progress.processed,
                total = progress.total,
                label = "Finding faces",
                onStop = onStopScan,
            )
        }

        when {
            !state.available -> ModelsUnavailable(Modifier.fillMaxSize())
            state.isEmpty -> NoPeopleYet(
                scanning = state.scanning != null,
                onScan = onScan,
                modifier = Modifier.fillMaxSize(),
            )
            else -> PeopleGrid(
                state = state,
                imageLoader = imageLoader,
                onName = onName,
                onNameEdited = onNameEdited,
                onDismiss = onDismiss,
                onRestore = onRestore,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (confirmingPurge) {
        val count = personCategoryCount()
        ConfirmDialog(
            title = "Delete all face data?",
            message = buildString {
                append(
                    if (count == 1) "1 person category" else "$count person categories",
                )
                append(" and every name you've given in this folder will be deleted. ")
                append(
                    "The detected-faces cache is shared across folders and is cleared entirely, so " +
                        "other folders keep their names but will re-detect faces on their next scan. ",
                )
                append("Your photos are not touched.")
            },
            confirmLabel = "Delete face data",
            confirmDestructive = true,
            onConfirm = {
                confirmingPurge = false
                onPurge()
            },
            onDismiss = { confirmingPurge = false },
        )
    }
}

@Composable
private fun PeopleTopBar(
    rootName: String,
    toNameCount: Int,
    scanning: Boolean,
    available: Boolean,
    hasPeople: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPurgeRequested: () -> Unit,
) {
    TopBarScaffold {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text("People", style = AppTheme.typography.titleMedium)
        Text(
            text = rootName,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (toNameCount > 0) {
            Text(
                text = if (toNameCount == 1) "1 to name" else "$toNameCount to name",
                style = AppTheme.typography.labelMedium,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppOutlinedButton(
            text = when {
                scanning -> "Scanning…"
                hasPeople -> "Rescan"
                else -> "Scan for faces"
            },
            onClick = onScan,
            enabled = available && !scanning,
        )
        PeopleActionsMenu(onPurgeRequested = onPurgeRequested)
    }
}

/** The screen's "⋯" overflow. One item today; it is where anything else library-wide would land. */
@Composable
private fun PeopleActionsMenu(onPurgeRequested: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "People actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Delete all face data…") },
                onClick = {
                    expanded = false
                    onPurgeRequested()
                },
            )
        }
    }
}

/**
 * The hard-requirement unavailable state. A missing model resource or a stripped runtime makes the
 * scan return *nothing*, which is indistinguishable from "this folder has no faces" — so the packaged
 * build needs a state that says which it is. Never hide this row instead of rendering it.
 */
@Composable
private fun ModelsUnavailable(modifier: Modifier = Modifier) {
    ErrorPlaceholder(
        message = "Face scanning unavailable — the on-device face models couldn't be loaded on this " +
            "machine, so no faces can be found. Everything else keeps working.",
        icon = Icons.Outlined.PersonSearch,
        modifier = modifier,
    )
}

@Composable
private fun NoPeopleYet(scanning: Boolean, onScan: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            ErrorPlaceholder(
                message = if (scanning) {
                    "Looking for faces. Everything stays on your device."
                } else {
                    "No people yet. Scan this folder to find faces and give them names."
                },
                icon = Icons.Outlined.Person,
            )
            if (!scanning) AppButton(text = "Scan for faces", onClick = onScan)
        }
    }
}

@Composable
private fun PeopleGrid(
    state: PeopleUiState,
    imageLoader: ImageLoader,
    onName: (PersonId, String) -> Unit,
    onNameEdited: () -> Unit,
    onDismiss: (PersonId) -> Unit,
    onRestore: (PersonId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PERSON_CARD_MIN_WIDTH),
        modifier = modifier,
        contentPadding = PaddingValues(AppTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
    ) {
        section("To name", state.toName) { card ->
            PersonCardView(
                card = card,
                imageLoader = imageLoader,
                onName = { onName(card.id, it) },
                onNameEdited = onNameEdited,
                secondaryLabel = "Skip",
                onSecondary = { onDismiss(card.id) },
                tertiaryLabel = "Not a person",
                onTertiary = { onDismiss(card.id) },
            )
        }
        section("Named", state.named) { card ->
            PersonCardView(
                card = card,
                imageLoader = imageLoader,
                onName = { onName(card.id, it) },
                onNameEdited = onNameEdited,
            )
        }
        section("Skipped", state.skipped) { card ->
            PersonCardView(
                card = card,
                imageLoader = imageLoader,
                onName = { onName(card.id, it) },
                onNameEdited = onNameEdited,
                secondaryLabel = "Bring back",
                onSecondary = { onRestore(card.id) },
            )
        }
    }
}

/**
 * A full-width label followed by that section's cards, emitted only when the section has any. Keeps
 * the three sections one grid (so cards line up across them) rather than three stacked grids.
 */
private fun LazyGridScope.section(
    title: String,
    cards: List<PersonCard>,
    card: @Composable (PersonCard) -> Unit,
) {
    if (cards.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }, key = "header-$title") {
        Text(
            text = title.uppercase(),
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppTheme.spacing.sm, bottom = AppTheme.spacing.xs),
        )
    }
    items(cards.size, key = { cards[it].id.value }) { index -> card(cards[index]) }
}

/**
 * One cluster: its cover crop, how many photos it spans, an inline name field, and up to two verdict
 * actions. Editing state is local to the card and re-seeded when the person changes ([remember] keyed
 * on the id), so a rescan reshuffling the list can't strand a half-typed name on someone else's card.
 */
@Composable
private fun PersonCardView(
    card: PersonCard,
    imageLoader: ImageLoader,
    onName: (String) -> Unit,
    onNameEdited: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    var draft by remember(card.id) { mutableStateOf(card.name.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        if (card.coverPhoto != null) {
            FaceCrop(
                photo = card.coverPhoto,
                loader = imageLoader,
                box = card.coverBox,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        } else {
            FaceCrop(
                image = null,
                box = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        Text(
            text = if (card.photoCount == 1) "1 photo" else "${card.photoCount} photos",
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
        val dirty = draft.isNotBlank() && draft != card.name
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onNameEdited()
            },
            singleLine = true,
            placeholder = { Text("Add a name") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onName(draft) }),
            // Commit rides *in* the field rather than as a third text button beneath it: at card
            // width three buttons wrap mid-word, and "save what I just typed" belongs next to the
            // text anyway. Enter does the same thing.
            trailingIcon = if (dirty) {
                {
                    IconButton(onClick = { onName(draft) }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = if (card.isNamed) "Rename" else "Save name",
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (secondaryLabel != null || tertiaryLabel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                if (secondaryLabel != null && onSecondary != null) {
                    AppTextButton(text = secondaryLabel, onClick = onSecondary)
                }
                if (tertiaryLabel != null && onTertiary != null) {
                    AppTextButton(text = tertiaryLabel, onClick = onTertiary)
                }
            }
        }
    }
}

/** A muted full-width line for a non-blocking warning (unreadable sidecar, duplicate name). */
@Composable
private fun NoticeRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.PersonSearch,
            contentDescription = null,
            tint = AppTheme.colorScheme.error,
            modifier = Modifier.size(AppTheme.dimens.iconSm),
        )
        Text(text, style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.error)
    }
}
