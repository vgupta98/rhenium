package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.domain.faces.FaceEmbedding
import com.vishalgupta.photoselector.domain.faces.PhotoFaces
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.testing.FakeFaceDetector
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceCacheTest {

    private fun tempCache(
        detectorId: String = "det-a",
        embedderId: String = "emb-a",
    ): Pair<FaceCache, Path> {
        val dir = Files.createTempDirectory("face-cache-test").also { it.toFile().deleteOnExit() }
        return FaceCache(cacheDir = dir, detectorId = detectorId, embedderId = embedderId) to dir
    }

    private fun photo(name: String, sizeBytes: Long = 1000L, mtime: Long = 1000L) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/photos/$name.jpg"),
        relativePath = "$name.jpg",
        fileName = "$name.jpg",
        sizeBytes = sizeBytes,
        lastModifiedEpochMs = mtime,
    )

    private fun faces(count: Int = 2) = PhotoFaces(
        detections = List(count) { FakeFaceDetector.detection(score = 0.5f + it * 0.1f) },
        embeddings = List(count) { FaceEmbedding(floatArrayOf(it.toFloat(), 1f, -0.5f)) },
    )

    @Test fun `roundtrip preserves detections and embeddings`() {
        val (cache, _) = tempCache()
        val p = photo("test")
        assertNull(cache.get(p))

        cache.put(p, faces(count = 2))
        val got = assertNotNull(cache.get(p))

        assertEquals(2, got.detections.size)
        assertEquals(0.6f, got.detections[1].score)
        assertEquals(0.25f, got.detections[0].box.x)
        assertEquals(5, got.detections[0].landmarks.size)
        assertEquals(0.3f, got.detections[0].landmarks[0].x)
        assertTrue(floatArrayOf(1f, 1f, -0.5f).contentEquals(got.embeddings[1].values))
    }

    @Test fun `a photo with no faces is cached as a real answer`() {
        val (cache, _) = tempCache()
        val p = photo("empty")

        cache.put(p, PhotoFaces.EMPTY)

        val got = assertNotNull(cache.get(p), "an empty result must be a hit, not a miss")
        assertTrue(got.detections.isEmpty())
    }

    @Test fun `cache miss when source file metadata changes`() {
        val (cache, _) = tempCache()
        cache.put(photo("test", sizeBytes = 1000, mtime = 1000), faces())

        assertNotNull(cache.get(photo("test", sizeBytes = 1000, mtime = 1000)))
        assertNull(cache.get(photo("test", sizeBytes = 1000, mtime = 2000)))
        assertNull(cache.get(photo("test", sizeBytes = 2000, mtime = 1000)))
    }

    @Test fun `swapping either model re-keys the cache`() {
        val dir = Files.createTempDirectory("face-cache-test").also { it.toFile().deleteOnExit() }
        val p = photo("test")
        FaceCache(dir, detectorId = "det-a", embedderId = "emb-a").put(p, faces())

        assertNotNull(FaceCache(dir, "det-a", "emb-a").get(p))
        assertNull(FaceCache(dir, "det-b", "emb-a").get(p), "a detector swap must miss")
        assertNull(FaceCache(dir, "det-a", "emb-b").get(p), "an embedder swap must miss")
    }

    @Test fun `an entry written by another format version is a clean miss`() {
        val (cache, dir) = tempCache()
        val p = photo("test")
        cache.put(p, faces())

        // Doctor the stored version field (bytes 4..8) to simulate a FORMAT_VERSION bump.
        var patched = false
        dir.resolve("faces").forEachDirectoryEntry { shard ->
            shard.forEachDirectoryEntry { file ->
                val bytes = Files.readAllBytes(file)
                ByteBuffer.wrap(bytes).putInt(4, FaceCache.FORMAT_VERSION + 1)
                Files.write(file, bytes)
                patched = true
            }
        }
        assertTrue(patched, "expected a stored entry to patch")

        assertNull(cache.get(p))
    }

    @Test fun `a corrupted entry returns null and is purged`() {
        val (cache, dir) = tempCache()
        val p = photo("test")
        cache.put(p, faces())

        var corrupted = false
        dir.resolve("faces").forEachDirectoryEntry { shard ->
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
