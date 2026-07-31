package com.vishalgupta.photoselector.data.ai

import com.vishalgupta.photoselector.data.io.ShardedBlobCache
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * A persistent, content-keyed on-disk cache of a *computed grouping* — the lightweight group
 * structure (frame ids + key frame per group), NOT pixels or embeddings (that is [EmbeddingCache]'s
 * job). It exists so re-entering the Similarity lens on an unchanged folder is instant rather than
 * re-running the minute-long model pass: the embedding cache already makes the *second* embed cheap,
 * but the grouping itself was recomputed every time.
 *
 * Storage mechanics (hash → shard → atomic write, size-capped eviction) are the shared
 * [ShardedBlobCache]'s; what stays here is this cache's own *key composition* — which is
 * deliberately a different shape from [EmbeddingCache]'s and must stay byte-identical (see the
 * golden-key test in `GroupingResultCacheTest`). The key folds in the producing model's id (a model
 * swap that changes vectors must re-key, exactly as for embeddings) and a fingerprint of the photo
 * set — each frame's `path|size|mtime`, in order — so a source edit, add, remove, reorder, model
 * swap, or format bump all miss automatically. The photo set IS the (root, scope) here, so the
 * fingerprint subsumes scoping without threading root/scope through the grouper; the adjacency rule
 * keeps groups inside one folder regardless.
 *
 * A hit still verifies every stored id resolves against the live photos and reconstructs from THOSE
 * `Photo` objects; any mismatch (or a corrupt/forward-version file) is treated as a miss and recomputes,
 * so a stale grouping can never show.
 */
class GroupingResultCache(
    cacheDir: Path,
    private val json: Json,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val blobs = ShardedBlobCache(
        cacheDir = cacheDir,
        directoryName = "groupings",
        fileExtension = FILE_EXTENSION,
        maxBytes = maxBytes,
    )

    fun startEviction(scope: CoroutineScope) {
        blobs.startEviction(scope)
    }

    /** The cache key for [photos] under [modelId]. Public so the decorator computes it once per pass. */
    fun keyFor(modelId: String, photos: List<Photo>): String = buildString {
        append(modelId)
        append('\n')
        append(FORMAT_VERSION)
        for (p in photos) {
            append('\n')
            append(p.absolutePath)
            append('|')
            append(p.sizeBytes)
            append('|')
            append(p.lastModifiedEpochMs)
        }
    }

    /**
     * The cached grouping for [key], reconstructed against [photos], or null on a miss / any mismatch.
     * [photos] is the live slice: the stored ids are resolved against it so the returned groups carry
     * the current [Photo] instances.
     */
    fun get(key: String, photos: List<Photo>): List<PhotoGroup>? = blobs.read(key) { bytes ->
        val dto = json.decodeFromString(GroupingFileDto.serializer(), bytes.toString(Charsets.UTF_8))
        if (dto.version != FORMAT_VERSION) null else reconstruct(dto, photos)
    }

    fun put(key: String, groups: List<PhotoGroup>) {
        val dto = GroupingFileDto(
            version = FORMAT_VERSION,
            groups = groups.map { group ->
                when (group) {
                    is PhotoGroup.Single -> GroupDto(ids = listOf(group.photo.id.value))
                    is PhotoGroup.Burst -> GroupDto(
                        ids = group.photos.map { it.id.value },
                        keyIndex = group.keyIndex,
                    )
                }
            },
        )
        blobs.write(key, json.encodeToString(GroupingFileDto.serializer(), dto).toByteArray(Charsets.UTF_8))
    }

    private fun reconstruct(dto: GroupingFileDto, photos: List<Photo>): List<PhotoGroup>? {
        val byId = photos.associateBy { it.id.value }
        return try {
            dto.groups.map { g ->
                val frames = g.ids.map { id -> byId[id] ?: return null }
                if (frames.size >= 2) {
                    PhotoGroup.Burst(frames, keyIndex = g.keyIndex)
                } else {
                    PhotoGroup.Single(frames.first())
                }
            }
        } catch (_: Throwable) {
            // A corrupt entry (e.g. an out-of-bounds keyIndex tripping Burst's require) — recompute.
            null
        }
    }

    @Serializable
    private data class GroupingFileDto(val version: Int, val groups: List<GroupDto>)

    @Serializable
    private data class GroupDto(
        val ids: List<String>,
        val keyIndex: Int = 0,
    )

    companion object {
        // Bump when the on-disk schema (or the meaning of a stored field) changes, OR when the
        // grouping algorithm changes (cached groups are the algorithm's output — stale logic must
        // not be served). v2: dropped the unused keyIsSuggested field; v1 entries are rejected and
        // recompute. v3: SimilarityGrouper switched to the per-event Adaptive threshold.
        // v4: SimilarityGrouper added the capture-time boost (timeBoosted JoinRule), so groupings change.
        // v5: HEIC now yields capture time (HeicCaptureMetadataSource), so the boost fires on HEIC folders
        // that previously had none — their cached groupings would otherwise be served stale (same content key).
        const val FORMAT_VERSION = 5
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
        private const val FILE_EXTENSION = "grp"
    }
}
