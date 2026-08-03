package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.grouping.PhotoGrouper
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the one expensive (Similarity) grouping pass, decoupled from any grid's *displayed* lens.
 *
 * A thin, typed facade over [BackgroundPassCoordinator], which holds the whole mechanism (slice
 * dedup, supersede-by-generation, the progress grace window, monotonic progress coalescing, reset).
 * The only thing that is specific to grouping is the work body below and the [List]-of-[PhotoGroup]
 * result type — see [BackgroundPassCoordinator] for why the pass is hoisted out of the grid at all.
 */
class GroupingCoordinator(
    grouper: PhotoGrouper,
    parentJob: Job?,
    dispatcher: CoroutineDispatcher,
) {
    private val pass = BackgroundPassCoordinator<List<PhotoGroup>>(
        parentJob = parentJob,
        dispatcher = dispatcher,
    ) { photos, onProgress -> grouper.group(photos, onProgress) }

    /** Live progress of the running Similarity pass; null while idle or inside the grace window. */
    val progress: StateFlow<BackgroundPassCoordinator.Progress?> get() = pass.progress

    /** @see BackgroundPassCoordinator.activeGeneration */
    val activeGeneration: Int get() = pass.activeGeneration

    /** @see BackgroundPassCoordinator.passFor */
    fun groupingFor(photos: List<Photo>): Deferred<List<PhotoGroup>> = pass.passFor(photos)

    /** Drops any in-flight pass and clears progress — called on a root change. */
    fun reset() {
        pass.reset()
    }
}
