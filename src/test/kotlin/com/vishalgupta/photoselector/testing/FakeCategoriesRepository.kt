package com.vishalgupta.photoselector.testing

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
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

    override fun observeCategories(root: RootFolder): StateFlow<List<Category>> = cats.asStateFlow()
    override fun observeMemberships(root: RootFolder): StateFlow<Map<CategoryId, Set<PhotoId>>> = members.asStateFlow()
    override fun isReadOnly(root: RootFolder): StateFlow<Boolean> = readOnly.asStateFlow()
    override suspend fun create(root: RootFolder, name: String, rule: CategoryRule?): CategoryId = error("unused")
    override suspend fun rename(root: RootFolder, id: CategoryId, newName: String) {}
    override suspend fun delete(root: RootFolder, id: CategoryId) {}

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
