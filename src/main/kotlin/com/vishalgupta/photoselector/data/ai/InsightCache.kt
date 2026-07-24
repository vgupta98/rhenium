package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.data.io.AtomicJsonWriter
import com.vishalgupta.photoselector.domain.model.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * A persistent, content-keyed, size-capped on-disk cache of one per-photo insight scalar. The exact
 * shape and discipline of [EmbeddingCache] / [com.vishalgupta.photoselector.data.image.DiskThumbnailCache]:
 * a `(path|size|mtime|providerId|providerVersion|version)` SHA key (so a source edit, a provider swap or
 * a format bump all miss automatically), 256-way sharding, atomic writes, and best-effort eviction by
 * file mtime.
 *
 * Each entry is a tiny binary blob (a magic + version + one float), not JSON — a raw insight score is a
 * dense float. [providerId] + [providerVersion] fold into the key so two providers never read each
 * other's scores and a version bump re-keys automatically, exactly like [EmbeddingCache.modelId].
 */
class InsightCache(
    cacheDir: Path,
    private val providerId: String,
    private val providerVersion: Int,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val insightsDir = cacheDir.resolve("insights")

    fun startEviction(scope: CoroutineScope) {
        scope.launch { evict() }
    }

    /** The cached raw score for [photo], or null on a miss (or a corrupt entry, which self-heals). */
    fun get(photo: Photo): Float? {
        val file = cacheFileFor(photo)
        if (!file.exists()) return null
        return try {
            decode(Files.readAllBytes(file))
        } catch (_: Throwable) {
            file.deleteIfExists()
            null
        }
    }

    fun put(photo: Photo, value: Float) {
        try {
            AtomicJsonWriter.write(cacheFileFor(photo), encode(value))
        } catch (_: Throwable) {
            // Non-fatal — next session just recomputes this photo.
        }
    }

    private fun encode(value: Float): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES)
        buffer.putInt(MAGIC)
        buffer.putInt(FORMAT_VERSION)
        buffer.putFloat(value)
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): Float? {
        if (bytes.size != HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) return null
        if (buffer.int != FORMAT_VERSION) return null
        return buffer.float
    }

    private fun evict() {
        if (!insightsDir.exists()) return
        try {
            data class CacheFile(val path: Path, val size: Long, val lastModified: Long)

            val files = insightsDir.toFile().walkTopDown()
                .filter { it.isFile && it.extension == FILE_EXTENSION }
                .map { CacheFile(it.toPath(), it.length(), it.lastModified()) }
                .toMutableList()
            var remaining = files.sumOf { it.size }
            if (remaining <= maxBytes) return
            files.sortBy { it.lastModified }
            for (f in files) {
                if (remaining <= maxBytes) break
                f.path.deleteIfExists()
                remaining -= f.size
            }
        } catch (_: Throwable) {
            // Eviction is best-effort.
        }
    }

    private fun cacheFileFor(photo: Photo): Path {
        val input = "${photo.absolutePath}|${photo.sizeBytes}|${photo.lastModifiedEpochMs}|" +
            "$providerId|$providerVersion|$FORMAT_VERSION"
        val hash = sha256Hex(input)
        val shard = hash.substring(0, 2)
        return insightsDir.resolve(shard).resolve("$hash.$FILE_EXTENSION")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        // v1: initial on-disk shape (magic + version + one float). Bump if the stored shape changes.
        const val FORMAT_VERSION = 1
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
        private const val MAGIC = 0x50534931 // "PSI1"
        private const val FILE_EXTENSION = "ins"
        // magic + version + float
        private const val HEADER_BYTES = 2 * Int.SIZE_BYTES + Float.SIZE_BYTES
    }
}
