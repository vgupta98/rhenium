package com.vishalgupta.photoselector.data.export

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.ConflictPolicy
import com.vishalgupta.photoselector.domain.repository.CopyReport
import com.vishalgupta.photoselector.domain.repository.PhotoExporter
import com.vishalgupta.photoselector.domain.repository.XmpReport
import java.nio.file.Path

class CompositePhotoExporter(
    private val txt: TxtPhotoExporter,
    private val copy: CopyPhotoExporter,
    private val xmp: XmpSidecarPhotoExporter,
) : PhotoExporter {
    override suspend fun exportTxt(
        root: RootFolder,
        favourites: List<Photo>,
        destinationTxt: Path,
    ) {
        txt.export(root, favourites, destinationTxt)
    }

    override suspend fun copyToFolder(
        root: RootFolder,
        favourites: List<Photo>,
        destDir: Path,
        policy: ConflictPolicy,
        onProgress: (copied: Int, total: Int) -> Unit,
    ): CopyReport = copy.copy(root, favourites, destDir, policy, onProgress)

    override suspend fun exportXmpSidecars(
        root: RootFolder,
        photos: List<Photo>,
        favouriteIds: Set<PhotoId>,
        rejectedIds: Set<PhotoId>,
        onProgress: (written: Int, total: Int) -> Unit,
    ): XmpReport = xmp.exportSidecars(root, photos, favouriteIds, rejectedIds, onProgress)
}
