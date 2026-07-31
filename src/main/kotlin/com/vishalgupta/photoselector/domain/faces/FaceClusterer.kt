package com.vishalgupta.photoselector.domain.faces

/**
 * Groups face embeddings into distinct [Person]s. Pure and framework-free — the expensive
 * detect/embed work happens in `data/faces`; this file owns only the decision of who is who.
 *
 * ## Average linkage, not connected components
 * Two clusters merge on the **mean** distance between all their cross-pairs, not the closest pair.
 * Single-linkage (equivalently: thresholding pairwise edges and taking connected components) chains:
 * one borderline photo of A that happens to sit near B collapses both people into one cluster, and
 * for a naming UI that is the worst possible failure — the user names "Alice" and gets Bob's photos
 * too, with no obvious way to split them. Average linkage needs a cluster to be *consistently* close
 * before it merges, so a single bad edge is outvoted.
 *
 * Cost is O(n^2) time and n(n-1)/2 floats of memory (a packed lower triangle), which is the
 * deliberate trade: a few thousand faces is a few tens of MB and a fraction of the scan's decode
 * cost. Merges use the Lance-Williams update, so the matrix is maintained rather than recomputed.
 *
 * ## Rescan preserves named people
 * A scan is a *full* recluster, but a name is the one thing the user authored, so it must survive.
 * [cluster] therefore runs in two phases: every face is first offered to the nearest **named**
 * person's centroid and joins it if within the cut; only the residue is clustered from scratch. A
 * named person with no surviving faces keeps its stored centroid so it can still re-match later.
 * Unnamed cluster identity and ordering are explicitly *not* stable across scans — nothing
 * user-visible hangs off them.
 */
object FaceClusterer {

    /**
     * SFace's published cosine-similarity operating point for "same person" (`0.363`). It is a
     * verification threshold, not one measured for this corpus, so it is a *seed* — it sits behind
     * [ThresholdRule] precisely so it can be tuned in one line once there is a labelled face set.
     */
    const val DEFAULT_COSINE_SIMILARITY = 0.363f

    /** [DEFAULT_COSINE_SIMILARITY] expressed as the cosine *distance* the clusterer links on. */
    const val DEFAULT_DISTANCE_CUT = 1f - DEFAULT_COSINE_SIMILARITY

    /**
     * Chooses the cosine-distance cut below which two clusters may merge, given every pairwise
     * distance in the scan. Shaped exactly like
     * [com.vishalgupta.photoselector.domain.grouping.SimilarityGrouper.ThresholdRule] so a future
     * adaptive rule slots in without touching a caller.
     */
    fun interface ThresholdRule {
        fun cut(pairwiseDistances: List<Float>): Float
    }

    /** Constant cut derived from a cosine *similarity*; the shipped default. */
    fun fixed(similarity: Float = DEFAULT_COSINE_SIMILARITY): ThresholdRule =
        ThresholdRule { 1f - similarity }

    /**
     * Clusters [faces] into people.
     *
     * @param faces the face ids to cluster, in a deterministic order (the scan's photo order).
     * @param embeddings each face's embedding; a face with no embedding is skipped entirely.
     * @param known the previous scan's people. Only the **named** ones influence the result — they
     *   keep their id and name and absorb any face within the cut of their centroid.
     * @param rule the merge cut (see [ThresholdRule]).
     * @param newPersonId generates ids for freshly discovered (unnamed) people. Injected so the
     *   function stays pure and tests are deterministic.
     */
    fun cluster(
        faces: List<FaceId>,
        embeddings: Map<FaceId, FaceEmbedding>,
        known: List<Person> = emptyList(),
        rule: ThresholdRule = fixed(),
        newPersonId: () -> PersonId,
    ): List<Person> {
        val embedded = faces.filter { embeddings[it] != null }
        val named = known.filter { it.isNamed }
        if (embedded.isEmpty()) return named.map { it.copy(faces = emptyList()) }

        val cut = rule.cut(pairwiseDistances(embedded, embeddings))

        // Phase 1 - re-match onto named centroids, so a rename survives a rescan.
        val namedCentroids = named.map { it to it.centroidEmbedding() }
        val claimed = LinkedHashMap<PersonId, MutableList<FaceId>>()
        val residue = ArrayList<FaceId>(embedded.size)
        for (face in embedded) {
            val embedding = embeddings.getValue(face)
            var bestId: PersonId? = null
            var bestDistance = Float.MAX_VALUE
            for ((person, centroid) in namedCentroids) {
                if (centroid == null) continue
                val d = embedding.cosineDistanceTo(centroid)
                if (d < bestDistance) {
                    bestDistance = d
                    bestId = person.id
                }
            }
            if (bestId != null && bestDistance <= cut) {
                claimed.getOrPut(bestId) { ArrayList() } += face
            } else {
                residue += face
            }
        }

        val preserved = named.map { person ->
            val members = claimed[person.id].orEmpty()
            person.copy(
                faces = members,
                // A person that kept no faces keeps its stored centroid, so it can re-match a later scan.
                centroid = if (members.isEmpty()) person.centroid else centroidOf(members, embeddings),
            )
        }

        // Phase 2 - agglomerate the residue from scratch.
        val discovered = agglomerate(residue, embeddings, cut).map { members ->
            Person(
                id = newPersonId(),
                name = null,
                faces = members,
                centroid = centroidOf(members, embeddings),
            )
        }
        return preserved + discovered
    }

