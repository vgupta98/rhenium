package com.vishalgupta.photoselector.presentation.designsystem.organism

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.presentation.designsystem.atom.FavouriteStar
import com.vishalgupta.photoselector.presentation.designsystem.atom.LoadingIndicator
import com.vishalgupta.photoselector.presentation.designsystem.atom.RejectFlag
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val THUMBNAIL_VIEWPORT_PX = 320

/**
 * A square photo tile: decoded image (cropped to fill), an optional star marking
 * Favourites membership (in any scope, including a custom-category grid), a focus
 * border, and a "last viewed" underline. Decodes lazily through [loader], keyed on the
 * photo id.
 *
 * [isRejected] marks the cull's reject half: the tile dims under a scrim and shows a reject flag
 * (top-end, taking the favourite star's corner — a tile reads as kept XOR rejected).
 *
 * [categoryBadges] are the digit slots (1..9) of the custom categories this photo belongs
 * to — shown as small numbered chips so filing a photo with a number key leaves a visible,
 * inspectable mark (favourites stays the star). The list type is immutable so the tile stays
 * skippable: a membership change elsewhere doesn't churn this tile.
 *
 * [isSelected] marks the tile as part of a multi-select (accent ring + scale-down + a check
 * badge). When [onToggleSelect] (Cmd+Click) / [onRangeSelect] (Shift+Click) are supplied, a
 * modified click drives selection while a plain click still falls through to [onClick] — so the
 * established "click a tile to open it" gesture is untouched.
 *
 * [burstCount], when non-null, marks this tile as the collapsed representative of a group of that
 * many frames (a burst or a similarity cluster). The cover photo is drawn inset over a small fanned
 * "deck" of cards so the stack reads as a stack at a glance, with a count pill naming how many; the
 * tile still shows [photo] (the group's key frame), and the caller wires [onClick] to open the run
 * side by side rather than a single browser.
 *
 * Two further cues ride alongside [burstCount] and are both presentation-only (stable types, so the
 * tile stays skippable):
 *  - [groupGlyph] is the count pill's glyph — the caller passes the lens's icon (stacked frames for a
 *    time burst, a sparkle for a similarity cluster) so a grouped tile silently says *why* it grouped.
 *    Defaults to the stacked-frames glyph when null.
 *  - [onReview], when non-null, reveals a "Review N →" chip on hover that opens the run's frames in
 *    Compare/Survey straight away — the "decide now" path next to expand-in-place. Hover-only, so the
 *    keyboard path (a focused-group `C`) is the non-hover fallback the grid wires separately.
 */
