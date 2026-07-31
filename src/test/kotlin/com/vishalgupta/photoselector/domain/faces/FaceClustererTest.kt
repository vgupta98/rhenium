package com.vishalgupta.photoselector.domain.faces

import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceClustererTest {

    private var counter = 0
    private val ids: () -> PersonId = { PersonId("p-${counter++}") }

    private fun face(name: String) = FaceId(PhotoId(name), 0)

    /** A unit 2D vector at [degrees], padded to look like a real (longer) embedding. */
    private fun vec(degrees: Double): FaceEmbedding {
        val t = Math.toRadians(degrees)
        return FaceEmbedding(floatArrayOf(Math.cos(t).toFloat(), Math.sin(t).toFloat()))
    }

    private fun cluster(
        embeddings: Map<FaceId, FaceEmbedding>,
        known: List<Person> = emptyList(),
        similarity: Float = 0.7f,
    ): List<Person> = FaceClusterer.cluster(
        faces = embeddings.keys.toList(),
        embeddings = embeddings,
        known = known,
        rule = FaceClusterer.fixed(similarity),
        newPersonId = ids,
    )

    @Test
    fun identicalEmbeddingsBecomeOnePerson() {
        val a = face("a")
        val b = face("b")
        val c = face("c")
        val same = vec(12.0)

        val people = cluster(mapOf(a to same, b to same, c to same))

        assertEquals(1, people.size)
        assertEquals(setOf(a, b, c), people.single().faces.toSet())
        assertEquals(setOf(PhotoId("a"), PhotoId("b"), PhotoId("c")), people.single().photos)
    }

    @Test
    fun orthogonalEmbeddingsStayApart() {
        val people = cluster(mapOf(face("a") to vec(0.0), face("b") to vec(90.0)))

        assertEquals(2, people.size)
    }

    @Test
    fun averageLinkageDoesNotChainTwoPeopleThroughABorderlineFace() {
        // A~B and B~C both just clear the cut (cos 45 = 0.707 vs a 0.70 bar), but A and C are
        // orthogonal. Single-linkage / connected-components would collapse all three into one
        // person - the worst failure mode for a naming UI. Average linkage must not:
        // after {A,B} merges, d({A,B}, C) = (0.293 + 1.0) / 2 = 0.646, far above the 0.30 cut.
        val a = face("a")
        val b = face("b")
        val c = face("c")

        val people = cluster(mapOf(a to vec(0.0), b to vec(45.0), c to vec(90.0)))

        assertEquals(2, people.size, "A~B, B~C, A!~C must not collapse into one person")
        val together = people.first { it.faces.size == 2 }
        assertEquals(setOf(a, b), together.faces.toSet())
        assertEquals(listOf(c), people.first { it.faces.size == 1 }.faces)
    }

    @Test
    fun aNamedPersonSurvivesAReclusterByCentroidRematch() {
        val alice = Person(
            id = PersonId("alice"),
            name = "Alice",
            faces = listOf(face("old")),
            centroid = listOf(1f, 0f),
        )
        val fresh = face("new")

        // A rescan sees a completely different face id, but the same face.
        val people = cluster(mapOf(fresh to vec(5.0)), known = listOf(alice))

        val kept = assertNotNull(people.firstOrNull { it.id == PersonId("alice") })
        assertEquals("Alice", kept.name)
        assertEquals(listOf(fresh), kept.faces)
        assertEquals(1, people.size, "the re-matched face must not also spawn a new person")
    }

    @Test
    fun anUnnamedKnownPersonIsDiscardedAndRebuilt() {
        val stale = Person(id = PersonId("stale"), name = null, faces = listOf(face("x")), centroid = listOf(1f, 0f))

        val people = cluster(mapOf(face("y") to vec(0.0)), known = listOf(stale))

        assertTrue(people.none { it.id == PersonId("stale") }, "unnamed cluster identity is not stable by design")
        assertEquals(1, people.size)
    }

    @Test
    fun aNamedPersonWithNoSurvivingFacesKeepsItsCentroidForALaterScan() {
        val bob = Person(PersonId("bob"), name = "Bob", faces = listOf(face("gone")), centroid = listOf(1f, 0f))

        val people = cluster(mapOf(face("other") to vec(90.0)), known = listOf(bob))

        val kept = assertNotNull(people.firstOrNull { it.id == PersonId("bob") })
        assertTrue(kept.faces.isEmpty())
        assertEquals(listOf(1f, 0f), kept.centroid, "the stored centroid must survive so Bob can re-match later")
    }

    @Test
    fun anEmptyScanKeepsOnlyTheNamedPeople() {
        val named = Person(PersonId("n"), name = "Named", faces = listOf(face("a")), centroid = listOf(1f, 0f))
        val unnamed = Person(PersonId("u"), name = null, faces = listOf(face("b")), centroid = listOf(0f, 1f))

        val people = FaceClusterer.cluster(
            faces = emptyList(),
            embeddings = emptyMap(),
            known = listOf(named, unnamed),
            newPersonId = ids,
        )

        assertEquals(listOf(PersonId("n")), people.map { it.id })
        assertTrue(people.single().faces.isEmpty())
    }

    @Test
    fun facesWithoutAnEmbeddingAreIgnored() {
        val embedded = face("a")
        val people = FaceClusterer.cluster(
            faces = listOf(embedded, face("no-embedding")),
            embeddings = mapOf(embedded to vec(0.0)),
            newPersonId = ids,
        )

        assertEquals(listOf(listOf(embedded)), people.map { it.faces })
    }

    @Test
    fun theShippedRuleNeverMaterialisesThePairwiseDistances() {
        // The full pairwise triangle is O(n^2) - 12,000 faces is ~288 MB - so a rule that doesn't
        // read it must never cause it to be built. The producer is lazy for exactly this reason.
        var produced = 0
        val counting = FaceClusterer.ThresholdRule { distances ->
            FaceClusterer.fixed(0.7f).cut { produced++; distances() }
        }

        FaceClusterer.cluster(
            faces = listOf(face("a"), face("b"), face("c")),
            embeddings = mapOf(face("a") to vec(0.0), face("b") to vec(1.0), face("c") to vec(90.0)),
            rule = counting,
            newPersonId = ids,
        )

        assertEquals(0, produced, "fixed() must not touch the pairwise distances")
    }

    @Test
    fun packedDistancesRefusesAFaceWithNoEmbedding() {
        // The triangle is indexed positionally, so silently dropping a gap would hand the caller a
        // shorter array in a different ordering - out-of-bounds at best, wrong clusters at worst.
        val a = face("a")
        val missing = face("missing")

        assertFailsWith<IllegalArgumentException> {
            FaceClusterer.packedDistances(listOf(a, missing), mapOf(a to vec(0.0)))
        }
    }

    @Test
    fun packedDistancesIsTheLowerTriangleInIndexOrder() {
        val a = face("a")
        val b = face("b")
        val c = face("c")
        val embeddings = mapOf(a to vec(0.0), b to vec(90.0), c to vec(180.0))

        val packed = FaceClusterer.packedDistances(listOf(a, b, c), embeddings)

        // distance(i, j) for i > j lives at i*(i-1)/2 + j: [ (1,0), (2,0), (2,1) ].
        assertEquals(3, packed.size)
        assertEquals(1f, packed[0], 1e-4f) // cos 90  -> 1 - 0
        assertEquals(2f, packed[1], 1e-4f) // cos 180 -> 1 - (-1)
        assertEquals(1f, packed[2], 1e-4f)
        assertEquals(0, FaceClusterer.packedDistances(listOf(a), embeddings).size)
    }

    @Test
    fun centroidIsTheRenormalisedMeanOfTheClustersEmbeddings() {
        val a = face("a")
        val b = face("b")
        val centroid = FaceClusterer.centroidOf(listOf(a, b), mapOf(a to vec(0.0), b to vec(90.0)))

        val expected = (sqrt(0.5)).toFloat()
        assertEquals(expected, centroid[0], 1e-4f)
        assertEquals(expected, centroid[1], 1e-4f)
    }

    /**
     * The NN-chain implementation must agree *exactly* with the textbook "repeatedly merge the
     * globally closest pair" average-linkage algorithm — "same answer, better complexity" is the
     * entire justification for using it, and nothing else in the repo would catch a subtly wrong
     * chain/Lance-Williams/threshold-cut interaction.
     *
     * Randomised over many shapes and cuts. The reference below is deliberately the naive O(n^3)
     * version, written straight from the definition.
     */
    @Test
    fun nnChainAgreesWithANaiveAverageLinkageReference() {
        val random = java.util.Random(20260801L)

        repeat(200) { trial ->
            val n = 2 + random.nextInt(14)
            val faces = List(n) { face("f$it") }
            // Vectors on the unit circle: the whole distance range 0..2 is reachable, and clusters
            // are easy to eyeball if a trial ever fails.
            val embeddings = faces.associateWith { vec(random.nextDouble() * 360.0) }
            val cut = random.nextFloat() * 1.6f

            counter = 0
            val actual = FaceClusterer.cluster(
                faces = faces,
                embeddings = embeddings,
                rule = FaceClusterer.ThresholdRule { cut },
                newPersonId = ids,
            ).map { it.faces.toSet() }.toSet()

            val expected = naiveAverageLinkage(faces, embeddings, cut)

            assertEquals(expected, actual, "trial $trial (n=$n, cut=$cut) diverged from the reference")
        }
    }

    /** Textbook average linkage: merge the globally closest pair while it is within [cut]. O(n^3). */
    private fun naiveAverageLinkage(
        faces: List<FaceId>,
        embeddings: Map<FaceId, FaceEmbedding>,
        cut: Float,
    ): Set<Set<FaceId>> {
        val clusters = faces.map { mutableSetOf(it) }.toMutableList()

        fun linkage(a: Set<FaceId>, b: Set<FaceId>): Float {
            var sum = 0f
            for (x in a) for (y in b) sum += embeddings.getValue(x).cosineDistanceTo(embeddings.getValue(y))
            return sum / (a.size * b.size)
        }

        while (clusters.size > 1) {
            var bestI = -1
            var bestJ = -1
            var best = Float.MAX_VALUE
            for (i in clusters.indices) {
                for (j in 0 until i) {
                    val d = linkage(clusters[i], clusters[j])
                    if (d < best) {
                        best = d
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0 || best > cut) break
            clusters[bestJ].addAll(clusters[bestI])
            clusters.removeAt(bestI)
        }
        return clusters.map { it.toSet() }.toSet()
    }

    @Test
    fun clusteringIsDeterministicForAGivenFaceOrder() {
        val input = mapOf(
            face("a") to vec(0.0),
            face("b") to vec(2.0),
            face("c") to vec(80.0),
            face("d") to vec(82.0),
        )

        counter = 0
        val first = cluster(input).map { it.faces.toSet() }.toSet()
        counter = 0
        val second = cluster(input).map { it.faces.toSet() }.toSet()

        assertEquals(first, second)
        assertEquals(2, first.size)
    }
}
