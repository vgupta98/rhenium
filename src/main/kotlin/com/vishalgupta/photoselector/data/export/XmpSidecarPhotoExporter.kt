package com.vishalgupta.photoselector.data.export

import com.vishalgupta.photoselector.data.format.RawDecoder.Companion.RawFormat
import com.vishalgupta.photoselector.data.io.AtomicJsonWriter
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.XmpReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.nameWithoutExtension

/**
 * **Phase 1 — RAW-only.** Writes an XMP sidecar (`IMG_1234.CR2` -> `IMG_1234.xmp`) next to each
 * proprietary-RAW source so the cull's keep/reject decisions travel into Adobe Bridge / Lightroom /
 * Capture One, which read a photo's rating from a sidecar sharing its basename.
 *
 * Only the two decisions the model has flow through, as the single `xmp:Rating` field (validated in
 * Bridge — reject and stars share it):
 *  1. In Rejects         -> `xmp:Rating="-1"` (Adobe's reject flag)
 *  2. else in Favourites -> `xmp:Rating="5"`
 *  3. else               -> the rating is *cleared* iff we previously wrote it (an un-decide), else
 *                           the sidecar is left alone.
 *
 * Scope is RAW-only because a sidecar is the wrong channel for JPEG/HEIC — those carry their rating in
 * embedded XMP, which a later phase will write. Non-RAW photos are counted [XmpReport.unsupported] and
 * skipped. The RAW set is matched by extension via [RawFormat.matches] (no decode needed, so this
 * works cross-platform, unlike the macOS-gated decode registry). We iterate photos one-to-one — a
 * same-basename RAW+JPEG pair writes the RAW's `.xmp` and counts the JPEG as unsupported; there is no
 * basename fold (the old fold silently dropped decisions).
 *
 * Merging, not clobbering: a real Bridge sidecar is a full RDF document (mirrored EXIF, history,
 * digest). [XmpDocument] parses the existing file, mutates only our two owned fields, and re-serializes
 * so nothing else is touched. Writes go through [AtomicJsonWriter] (temp + atomic rename) so a crash
 * never leaves a half-written sidecar shadowing a photo.
 */
class XmpSidecarPhotoExporter {

    suspend fun exportSidecars(
        @Suppress("UNUSED_PARAMETER") root: RootFolder,
        photos: List<Photo>,
        favouriteIds: Set<PhotoId>,
        rejectedIds: Set<PhotoId>,
        onProgress: (written: Int, total: Int) -> Unit,
    ): XmpReport = withContext(Dispatchers.IO) {
        var written = 0
        var cleared = 0
        var unsupported = 0
        val failed = ArrayList<Pair<Photo, Throwable>>()
        val total = photos.size

        photos.forEachIndexed { index, photo ->
            // Cooperative cancellation at each file boundary, mirroring CopyPhotoExporter.
            ensureActive()
            if (!RawFormat.matches(photo.absolutePath)) {
                unsupported++
            } else {
                val decision = decisionFor(
                    isRejected = photo.id in rejectedIds,
                    isFavourite = photo.id in favouriteIds,
                )
                val sidecar = photo.absolutePath.resolveSibling(
                    "${photo.absolutePath.nameWithoutExtension}.xmp",
                )
                try {
                    val existing = if (Files.exists(sidecar)) Files.readAllBytes(sidecar) else null
                    when (val outcome = XmpDocument.merge(existing, decision)) {
                        is XmpMergeOutcome.Write -> {
                            AtomicJsonWriter.write(sidecar, outcome.bytes)
                            if (outcome.cleared) cleared++ else written++
                        }
                        XmpMergeOutcome.Skip -> Unit
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    failed += photo to t
                }
            }
            onProgress(index + 1, total)
        }

        XmpReport(written = written, cleared = cleared, unsupported = unsupported, failed = failed)
    }
}

/** Resolves the singular XMP rating for a photo from its two built-in memberships (reject wins). */
fun decisionFor(isRejected: Boolean, isFavourite: Boolean): RatingDecision = when {
    isRejected -> RatingDecision.Rejected
    isFavourite -> RatingDecision.Favourite
    else -> RatingDecision.Undecided
}
