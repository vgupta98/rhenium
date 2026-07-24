package com.vishalgupta.photoselector.domain.insight

import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.CategoryRuleResolver
import com.vishalgupta.photoselector.domain.model.InsightComparator
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId

/**
 * The generic [CategoryRuleResolver] for the predicate tree: it evaluates [CategoryRule.Leaf] against
 * an [InsightSource] and composes [And] / [Or] / [Not]. The one non-insight node, [CategoryRule.RawFiles],
 * is delegated to [rawFilesResolver] so that demo rule keeps its exact behaviour.
 *
 * ## Graceful degrade (load-bearing)
 * A leaf whose provider is unknown/unavailable, or whose photo hasn't been analyzed, reads `null` from
 * the [source] and simply doesn't match — the leaf "matches nothing", never crashes, and the category
 * stays smart (its tree shape is known; it just resolves empty). This is distinct from PR #120's
 * unknown-rule-*type* fallback (which keeps the category *manual*): here the tree shape decodes fine, so
 * the category is genuinely smart but currently empty. Composed under a [Not] that means "everything
 * except the (empty) match" — correct boolean semantics, still no crash.
 */
class PredicateTreeResolver(
    private val rawFilesResolver: CategoryRuleResolver,
    private val source: InsightSource,
) : CategoryRuleResolver {

    override suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId> = when (rule) {
        CategoryRule.RawFiles -> rawFilesResolver.resolve(rule, photos)
        is CategoryRule.Leaf -> resolveLeaf(rule, photos)
        is CategoryRule.And -> {
            if (rule.children.isEmpty()) {
                emptySet()
            } else {
                var acc: Set<PhotoId>? = null
                for (child in rule.children) {
                    val matched = resolve(child, photos)
                    acc = acc?.let { it intersect matched } ?: matched
                    if (acc.isEmpty()) break
                }
                acc.orEmpty()
            }
        }
        is CategoryRule.Or -> {
            val acc = LinkedHashSet<PhotoId>()
            for (child in rule.children) acc += resolve(child, photos)
            acc
        }
        is CategoryRule.Not -> {
            val matched = resolve(rule.child, photos)
            photos.filterTo(LinkedHashSet()) { it.id !in matched }.mapTo(LinkedHashSet()) { it.id }
        }
    }

    private suspend fun resolveLeaf(leaf: CategoryRule.Leaf, photos: List<Photo>): Set<PhotoId> {
        val out = LinkedHashSet<PhotoId>()
        for (photo in photos) {
            val value = source.bandedValue(leaf.providerId, photo) ?: continue
            if (matches(leaf, value)) out += photo.id
        }
        return out
    }

    private fun matches(leaf: CategoryRule.Leaf, value: InsightValue): Boolean = when (leaf.comparator) {
        InsightComparator.InBand -> value is InsightValue.Scalar && value.band == leaf.operand
    }
}
