package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingCacheTest {

    private fun tempCache(modelId: String = "model-a"): Pair<EmbeddingCache, Path> {
        val dir = Files.createTempDirectory("embedding-cache-test")
        dir.toFile().deleteOnExit()
        return EmbeddingCache(cacheDir = dir, modelId = modelId) to dir
    }

    private fun photo(name: String, sizeBytes: Long = 1000L, mtime: Long = 1000L) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/photos/$name.jpg"),
        relativePath = "$name.jpg",
        fileName = "$name.jpg",
        sizeBytes = sizeBytes,
        lastModifiedEpochMs = mtime,
    )

    @Test fun `roundtrip preserves the embedding and sharpness`() {
        val (cache, _) = tempCache()
        val p = photo("test")
        assertNull(cache.get(p))

        cache.put(p, PhotoFeatures(floatArrayOf(0.1f, -0.2f, 0.3f), sharpness = 42.5f))
        val got = assertNotNull(cache.get(p))

        assertEquals(42.5f, got.sharpness)
        assertTrue(floatArrayOf(0.1f, -0.2f, 0.3f).contentEquals(got.embedding))
    }

    @Test fun `a different model never reads another model's vectors`() {
        val dir = Files.createTempDirectory("embedding-cache-test").also { it.toFile().deleteOnExit() }
        val p = photo("test")
        EmbeddingCache(dir, modelId = "model-a").put(p, PhotoFeatures(floatArrayOf(1f), 1f))

        assertNotNull(EmbeddingCache(dir, modelId = "model-a").get(p))
        assertNull(EmbeddingCache(dir, modelId = "model-b").get(p))
    }

    @Test fun `cache miss when source file metadata changes`() {
        val (cache, _) = tempCache()
        cache.put(photo("test", sizeBytes = 1000, mtime = 1000), PhotoFeatures(floatArrayOf(1f), 1f))

        assertNotNull(cache.get(photo("test", sizeBytes = 1000, mtime = 1000)))
        assertNull(cache.get(photo("test", sizeBytes = 1000, mtime = 2000)))
        assertNull(cache.get(photo("test", sizeBytes = 2000, mtime = 1000)))
    }

    /**
     * GOLDEN KEY - pinned deliberately. The exact on-disk path a known photo hashes to, so any change
     * to key composition, hashing, truncation or sharding shows up as a failing test rather than as
     * every user silently re-paying the minute-long cold embedding pass on their next launch.
     *
     * Derived from `sha256("<path>|<size>|<mtime>|<modelId>|<FORMAT_VERSION>")`, first 8 bytes, hex.
     * If this fails after a deliberate [EmbeddingCache.FORMAT_VERSION] bump, recompute and update it;
     * if it fails for any other reason, the change is a cache-invalidating bug.
     */
    @Test fun `cache file path is stable`() {
        val (cache, dir) = tempCache(modelId = "model-a")
        cache.put(photo("test", sizeBytes = 1000, mtime = 1000), PhotoFeatures(floatArrayOf(1f), 1f))

        val stored = Files.walk(dir).filter { Files.isRegularFile(it) }.toList()
        assertEquals(1, stored.size, "expected exactly one stored entry")
        // "/photos/test.jpg|1000|1000|model-a|3"
        assertEquals("embeddings/df/df2352c29931c4df.emb", dir.relativize(stored.single()).toString())
    }

    @Test fun `a corrupted entry returns null and is purged`() {
        val (cache, dir) = tempCache()
        val p = photo("test")
        cache.put(p, PhotoFeatures(floatArrayOf(1f, 2f), 1f))

        // Truncate the stored blob to garbage, then confirm the read self-heals.
        val embeddingsDir = dir.resolve("embeddings")
        var corrupted = false
        embeddingsDir.forEachDirectoryEntry { shard ->
            shard.forEachDirectoryEntry { file ->
                Files.write(file, byteArrayOf(1, 2, 3))
                corrupted = true
            }
        }
        assertTrue(corrupted, "expected a stored entry to corrupt")

        assertNull(cache.get(p))
        assertNull(cache.get(p)) // already deleted; still a clean miss
    }
}