@Composable
fun PhotoThumbnail(
    photo: Photo,
    loader: ImageLoader,
    isMarked: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRejected: Boolean = false,
    isLastViewed: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onRangeSelect: (() -> Unit)? = null,
    categoryBadges: ImmutableList<Int> = persistentListOf(),
    burstCount: Int? = null,
    groupGlyph: ImageVector? = null,
    onReview: (() -> Unit)? = null,
    withinBurst: Boolean = false,
) {
    val bitmap by produceState<ImageBitmap?>(null, photo.id) {
        value = loader.load(photo, viewportLongEdgePx = THUMBNAIL_VIEWPORT_PX)
    }
    // Selection's accent ring takes precedence over the focus cursor on the rare tile that is both;
    // an expanded-burst frame carries a dim bracket ring only when neither of those is present.
    val ringColor = when {
        isSelected -> AppTheme.colors.selectionRing
        isFocused -> AppTheme.colors.focusRing
        withinBurst -> AppTheme.colors.burstFrameRing
        else -> null
    }
    val borderMod = if (ringColor != null) {
        Modifier.border(AppTheme.dimens.focusBorderWidth, ringColor, MaterialTheme.shapes.small)
    } else {
        Modifier
    }
    // Selected tiles ease down a touch so the picked-out set reads at a glance.
    val tileScale by animateFloatAsState(if (isSelected) 0.95f else 1f, label = "tileSelectScale")
    // One plain clickable carries every click; the held modifier is read from window state at
    // click time (NOT during composition, so it never costs the tile a recomposition) and routes
    // it — Cmd toggles selection, Shift ranges, unmodified opens. This keeps the single proven
    // open path and its click semantics, instead of layering a modifier-matching pointer node
    // that races the clickable and lets one swallow the other.
    val windowInfo = LocalWindowInfo.current

    // Hover drives the "Review N →" CTA on a collapsed group. The hoverable is attached only when
    // [onReview] is wired (groups), and the [hovered] state is read only behind that same null-check
    // below — so although every tile collects the source, a single never attaches the hoverable and
    // never reads [hovered], so it never recomposes on hover and stays the pre-CTA tile.
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    // A collapsed group (burst or similarity cluster) reads as a small fanned deck: a couple of
    // neutral cards peek out top-right behind an inset cover photo, so a stack of frames is legible
    // as a stack without leaning on the count pill alone. The deck is a *background draw* on the
    // outer full-cell node and the cover sits in an inset child Box. Interaction, the focus/selection
    // ring and the deck all live on that outer node, so the whole stack (peeking cards included) is
    // one click target and the ring brackets the stack — clicking the peek that signals "group"
    // actually expands it. A single photo takes neither branch: no deck, no inset child, so it stays
    // the pre-deck single-layout-node tile byte for byte. Keep the inset child a plain fillMaxSize
    // Box — an earlier matchParentSize/nested-deck version looped the measure/draw phase under grid
    // scroll (invisible to the static screenshot tests, caught by GridKeyboardTest).
    val isGroup = burstCount != null
    val stackInset = AppTheme.dimens.burstStackInset
    val deckCard = AppTheme.colors.burstStackCard
    val deckEdge = AppTheme.colors.tileBackground

    // The cover — decoded photo (cropped to fill) plus its corner badges. Emitted either directly in
    // the full-cell tile (single) or in the inset child over the deck (group), so both paths render
    // the identical content; it just aligns to the cover bounds in each case.
    val cover: @Composable BoxScope.() -> Unit = {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = photo.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LoadingIndicator()
        }
        // A rejected tile is dimmed (it visibly recedes in the contact sheet) and flagged top-end.
        // Reject is the cull's negative half, so it takes the star's corner and suppresses it — a
        // photo realistically reads as kept XOR rejected, never both at once.
        if (isRejected) {
            Box(Modifier.fillMaxSize().background(AppTheme.colors.rejectScrim))
            RejectFlag(
                filled = true,
                tint = AppTheme.colors.reject,
                contentDescription = "Rejected",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppTheme.spacing.xs)
                    .size(AppTheme.dimens.iconSm),
            )
        } else if (isMarked) {
            FavouriteStar(
                filled = true,
                tint = AppTheme.colors.favourite,
                contentDescription = "Favourited",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppTheme.spacing.xs)
                    .size(AppTheme.dimens.iconSm),
            )
        }
        if (categoryBadges.isNotEmpty()) {
            CategoryBadges(
                badges = categoryBadges,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(AppTheme.spacing.xs),
            )
        }
        if (burstCount != null) {
            // Bottom-start keeps the count pill clear of the star (top-end), category badges
            // (top-start) and the select check (bottom-end).
            BurstBadge(
                count = burstCount,
                glyph = groupGlyph ?: Icons.Filled.BurstMode,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(AppTheme.spacing.xs),
            )
        }
        // Hover-revealed "decide now" CTA — top-start (clear of the star/count/check), neutral
        // overlay-chrome chip (the amber accent is reserved for favourite/selection). Keyboard users
        // reach the same action via a focused-group `C`, wired by the grid.
        if (onReview != null && hovered) {
            ReviewChip(
                count = burstCount ?: 0,
                onClick = onReview,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(AppTheme.spacing.xs),
            )
        }
        if (isLastViewed) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(AppTheme.dimens.lastViewedIndicatorHeight)
                    .background(AppTheme.colors.lastViewedIndicator),
            )
        }
        if (isSelected) {
            // Bottom-end keeps the check clear of the star (top-end) and category badges (top-start).
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppTheme.spacing.xs)
                    .size(AppTheme.dimens.iconSm)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                )
            }
        }
    }

    Box(
        modifier
            .aspectRatio(1f)
            .scale(tileScale)
            .then(
                if (isGroup) {
                    Modifier.drawBehind { drawBurstDeck(stackInset.toPx(), deckCard, deckEdge) }
                } else {
                    Modifier
                },
            )
            .then(borderMod)
            // The deck IS the backdrop in the peeking strips, so a group draws no outer background;
            // the cover's own background (on the inset child) fills the rest. A single keeps the
            // full-cell backdrop here, exactly as before.
            .then(if (isGroup) Modifier else Modifier.background(AppTheme.colors.tileBackground))
            .clickable {
                val mods = windowInfo.keyboardModifiers
                when {
                    onToggleSelect != null && mods.isMetaPressed && !mods.isShiftPressed -> onToggleSelect()
                    onRangeSelect != null && mods.isShiftPressed -> onRangeSelect()
                    else -> onClick()
                }
            }
            // Track hover only for a reviewable group, so a single never subscribes (see [hovered]).
            .then(if (onReview != null) Modifier.hoverable(hoverSource) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = stackInset, end = stackInset)
                    .background(AppTheme.colors.tileBackground),
                contentAlignment = Alignment.Center,
                content = cover,
            )
        } else {
            cover()
        }
    }
}

