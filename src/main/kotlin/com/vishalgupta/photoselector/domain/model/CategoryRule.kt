package com.vishalgupta.photoselector.domain.model

/**
 * A pure, serialisable descriptor of how a [smart][CategoryKind.SMART] category resolves its
 * membership from the photos in a root. It carries **no** resolved ids and holds **no** manual
 * overrides — a rule is a stable predicate description, resolved on demand by a
 * [CategoryRuleResolver]. Manual pins/excludes layer on top in the repository, never here.
 *
 * Sealed so a future on-device signal (e.g. an AI "people" or "blurry" rule) is one more case
 * plus its resolver, with no change to the categories model, persistence, or the grid.
 */
sealed interface CategoryRule {
    /** Matches camera RAW files, classified by file extension (the non-AI demo rule). */
    data object RawFiles : CategoryRule
}

/**
 * The seam that turns a [CategoryRule] into a concrete membership over a batch of photos. Batch +
 * `suspend` so a future rule can do real work (decode/inference) off-thread; **override-unaware and
 * feature-agnostic** — it returns only the rule's natural matches, and the repository is the single
 * place that folds manual pins/excludes on top. Kept in the domain, over pure [Photo]s, so no
 * resolver reaches into the data layer.
 */
interface CategoryRuleResolver {
    /** The set of photos [rule] naturally matches within [photos]. Pins/excludes are not its concern. */
    suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId>
}

/**
 * Resolves [CategoryRule.RawFiles] by classifying each photo's [Photo.fileName] extension against a
 * supplied set of RAW extensions. The set is *injected* (sourced from the format layer's RAW
 * decoder in DI) rather than hard-coded here, so the app has one source of truth for "what is RAW".
 * Extensions are compared case-insensitively.
 */
class RawFilesResolver(rawExtensions: Set<String>) : CategoryRuleResolver {
    private val rawExtensions: Set<String> = rawExtensions.mapTo(HashSet()) { it.lowercase() }

    override suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId> = when (rule) {
        CategoryRule.RawFiles -> photos.filterTo(LinkedHashSet()) { it.isRaw() }.mapTo(LinkedHashSet()) { it.id }
    }

    private fun Photo.isRaw(): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in rawExtensions
}
