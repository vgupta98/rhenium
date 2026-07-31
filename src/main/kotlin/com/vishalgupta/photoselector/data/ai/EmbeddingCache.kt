package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.data.io.ShardedBlobCache
import com.vishalgupta.photoselector.domain.model.Photo
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.nio.file.Path

/**
 * A persistent, content-keyed, size-capped on-disk cache of per-photo [PhotoFeatures]. The storage
 * mechanics (hash → shard → atomic write, size-capped eviction) live in the shared
 * [ShardedBlobCache]; what stays here is this cache's own *key composition* and binary encoding.
 *
 * The key is `path|size|mtime|modelId|version`, so a source edit, a model swap or a format bump all
 * miss automatically. **Its exact byte shape is load-bearing** — see the golden-key test in
 * `EmbeddingCacheTest`; a "tidy-up" here re-charges every user the minute-long cold embedding pass.
 *
 * Embedding a folder of photos is the feature's one expensive step; caching it is what lets the
 * cost be paid once and survive a restart. Entries are tiny (a few KB), so the cap is generous.
 *
 * [modelId] is the producing model's identity; it is folded into the key so two models never read
 * each other's vectors. Each entry is a small binary blob, not JSON — vectors are dense floats.
 */
class EmbeddingCache(
    cacheDir: Path,
    private val modelId: String,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val blobs = ShardedBlobCache(
        cacheDir = cacheDir,
        directoryName = "embeddings",
        fileExtension = FILE_EXTENSION,
        maxBytes = maxBytes,
    )

    fun startEviction(scope: CoroutineScope) {
        blobs.startEviction(scope)
    }

    fun get(photo: Photo): PhotoFeatures? = blobs.read(keyFor(photo)) { decode(it) }

    fun put(photo: Photo, features: PhotoFeatures) {
        blobs.write(keyFor(photo), encode(features))
    }

    private fun keyFor(photo: Photo): String =
        "${photo.absolutePath}|${photo.sizeBytes}|${photo.lastModifiedEpochMs}|$modelId|$FORMAT_VERSION"

    private fun encode(features: PhotoFeatures): ByteArray {
        val dims = features.embedding.size
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dims * Float.SIZE_BYTES)
        buffer.putInt(MAGIC)
        buffer.putInt(FORMAT_VERSION)
        buffer.putInt(dims)
        buffer.putFloat(features.sharpness)
        for (v in features.embedding) buffer.putFloat(v)
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): PhotoFeatures? {
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) return null
        if (buffer.int != FORMAT_VERSION) return null
        val dims = buffer.int
        if (dims <= 0 || dims > MAX_DIMENSIONS) return null
        if (bytes.size != HEADER_BYTES + dims * Float.SIZE_BYTES) return null
        val sharpness = buffer.float
        val embedding = FloatArray(dims) { buffer.float }
        return PhotoFeatures(embedding = embedding, sharpness = sharpness)
    }

    companion object {
        // v3: sharpness is scored on a fixed 768px canonical canvas (large frames decoded down,
        // small frames scaled up), so previously-cached sharpness values are stale and must
        // recompute. (v2 moved sharpness off the 224px embedding decode onto a higher-res one.)
        const val FORMAT_VERSION = 3
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024
        private const val MAGIC = 0x50534531 // "PSE1"
        private const val FILE_EXTENSION = "emb"
        private const val MAX_DIMENSIONS = 1 shl 16
        // magic + version + dims + sharpness
        private const val HEADER_BYTES = 4 * Int.SIZE_BYTES
    }
}
