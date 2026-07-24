package com.vishalgupta.photoselector.presentation.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.CategoryKind
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.providerIds
import com.vishalgupta.photoselector.presentation.designsystem.atom.TileSignal
import com.vishalgupta.photoselector.presentation.designsystem.atom.TileSignalTint
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * The insight-derived tile signals for [photo]: one chip per **insight-backed smart category** the photo
 * belongs to (an auto/rule glyph + the category name). Generic over the taxonomy — it reads resolved
 * membership, not any one signal — so a future insight smart category surfaces on tiles for free.
 *
 * The extension-based "RAW files" smart category is deliberately excluded (its rule references no insight
 * provider), so this is purely AI-insight chrome. Returns a stable empty list when the photo carries none,
 * keeping the host tile ([com.vishalgupta.photoselector.presentation.designsystem.organism.PhotoThumbnail]
 * / `SurveyTileView`) strong-skippable.
 */
fun insightTileSignals(
    photo: PhotoId,
    categories: List<Category>,
    memberships: Map<CategoryId, Set<PhotoId>>,
): ImmutableList<TileSignal> = insightTileSignals(
    memberOf = categories.filterTo(HashSet()) { photo in memberships[it.id].orEmpty() }.mapTo(HashSet()) { it.id },
    categories = categories,
)

/**
 * The set-membership overload, for callers (the survey tile) that already know which category ids a photo
 * belongs to. Emits a chip per insight-backed smart category in [memberOf].
 */
fun insightTileSignals(
    memberOf: Set<CategoryId>,
    categories: List<Category>,
): ImmutableList<TileSignal> {
    val signals = categories.filter { category ->
        category.kind == CategoryKind.SMART &&
            category.rule?.providerIds()?.isNotEmpty() == true &&
            category.id in memberOf
    }
    if (signals.isEmpty()) return persistentListOf()
    return signals.map { category ->
        TileSignal(
            icon = Icons.Outlined.AutoAwesome,
            label = category.name,
            contentDescription = category.name,
            tint = TileSignalTint.Positive,
        )
    }.toImmutableList()
}
