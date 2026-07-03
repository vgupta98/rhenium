package com.vishalgupta.photoselector.domain.usecase

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.PhotoExporter
import com.vishalgupta.photoselector.domain.repository.XmpReport

/**
 * Writes an XMP sidecar next to each photo carrying its cull decision (Favourites / Rejects) as
 * `xmp:Rating` / `xmp:Label`, for a Lightroom / Capture One handoff. Mirrors [ExportPhotosTxtUseCase]
 * / [CopyPhotosToFolderUseCase]: a thin pass-through over the [PhotoExporter] seam.
 */
class ExportPhotosXmpUseCase(private val exporter: PhotoExporter) {
    suspend operator fun invoke(
        root: RootFolder,
        photos: List<Photo>,
        favouriteIds: Set<PhotoId>,
        rejectedIds: Set<PhotoId>,
        onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
    ): XmpReport = exporter.exportXmpSidecars(root, photos, favouriteIds, rejectedIds, onProgress)
}
