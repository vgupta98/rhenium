#!/usr/bin/env bash
#
# Fetches the two bundled face models from opencv/opencv_zoo into
# src/main/resources/models/ and prints their SHA-256.
#
# This is a *fetch-and-verify* step, not a re-export: we ship upstream's blobs
# byte-for-byte. So the pin is an exact commit SHA (not a branch or a release
# tag, either of which can move under us) and every download is checked against
# the SHA-256 recorded in the repo's own Git LFS pointer at that commit.
#
# Usage:  ./fetch_face_models.sh          # download + verify + install
#         ./fetch_face_models.sh --check  # verify what is already installed
#
# Requires: bash, curl, python3, shasum (all present on a stock macOS).

set -euo pipefail

# Exact opencv_zoo commit the blobs are pinned to. Bumping this means re-running
# the script, updating README.md's "Expected output" hashes, and bumping the
# matching `id` in OnnxFaceDetector / OnnxFaceEmbedder so FaceCache re-keys.
readonly ZOO_COMMIT="f12e12798e8314f7c074a6656816c048dcc95b7a"
readonly ZOO_REPO="opencv/opencv_zoo"

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly OUT_DIR="${SCRIPT_DIR}/../../src/main/resources/models"

# upstream path                                                              -> installed name
readonly MODELS=(
  "models/face_detection_yunet/face_detection_yunet_2023mar.onnx|face-detect-yunet.onnx"
  "models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx|face-embed-sface.onnx"
)

# opencv_zoo stores its .onnx blobs in Git LFS, so two endpoints are involved:
#   raw.githubusercontent.com   -> the LFS *pointer* at the pinned commit
#   media.githubusercontent.com -> the LFS *object* bytes
# The pointer carries the object's own SHA-256, which is exactly the integrity
# anchor we want: download the bytes, then check them against the oid the pinned
# commit names. (The LFS batch API is not used - opencv_zoo has repeatedly sat
# over its LFS budget, which 403s that endpoint while /media keeps serving.)
download_lfs_object() {
  local upstream_path="$1" dest="$2"
  local pointer oid size actual

  pointer="$(curl -fsSL "https://raw.githubusercontent.com/${ZOO_REPO}/${ZOO_COMMIT}/${upstream_path}")"
  oid="$(printf '%s\n' "${pointer}" | sed -n 's/^oid sha256://p')"
  size="$(printf '%s\n' "${pointer}" | sed -n 's/^size //p')"
  if [[ -z "${oid}" || -z "${size}" ]]; then
    echo "error: ${upstream_path} at ${ZOO_COMMIT} is not an LFS pointer" >&2
    exit 1
  fi

  curl -fsSL -o "${dest}" \
    "https://media.githubusercontent.com/media/${ZOO_REPO}/${ZOO_COMMIT}/${upstream_path}"

  actual="$(shasum -a 256 "${dest}" | cut -d' ' -f1)"
  if [[ "${actual}" != "${oid}" ]]; then
    echo "error: ${dest} sha256 ${actual} != pinned ${oid}" >&2
    exit 1
  fi
}

if [[ "${1:-}" == "--check" ]]; then
  for entry in "${MODELS[@]}"; do
    name="${entry##*|}"
    shasum -a 256 "${OUT_DIR}/${name}"
  done
  exit 0
fi

mkdir -p "${OUT_DIR}"
echo "opencv_zoo @ ${ZOO_COMMIT}"
for entry in "${MODELS[@]}"; do
  upstream="${entry%%|*}"
  name="${entry##*|}"
  echo "fetching ${upstream}"
  download_lfs_object "${upstream}" "${OUT_DIR}/${name}"
done

echo
echo "installed in ${OUT_DIR}:"
for entry in "${MODELS[@]}"; do
  name="${entry##*|}"
  bytes="$(wc -c <"${OUT_DIR}/${name}" | tr -d ' ')"
  printf '%s  %s (%s bytes)\n' "$(shasum -a 256 "${OUT_DIR}/${name}" | cut -d' ' -f1)" "${name}" "${bytes}"
done
