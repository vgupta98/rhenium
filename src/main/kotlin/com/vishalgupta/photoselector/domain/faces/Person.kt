package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.PhotoId

@JvmInline
value class PersonId(val value: String)

/**
 * One distinct person the face pipeline found in a root: the cluster of faces believed to be them,
 * their mean embedding, and — once the user names them — a [name].
 *
 * A person is **derived state with one user-owned field**. The faces and the [centroid] are recomputed
 * by every scan; [name] is the only thing a human authored, so it is what a rescan must preserve
 * (see [FaceClusterer.cluster]'s named-centroid re-match). An unnamed person's id and ordering are
 * *not* stable across scans — deliberately, since nothing user-visible hangs off them.
 *
 * [centroid] is persisted rather than recomputed on load so a named person can be re-matched even
 * when the photos that originally defined them have since left the root.
 *
 * PII: a person's [name] is user data. Never log it (nor a face crop) — see CLAUDE.md.
 */
data class Person(
    val id: PersonId,
    val name: String? = null,
    val faces: List<FaceId> = emptyList(),
    val centroid: List<Float> = emptyList(),
) {
    /** True once the user has named this person; only named people survive a rescan by identity. */
    val isNamed: Boolean get() = !name.isNullOrBlank()

    /** The photos this person appears in, in first-seen order — the membership a person category resolves to. */
    val photos: Set<PhotoId> get() = faces.mapTo(LinkedHashSet()) { it.photo }

    /** The centroid as an embedding, for distance comparisons. Empty centroid -> null (never matches). */
    fun centroidEmbedding(): FaceEmbedding? =
        if (centroid.isEmpty()) null else FaceEmbedding(centroid.toFloatArray())
}
