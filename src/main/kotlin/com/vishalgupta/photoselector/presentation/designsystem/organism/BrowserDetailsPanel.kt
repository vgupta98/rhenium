package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightValue
import com.vishalgupta.photoselector.domain.insight.LabeledInsight
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The browser's right-anchored details panel: the file/EXIF facts for the photo on screen plus a
 * clearly-marked slot for on-device AI insights that a future feature will populate. Toggled by the
 * browser's `I` key (a latch that persists across photo navigation for the session), it re-centers the
 * image to make room while open.
 *
 * Feature-agnostic and dumb: the screen hands it already-resolved values ([captureTimeEpochMs] /
 * [cameraId] come from the memoized `CaptureMetadataSource`, null when nothing readable), so the panel
 * only formats and lays them out. The capture time is EXIF-derived and stored interpreted as UTC (only
 * frame-to-frame deltas matter elsewhere), so it is formatted back in UTC to recover the shot's own
 * wall-clock string.
 */
@Composable
fun BrowserDetailsPanel(
    fileName: String,
    relativePath: String,
    sizeBytes: Long,
    captureTimeEpochMs: Long?,
    cameraId: String?,
    modifier: Modifier = Modifier,
    // On-device AI insights for this photo, computed lazily while the panel is open. Rendered generically
    // over the insight taxonomy (label + typed value), not hardcoded to any one signal. Empty while the
    // lazy compute is in flight or when nothing is assessable.
    insights: List<LabeledInsight> = emptyList(),
) {
    Surface(
        modifier = modifier,
        color = AppTheme.colorScheme.surface,
        contentColor = AppTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AppTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Text(text = "Details", style = AppTheme.typography.titleMedium)

            Text(
                text = fileName,
                style = AppTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            DetailRow("Path", relativePath)
            DetailRow("Size", formatSize(sizeBytes))
            DetailRow("Captured", captureTimeEpochMs?.let(::formatCaptureTime) ?: NOT_AVAILABLE)
            DetailRow("Camera", cameraId?.takeIf { it.isNotBlank() } ?: NOT_AVAILABLE)

            HorizontalDivider(color = AppTheme.colorScheme.outlineVariant)

            Text(text = "AI insights", style = AppTheme.typography.titleMedium)
            if (insights.isEmpty()) {
                Text(
                    text = "No insights for this photo",
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                insights.forEach { insight -> InsightRow(insight) }
            }
        }
    }
}

/** One AI-insight row: the provider's label plus its value rendered by taxonomy type. */
@Composable
private fun InsightRow(insight: LabeledInsight) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)) {
        Text(
            text = insight.label,
            style = AppTheme.typography.labelMedium,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
        when (val value = insight.value) {
            is InsightValue.Scalar -> ScalarInsight(value)
            // Scaffold taxonomy cases carry no render yet (see InsightValue); ignore until a later phase.
            is InsightValue.Boolean, is InsightValue.Label, is InsightValue.LabelSet -> Unit
        }
    }
}

/** A scalar insight: its human band chip plus a labelled bar of the raw score within its range. */
@Composable
private fun ScalarInsight(scalar: InsightValue.Scalar) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scalar.band?.let { band ->
            val positive = band == InsightBand.Sharp
            Surface(
                shape = AppTheme.shapes.small,
                color = if (positive) AppTheme.colors.favourite else AppTheme.colorScheme.surfaceVariant,
                contentColor = if (positive) AppTheme.colorScheme.onPrimary else AppTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = band.id.replaceFirstChar { it.uppercase() },
                    style = AppTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = AppTheme.spacing.sm,
                        vertical = AppTheme.spacing.xxs,
                    ),
                )
            }
        }
        scalar.range?.let { range ->
            val span = (range.max - range.min).takeIf { it > 0f } ?: 1f
            val fraction = ((scalar.raw - range.min) / span).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.weight(1f),
                color = AppTheme.colors.favourite,
                trackColor = AppTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** A "label: value" row — the label muted, the value on the surface's own content colour. */
@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)) {
        Text(
            text = label,
            style = AppTheme.typography.labelMedium,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = AppTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
        )
    }
}

private const val NOT_AVAILABLE = "Not available"

private val CAPTURE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun formatCaptureTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).format(CAPTURE_TIME_FORMAT)

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