/** Small corner radius for the deck cards, matching the tile's rounded-small look. */
private val DECK_CORNER = 4.dp

/**
 * Paints the two neutral cards behind a collapsed group's (inset) cover photo, stepped up toward the
 * top-right so the tile reads as a stack of frames. Drawn at the full cell bounds (this runs before
 * the cover's `padding`), so only the cards' top/right edges peek past the inset cover. The back card
 * peeks the furthest ([insetPx]); the middle card half that. An [edge] hairline separates each card.
 */
private fun DrawScope.drawBurstDeck(insetPx: Float, card: Color, edge: Color) {
    val cardSize = Size(size.width - insetPx, size.height - insetPx)
    val corner = CornerRadius(DECK_CORNER.toPx())
    val hairline = Stroke(width = 1.dp.toPx())
    // Back-most card: flush to the top-end corner.
    drawDeckCard(Offset(insetPx, 0f), cardSize, corner, card, edge, hairline)
    // Middle card: half a step back toward the cover.
    val half = insetPx / 2f
    drawDeckCard(Offset(half, half), cardSize, corner, card, edge, hairline)
}

private fun DrawScope.drawDeckCard(
    topLeft: Offset,
    cardSize: Size,
    corner: CornerRadius,
    fill: Color,
    edge: Color,
    hairline: Stroke,
) {
    drawRoundRect(color = fill, topLeft = topLeft, size = cardSize, cornerRadius = corner)
    drawRoundRect(color = edge, topLeft = topLeft, size = cardSize, cornerRadius = corner, style = hairline)
}

/** Max chips drawn before collapsing the tail into a "+N" overflow chip. */
private const val MAX_VISIBLE_BADGES = 4

/**
 * The custom-category slots a photo is filed in, as small numbered chips. The number is the
 * `1..9` key that toggles that category, so the badge doubles as a reminder of the hotkey.
 * Capped so a heavily-filed photo can't blow out the tile.
 */
@Composable
private fun CategoryBadges(badges: ImmutableList<Int>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        badges.take(MAX_VISIBLE_BADGES).forEach { slot -> CategoryBadge(slot.toString()) }
        val overflow = badges.size - MAX_VISIBLE_BADGES
        if (overflow > 0) CategoryBadge("+$overflow")
    }
}

/**
 * The collapsed-group count pill: the lens's [glyph] plus the frame count, telling the user this one
 * tile stands in for N frames that open together. Drawn in the translucent-dark overlay-chrome chip
 * style (shared with the browser HUD) with bright text, so it stays legible over both bright and dark
 * photos — louder than a category chip because it changes what a click does (it opens a run, not a
 * single). The [glyph] reflects the active lens (stacked frames for a time burst, a sparkle for a
 * similarity cluster), so the tile silently echoes the toolbar's chosen lens.
 */
@Composable
private fun BurstBadge(count: Int, glyph: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = AppTheme.colors.overlayChromeBackground,
        contentColor = AppTheme.colors.onOverlayChrome,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AppTheme.dimens.badgeInset, vertical = AppTheme.spacing.xxs),
        ) {
            Icon(
                imageVector = glyph,
                contentDescription = "Group of $count",
                modifier = Modifier.size(AppTheme.dimens.iconSm),
            )
            Text(text = count.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Hover-revealed "Review N →" chip: opens the group's frames straight into Compare/Survey — the
 * "decide now" path. Its own [clickable] sits inside the tile's click target and consumes the click,
 * so reviewing never doubles as expand-in-place. Neutral overlay-chrome, like the count pill (this is
 * navigation chrome, not the accent-coloured keeper action).
 */
@Composable
private fun ReviewChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = AppTheme.colors.overlayChromeBackground,
        contentColor = AppTheme.colors.onOverlayChrome,
    ) {
        Text(
            text = "Review $count →",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = AppTheme.dimens.badgeInset,
                vertical = AppTheme.dimens.badgeVerticalInset,
            ),
        )
    }
}

@Composable
private fun CategoryBadge(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = AppTheme.colors.categoryMemberContainer,
        contentColor = AppTheme.colors.categoryMemberContent,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = AppTheme.dimens.badgeInset,
                vertical = AppTheme.dimens.badgeVerticalInset,
            ),
        )
    }
}
