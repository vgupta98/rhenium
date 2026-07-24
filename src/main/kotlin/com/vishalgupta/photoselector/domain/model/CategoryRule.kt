package com.vishalgupta.photoselector.domain.model

import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightProviderId

/**
 * A pure, serialisable descriptor of how a [smart][CategoryKind.SMART] category resolves its
 * membership from the photos in a root. It carries **no** resolved ids and holds **no** manual
 * overrides — a rule is a stable predicate description, resolved on demand by a
 * [CategoryRuleResolver]. Manual pins/excludes layer on top in the repository, never here.
 *
 * A **predicate tree**, not a single flag: a [Leaf] tests one insight provider's banded output, and
 * [And] / [Or] / [Not] compose leaves into a boolean expression. This is the generalization the
 * insight-provider platform hangs off (`proposals/extensibility/02-insight-provider-platform.md`) — a
 * future on-device signal is a new provider id in a [Leaf], with no change to the tree, the resolver
 * shape, persistence, or the grid. [RawFiles] is kept as the one non-insight leaf (a demo rule with no
 * model behind it), so the two kinds of smart rule coexist behind the same seam.
 */
sealed interface CategoryRule {
    /** Matches camera RAW files, classified by file extension (the non-AI demo rule). */
    data object RawFiles : CategoryRule

    /**
     * Tests one [providerId]'s banded insight against [operand] via [comparator]. Phase 1 ships only
     * the [InsightComparator.InBand] comparator (scalar-band membership, e.g. `sharpness in Sharp`); the
     * operand is always a human [InsightBand], never a raw number.
     */
    data class Leaf(
        val providerId: InsightProviderId,
        val comparator: InsightComparator,
        val operand: InsightBand,
    ) : CategoryRule

    /** True iff every child matches. */
    data class And(val children: List<CategoryRule>) : CategoryRule

    /** True iff any child matches. */
    data class Or(val children: List<CategoryRule>) : CategoryRule

    /** True iff [child] does not match. */
    data class Not(val child: CategoryRule) : CategoryRule
}

/**
 * How a [CategoryRule.Leaf] compares a provider's output to its operand. Phase 1 ships only [InBand]
 * (scalar band membership); the boolean/label comparators the taxonomy reserves arrive with those
 * insight-value cases in a later phase.
 */
enum class InsightComparator { InBand }

/** Every insight provider id referenced by leaves in this tree. Empty ⇒ no insight pass is needed. */
fun CategoryRule.providerIds(): Set<InsightProviderId> = when (this) {
    CategoryRule.RawFiles -> emptySet()
    is CategoryRule.Leaf -> setOf(providerId)
    is CategoryRule.And -> children.flatMapTo(LinkedHashSet()) { it.providerIds() }
    is CategoryRule.Or -> children.flatMapTo(LinkedHashSet()) { it.providerIds() }
    is CategoryRule.Not -> child.providerIds()
}

/**
 * The seam that turns a [CategoryRule] into a concrete membership over a batch of photos. Batch +
 * `suspend` so a rule can do real work (decode/inference) off-thread; **override-unaware and
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
 *
 * Only [CategoryRule.RawFiles] is its concern — every other tree node is handled by
 * [com.vishalgupta.photoselector.domain.insight.PredicateTreeResolver], which delegates the RawFiles
 * leaf here — so any non-RawFiles rule resolves to no matches.
 */
class RawFilesResolver(rawExtensions: Set<String>) : CategoryRuleResolver {
    private val rawExtensions: Set<String> = rawExtensions.mapTo(HashSet()) { it.lowercase() }

    override suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId> = when (rule) {
        CategoryRule.RawFiles -> photos.filterTo(LinkedHashSet()) { it.isRaw() }.mapTo(LinkedHashSet()) { it.id }
        else -> emptySet()
    }

    private fun Photo.isRaw(): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in rawExtensions
}
