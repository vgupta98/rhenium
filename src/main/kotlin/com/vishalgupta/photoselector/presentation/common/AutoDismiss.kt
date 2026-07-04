package com.vishalgupta.photoselector.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Collects a one-shot [flow] into transient state: each emission is surfaced for [timeoutMs] and then
 * cleared, and a fresh emission replaces the previous one immediately ([collectLatest] semantics — the
 * pending clear is cancelled and restarted, so a rapid second event never queues behind the first
 * timer). Returns the current value (or null when idle) for a transient pill / toast to render.
 *
 * This hand-rolled `LaunchedEffect { flow.collectLatest { x = it; delay(N); x = null } }` pattern was
 * inlined verbatim across the grid (three pills) and the browser (two), each with its own literal
 * timeout — this is the single seam so the timers and collectLatest behaviour live in one place.
 *
 * [resetKey] clears the value immediately whenever it changes (e.g. the browser drops a stale toast the
 * moment the shown photo changes, rather than letting it linger out the timer). Defaulted so callers
 * that only need the timer pass nothing.
 */
@Composable
fun <T> rememberAutoDismiss(
    flow: Flow<T>,
    timeoutMs: Long,
    resetKey: Any? = Unit,
): State<T?> {
    val state = remember { mutableStateOf<T?>(null) }
    var value by state
    LaunchedEffect(flow, timeoutMs) {
        flow.collectLatest {
            value = it
            delay(timeoutMs)
            value = null
        }
    }
    LaunchedEffect(resetKey) {
        value = null
    }
    return state
}
