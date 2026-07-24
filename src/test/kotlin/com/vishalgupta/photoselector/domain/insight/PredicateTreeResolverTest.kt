package com.vishalgupta.photoselector.domain.insight

import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.CategoryRuleResolver
import com.vishalgupta.photoselector.domain.model.InsightComparator
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RawFilesResolver
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PredicateTreeResolverTest {

    private val sharpness = InsightProviderId("sharpness")
    private val faces = InsightProviderId("faces") // deliberately never seeded in the fake source

    private fun photo(name: String) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/root/$name"),
        relativePath = name,
        fileName = name,
        sizeBytes = 1,
        lastModifiedEpochMs = 1,
    )

    private val a = photo("a.jpg")
    private val b = photo("b.arw")
    private val c = photo("c.jpg")
    private val photos = listOf(a, b, c)

    /** Returns a banded scalar for known (provider, photo) pairs; null (⇒ no signal) otherwise. */
    private class FakeSource(private val bands: Map<Pair<InsightProviderId, PhotoId>, InsightBand>) : InsightSource {
        override suspend fun bandedValue(providerId: InsightProviderId, photo: Photo): InsightValue? {
            val band = bands[providerId to photo.id] ?: return null
            return InsightValue.Scalar(raw = 0f, band = band)
        }
    }

    private fun resolver(source: InsightSource): CategoryRuleResolver = PredicateTreeResolver(
        rawFilesResolver = RawFilesResolver(setOf("arw", "nef")),
        source = source,
    )

    private fun leaf(provider: InsightProviderId, band: InsightBand) =
        CategoryRule.Leaf(provider, InsightComparator.InBand, band)

    @Test fun `leaf matches only photos in the operand band`() = runTest {
        val source = FakeSource(
            mapOf(
                (sharpness to a.id) to InsightBand.Sharp,
                (sharpness to b.id) to InsightBand.Soft,
                (sharpness to c.id) to InsightBand.Sharp,
            ),
        )
        val matches = resolver(source).resolve(leaf(sharpness, InsightBand.Sharp), photos)
        assertEquals(setOf(a.id, c.id), matches)
    }

    @Test fun `and or not compose leaves`() = runTest {
        val source = FakeSource(
            mapOf(
                (sharpness to a.id) to InsightBand.Sharp,
                (sharpness to b.id) to InsightBand.Sharp,
                (faces to a.id) to InsightBand.Sharp, // reuse the band as a stand-in yes/no
            ),
        )
        val sharp = leaf(sharpness, InsightBand.Sharp)
        val hasFace = leaf(faces, InsightBand.Sharp)

        assertEquals(setOf(a.id), resolver(source).resolve(CategoryRule.And(listOf(sharp, hasFace)), photos))
        assertEquals(setOf(a.id, b.id), resolver(source).resolve(CategoryRule.Or(listOf(sharp, hasFace)), photos))
        // Not(sharp) = everything the sharp leaf did not match (c has no signal, b/c → complement of {a,b}).
        assertEquals(setOf(c.id), resolver(source).resolve(CategoryRule.Not(sharp), photos))
    }

    @Test fun `an unavailable provider leaf matches nothing, never crashes`() = runTest {
        // `faces` has no entries in the source (mirrors an unregistered/unentitled provider).
        val source = FakeSource(mapOf((sharpness to a.id) to InsightBand.Sharp))
        assertEquals(emptySet<PhotoId>(), resolver(source).resolve(leaf(faces, InsightBand.Sharp), photos))
        // Composed under And it degrades the whole rule to empty; the resolver still returns cleanly.
        val rule = CategoryRule.And(listOf(leaf(sharpness, InsightBand.Sharp), leaf(faces, InsightBand.Sharp)))
        assertEquals(emptySet<PhotoId>(), resolver(source).resolve(rule, photos))
    }

    @Test fun `raw files leaf is delegated to the raw resolver`() = runTest {
        val source = FakeSource(emptyMap())
        assertEquals(setOf(b.id), resolver(source).resolve(CategoryRule.RawFiles, photos))
    }
}
