package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.grouping.GroupingProgress
import com.vishalgupta.photoselector.domain.grouping.PhotoGrouper
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the coordinator's identity + progress contract — the parts the grid relies on to tell
 * its own background pass from one a different slice has superseded, and to never show a stale value.
 */
class GroupingCoordinatorTest {

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

    /** A grouper that parks on [gate] so a pass stays in-flight; optionally reports [report] first. */
    private fun gatedGrouper(gate: CompletableDeferred<Unit>, report: Pair<Int, Int>? = null) =
        object : PhotoGrouper {
            override suspend fun group(photos: List<Photo>, onProgress: GroupingProgress): List<PhotoGroup> {
                report?.let { (processed, _) -> onProgress(processed, photos.size) }
                gate.await()
                return photos.map(PhotoGroup::Single)
            }
        }

    @Test
    fun activeGeneration_bumpsOnSupersedeButNotOnReattach() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = GroupingCoordinator(
            grouper = gatedGrouper(gate),
            parentJob = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val a = photos.take(3)
        val b = photos.drop(3)

        coordinator.groupingFor(a)
        val genA = coordinator.activeGeneration

        coordinator.groupingFor(a)
        assertEquals("re-attaching to the same slice must not bump the generation", genA, coordinator.activeGeneration)

        coordinator.groupingFor(b)
        assertNotEquals("a different slice supersedes and must bump the generation", genA, coordinator.activeGeneration)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun supersede_clearsTheSupersededSlicesProgress() = runTest {
        // The old pass reports progress and stays in-flight; once its grace window passes, the bar shows
        // its count. A different slice then supersedes it — the progress must drop to null at once rather
        // than carry the old slice's count and (wrong) denominator into the new pass's grace window.
        val gate = CompletableDeferred<Unit>()
        val coordinator = GroupingCoordinator(
            grouper = gatedGrouper(gate, report = 2 to 0),
            parentJob = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val a = photos.take(3)
        val b = photos.drop(3)

        coordinator.groupingFor(a)
        advanceUntilIdle() // run past the grace window so the armed bar shows a's count
        assertEquals(BackgroundPassCoordinator.Progress(2, a.size), coordinator.progress.value)

        coordinator.groupingFor(b)
        assertNull("superseding a different slice clears the stale progress immediately", coordinator.progress.value)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun reset_dropsTheInFlightPassAndClearsProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = GroupingCoordinator(
            grouper = gatedGrouper(gate, report = 1 to 0),
            parentJob = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        coordinator.groupingFor(photos)
        advanceUntilIdle()
        assertEquals(BackgroundPassCoordinator.Progress(1, photos.size), coordinator.progress.value)

        coordinator.reset()
        assertNull("reset clears progress", coordinator.progress.value)

        gate.complete(Unit)
        advanceUntilIdle()
    }
}
