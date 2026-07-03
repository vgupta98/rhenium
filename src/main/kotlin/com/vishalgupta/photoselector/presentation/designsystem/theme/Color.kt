package com.vishalgupta.photoselector.presentation.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The single warm accent. One colour carries every "this is the deliberate,
 * positive thing" meaning in the app: the primary action and the favourite. All
 * other state (category membership, keyboard focus, last-viewed) is expressed
 * with hueless neutral treatments so colour never competes with the photos.
 */
private val Accent = Color(0xFFE9A93C)
private val OnAccent = Color(0xFF1A1A1A)

/**
 * App-specific semantic colors that don't map cleanly onto Material3's
 * [androidx.compose.material3.ColorScheme]. Anything that *does* map
 * (primary, surface, error, onSurfaceVariant, …) belongs in [DarkColorScheme]
 * and is read via `AppTheme.colorScheme` (the single token accessor, which
 * delegates to Material) instead of duplicated here.
 *
 * Read these through `AppTheme.colors`.
 */
@Immutable
data class AppColors(
    /** Warm accent for the favourite star and the favourited toast (== the primary action colour). */
    val favourite: Color,
    /**
     * Muted red for the reject flag (the cull's "reject" half) — the one cool, negative accent,
     * deliberately distinct from the warm [favourite] so keep and reject read apart at a glance.
     */
    val reject: Color,
    /** Translucent dark scrim dimming a rejected tile so culls visibly recede in the contact sheet. */
    val rejectScrim: Color,
    /** Neutral light fill marking a custom-category membership (hueless; distinct from the amber favourite). */
    val categoryMemberContainer: Color,
    /** Foreground on [categoryMemberContainer]. */
    val categoryMemberContent: Color,
    /** Bright neutral ring marking the keyboard-focused tile (the cursor). */
    val focusRing: Color,
    /**
     * Warm accent ring marking a multi-selected tile. Selection is a deliberate, in-progress
     * action (it arms a bulk operation), so it earns the accent — distinct from the hueless
     * [focusRing] cursor it can coexist with.
     */
    val selectionRing: Color,
    /** Dim neutral marker for the last-viewed tile (weaker than [focusRing], by both brightness and shape). */
    val lastViewedIndicator: Color,
    /**
     * Dim ring bracketing the frames of an expanded burst so the unfolded run reads as one unit.
     * Dimmer than [focusRing] and yields to it and the [selectionRing] when a frame is focused or
     * selected.
     */
    val burstFrameRing: Color,
    /**
     * Fill of the offset "deck" cards drawn behind a collapsed group tile (burst or similarity), so
     * a stack of frames reads as a stack at a glance rather than relying on the count badge alone.
     * A mid neutral — light enough to separate from the near-black window behind the grid, hueless
     * so it never competes with the photos. Card edges are separated by a [tileBackground] hairline.
     */
    val burstStackCard: Color,
    /** Backdrop behind a photo thumbnail while it decodes. */
    val tileBackground: Color,
    /** Neutral pill-toast background. */
    val toastBackground: Color,
    /** Neutral pill-toast foreground. */
    val toastContent: Color,
    /** Favourited pill-toast background. */
    val favouriteToastBackground: Color,
    /** Favourited pill-toast foreground. */
    val favouriteToastContent: Color,
    /** Added-to-a-category pill-toast background (positive cue, shared across categories). */
    val toastAddedBackground: Color,
    /** Added-to-a-category pill-toast foreground. */
    val toastAddedContent: Color,
    /** Removed-from-a-category pill-toast background (muted cue, shared across categories). */
    val toastRemovedBackground: Color,
    /** Removed-from-a-category pill-toast foreground. */
    val toastRemovedContent: Color,
    /** Translucent scrim over the photo behind the browser top bar. */
    val topBarScrim: Color,
    /** Translucent dark pill behind the browser's overlaid chrome (category HUD, keyboard legend). */
    val overlayChromeBackground: Color,
    /** Neutral fill for an inactive control on [overlayChromeBackground] (HUD chip, key cap). */
    val overlayChromeInactiveFill: Color,
    /** Foreground on [overlayChromeBackground] — bright for legibility over the photo. */
    val onOverlayChrome: Color,
    /** Vertical scrollbar, idle. */
    val scrollbarIdle: Color,
    /** Vertical scrollbar, hovered. */
    val scrollbarHover: Color,
)

/** The single dark palette. Values preserved verbatim from the pre-token UI. */
val DarkAppColors = AppColors(
    favourite = Accent,
    reject = Color(0xFFE0594B),
    rejectScrim = Color.Black.copy(alpha = 0.5f),
    categoryMemberContainer = Color(0xFFE6E6E6),
    categoryMemberContent = Color(0xFF1A1A1A),
    focusRing = Color(0xFFF5F5F5),
    selectionRing = Accent,
    lastViewedIndicator = Color.White.copy(alpha = 0.5f),
    burstFrameRing = Color.White.copy(alpha = 0.28f),
    burstStackCard = Color(0xFF8C8C8C),
    tileBackground = Color(0xFF1E1E1E),
    toastBackground = Color(0xFF2A2A2A),
    toastContent = Color(0xFFE6E6E6),
    favouriteToastBackground = Accent,
    favouriteToastContent = OnAccent,
    toastAddedBackground = Color(0xFF2E7D46),
    toastAddedContent = Color(0xFFEAF6EC),
    toastRemovedBackground = Color(0xFF3A3030),
    toastRemovedContent = Color(0xFFEDDADA),
    topBarScrim = Color.Black.copy(alpha = 0.55f),
    overlayChromeBackground = Color.Black.copy(alpha = 0.6f),
    overlayChromeInactiveFill = Color.White.copy(alpha = 0.16f),
    onOverlayChrome = Color.White,
    scrollbarIdle = Color.White.copy(alpha = 0.3f),
    scrollbarHover = Color.White.copy(alpha = 0.6f),
)

/**
 * Material3 color scheme on the baseline dark defaults, with [primary] retargeted to the
 * single warm [Accent] so every primary action (filled/outlined/text buttons) reads as one
 * action colour instead of Material's stock lilac.
 */
val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }
