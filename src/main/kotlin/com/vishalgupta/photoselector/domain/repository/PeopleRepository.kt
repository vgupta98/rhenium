package com.vishalgupta.photoselector.domain.repository

import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-root storage of the people the face pipeline found. Re-binds context on root switch, exactly
 * like [CategoriesRepository] — the two are siblings, and a person category is the join between them.
 *
 * The split of responsibility mirrors the categories repository: a scan produces the whole set at
 * once ([replaceAll]), while [rename] is the single user-authored edit. Everything else is derived
 * and may be recomputed at any time.
 *
 * [photosOf] is deliberately a plain, non-suspending read: it is the lookup a
 * [com.vishalgupta.photoselector.domain.faces.PersonCategoryRuleResolver] is injected with, and the
 * categories repository already resolves rules on its own off-thread pass.
 */
interface PeopleRepository {
    /** Every person in [root], scan order. Named people first is *not* guaranteed — that is a UI concern. */
    fun observePeople(root: RootFolder): StateFlow<List<Person>>

    /** Replaces the whole set with a scan's result and persists it. */
    suspend fun replaceAll(root: RootFolder, people: List<Person>)

    /** Names (or renames) a person. A blank name clears it, returning them to "unnamed". */
    suspend fun rename(root: RootFolder, id: PersonId, name: String?)

    /** Forgets a person entirely. Called when their category is deleted, so the two never drift. */
    suspend fun delete(root: RootFolder, id: PersonId)

    /**
     * Photos [id] appears in within [root], binding to it on demand (like [observePeople]), or
     * **null** when [root] has no such person.
     *
     * The null is load-bearing and must not be flattened to an empty set. A person category's
     * membership is `(ruleMatches ∪ pins) \ excludes`, and the categories repository *prunes* stored
     * overrides against `ruleMatches` — so answering "matches nothing" for a person we simply cannot
     * resolve would delete the user's manual pins and excludes from disk. "This root has never heard
     * of that person" is not the same answer as "that person is in no photos", and only the second
     * is authoritative enough to prune against.
     */
    fun photosOf(root: RootFolder, id: PersonId): Set<PhotoId>?

    suspend fun clearContext()
}
