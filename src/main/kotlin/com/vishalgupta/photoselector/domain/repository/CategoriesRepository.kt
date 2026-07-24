package com.vishalgupta.photoselector.domain.repository

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The analysis state of a category, for the rail's tri-state signal. Insight-backed smart categories
 * need three states a binary "resolved set" can't express; every other category is [AlwaysReady].
 *
 * - [AlwaysReady]: manual categories and the extension-based "RAW files" smart category — no analysis
 *   gate, so their count is always meaningful and shown as-is.
 * - [NotAnalyzed]: an insight-backed smart category whose folder hasn't been analyzed — show `—` and
 *   prompt the user to analyze, not a misleading `0`.
 * - [Analyzed]: analyzed; the count is real (and may legitimately be `0`).
 * - [Stale]: analyzed, but the folder's photo set has since changed, so the membership may be out of
 *   date — offer a re-analyze rather than silently serving old membership.
 */
enum class CategoryAnalysisState { AlwaysReady, NotAnalyzed, Analyzed, Stale }

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
     * Per-category analysis state, for the rail's tri-state signal on insight-backed smart categories.
     * The default treats every category as [CategoryAnalysisState.AlwaysReady] (an id absent from the map
     * is also read as AlwaysReady), so a repository with no insight gating — and the test fakes — stay
     * behaviour-identical without implementing it. The real implementation maps the insight pass's state.
     */
    fun observeAnalysisStates(root: RootFolder): StateFlow<Map<CategoryId, CategoryAnalysisState>> =
        MutableStateFlow<Map<CategoryId, CategoryAnalysisState>>(emptyMap()).asStateFlow()

    /** Creates a new custom category and returns its generated id. */
    suspend fun create(root: RootFolder, name: String): CategoryId

    /** Renames a category. Throws if [id] is the built-in Favourites. */
    suspend fun rename(root: RootFolder, id: CategoryId, newName: String)

    /** Deletes a category. Throws if [id] is the built-in Favourites. */
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
