package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.domain.faces.FaceAlignment
import com.vishalgupta.photoselector.domain.faces.FaceClusterer
import com.vishalgupta.photoselector.domain.faces.FaceDetection
import com.vishalgupta.photoselector.domain.faces.FaceDetector
import com.vishalgupta.photoselector.domain.faces.FaceEmbedder
import com.vishalgupta.photoselector.domain.faces.FaceEmbedding
import com.vishalgupta.photoselector.domain.faces.FaceId
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.faces.PhotoFaces
import com.vishalgupta.photoselector.domain.model.DecodedImage
import com.vishalgupta.photoselector.domain.model.Photo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** `(processed, total)`. Same shape as `GroupingProgress`, so a coordinator drives either pass alike. */
typealias FaceScanProgress = (processed: Int, total: Int) -> Unit

/**
 * The whole-root face pass: decode -> detect -> align -> embed each photo, then cluster every face
 * into [Person]s. The data-layer half of the face pipeline — it owns the expensive decode/inference;
 * detection decoding, alignment and clustering all stay pure in `domain/faces`.
 *
 * Structured exactly like `SimilarityPhotoGrouper.group`, deliberately: a [Semaphore] of width
 * [concurrency] caps how many frames are in flight (a memory/CPU ceiling — each decodes a
 * detector-sized canvas and runs two inferences), every task is a child of one `coroutineScope` so a
 * cancelled scan tears the whole fan-out down structurally, each task `ensureActive()`s before doing
 * work, an [AtomicInteger] drives progress (tasks finish out of order) and results land in a
 * [ConcurrentHashMap]. The clustering step is order-independent, so the result matches a sequential
 * pass. The progress callback carries the same `(processed, total)` shape as the grouping pass, so
 * a follow-up coordinator drops straight on.
 *
 * Per-photo results are memoised in [cache], so a rescan after adding a folder only pays for the new
 * photos. A photo whose *inference* failed is not cached (it retries next pass); a photo with no
 * faces is (that's a real answer).
 *
 * PII: never log a person's name or write a face crop to disk. Only vectors are persisted.
 */
class FaceScanner(
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val cache: FaceCache,
    private val decode: suspend (Photo) -> DecodedImage?,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val rule: FaceClusterer.ThresholdRule = FaceClusterer.fixed(),
    private val newPersonId: () -> PersonId = { PersonId(UUID.randomUUID().toString()) },
) {

    /**
     * Scans [photos] and returns the people found. [known] is the previous result — its **named**
     * entries keep their identity through the recluster (see [FaceClusterer.cluster]).
     */
    suspend fun scan(
        photos: List<Photo>,
        known: List<Person> = emptyList(),
        onProgress: FaceScanProgress = { _, _ -> },
    ): List<Person> {
        if (photos.isEmpty()) return known.filter { it.isNamed }.map { it.copy(faces = emptyList()) }
        val total = photos.size
        val perPhoto = ConcurrentHashMap<Photo, PhotoFaces>(total)
        val processed = AtomicInteger(0)
        val gate = Semaphore(concurrency.coerceAtLeast(1))

        coroutineScope {
            photos.forEach { photo ->
                launch {
                    gate.withPermit {
                        ensureActive()
                        facesFor(photo)?.let { perPhoto[photo] = it }
                    }
                    onProgress(processed.incrementAndGet(), total)
                }
            }
        }

        // coroutineScope joined every task. Rebuild the face list in *photo order* so face ids (and
        // therefore an unnamed cluster's contents) are deterministic for a given photo set.
        val faces = ArrayList<FaceId>()
        val embeddings = HashMap<FaceId, FaceEmbedding>()
        for (photo in photos) {
            val found = perPhoto[photo] ?: continue
            found.detections.indices.forEach { i ->
                val id = FaceId(photo.id, i)
                faces += id
                embeddings[id] = found.embeddings[i]
            }
        }
        return FaceClusterer.cluster(
            faces = faces,
            embeddings = embeddings,
            known = known,
            rule = rule,
            newPersonId = newPersonId,
        )
    }

    /** One photo's faces, from cache or freshly computed. Null when detection failed (so it isn't cached). */
    private suspend fun facesFor(photo: Photo): PhotoFaces? {
        cache.get(photo)?.let { return it }
        val image = decode(photo) ?: return null
        val detections = detector.detect(image) ?: return null
        if (detections.isEmpty()) {
            cache.put(photo, PhotoFaces.EMPTY)
            return PhotoFaces.EMPTY
        }
        val kept = ArrayList<FaceDetection>(detections.size)
        val vectors = ArrayList<FaceEmbedding>(detections.size)
        for (detection in detections) {
            // A face whose embedding failed is dropped rather than stored without a vector - an
            // un-embeddable face can't be clustered, and a half-face entry would confuse the cache.
            val vector = embedder.embed(FaceAlignment.alignedCrop(image, detection)) ?: continue
            kept += detection
            vectors += vector
        }
        val faces = PhotoFaces(kept, vectors)
        cache.put(photo, faces)
        return faces
    }

    private companion object {
        // Standalone default (tests, direct construction). Production wires the same width AppContainer
        // sizes the decode pool with, matching the Similarity pass.
        const val DEFAULT_CONCURRENCY = 4
    }
}
