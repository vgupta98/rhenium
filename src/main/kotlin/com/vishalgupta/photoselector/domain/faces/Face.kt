package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.PhotoId

/**
 * Identity of one detected face: the photo it was found in plus its index within that photo's
 * detection list (detections are emitted in a deterministic order, so the pair is stable for as long
 * as the photo's bytes are). Not a value class — the [photo] half is read constantly (a person's
 * photo set is derived from it), and a packed string id would have to be parsed back apart.
 *
 * A face id is *derived*, never user-owned: an edit to the underlying file re-detects and re-numbers.
 * The durable thing a user creates is the [Person] name, which survives a rescan by centroid
 * re-match (see [FaceClusterer.cluster]) rather than by face id.
 */
data class FaceId(val photo: PhotoId, val index: Int)

/**
 * A face as a [Person] carries it: its [id] plus enough of the detection to *draw* it — the
 * normalised [box] and the detector's [score].
 *
 * The box rides here (and into the people sidecar) rather than being looked up in the face cache on
 * demand, because composing that cache's key needs both model ids, which would force the ONNX
 * sessions open just to render a crop — and the cache is size-capped, so an old entry may simply be
 * gone. Both are null/zero for a face read from a sidecar written before boxes were stored; a UI
 * showing crops filters on [box] rather than assuming one.
 */
data class FaceRef(val id: FaceId, val box: FaceBox? = null, val score: Float = 0f)

/** A point in an image, normalised to `0..1` of the image's width/height. */
data class FacePoint(val x: Float, val y: Float)

/**
 * An axis-aligned face box, normalised to `0..1` of the source image (so it survives any later
 * decode at a different resolution). [x]/[y] are the top-left corner.
 */
data class FaceBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

/**
 * One face found in a photo by a [FaceDetector]: where it is, its five landmarks, and how confident
 * the detector is. Coordinates are normalised to the source image, never pixels, so a detection
 * cached against a 640px decode still aligns a crop taken from the full-resolution file.
 *
 * [landmarks] is always five points in YuNet/SFace order — right eye, left eye, nose tip, right mouth
 * corner, left mouth corner ("right" meaning the subject's right, i.e. image-left). That order is
 * what [FaceAlignment]'s canonical template is expressed in; do not reorder it.
 */
data class FaceDetection(
    val box: FaceBox,
    val landmarks: List<FacePoint>,
    val score: Float,
) {
    init {
        require(landmarks.size == LANDMARK_COUNT) {
            "a face detection carries exactly $LANDMARK_COUNT landmarks, got ${landmarks.size}"
        }
    }

    companion object {
        const val LANDMARK_COUNT = 5
    }
}

/**
 * A face's L2-normalised recognition vector. Wraps the raw floats so a face embedding can never be
 * confused with a *photo* embedding (`data/ai`'s visual-similarity vector) — they come from different
 * models and are meaningless to each other.
 *
 * **Equality is array identity.** A value class delegates `equals` to its underlying type, and
 * `FloatArray.equals` is referential; compare embeddings with [cosineSimilarityTo], never `==`.
 *
 * Both this and every consumer assume the vector is already L2-normalised (the embedders normalise
 * on the way out), so cosine similarity is a plain dot product.
 */
@JvmInline
value class FaceEmbedding(val values: FloatArray) {
    val dimensions: Int get() = values.size

    /** Cosine similarity in `-1..1`; `1` is identical. Returns `0` for mismatched widths. */
    fun cosineSimilarityTo(other: FaceEmbedding): Float {
        if (values.size != other.values.size) return 0f
        var dot = 0f
        for (i in values.indices) dot += values[i] * other.values[i]
        return dot
    }

    /** Cosine *distance* in `0..2` — what [FaceClusterer] links on. */
    fun cosineDistanceTo(other: FaceEmbedding): Float = 1f - cosineSimilarityTo(other)
}

/** Everything one photo contributed to the face index: its detections and their embeddings, aligned by index. */
data class PhotoFaces(
    val detections: List<FaceDetection>,
    val embeddings: List<FaceEmbedding>,
) {
    init {
        require(detections.size == embeddings.size) {
            "detections and embeddings must be parallel, got ${detections.size} vs ${embeddings.size}"
        }
    }

    companion object {
        val EMPTY = PhotoFaces(emptyList(), emptyList())
    }
}
