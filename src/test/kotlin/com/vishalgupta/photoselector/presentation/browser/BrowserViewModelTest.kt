package com.vishalgupta.photoselector.presentation.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.grouping.CaptureMetadata
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.PhotoTrash
import com.vishalgupta.photoselector.domain.repository.TrashReport
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.testing.FakeCategoriesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Path

/**
 * Concurrency behaviour of [BrowserViewModel.deleteCurrent]. The delete target and its index are
 * snapshotted before `moveToTrash` suspends, so a navigation that races the in-flight move can't
 * make focus land a slot off the reel. A [StandardTestDispatcher] (injected via the new
 * StateHolder dispatcher seam) drives the launched delete coroutine step by step, and a
 * [CompletableDeferred] gate holds the move open exactly while the navigation is fired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {

    private val photos = (0 until 6).map { i ->
        Photo(
            id = PhotoId("p$i"),
            absolutePath = Path.of("/photos/img$i.jpg"),
            relativePath = "img$i.jpg",
            fileName = "img$i.jpg",
            sizeBytes = 1,
            lastModifiedEpochMs = 0,
        )
    }

    private val noOpImageLoader = object : ImageLoader {
        override suspend fun load(photo: Photo, viewportLongEdgePx: Int): ImageBitmap? = null
        override fun prefetch(photos: List<Photo>, viewportLongEdgePx: Int, scope: CoroutineScope) {}
        override fun evictAll() {}
        override fun pin(id: PhotoId) {}
        override fun unpinAllExcept(id: PhotoId?) {}
    }

    @Test
    fun deleteCurrent_landsRelativeToTheDeletedPhoto_notWhereTheUserNavigatedMidMove() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Holds moveToTrash open so a navigation can be interleaved into the in-flight window.
        val gate = CompletableDeferred<Unit>()
        val trash = object : PhotoTrash {
            override suspend fun moveToTrash(photos: List<Photo>): TrashReport {
                gate.await()
                return TrashReport(trashed = photos.size, failed = emptyList())
            }
        }
        val vm = BrowserViewModel(
            root = RootFolder(Path.of("/photos")),
            photos = photos,
            initialIndex = 2,
            categories = FakeCategoriesRepository(),
            moveToTrash = MovePhotosToTrashUseCase(trash),
            imageLoader = noOpImageLoader,
            captureMetadataSource = { CaptureMetadata.NONE },
            isReadOnly = MutableStateFlow(false),
            dispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(PhotoId("p2"), vm.state.value.currentPhoto?.id)

        // Arm the delete (snapshots index 2 / p2), then let its coroutine run up to the gated move.
        vm.deleteCurrent()
        runCurrent()

        // The user pages forward twice while the move is still in flight.
        vm.next() // p2 -> p3
        vm.next() // p3 -> p4
        advanceUntilIdle()
        assertEquals(PhotoId("p4"), vm.state.value.currentPhoto?.id)

        // Release the move; the delete resumes and reconciles the reel.
        gate.complete(Unit)
        advanceUntilIdle()

        val st = vm.state.value
        assertFalse("the deleted photo is gone", st.photos.any { it.id == PhotoId("p2") })
        // Landing follows the deleted photo's position (the photo that was after it), NOT the
        // index the user navigated to mid-move. Reading currentIndex after the suspend would have
        // landed on p5 here.
        assertEquals(PhotoId("p3"), st.currentPhoto?.id)
        assertEquals(2, st.currentIndex)

        vm.onClear()
    }
}
