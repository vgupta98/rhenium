package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.data.io.ShardedBlobCache
import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.faces.FaceDetection
import com.vishalgupta.photoselector.domain.faces.FaceEmbedding
import com.vishalgupta.photoselector.domain.faces.FacePoint
import com.vishalgupta.photoselector.domain.faces.PhotoFaces
import com.vishalgupta.photoselector.domain.model.Photo
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.nio.file.Path

/**
 * A persistent, content-keyed, size-capped on-disk cache of one photo's [PhotoFaces] — its
 * detections and their embeddings. The third consumer of [ShardedBlobCache]; like its two siblings
 * it owns only its own key composition and binary encoding.
 *
 * The key is `path|size|mtime|detectorId|embedderId|version`: a source edit, a swap of *either*
 * model, or a format bump all miss automatically. Both model ids are in the key because a photo's
 * entry holds output from both, and a detector change invalidates the embeddings that were computed
 * from its boxes just as surely as an embedder change does.
 *
 * Detecting and embedding a folder of faces is the pipeline's one expensive step; caching it is what
 * makes a rescan (or a relaunch) cheap. Entries are a few KB — five 128-float vectors is 2.5 KB — so
 * the cap is generous.
 *
 * A photo with **no** faces is cached as an empty entry, on purpose: "we looked and there was nobody"
 * is a real, reusable result. Only an inference *failure* goes uncached, so a transient hiccup
 * retries next pass rather than writing the photo out of the index for the life of the file.
 */
class FaceCache(
    cacheDir: Path,
    private val detectorId: String,
    private val embedderId: String,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val blobs = ShardedBlobCache(
        cacheDir = cacheDir,
        directoryName = "faces",
        fileExtension = FILE_EXTENSION,
        maxBytes = maxBytes,
    )

    fun startEviction(scope: CoroutineScope) {
        blobs.startEviction(scope)
    }

    fun get(photo: Photo): PhotoFaces? = blobs.read(keyFor(photo)) { decode(it) }

    fun put(photo: Photo, faces: PhotoFaces) {
        blobs.write(keyFor(photo), encode(faces))
    }

    private fun keyFor(photo: Photo): String =
        "${photo.absolutePath}|${photo.sizeBytes}|${photo.lastModifiedEpochMs}|" +
            "$detectorId|$embedderId|$FORMAT_VERSION"

    /**
     * `magic | version | dims | faceCount` then, per face:
     * `score | box(4) | landmarks(10) | embedding(dims)` — all little-endian-agnostic Java floats.
     * A photo with no faces stores `dims = 0`.
     */
    private fun encode(faces: PhotoFaces): ByteArray {
        val dims = faces.embeddings.firstOrNull()?.dimensions ?: 0
        val perFace = (1 + 4 + 2 * FaceDetection.LANDMARK_COUNT + dims) * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocate(HEADER_BYTES + faces.detections.size * perFace)
        buffer.putInt(MAGIC)
        buffer.putInt(FORMAT_VERSION)
        buffer.putInt(dims)
        buffer.putInt(faces.detections.size)
        for (i in faces.detections.indices) {
            val d = faces.detections[i]
            buffer.putFloat(d.score)
            buffer.putFloat(d.box.x)
            buffer.putFloat(d.box.y)
            buffer.putFloat(d.box.width)
            buffer.putFloat(d.box.height)
            for (p in d.landmarks) {
                buffer.putFloat(p.x)
                buffer.putFloat(p.y)
            }
            for (v in faces.embeddings[i].values) buffer.putFloat(v)
        }
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): PhotoFaces? {
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) return null
        if (buffer.int != FORMAT_VERSION) return null
        val dims = buffer.int
        val count = buffer.int
        if (dims < 0 || dims > MAX_DIMENSIONS || count < 0 || count > MAX_FACES) return null
        val perFace = (1 + 4 + 2 * FaceDetection.LANDMARK_COUNT + dims) * Float.SIZE_BYTES
        if (bytes.size != HEADER_BYTES + count * perFace) return null

        val detections = ArrayList<FaceDetection>(count)
        val embeddings = ArrayList<FaceEmbedding>(count)
        repeat(count) {
            val score = buffer.float
            val box = FaceBox(buffer.float, buffer.float, buffer.float, buffer.float)
            val landmarks = List(FaceDetection.LANDMARK_COUNT) { FacePoint(buffer.float, buffer.float) }
            detections += FaceDetection(box = box, landmarks = landmarks, score = score)
            embeddings += FaceEmbedding(FloatArray(dims) { buffer.float })
        }
        return PhotoFaces(detections, embeddings)
    }

    companion object {
        /**
         * Bump when the stored shape changes **or** when detection/alignment/embedding logic changes
         * in a way the model ids don't already capture (the cache holds those steps' output).
         * v1: initial.
         */
        const val FORMAT_VERSION = 1
        const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024
        private const val MAGIC = 0x50534631 // "PSF1"
        private const val FILE_EXTENSION = "fce"
        private const val MAX_DIMENSIONS = 1 shl 16
        private const val MAX_FACES = 4096
        // magic + version + dims + faceCount
        private const val HEADER_BYTES = 4 * Int.SIZE_BYTES
    }
}
