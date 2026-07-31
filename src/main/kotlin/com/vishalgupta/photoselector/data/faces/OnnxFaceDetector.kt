package com.vishalgupta.photoselector.data.faces

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vishalgupta.photoselector.domain.faces.FaceDetection
import com.vishalgupta.photoselector.domain.faces.FaceDetector
import com.vishalgupta.photoselector.domain.faces.YuNetPostProcessing
import com.vishalgupta.photoselector.domain.model.DecodedImage
import java.nio.FloatBuffer

/**
 * The shipped [FaceDetector]: YuNet (libfacedetection) run through ONNX Runtime. Same shape as
 * `OnnxEmbeddingModel` — private constructor plus an `object Loader.fromResource()`, an [id] folded
 * into cache keys, and inference that returns `null` instead of throwing so one bad frame can never
 * take down a whole-root scan.
 *
 * The blob ships as a classpath resource (see `tools/face-models/`); nothing is downloaded and no
 * pixels leave the machine. Everything *decidable without a model* lives in the pure
 * [YuNetPostProcessing] — the letterbox in, the head decode and the letterbox out — leaving this
 * class with only the session, the tensor and the output-name plumbing.
 *
 * ## Input contract
 * The graph has a **fixed** `[1, 3, 640, 640]` input (probed at load), fed raw BGR 0..255 with no
 * mean subtraction or scaling (`blobFromImage` defaults, `swapRB=false`), aspect-preserved via
 * [YuNetPostProcessing.letterbox].
 *
 * Holds a native ONNX Runtime session; [close] releases it. In production it is app-lifetime and
 * lazily created by `AppContainer`, so a user who never scans for faces never pays for it.
 */
class OnnxFaceDetector private constructor(
    override val id: String,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val inputEdge: Int,
    private val scoreThreshold: Float,
    private val nmsThreshold: Float,
) : FaceDetector, AutoCloseable {

    override fun detect(image: DecodedImage): List<FaceDetection>? = try {
        if (image.width <= 0 || image.height <= 0) {
            emptyList()
        } else {
            val canvas = YuNetPostProcessing.letterbox(image, inputEdge)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(canvas.planarBgr), inputShape()).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val decoded = YuNetPostProcessing.decode(
                        outputs = readHeads(result),
                        inputEdge = inputEdge,
                        scoreThreshold = scoreThreshold,
                        nmsThreshold = nmsThreshold,
                    )
                    YuNetPostProcessing.unletterbox(decoded, canvas.usedWidth, canvas.usedHeight)
                }
            }
        }
    } catch (t: Throwable) {
        System.err.println("OnnxFaceDetector.detect failed, returning null (not cached): ${t.message}")
        null
    }

    /**
     * Pulls the twelve heads out **by name** (`cls_8`, `obj_8`, ... `kps_32`) rather than by
     * position — the same names OpenCV asks the graph for, and a re-export that reorders outputs
     * would silently mis-decode if we indexed positionally.
     */
    private fun readHeads(result: OrtSession.Result): List<YuNetPostProcessing.StrideOutputs> =
        YuNetPostProcessing.STRIDES.map { stride ->
            YuNetPostProcessing.StrideOutputs(
                cls = head(result, "cls_$stride"),
                obj = head(result, "obj_$stride"),
                bbox = head(result, "bbox_$stride"),
                kps = head(result, "kps_$stride"),
            )
        }

    private fun head(result: OrtSession.Result, name: String): FloatArray =
        flatten(result.get(name).orElse(null)?.value)

    private fun inputShape() = longArrayOf(1, 3, inputEdge.toLong(), inputEdge.toLong())

    override fun close() {
        session.close()
    }

    private companion object {
        const val DEFAULT_RESOURCE = "/models/face-detect-yunet.onnx"

        // Bump whenever the blob or this class's preprocessing changes, so FaceCache re-keys.
        // Mirrors OnnxEmbeddingModel.DEFAULT_ID's discipline.
        const val DEFAULT_ID = "yunet-2023mar-v1"

        /** Flattens a `[1, cells, k]` ONNX output (nested arrays) into one row-major float array. */
        fun flatten(value: Any?): FloatArray {
            val batch = (value as? Array<*>)?.firstOrNull() ?: return FloatArray(0)
            val rows = batch as? Array<*> ?: return (batch as? FloatArray) ?: FloatArray(0)
            var total = 0
            for (row in rows) total += (row as? FloatArray)?.size ?: 0
            val out = FloatArray(total)
            var i = 0
            for (row in rows) {
                val floats = row as? FloatArray ?: continue
                System.arraycopy(floats, 0, out, i, floats.size)
                i += floats.size
            }
            return out
        }
    }

    /**
     * Loads the bundled YuNet blob off the classpath and opens a session. Throws if the resource is
     * missing or the model fails to load; the caller (see `AppContainer`) treats that as "face
     * scanning unavailable" rather than letting it break the app.
     */
    object Loader {
        fun fromResource(
            resourcePath: String = DEFAULT_RESOURCE,
            id: String = DEFAULT_ID,
            scoreThreshold: Float = YuNetPostProcessing.DEFAULT_SCORE_THRESHOLD,
            nmsThreshold: Float = YuNetPostProcessing.DEFAULT_NMS_THRESHOLD,
        ): OnnxFaceDetector {
            val bytes = OnnxFaceDetector::class.java.getResourceAsStream(resourcePath)
                ?.use { it.readBytes() }
                ?: error("Face detection model resource not found on classpath: $resourcePath")

            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(bytes, OrtSession.SessionOptions())
            val inputName = session.inputNames.first()
            return OnnxFaceDetector(
                id = id,
                env = env,
                session = session,
                inputName = inputName,
                // Probed from the graph, never assumed: this model declares a fixed square input, and
                // reading it here means a re-exported blob at another resolution needs no code change.
                inputEdge = probeInputEdge(session, inputName),
                scoreThreshold = scoreThreshold,
                nmsThreshold = nmsThreshold,
            )
        }

        private fun probeInputEdge(session: OrtSession, inputName: String): Int {
            val shape = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
            val declared = shape?.lastOrNull()?.toInt() ?: -1
            return if (declared > 0) declared else YuNetPostProcessing.INPUT_EDGE
        }
    }
}
