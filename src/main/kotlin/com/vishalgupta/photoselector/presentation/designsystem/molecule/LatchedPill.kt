package com.vishalgupta.photoselector.presentation.designsystem.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A transient pill that fades [content] in while [value] is non-null and out when it clears, latching
 * the last non-null value so it stays rendered through the exit animation (clearing the state would
 * otherwise yank the content before the fade finishes).
 *
 * This "latch the content through the fade-out" pattern was inlined verbatim in two grid pills (the
 * message pill and the category-toggle pill) and the browser shows the same toggle, so it lives here
 * next to [PillToast] as a reusable primitive rather than per screen. Call sites supply only the body —
 * typically a [PillToast] — keeping the latch/fade in one place.
 *
 * Note this is for *transient* content: the last non-null [value] is retained for the lifetime of the
 * composable (so it can render through the exit fade), so the body never goes blank once shown — it
 * only fades out. Don't reuse this for content that should disappear entirely when cleared.
 */
@Composable
fun <T : Any> LatchedPill(
    value: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    var displayed by remember { mutableStateOf<T?>(null) }
    if (value != null) displayed = value
    AnimatedVisibility(
        visible = value != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        displayed?.let { content(it) }
    }
}
