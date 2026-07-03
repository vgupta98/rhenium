package com.vishalgupta.photoselector.domain.model

import java.nio.file.Path

data class RootFolder(val path: Path) {
    val categoriesFile: Path get() = path.resolve(CATEGORIES_FILE_NAME)
    /** Legacy single-bucket favourites file (read once to migrate into categories). */
    val favouritesFile: Path get() = path.resolve(FAVOURITES_FILE_NAME)
    /** Where the favourites file is moved after migration, kept one release as a safety net. */
    val favouritesBackupFile: Path get() = path.resolve("$FAVOURITES_FILE_NAME.bak")
    val positionFile: Path get() = path.resolve(POSITION_FILE_NAME)
    /** Per-root persisted "keep RAW XMP sidecars in sync" toggle (see XmpSyncPreferences). */
    val xmpSyncFile: Path get() = path.resolve(XMP_SYNC_FILE_NAME)

    companion object {
        const val CATEGORIES_FILE_NAME: String = ".photo-selector-categories.json"
        const val FAVOURITES_FILE_NAME: String = ".photo-selector-favourites.json"
        const val POSITION_FILE_NAME: String = ".photo-selector-position.json"
        const val XMP_SYNC_FILE_NAME: String = ".photo-selector-xmp-sync.json"
    }
}