    /** Every pairwise cosine distance among [faces], for a [ThresholdRule] to reason over. */
    fun pairwiseDistances(faces: List<FaceId>, embeddings: Map<FaceId, FaceEmbedding>): List<Float> {
        val vectors = faces.mapNotNull { embeddings[it] }
        if (vectors.size < 2) return emptyList()
        val out = ArrayList<Float>(vectors.size * (vectors.size - 1) / 2)
        for (i in 1 until vectors.size) {
            for (j in 0 until i) out += vectors[i].cosineDistanceTo(vectors[j])
        }
        return out
    }

    /** The mean (re-normalised) embedding of [faces], as a plain float list for persistence. */
    fun centroidOf(faces: List<FaceId>, embeddings: Map<FaceId, FaceEmbedding>): List<Float> {
        val vectors = faces.mapNotNull { embeddings[it]?.values }
        val dims = vectors.firstOrNull()?.size ?: return emptyList()
        val sum = FloatArray(dims)
        var counted = 0
        for (v in vectors) {
            if (v.size != dims) continue
            for (i in 0 until dims) sum[i] += v[i]
            counted++
        }
        if (counted == 0) return emptyList()
        var norm = 0f
        for (i in 0 until dims) {
            sum[i] /= counted
            norm += sum[i] * sum[i]
        }
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-6f) for (i in 0 until dims) sum[i] /= norm
        return sum.toList()
    }

    /**
     * Average-linkage agglomerative clustering of [faces], cut at [cut].
     *
     * Built with the **nearest-neighbour chain** algorithm over a packed lower-triangular distance
     * matrix maintained by the Lance-Williams update. The naive "rescan the whole matrix for the
     * global minimum on every merge" loop is O(n^3), which a few thousand faces would make
     * unusable; NN-chain gets the identical dendrogram in O(n^2) because average linkage is a
     * *reducible* criterion (Mullner 2011). Reducibility also means merge heights never invert, so
     * cutting the dendrogram at [cut] is the same thing as having stopped merging there.
     */
    private fun agglomerate(
        faces: List<FaceId>,
        embeddings: Map<FaceId, FaceEmbedding>,
        cut: Float,
    ): List<List<FaceId>> {
        val n = faces.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(listOf(faces[0]))

        // Lower triangle, row-major: distance(i, j) for i > j lives at i*(i-1)/2 + j.
        val distances = FloatArray(n * (n - 1) / 2)
        for (i in 1 until n) {
            val vi = embeddings.getValue(faces[i])
            val base = i * (i - 1) / 2
            for (j in 0 until i) distances[base + j] = vi.cosineDistanceTo(embeddings.getValue(faces[j]))
        }

        fun distanceAt(i: Int, j: Int): Float =
            if (i > j) distances[i * (i - 1) / 2 + j] else distances[j * (j - 1) / 2 + i]

        fun setDistanceAt(i: Int, j: Int, value: Float) {
            if (i > j) distances[i * (i - 1) / 2 + j] = value else distances[j * (j - 1) / 2 + i] = value
        }

        val size = IntArray(n) { 1 }
        val alive = BooleanArray(n) { true }
        val chain = IntArray(n)
        var chainLength = 0
        var liveCount = n
        // (survivor, absorbed, height) in merge order; heights are non-decreasing.
        val merges = ArrayList<Triple<Int, Int, Float>>(n - 1)

        while (liveCount > 1) {
            if (chainLength == 0) {
                chain[chainLength++] = (0 until n).first { alive[it] }
            }
            val a = chain[chainLength - 1]
            var b = -1
            var best = Float.MAX_VALUE
            for (k in 0 until n) {
                if (!alive[k] || k == a) continue
                val d = distanceAt(a, k)
                // Tie-break on index so the result is deterministic for identical embeddings.
                if (d < best || (d == best && k < b)) {
                    best = d
                    b = k
                }
            }
            if (b < 0) break
            if (chainLength >= 2 && b == chain[chainLength - 2]) {
                // Reciprocal nearest neighbours - merge them. The lower index survives.
                val survivor = minOf(a, b)
                val absorbed = maxOf(a, b)
                val sizeS = size[survivor]
                val sizeAb = size[absorbed]
                for (k in 0 until n) {
                    if (!alive[k] || k == survivor || k == absorbed) continue
                    setDistanceAt(
                        survivor,
                        k,
                        (sizeS * distanceAt(survivor, k) + sizeAb * distanceAt(absorbed, k)) / (sizeS + sizeAb),
                    )
                }
                size[survivor] = sizeS + sizeAb
                alive[absorbed] = false
                liveCount--
                merges += Triple(survivor, absorbed, best)
                chainLength -= 2
            } else {
                chain[chainLength++] = b
            }
        }

        // Cut the dendrogram: apply only the merges at or below the threshold.
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }
        for ((survivor, absorbed, height) in merges) {
            if (height > cut) continue
            parent[find(absorbed)] = find(survivor)
        }

        val grouped = LinkedHashMap<Int, MutableList<FaceId>>()
        for (i in 0 until n) grouped.getOrPut(find(i)) { ArrayList() } += faces[i]
        return grouped.values.map { it.toList() }
    }
}
