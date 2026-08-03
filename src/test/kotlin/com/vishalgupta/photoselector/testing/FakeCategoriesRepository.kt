package com.vishalgupta.photoselector.testing

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.CategoryKind
import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared in-memory [CategoriesRepository] fake for view-model tests. Every membership mutation is
 * real (it updates [members]) and every mutating call is recorded, so a test can either drive
 * behaviour through the flows or assert on [toggleCalls] / [addCalls]. Defaults to a single
 * Favourites category so the common case needs no arguments.
 */
class FakeCategoriesRepository(
    initial: List<Category> = listOf(Category.favourites()),
) : CategoriesRepository {
    private val cats = MutableStateFlow(initial)
    private val members = MutableStateFlow<Map<CategoryId, Set<PhotoId>>>(emptyMap())
    private val readOnly = MutableStateFlow(false)

    val toggleCalls = mutableListOf<Pair<CategoryId, PhotoId>>()
    val addCalls = mutableListOf<Pair<CategoryId, Set<PhotoId>>>()

    // Fired after a delete/rename, mirroring JsonCategoriesRepository's hooks, so a test can wire the
    // same person-disposal/rename side effects DI does without standing up the real repository.
    var onCategoryDeleted: suspend (RootFolder, Category) -> Unit = { _, _ -> }
    var onCategoryRenamed: suspend (RootFolder, Category) -> Unit = { _, _ -> }

    private var nextId = 0

    override fun observeCategories(root: RootFolder): StateFlow<List<Category>> = cats.asStateFlow()
    override fun observeMemberships(root: RootFolder): StateFlow<Map<CategoryId, Set<PhotoId>>> = members.asStateFlow()
    override fun isReadOnly(root: RootFolder): StateFlow<Boolean> = readOnly.asStateFlow()

    override suspend fun create(root: RootFolder, name: String, rule: CategoryRule?): CategoryId {
        val id = CategoryId("cat-${nextId++}")
        cats.value = cats.value + Category(
            id = id,
            name = name.trim(),
            builtIn = false,
            kind = if (rule != null) CategoryKind.SMART else CategoryKind.MANUAL,
            rule = rule,
        )
        return id
    }

    override suspend fun rename(root: RootFolder, id: CategoryId, newName: String) {
        cats.value = cats.value.map { if (it.id == id) it.copy(name = newName.trim()) else it }
        cats.value.firstOrNull { it.id == id }?.let { onCategoryRenamed(root, it) }
    }

    override suspend fun delete(root: RootFolder, id: CategoryId) {
        val removed = cats.value.firstOrNull { it.id == id }
        cats.value = cats.value.filterNot { it.id == id }
        members.value = members.value - id
        removed?.let { onCategoryDeleted(root, it) }
    }

    override suspend fun toggleMembership(root: RootFolder, id: CategoryId, photo: PhotoId): Boolean {
        toggleCalls += id to photo
        val current = members.value[id].orEmpty()
        val next = if (photo in current) current - photo else current + photo
        members.value = members.value + (id to next)
        return photo in next
    }

    override suspend fun addMemberships(root: RootFolder, id: CategoryId, photos: Collection<PhotoId>): Int {
        addCalls += id to photos.toSet()
        val current = members.value[id].orEmpty()
        val added = photos.filter { it !in current }
        members.value = members.value + (id to (current + added))
        return added.size
    }

    override suspend fun removeMemberships(root: RootFolder, photos: Collection<PhotoId>) {
        val toRemove = photos.toSet()
        members.value = members.value.mapValues { (_, ids) -> ids - toRemove }
    }

    override suspend fun clearContext() {}
}
