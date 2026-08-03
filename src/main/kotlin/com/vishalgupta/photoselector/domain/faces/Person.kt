package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.PhotoId

@JvmInline
value class PersonId(val value: String)

/**
 * One distinct person the face pipeline found in a root: the cluster of faces believed to be them,
 * their mean embedding, and — once the user names them — a [name].
 *
 * A person is **derived state with two user-owned fields**. The faces and the [centroid] are
 * recomputed by every scan; [name] and [dismissed] are the only things a human authored, so they are
 * what a rescan must preserve (see [FaceClusterer.cluster]'s anchor re-match). An unnamed, un-dismissed
 * person's id and ordering are *not* stable across scans — deliberately, since nothing user-visible
 * hangs off them.
 *
 * [centroid] is persisted rather than recomputed on load so an anchored person can be re-matched even
 * when the photos that originally defined them have since left the root.
 *
 * PII: a person's [name] is user data. Never log it (nor a face crop) — see CLAUDE.md.
 */
data class Person(
    val id: PersonId,
    val name: String? = null,
    val faces: List<FaceRef> = emptyList(),
    val centroid: List<Float> = emptyList(),
    /**
     * The user looked at this cluster and said "not a person" (or skipped it) — it stays out of the
     * naming queue. Persisted, and an *anchor* for the rescan re-match exactly like [name] is: without
     * that, a rescan mid-naming renumbers the unnamed clusters and every dismissal is thrown away.
     */
    val dismissed: Boolean = false,
) {
    /** True once the user has named this person; only anchored people survive a rescan by identity. */
    val isNamed: Boolean get() = !name.isNullOrBlank()

    /**
     * True when this person's identity must survive a rescan — either the user named them or they
     * dismissed the cluster. The set [FaceClusterer]'s phase-1 centroid re-match runs over.
     */
    val isAnchor: Boolean get() = isNamed || dismissed

    /** The face to show on this person's card: the most confident detection that has a drawable box. */
    val coverFace: FaceRef? get() = faces.filter { it.box != null }.maxByOrNull { it.score }

    /** The photos this person appears in, in first-seen order — the membership a person category resolves to. */
    val photos: Set<PhotoId> get() = faces.mapTo(LinkedHashSet()) { it.id.photo }

    /** Just the identities, for callers that don't care where on the photo the face sits. */
    val faceIds: List<FaceId> get() = faces.map { it.id }

    /** The centroid as an embedding, for distance comparisons. Empty centroid -> null (never matches). */
    fun centroidEmbedding(): FaceEmbedding? =
        if (centroid.isEmpty()) null else FaceEmbedding(centroid.toFloatArray())
}
