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

class InsightCacheTest {

    private fun tempCache(providerId: String = "sharpness", version: Int = 1): Pair<InsightCache, Path> {
        val dir = Files.createTempDirectory("insight-cache-test")
        dir.toFile().deleteOnExit()
        return InsightCache(cacheDir = dir, providerId = providerId, providerVersion = version) to dir
    }

    private fun photo(name: String, sizeBytes: Long = 1000L, mtime: Long = 1000L) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/photos/$name.jpg"),
        relativePath = "$name.jpg",
        fileName = "$name.jpg",
        sizeBytes = sizeBytes,
        lastModifiedEpochMs = mtime,
    )

    @Test fun `roundtrip preserves the score`() {
        val (cache, _) = tempCache()
        val p = photo("test")
        assertNull(cache.get(p))

        cache.put(p, 123.5f)
        assertEquals(123.5f, assertNotNull(cache.get(p)))
    }

    @Test fun `a different provider never reads another provider's score`() {
        val dir = Files.createTempDirectory("insight-cache-test").also { it.toFile().deleteOnExit() }
        val p = photo("test")
        InsightCache(dir, providerId = "sharpness", providerVersion = 1).put(p, 9f)

        assertNotNull(InsightCache(dir, providerId = "sharpness", providerVersion = 1).get(p))
        assertNull(InsightCache(dir, providerId = "faces", providerVersion = 1).get(p))
    }

    @Test fun `a provider version bump re-keys the entry`() {
        val dir = Files.createTempDirectory("insight-cache-test").also { it.toFile().deleteOnExit() }
        val p = photo("test")
        InsightCache(dir, providerId = "sharpness", providerVersion = 1).put(p, 9f)

        assertNotNull(InsightCache(dir, providerId = "sharpness", providerVersion = 1).get(p))
        assertNull(InsightCache(dir, providerId = "sharpness", providerVersion = 2).get(p))
    }

    @Test fun `cache miss when source file metadata changes`() {
        val (cache, _) = tempCache()
        cache.put(photo("test", sizeBytes = 1000, mtime = 1000), 7f)

        assertNotNull(cache.get(photo("test", sizeBytes = 1000, mtime = 1000)))
        assertNull(cache.get(photo("test", sizeBytes = 1000, mtime = 2000)))
        assertNull(cache.get(photo("test", sizeBytes = 2000, mtime = 1000)))
    }

    @Test fun `a corrupted entry returns null and is purged`() {
        val (cache, dir) = tempCache()
        val p = photo("test")
        cache.put(p, 5f)

        var corrupted = false
        dir.resolve("insights").forEachDirectoryEntry { shard ->
            shard.forEachDirectoryEntry { file ->
                Files.write(file, byteArrayOf(1, 2, 3))
                corrupted = true
            }
        }
        assertTrue(corrupted, "expected a stored entry to corrupt")

        assertNull(cache.get(p))
        assertNull(cache.get(p))
    }
}
