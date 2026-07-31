package com.vishalgupta.photoselector.data.faces

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vishalgupta.photoselector.domain.faces.FaceAlignment
import com.vishalgupta.photoselector.domain.faces.FaceEmbedding
import com.vishalgupta.photoselector.domain.faces.FaceEmbedder
import com.vishalgupta.photoselector.domain.model.DecodedImage
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * The shipped [FaceEmbedder]: SFace (int8-quantised) run through ONNX Runtime, turning an aligned
 * 112x112 face crop into a comparable vector. Same shape as `OnnxEmbeddingModel` and
 * [OnnxFaceDetector] — private constructor plus `object Loader.fromResource()`, an [id] folded into
 * cache keys, [dimensions] **probed from the graph** at load, and `null` (never a throw) on failure.
 *
 * ## Input contract
 * `[1, 3, 112, 112]`, RGB, raw 0..255 with no mean subtraction — OpenCV builds SFace's blob with
 * `blobFromImage(aligned, 1, Size(112,112), Scalar(0,0,0), swapRB = true, crop = false)`, and the
 * app's decoded images are BGRA, so the channel order is reversed on the way in. The crop must come
 * from [FaceAlignment.alignedCrop]; a raw bounding-box crop measurably degrades the embedding.
 *
 * The output is L2-normalised here (OpenCV normalises inside `match`), so a cosine similarity is a
 * plain dot product for every consumer — the same convention `EmbeddingModel` follows.
 */
class OnnxFaceEmbedder private constructor(
    override val id: String,
    override val dimensions: Int,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
) : FaceEmbedder, AutoCloseable {

    override fun embed(alignedFace: DecodedImage): FaceEmbedding? = try {
        if (alignedFace.width != EDGE || alignedFace.height != EDGE) {
            System.err.println("OnnxFaceEmbedder.embed got a ${alignedFace.width}x${alignedFace.height} crop, expected ${EDGE}x$EDGE")
            null
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(preprocess(alignedFace)), INPUT_SHAPE).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    FaceEmbedding(normalizeL2((result[0].value as Array<FloatArray>)[0]))
                }
            }
        }
    } catch (t: Throwable) {
        System.err.println("OnnxFaceEmbedder.embed failed, returning null (not cached): ${t.message}")
        null
    }

    /** BGRA -> planar RGB float CHW, values kept in 0..255 (SFace applies no normalisation). */
    private fun preprocess(image: DecodedImage): FloatArray {
        val out = FloatArray(3 * EDGE * EDGE)
        val plane = EDGE * EDGE
        val src = image.bgraBytes
        for (i in 0 until plane) {
            val p = i * 4
            // Channel offset within the source pixel: B=0, G=1, R=2. Output planes are R, G, B.
            out[i] = (src[p + 2].toInt() and 0xFF).toFloat()
            out[plane + i] = (src[p + 1].toInt() and 0xFF).toFloat()
            out[2 * plane + i] = (src[p].toInt() and 0xFF).toFloat()
        }
        return out
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val EDGE = FaceAlignment.TEMPLATE_EDGE
        const val DEFAULT_RESOURCE = "/models/face-embed-sface.onnx"

        // Bump whenever the blob or this class's preprocessing changes, so FaceCache re-keys.
        const val DEFAULT_ID = "sface-2021dec-int8-v1"

        val INPUT_SHAPE = longArrayOf(1, 3, EDGE.toLong(), EDGE.toLong())

        fun normalizeL2(vec: FloatArray): FloatArray {
            var norm = 0f
            for (v in vec) norm += v * v
            norm = sqrt(norm)
            if (norm > 1e-6f) for (i in vec.indices) vec[i] /= norm
            return vec
        }
    }

    /**
     * Loads the bundled SFace blob off the classpath and opens a session. Throws if the resource is
     * missing or the model fails to load; the caller treats that as "face scanning unavailable".
     */
    object Loader {
        fun fromResource(
            resourcePath: String = DEFAULT_RESOURCE,
            id: String = DEFAULT_ID,
        ): OnnxFaceEmbedder {
            val bytes = OnnxFaceEmbedder::class.java.getResourceAsStream(resourcePath)
                ?.use { it.readBytes() }
                ?: error("Face recognition model resource not found on classpath: $resourcePath")

            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(bytes, OrtSession.SessionOptions())
            val inputName = session.inputNames.first()
            return OnnxFaceEmbedder(id, probeDimensions(env, session, inputName), env, session, inputName)
        }

        // One zero-input run reports the true feature width, so a model swap needs no caller change.
        private fun probeDimensions(env: OrtEnvironment, session: OrtSession, inputName: String): Int {
            val zeros = FloatArray(3 * EDGE * EDGE)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(zeros), INPUT_SHAPE).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    return (result[0].value as Array<FloatArray>)[0].size
                }
            }
        }
    }
}
