package com.vishalgupta.photoselector.presentation.rootpicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.vishalgupta.photoselector.presentation.common.NativeFileDialogs
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppOutlinedButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.LoadingIndicator
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.coroutines.launch

@Composable
fun RootFolderPickerScreen(viewModel: RootFolderPickerViewModel) {
    DisposableEffect(viewModel) { onDispose { viewModel.onClear() } }
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    RootFolderPickerScreen(
        state = state,
        onPickFolder = {
            coroutineScope.launch {
                val picked = NativeFileDialogs.pickDirectory("Choose photo folder")
                if (picked != null) viewModel.startScan(picked)
            }
        },
        onCancelScan = viewModel::cancelScan,
    )
}

@Composable
fun RootFolderPickerScreen(
    state: RootPickerUiState,
    onPickFolder: () -> Unit,
    onCancelScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().padding(PaddingValues(AppTheme.spacing.xxl)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
        ) {
            // A fixed brand anchor across every phase — the body below it swaps, the header
            // doesn't, so the screen never jolts as the scan starts, finishes or fails.
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = AppTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTheme.dimens.iconLg),
            )
            Text("Rhenium", style = AppTheme.typography.headlineLarge)
            Text(
                "Thousands of shots, down to your best.",
                style = AppTheme.typography.titleMedium,
                color = AppTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            when (state.phase) {
                RootPickerUiState.Phase.Idle, RootPickerUiState.Phase.Done -> {
                    SupportingText("Choose a folder to begin. Everything runs on your device - nothing is uploaded.")
                    AppButton(
                        text = "Choose folder…",
                        leadingIcon = Icons.Default.Folder,
                        onClick = onPickFolder,
                    )
                }
                RootPickerUiState.Phase.Scanning -> {
                    LoadingIndicator(Modifier.size(AppTheme.dimens.progressIndicatorLg))
                    Text("Scanning…", style = AppTheme.typography.titleMedium)
                    SupportingText("${state.found} photos · ${state.scanned} files seen")
                    AppOutlinedButton(text = "Cancel", onClick = onCancelScan)
                }
                RootPickerUiState.Phase.Failed -> {
                    Text(
                        "Couldn't scan that folder",
                        style = AppTheme.typography.titleMedium,
                    )
                    Text(
                        state.errorMessage ?: "Unknown error",
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    AppButton(text = "Try again", onClick = onPickFolder)
                }
            }
        }
    }
}

/** A centered, width-capped muted line — the screen's supporting copy and counts. */
@Composable
private fun SupportingText(text: String) {
    Text(
        text,
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = AppTheme.dimens.supportingTextMaxWidth),
    )
}
