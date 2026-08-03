package com.vishalgupta.photoselector.data.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * The shared *mechanics* of the app's content-keyed on-disk caches: hash a key to a file path,
 * 256-way shard it, read/write it atomically, and evict oldest-first under a size cap. Three caches
 * now sit on it — `EmbeddingCache`, `GroupingResultCache` and `FaceCache` — which had grown three
 * copies of the same twenty lines.
 *
 * ## What this deliberately does NOT own
 * **Key composition stays with each cache.** The caches key on different things in different shapes
 * (`path|size|mtime|modelId|version` for one photo's embedding; a newline-joined fingerprint of a
 * whole photo *set* for a grouping), and folding those into a shared "tidy" composer would change
 * the resulting bytes — silently invalidating every user's cache and re-charging them the
 * minute-long cold Similarity pass on next launch. This class takes an **already-composed key
 * string** and never inspects it.
 *
 * The hash is deliberately truncated to the first 8 bytes of SHA-256 (16 hex chars): plenty against
 * accidental collision at these cache sizes, and short enough to keep paths tidy. The first two hex
 * chars are the shard directory. Both are load-bearing — the golden-key tests in
 * `EmbeddingCacheTest` / `GroupingResultCacheTest` pin the exact resulting path.
 *
 * Keys are hashed as **explicit UTF-8**, not the platform default charset.
 *
 * A read never touches mtime, so eviction is least-recently-*written* rather than strictly LRU —
 * acceptable at these entry sizes and consistent with `DiskThumbnailCache`.
 */
class ShardedBlobCache(
    cacheDir: Path,
    directoryName: String,
    private val fileExtension: String,
    private val maxBytes: Long,
) {
    private val root: Path = cacheDir.resolve(directoryName)

    /** Best-effort size-cap eviction, once, off the caller's thread. */
    fun startEviction(scope: CoroutineScope) {
        scope.launch { evict() }
    }

    /**
     * Reads the entry for [key] and hands its bytes to [decode]. Null on a miss, on a `null` from
     * [decode] (a recognised-but-unusable entry, e.g. a version mismatch), or on a decode failure —
     * and a *failure* additionally purges the entry so the next read is a clean miss.
     */
    fun <T> read(key: String, decode: (ByteArray) -> T?): T? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            decode(Files.readAllBytes(file))
        } catch (_: Throwable) {
            file.deleteIfExists()
            null
        }
    }

    /** Atomically stores [bytes] under [key]. Best-effort: a failure just means a recompute next time. */
    fun write(key: String, bytes: ByteArray) {
        try {
            AtomicJsonWriter.write(fileFor(key), bytes)
        } catch (_: Throwable) {
            // Non-fatal by design - the cache is an optimisation, never a source of truth.
        }
    }

    /**
     * Deletes every entry, shards and all — the "forget this whole cache" primitive a user-facing
     * purge needs. Deliberately all-or-nothing: entries are keyed by a hash of an already-composed
     * key string this class never inspects, so there is no way to select the subset belonging to one
     * root (or one model). Best-effort, like every other write path here; a file that resists
     * deletion just costs a stale entry, never an error.
     */
    fun clear() {
        if (!root.exists()) return
        try {
            root.toFile().walkBottomUp().forEach { it.delete() }
        } catch (_: Throwable) {
            // Same posture as eviction: the cache is an optimisation, never a source of truth.
        }
    }

    /** The on-disk path [key] maps to. Exposed for tests that pin the layout. */
    fun fileFor(key: String): Path {
        val hash = sha256Hex(key)
        return root.resolve(hash.substring(0, 2)).resolve("$hash.$fileExtension")
    }

    private fun evict() {
        if (!root.exists()) return
        try {
            data class CacheFile(val path: Path, val size: Long, val lastModified: Long)

            val files = root.toFile().walkTopDown()
                .filter { it.isFile && it.extension == fileExtension }
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

    private companion object {
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
