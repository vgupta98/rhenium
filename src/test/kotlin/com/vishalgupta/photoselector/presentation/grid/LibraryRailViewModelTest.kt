package com.vishalgupta.photoselector.presentation.grid

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.PhotoTrash
import com.vishalgupta.photoselector.domain.repository.TrashReport
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.testing.FakeCategoriesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The rail's one destructive action: [LibraryRailViewModel.emptyRejectsToTrash] sweeps the whole
 * Rejects bucket to the Trash, then empties it — purging the trashed ids from every category and
 * notifying the container. Drives the view-model scope on a [StandardTestDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRailViewModelTest {

    private val root = RootFolder(Path.of("/root"))

    private fun photo(id: String) = Photo(
        id = PhotoId(id),
        absolutePath = Path.of("/root/$id.jpg"),
        relativePath = "$id.jpg",
        fileName = "$id.jpg",
        sizeBytes = 1,
        lastModifiedEpochMs = 0,
    )

    @Test
    fun emptyRejectsToTrash_trashesRejects_clearsBucket_andNotifies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        val all = listOf(photo("a"), photo("b"), photo("c"))
        repo.addMemberships(root, Category.REJECTS_ID, listOf(PhotoId("a"), PhotoId("c")))

        val trashed = mutableListOf<Photo>()
        val trash = object : PhotoTrash {
            override suspend fun moveToTrash(photos: List<Photo>): TrashReport {
                trashed += photos
                return TrashReport(trashed = photos.size, failed = emptyList())
            }
        }
        val deleted = mutableListOf<Set<PhotoId>>()
        val messages = mutableListOf<String>()

        val vm = LibraryRailViewModel(
            root = root,
            categories = repo,
            moveToTrash = MovePhotosToTrashUseCase(trash),
            photosForRoot = { all },
            onPhotosDeleted = { deleted += it },
            dispatcher = dispatcher,
        )
        val collect = launch { vm.sweepEvents.collect { messages += it } }

        vm.emptyRejectsToTrash()
        advanceUntilIdle()

        assertEquals(listOf(PhotoId("a"), PhotoId("c")), trashed.map { it.id })
        assertEquals(emptySet<PhotoId>(), repo.observeMemberships(root).value[Category.REJECTS_ID].orEmpty())
        assertEquals(setOf(PhotoId("a"), PhotoId("c")), deleted.single())
        assertEquals(listOf("Moved 2 rejects to Trash"), messages)
        collect.cancel()
    }

    @Test
    fun emptyRejectsToTrash_withEmptyBucket_isANoOp() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeCategoriesRepository(initial = Category.builtIns)
        var trashCalls = 0
        val trash = object : PhotoTrash {
            override suspend fun moveToTrash(photos: List<Photo>): TrashReport {
                trashCalls++
                return TrashReport(trashed = photos.size, failed = emptyList())
            }
        }
        val vm = LibraryRailViewModel(
            root = root,
            categories = repo,
            moveToTrash = MovePhotosToTrashUseCase(trash),
            photosForRoot = { listOf(photo("a")) },
            dispatcher = dispatcher,
        )

        vm.emptyRejectsToTrash()
        advanceUntilIdle()

        assertEquals(0, trashCalls)
        assertTrue(repo.observeMemberships(root).value[Category.REJECTS_ID].orEmpty().isEmpty())
    }
}
