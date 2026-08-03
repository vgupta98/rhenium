package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns **one** long-running whole-folder pass over a photo slice, decoupled from whatever screen
 * happens to be showing while it runs. The mechanism only — what the pass actually *does* arrives as
 * [work], so a caller wraps this in a thin typed facade ([GroupingCoordinator] for the Similarity
 * grouping pass, [FaceScanCoordinator] for the face scan) rather than reimplementing it.
 *
 * These passes are minute-long on-device runs. Driving one from inside a screen's view model ties it
 * to that screen's lifetime, so navigating away (or switching a lens) cancels it — wasted work the
 * user then waits for again. This coordinator hoists the pass into a single, longer-lived owner: once
 * started it runs to completion regardless of what is on screen (results are cached downstream, so
 * the work is never thrown away), and its progress is exposed from one stable [progress] flow the
 * navigation host can surface app-wide (the off-grid chip).
 *
 * It keeps a single in-flight pass, deduplicated by slice: a repeat request for the same photo set
 * attaches to the running pass; a request for a different set supersedes the old one (the per-photo
 * caches make a superseded pass cheap to resume later). Lifetime is the *root* — [reset] drops the
 * in-flight pass and clears progress on a root change — while the object itself stays stable so a
 * single collector can watch [progress] across roots. [cancel] is the same drop, spelled for a user
 * who deliberately stopped a pass they started.
 *
 * Thread-safety: the mutating entry points ([passFor], [reset], [cancel]) guard their shared state
 * with an internal lock, so they are safe to call from any thread (in the running app they always
 * arrive on the EDT, where the lock is uncontended). The pass body itself runs on the injected
 * dispatcher and only reads the @Volatile [generation] and writes the thread-safe [progress] flow.
 *
 * @param work the pass: given the slice and a `(processed, total)` callback, produce a result. It is
 *   invoked inside this coordinator's own scope, so it must be cooperatively cancellable.
 */
class BackgroundPassCoordinator<R>(
    parentJob: Job?,
    dispatcher: CoroutineDispatcher,
    private val work: suspend (List<Photo>, (Int, Int) -> Unit) -> R,
) {
    /**
     * Live progress of the running pass: [processed] of [total]. Null while idle, and — like the
     * grid's Time bar — within a short grace window, so a warm pass (a cache hit that returns within
     * it) never flashes a 100% blip.
     */
    data class Progress(val processed: Int, val total: Int)

    private val scope = CoroutineScope(SupervisorJob(parentJob) + dispatcher)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    // Guards the request-path state below ([current], [currentIds], the [generation] bump and the
    // matching [_progress] reset). A pass is (re)started only on a lens switch, a deliberate trigger
    // or a root change, so the lock is cold — effectively always uncontended, since in the running app
    // every call already lands on the EDT. It is here so the class is *genuinely* thread-safe rather
    // than correct-by-accident: without it, the only thing keeping [current]/[currentIds] race-free is
    // the unwritten, unenforced assumption that every caller is confined to one thread — a landmine
    // the next edit could trip.
    private val lock = Any()
    // The slice the current pass is running over (for dedup) and the pass itself; mutated only under [lock].
    private var currentIds: List<PhotoId>? = null
    private var current: Deferred<R>? = null
    // Identifies the in-flight pass. Bumped on every supersede/reset, never reused, so a late worker
    // callback (cooperative cancellation lets one slip through) or the grace timer can tell it has been
    // replaced and stop publishing. Written under [lock]; @Volatile so the worker coroutine can read it
    // lock-free from the hot per-photo callback — comparing the id list there each tick would be O(n^2).
    @Volatile private var generation = 0

    /**
     * Generation of the pass currently in flight (or the last one started). A caller captures this
     * immediately after [passFor], on the same thread, to later tell whether the pass still running is
     * the one *it* requested — vs. one that a different slice has since superseded.
     */
    val activeGeneration: Int get() = generation

    /** True while a pass is in flight — the signal a "Stop" affordance is gated on. */
    val isRunning: Boolean get() = synchronized(lock) { current?.isActive == true }

    /**
     * The pass over [photos], starting it (in this coordinator's own scope) if none is already running
     * for this exact slice. Idempotent per slice — a repeat attaches to the in-flight pass — and a
     * different slice supersedes the prior one. The returned [Deferred] completes with the result;
     * awaiting it from a short-lived caller is safe because the *compute* lives here, not in the
     * caller's scope, so cancelling the await never stops the pass.
     */
    fun passFor(photos: List<Photo>): Deferred<R> {
        val ids = photos.map { it.id }
        synchronized(lock) {
            current?.let { if (currentIds == ids && it.isActive) return it }
            current?.cancel()
            // A genuine supersede (different slice): clear any armed progress so the new pass starts from
            // a clean slate. Otherwise the bar would carry the old slice's count *and denominator* through
            // the new pass's grace window — a stale value with the wrong total. The new grace timer
            // re-arms from its own count below.
            _progress.value = null
            currentIds = ids
            val gen = ++generation
            // scope.async only *schedules* the pass (the dispatcher never runs it inline under the lock),
            // so holding [lock] across this is non-blocking; the worker body below never takes the lock.
            val deferred = scope.async {
                // Only surface progress once the pass has run past the grace window: a warm pass (a
                // cache hit) returns within it and the timer never fires, so the bar stays hidden
                // — exactly the grid's Time-bar behaviour. The timer publishes the latest count reached so
                // far rather than 0, so a pass already well underway at the grace boundary doesn't snap back.
                val latest = AtomicInteger(0)
                val grace = launch {
                    delay(GRACE_MS)
                    // photos.size is the same total the per-photo callbacks publish (the pass reports
                    // total == photos.size), so the denominator is stable whichever path arms the bar first.
                    if (generation == gen) _progress.value = Progress(latest.get(), photos.size)
                }
                try {
                    work(photos) { processed, total ->
                        latest.updateAndGet { if (processed > it) processed else it }
                        // Coalesce + monotonic guard inside the atomic update: the pass reports from
                        // several threads out of order, and progress must only ever move forward. Nothing
                        // publishes until the grace timer has armed the bar (current value non-null).
                        _progress.update { cur ->
                            if (generation != gen || cur == null || processed <= cur.processed) cur
                            else Progress(processed, total)
                        }
                    }
                } finally {
                    grace.cancel()
                    if (generation == gen) _progress.value = null
                }
            }
            current = deferred
            return deferred
        }
    }

    /** Drops any in-flight pass and clears progress — called on a root change. */
    fun reset() {
        stop()
    }

    /**
     * Stops the in-flight pass at the user's request (the Stop affordance on the progress chip /
     * banner). Identical mechanics to [reset]; named separately because the *intent* differs, and a
     * later request for the same slice starts a fresh pass rather than re-attaching to a dead one.
     */
    fun cancel() {
        stop()
    }

    private fun stop() {
        synchronized(lock) {
            generation++
            current?.cancel()
            current = null
            currentIds = null
            _progress.value = null
        }
    }

    private companion object {
        const val GRACE_MS = 200L
    }
}
