package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupingResultCacheTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun tempCache(): Pair<GroupingResultCache, Path> {
        val dir = Files.createTempDirectory("grouping-cache-test").also { it.toFile().deleteOnExit() }
        return GroupingResultCache(cacheDir = dir, json = json) to dir
    }

    private fun photo(name: String, sizeBytes: Long = 1000L, mtime: Long = 1000L) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/photos/$name.jpg"),
        relativePath = "$name.jpg",
        fileName = "$name.jpg",
        sizeBytes = sizeBytes,
        lastModifiedEpochMs = mtime,
    )

    // a | [b c d] burst (key c) | e
    private fun photosAndGroups(): Pair<List<Photo>, List<PhotoGroup>> {
        val photos = listOf("a", "b", "c", "d", "e").map { photo(it) }
        val groups = listOf(
            PhotoGroup.Single(photos[0]),
            PhotoGroup.Burst(photos.subList(1, 4), keyIndex = 1),
            PhotoGroup.Single(photos[4]),
        )
        return photos to groups
    }

    @Test fun `roundtrip reconstructs groups against the live photos`() {
        val (cache, _) = tempCache()
        val (photos, groups) = photosAndGroups()
        val key = cache.keyFor("model-a", photos)
        assertNull(cache.get(key, photos))

        cache.put(key, groups)
        val got = assertNotNull(cache.get(key, photos))

        assertEquals(3, got.size)
        val burst = got[1] as PhotoGroup.Burst
        assertEquals(listOf("b", "c", "d"), burst.photos.map { it.id.value })
        assertEquals(1, burst.keyIndex)
        // Reconstructed from the SAME Photo instances we passed in.
        assertTrue(burst.photos[0] === photos[1])
    }

    @Test fun `a different model id never reads another model's grouping`() {
        val (cache, _) = tempCache()
        val (photos, groups) = photosAndGroups()
        cache.put(cache.keyFor("model-a", photos), groups)

        assertNotNull(cache.get(cache.keyFor("model-a", photos), photos))
        assertNull(cache.get(cache.keyFor("model-b", photos), photos))
    }

    @Test fun `editing any source file misses the cache`() {
        val (cache, _) = tempCache()
        val (photos, groups) = photosAndGroups()
        cache.put(cache.keyFor("model-a", photos), groups)

        // Same set, but one frame's mtime changed -> different fingerprint -> miss.
        val edited = photos.toMutableList().also { it[2] = photo("c", mtime = 9999L) }
        assertNull(cache.get(cache.keyFor("model-a", edited), edited))
        // Adding/removing a frame also re-keys.
        assertNull(cache.get(cache.keyFor("model-a", photos.dropLast(1)), photos.dropLast(1)))
    }

    /**
     * GOLDEN KEY - pinned deliberately, twin of `EmbeddingCacheTest`'s. Note the key *composition*
     * here differs from the embedding cache's (newline-joined fingerprint, model id first); both
     * shapes must stay byte-identical or every user re-pays the cold Similarity pass.
     *
     * Derived from `sha256("<modelId>\n<FORMAT_VERSION>" + "\n<path>|<size>|<mtime>" * n)`, first 8
     * bytes, hex. A deliberate [GroupingResultCache.FORMAT_VERSION] bump means recomputing this;
     * any other cause of failure is a cache-invalidating bug.
     */
    @Test fun `cache file path is stable`() {
        val (cache, dir) = tempCache()
        val (photos, groups) = photosAndGroups()
        cache.put(cache.keyFor("model-a", photos.take(3)), groups.take(1))

        val stored = Files.walk(dir).filter { Files.isRegularFile(it) }.toList()
        assertEquals(1, stored.size, "expected exactly one stored entry")
        // "model-a\n5\n/photos/a.jpg|1000|1000\n/photos/b.jpg|1000|1000\n/photos/c.jpg|1000|1000"
        assertEquals("groupings/b3/b38e6f12a67bf29a.grp", dir.relativize(stored.single()).toString())
    }

    @Test fun `a corrupt entry is a clean miss`() {
        val (cache, dir) = tempCache()
        val (photos, groups) = photosAndGroups()
        val key = cache.keyFor("model-a", photos)
        cache.put(key, groups)

        var corrupted = false
        dir.resolve("groupings").forEachDirectoryEntry { shard ->
            shard.forEachDirectoryEntry { file ->
                Files.write(file, byteArrayOf(1, 2, 3))
                corrupted = true
            }
        }
        assertTrue(corrupted, "expected a stored entry to corrupt")
        assertNull(cache.get(key, photos))
    }

    private fun <T> assertNotNull(value: T?): T = value ?: error("expected non-null")
}
