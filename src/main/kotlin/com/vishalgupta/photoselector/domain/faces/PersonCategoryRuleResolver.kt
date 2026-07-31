package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.CategoryRuleResolver
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId

/**
 * Resolves [CategoryRule.Person] into the photos that person appears in — the whole bridge between
 * the face pipeline and the categories model. Everything downstream comes free: a person rides
 * `CategoryScope.Category(id)` like any other bucket, `slice()` stays predicate-blind, and the
 * repository's pins/excludes give "not actually Alice" / "Alice is in this one too" with no new code.
 *
 * The `PersonId -> photos` lookup is *injected* rather than reached for, exactly as [
 * com.vishalgupta.photoselector.domain.model.RawFilesResolver] takes its RAW extension set from DI:
 * the resolver stays domain-pure and has no idea a people repository (or a file) exists.
 *
 * ## "Can't answer" is not "matches nothing"
 * [photosOf] returns null when the people index cannot answer for that id — no bound root, no
 * sidecar, or a person this root has never heard of. The resolver then declines the rule (returns
 * null) rather than reporting an empty match, and the categories repository leaves that category's
 * `ruleMatches` untouched and skips its override prune. Collapsing the two would be silently
 * destructive: an empty "authoritative" answer makes every stored pin and exclude look redundant, so
 * the very next rewrite deletes the user's corrections from disk. An *empty set* is still a valid
 * authoritative answer — the person exists and appears in nothing.
 *
 * The result is intersected with the photos actually in the scan, so a person whose photos have since
 * left the root contributes nothing rather than phantom ids.
 */
class PersonCategoryRuleResolver(
    private val photosOf: (PersonId) -> Set<PhotoId>?,
) : CategoryRuleResolver {

    override suspend fun resolve(rule: CategoryRule, photos: List<Photo>): Set<PhotoId>? {
        if (rule !is CategoryRule.Person) return null
        val appearsIn = photosOf(PersonId(rule.personId)) ?: return null
        if (appearsIn.isEmpty()) return emptySet()
        return photos.filterTo(LinkedHashSet()) { it.id in appearsIn }.mapTo(LinkedHashSet()) { it.id }
    }
}
