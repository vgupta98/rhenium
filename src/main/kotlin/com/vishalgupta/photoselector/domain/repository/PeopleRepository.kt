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
 * [photosOf] is deliberately a plain, non-suspending read over the bound root: it is the lookup a
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

    /** Photos [id] appears in, over the currently bound root; empty for an unknown id. */
    fun photosOf(id: PersonId): Set<PhotoId>

    suspend fun clearContext()
}
