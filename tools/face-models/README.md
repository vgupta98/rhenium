# Face models

Reproducible fetch of the two face models bundled under
`src/main/resources/models/` and loaded by `OnnxFaceDetector` / `OnnxFaceEmbedder`.

Unlike `tools/embedding-model/`, this is **not** a re-export: we ship upstream's
blobs byte-for-byte. So the script pins an exact **commit SHA** of
[opencv/opencv_zoo](https://github.com/opencv/opencv_zoo) — not a branch and not
a release tag, either of which can move — and verifies every download against
the SHA-256 recorded in that commit's own Git LFS pointer.

## What they are

| Role | Installed as | Upstream | Licence |
| --- | --- | --- | --- |
| Detect | `face-detect-yunet.onnx` | [`models/face_detection_yunet/face_detection_yunet_2023mar.onnx`](https://github.com/opencv/opencv_zoo/blob/f12e12798e8314f7c074a6656816c048dcc95b7a/models/face_detection_yunet/face_detection_yunet_2023mar.onnx) | **MIT** (libfacedetection, (c) 2020 Shiqi Yu) |
| Recognise | `face-embed-sface.onnx` | [`models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx`](https://github.com/opencv/opencv_zoo/blob/f12e12798e8314f7c074a6656816c048dcc95b7a/models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx) | **Apache-2.0** (SFace) |

Both licences permit commercial redistribution, which is why these two and not
the better-benchmarking alternatives:

> **InsightFace `buffalo_*` / SCRFD / ArcFace pretrained weights are deliberately
> rejected.** The *code* is Apache-2.0, but the published *weights* are licensed
> for non-commercial research only — incompatible with this project's free/open
> positioning and its open-core plan. Do not substitute them for better numbers.

### Contracts

**YuNet (detect)** — fixed input `input`: `[1, 3, 640, 640]`, **BGR**, raw 0..255,
no mean subtraction (OpenCV `blobFromImage` defaults). Twelve outputs:
`cls_S`, `obj_S`, `bbox_S`, `kps_S` for strides 8/16/32, each a flat row-major
grid of `(640/S)^2` cells. Decoded by the pure `YuNetPostProcessing`.

**SFace (recognise)** — fixed input `data`: `[1, 3, 112, 112]`, **RGB**, raw
0..255 (`swapRB = true`, scale 1, mean 0). Output `fc1`: `[1, 128]`, L2-normalised
Kotlin-side so cosine similarity is a dot product. The crop must be the aligned
112x112 produced by `FaceAlignment`; a raw box crop degrades it badly. The width
is **probed from the graph** at load, so a model swap needs no Kotlin change.

## Reproduce

```bash
cd tools/face-models
./fetch_face_models.sh          # download + verify + install into src/main/resources/models
./fetch_face_models.sh --check  # just print the hashes of what is installed
```

Requires bash, curl, python3 and shasum (all stock on macOS). Nothing is built
or converted, so the result is byte-identical for a given pinned commit.

## Expected output

```
8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  face-detect-yunet.onnx (232589 bytes)
2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a  face-embed-sface.onnx (9896933 bytes)
```

If a hash ever drifts, upstream changed the blob: verify deliberately, then
**bump the matching `id`** in `OnnxFaceDetector` / `OnnxFaceEmbedder` so
`FaceCache` re-keys and stale detections/embeddings are never served. (Identical
rule to `OnnxEmbeddingModel.DEFAULT_ID`.)

## Validating the decode port

`YuNetPostProcessing` is a hand port of OpenCV's `FaceDetectorYNImpl::postProcess`
(`modules/objdetect/src/face_detect.cpp`), because opencv_zoo's `yunet.py` no
longer contains a decode — it delegates to `cv.FaceDetectorYN`, and the 2021
revision of that script decodes the *older*, anchor-based YuNet (priors,
variances), which does not apply to the 2023mar graph.

A hand port with no in-repo reference is the riskiest part of the pipeline (a
subtly wrong decode yields plausible but offset boxes), so it was diffed against
OpenCV itself before landing: the same letterboxed 640x640 canvas fed to both
`cv.FaceDetectorYN` and a line-for-line Python mirror of the Kotlin decode, over
real photographs. **Every box, landmark and score matched to float noise
(max elementwise delta 3e-5).** `YuNetPostProcessingTest` then pins the same
arithmetic on hand-computed synthetic tensors, model-free, as the standing guard.

To repeat the diff, run the two implementations over the same canvas with
`opencv-python` + `onnxruntime` installed; the reference is `FaceDetectorYN`
created at `(640, 640)` with the same score/NMS thresholds.
