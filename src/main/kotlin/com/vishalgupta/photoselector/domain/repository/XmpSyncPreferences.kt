package com.vishalgupta.photoselector.domain.repository

import com.vishalgupta.photoselector.domain.model.RootFolder

/**
 * Per-root persistence of the "keep RAW XMP sidecars in sync" toggle. Scoped to the root (unlike the
 * global [AppPreferencesRepository]) because syncing is a property of *this* folder's cull — a
 * photographer turns it on for a shoot they're handing to Bridge / Lightroom and leaves it off
 * elsewhere. Kept behind an interface so the [com.vishalgupta.photoselector.presentation.common.XmpSyncCoordinator]
 * does no file IO and tests can substitute an in-memory fake.
 */
interface XmpSyncPreferences {
    /** Whether live XMP sidecar sync is enabled for [root]. Defaults false (off until turned on). */
    fun isEnabled(root: RootFolder): Boolean

    /** Persists the enabled flag for [root], remembered across sessions. */
    fun setEnabled(root: RootFolder, enabled: Boolean)
}
