package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The part of the shared pass mechanism that [GroupingCoordinatorTest] does not reach, because
 * grouping does not expose it: the user-initiated [BackgroundPassCoordinator.cancel] behind the face
 * scan's Stop action.
 *
 * Everything else (slice dedup, supersede, the grace window, monotonic progress) is exercised through
 * the grouping facade, deliberately — those are the behaviours the Similarity path already relied on
 * and the extraction had to preserve unchanged.
 */
class BackgroundPassCoordinatorTest {

    private val photos = (0 until 4).map { i ->
        Photo(
            id = PhotoId("p$i"),
            absolutePath = Path.of("/photos/img$i.jpg"),
            relativePath = "img$i.jpg",
            fileName = "img$i.jpg",
            sizeBytes = 1,
            lastModifiedEpochMs = 0,
        )
    }

    @Test
    fun cancelStopsTheRunningPassAndClearsProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = BackgroundPassCoordinator<Int>(
            parentJob = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        ) { photos, onProgress ->
            runs++
            onProgress(1, photos.size)
            gate.await()
            photos.size
        }

        val first = coordinator.passFor(photos)
        advanceUntilIdle() // past the grace window, so the bar is armed
        assertEquals(BackgroundPassCoordinator.Progress(1, photos.size), coordinator.progress.value)
        assertTrue(first.isActive)

        coordinator.cancel()

        assertNull(coordinator.progress.value, "a stopped pass must not leave its bar on screen")
        assertTrue(first.isCancelled, "the in-flight pass itself is cancelled, not just its bar")
        assertFalse(first.isActive)

        // And the *same* slice starts a fresh pass afterwards rather than re-attaching to the dead
        // one — otherwise "Stop" would be a one-way door for that folder.
        coordinator.passFor(photos)
        advanceUntilIdle()
        assertEquals(2, runs)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun aCompletedPassClearsItsProgressAndReturnsItsResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = BackgroundPassCoordinator<Int>(
            parentJob = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        ) { photos, onProgress ->
            onProgress(1, photos.size)
            gate.await()
            photos.size
        }

        val pass = coordinator.passFor(photos)
        advanceUntilIdle()
        assertTrue(pass.isActive)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(photos.size, pass.await())
        assertNull(coordinator.progress.value, "a finished pass leaves no bar behind")
    }
}
