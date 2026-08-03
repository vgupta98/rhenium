package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.data.faces.FaceScanner
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the one whole-root face scan — detect, embed and cluster every photo, then persist the people.
 *
 * The second typed facade over [BackgroundPassCoordinator] (the first is [GroupingCoordinator]), for
 * exactly the same reason: the scan is a minute-long on-device pass, and it must survive the user
 * navigating off the People screen rather than restarting every time they come back. Slice dedup
 * means a second "Scan" click attaches to the pass already running instead of starting a rival one.
 *
 * Lifetime mirrors [GroupingCoordinator], **not** `XmpSyncCoordinator`: one container-level instance
 * parented to the app scope, so the navigation host can collect one stable [progress] flow for the
 * off-screen chip, with [reset] on a root change. Unlike grouping it also offers [cancel] — a pass the
 * user deliberately started needs a stop.
 *
 * ## Availability is resolved by *running*, not by asking
 * [scanner] arrives as a **provider**, and is only invoked inside the pass body. Resolving it opens
 * both ONNX sessions, which the DI container keeps `by lazy` precisely so a user who never scans pays
 * neither the load nor the memory — a coordinator that probed availability up front (to render a
 * disabled row) would defeat that on every launch. So [available] starts null ("not yet known") and
 * latches to true/false on the first scan attempt. That still draws the distinction the packaged
 * build needs: after one attempt, "no faces found" and "face scanning unavailable" are different
 * screens, which is the point — a stripped runtime or a missing model resource reads as the former
 * unless the UI says otherwise.
 *
 * PII: never log a person's name, and never write a face crop to disk.
 */
class FaceScanCoordinator(
    private val scanner: () -> FaceScanner?,
    private val people: PeopleRepository,
    // The root the scan belongs to, read at pass start. Supplied by DI (the container owns the scanned
    // root) rather than passed per call, so this stays a plain BackgroundPassCoordinator facade.
    private val currentRoot: () -> RootFolder?,
    parentJob: Job?,
    dispatcher: CoroutineDispatcher,
) {
    private val _available = MutableStateFlow<Boolean?>(null)

    /**
     * Whether face scanning works on this machine: null until the first scan has resolved the models,
     * then true/false and stable for the session. The UI must render false as an explicit
     * "unavailable" state — never as an empty result.
     */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private val pass = BackgroundPassCoordinator<List<Person>>(
        parentJob = parentJob,
        dispatcher = dispatcher,
    ) { photos, onProgress ->
        // Resolving the provider is what opens the ONNX sessions, so it happens here — on the pass's
        // own dispatcher, once the user has actually asked for a scan — and never on the EDT.
        val scanner = scanner()
        _available.value = scanner != null
        val root = currentRoot()
        if (scanner == null || root == null) {
            emptyList()
        } else {
            // The previous result anchors the recluster: a named or dismissed person keeps their
            // identity through it (FaceClusterer phase 1), so a rescan never throws naming work away.
            val known = people.observePeople(root).value
            val found = scanner.scan(photos, known, onProgress)
            people.replaceAll(root, found)
            found
        }
    }

    /** Live progress of the running scan; null while idle or inside the grace window. */
    val progress: StateFlow<BackgroundPassCoordinator.Progress?> get() = pass.progress

    /** True while a scan is in flight — gates the Stop affordance. */
    val isScanning: Boolean get() = pass.isRunning

    /** Scans [photos], or attaches to the scan already running over the same slice. */
    fun scan(photos: List<Photo>): Deferred<List<Person>> = pass.passFor(photos)

    /** Stops the running scan at the user's request. A later [scan] starts a fresh pass. */
    fun cancel() {
        pass.cancel()
    }

    /** Drops any in-flight scan and clears progress — called on a root change. */
    fun reset() {
        pass.reset()
    }
}
