package com.vishalgupta.photoselector.domain.repository

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import java.nio.file.Path

enum class ConflictPolicy { OVERWRITE, SKIP, RENAME }

data class CopyReport(
    val copied: Int,
    val skipped: Int,
    val failed: List<Pair<Photo, Throwable>>,
)

/**
 * Outcome of an XMP-sidecar export (Phase 1, RAW-only): [written] sidecars whose `xmp:Rating` we set
 * (favourite / reject), [cleared] sidecars where an un-decided photo had our ownership stamp removed
 * (the rating we previously wrote, still untouched by the user, was cleared), [unsupported] non-RAW
 * photos skipped (JPEG/HEIC/etc — those decisions travel via a future embedding phase, not a
 * sidecar), and any per-photo [failed] writes.
 */
data class XmpReport(
    val written: Int,
    val cleared: Int,
    val unsupported: Int,
    val failed: List<Pair<Photo, Throwable>>,
)

interface PhotoExporter {
    suspend fun exportTxt(
        root: RootFolder,
        favourites: List<Photo>,
        destinationTxt: Path,
    )

    suspend fun copyToFolder(
        root: RootFolder,
        favourites: List<Photo>,
        destDir: Path,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
        onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> },
    ): CopyReport

    /**
     * Writes an XMP sidecar next to each proprietary-RAW photo (`IMG_1234.CR2` -> `IMG_1234.xmp`)
     * carrying the cull's keep/reject decision as `xmp:Rating` (reject `-1`, favourite `5`) for a
     * Bridge / Lightroom / Capture One handoff. [favouriteIds] and [rejectedIds] drive the rating
     * (reject wins); a RAW in neither has its rating cleared only if we previously wrote it (an
     * ownership stamp guards against clobbering a user's Bridge edit). Phase 1 is RAW-only: non-RAW
     * photos are counted [XmpReport.unsupported] and skipped (JPEG/HEIC ratings will travel via
     * embedded XMP in a later phase, not a sidecar).
     */
    suspend fun exportXmpSidecars(
        root: RootFolder,
        photos: List<Photo>,
        favouriteIds: Set<PhotoId>,
        rejectedIds: Set<PhotoId>,
        onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
    ): XmpReport
}
