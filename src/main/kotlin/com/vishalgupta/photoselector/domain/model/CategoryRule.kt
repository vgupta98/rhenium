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

    /**
     * Matches every photo a given person appears in, per the face pipeline's clustering.
     *
     * The id is carried as a plain [String] rather than a `PersonId` so the rule model stays
     * independent of `domain/faces` (it is also exactly what persists). The lookup from id to photos
     * lives in the resolver, not here — a rule remains a *description*, never a resolved snapshot.
     */
    data class Person(val personId: String) : CategoryRule
}

/**
 * The seam that turns a [CategoryRule] into a concrete membership over a batch of photos. Batch +
 * `suspend` so a future rule can do real work (decode/inference) off-thread; **override-unaware and
 * feature-agnostic** — it returns only the rule's natural matches, and the repository is the single
 * place that folds manual pins/excludes on top. Kept in the domain, over pure [Photo]s, so no
 * resolver reaches into the data layer.
 */
interface CategoryRuleResolver {
    /**
     * The set of photos [rule] naturally matches within [photos], or **null** when this resolver does
     * not handle that rule type — the same "not mine, ask the next one" signal
     * [com.vishalgupta.photoselector.data.format.CompositeCaptureMetadataSource] fans out on.
     * Pins/excludes are not a resolver's concern.
     */
    suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId>?
}

/**
 * Fans a rule across several rule-scoped [CategoryRuleResolver]s: the first one that *claims* the
 * rule type (returns non-null) wins. Each delegate is blind to the others' rule types, so order
 * affects cost, not correctness. No claimant yields null, which the repository treats exactly like an
 * unknown rule — the category shows its manual pins only, and its stored rule is carried through
 * untouched.
 */
class CompositeCategoryRuleResolver(
    private val resolvers: List<CategoryRuleResolver>,
) : CategoryRuleResolver {
    constructor(vararg resolvers: CategoryRuleResolver) : this(resolvers.toList())

    override suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId>? {
        for (resolver in resolvers) {
            resolver.resolve(rule, photos)?.let { return it }
        }
        return null
    }
}

/**
 * Resolves [CategoryRule.RawFiles] by classifying each photo's [Photo.fileName] extension against a
 * supplied set of RAW extensions. The set is *injected* (sourced from the format layer's RAW
 * decoder in DI) rather than hard-coded here, so the app has one source of truth for "what is RAW".
 * Extensions are compared case-insensitively.
 */
class RawFilesResolver(rawExtensions: Set<String>) : CategoryRuleResolver {
    private val rawExtensions: Set<String> = rawExtensions.mapTo(HashSet()) { it.lowercase() }

    override suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId>? = when (rule) {
        CategoryRule.RawFiles -> photos.filterTo(LinkedHashSet()) { it.isRaw() }.mapTo(LinkedHashSet()) { it.id }
        else -> null
    }

    private fun Photo.isRaw(): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in rawExtensions
}
