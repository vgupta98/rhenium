package com.vishalgupta.photoselector.domain.repository

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-root storage of flat photo categories. Re-binds context on root switch.
 *
 * Metadata ([observeCategories]) and membership ([observeMemberships]) are two
 * separate flows so a rename doesn't churn membership and a toggle doesn't churn
 * metadata. Membership is exposed as a single `Map<CategoryId, Set<PhotoId>>` —
 * not per-id flows — because the category dropdown needs counts for every category
 * at once, and per-id flows would mean N subscriptions plus lifecycle churn as
 * categories are created and deleted.
 *
 * This is also the extensibility seam: a future smart category resolves its
 * members behind [observeMemberships] (manual -> stored set, smart -> computed
 * predicate). Callers never learn whether a set was stored or computed.
 */
interface CategoriesRepository {
    /** Category metadata in display order; Favourites is always first. */
    fun observeCategories(root: RootFolder): StateFlow<List<Category>>

    /** Membership for every category at once, keyed by id. */
    fun observeMemberships(root: RootFolder): StateFlow<Map<CategoryId, Set<PhotoId>>>

    /**
     * Creates a new custom category and returns its generated id. Pass a [rule] to create a **smart**
     * category (e.g. `CategoryRule.Person`) that self-fills from it; the default `null` creates the
     * ordinary manual bucket.
     */
    suspend fun create(root: RootFolder, name: String, rule: CategoryRule? = null): CategoryId

    /** Renames a category. Throws for a built-in or a seeded smart category (see [Category.SMART_SEED_IDS]). */
    suspend fun rename(root: RootFolder, id: CategoryId, newName: String)

    /**
     * Deletes a category. Throws for a built-in or a seeded smart category. Deleting a rule-backed
     * category also disposes of whatever the rule pointed at (a person category forgets its person),
     * so the two models can never drift.
     */
    suspend fun delete(root: RootFolder, id: CategoryId)

    /** Toggles [photo]'s membership in [id]; returns true if it is now a member. */
    suspend fun toggleMembership(root: RootFolder, id: CategoryId, photo: PhotoId): Boolean

    /**
     * Files every photo in [photos] into [id] in a single write, leaving existing members
     * untouched. Additive (never removes) so a mixed selection ends up uniformly filed rather
     * than flip-flopped per tile. Returns how many were *newly* added.
     */
    suspend fun addMemberships(root: RootFolder, id: CategoryId, photos: Collection<PhotoId>): Int

    /**
     * Drops [photos] from *every* category in a single write — the membership side of deleting
     * the underlying files. A no-op (no write) when none of the ids are filed anywhere, so a
     * delete of un-categorised photos doesn't churn the file.
     */
    suspend fun removeMemberships(root: RootFolder, photos: Collection<PhotoId>)

    suspend fun clearContext()

    /** True when the categories file cannot be written (e.g. read-only volume). */
    fun isReadOnly(root: RootFolder): StateFlow<Boolean>
}
