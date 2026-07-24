package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.insight.BandingStrategy
import com.vishalgupta.photoselector.domain.insight.InsightAnalysisStatus
import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightProvider
import com.vishalgupta.photoselector.domain.insight.InsightProviderId
import com.vishalgupta.photoselector.domain.insight.InsightRange
import com.vishalgupta.photoselector.domain.insight.InsightValue
import com.vishalgupta.photoselector.domain.insight.ScalarBanding
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightCoordinatorTest {

    private val providerId = InsightProviderId("sharpness")

    private fun photo(name: String) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/root/$name"),
        relativePath = name,
        fileName = name,
        sizeBytes = 1,
        lastModifiedEpochMs = 1,
    )

    private class FakeProvider(
        override val id: InsightProviderId,
        private val scores: Map<PhotoId, Float>,
    ) : InsightProvider {
        override val version = 1
        override val displayName = "Sharpness"
        override suspend fun value(photo: Photo): InsightValue? =
            scores[photo.id]?.let { InsightValue.Scalar(raw = it) }
    }

    private val medianBanding = object : BandingStrategy {
        override fun fromDistribution(raws: List<Float>): ScalarBanding = ScalarBanding.fromDistribution(
            values = raws, percentile = 0.5f,
            atOrAbove = InsightBand.Sharp, below = InsightBand.Soft,
            provisionalThreshold = 100f, provisionalRange = InsightRange(0f, 500f),
        )
        override fun provisional(): ScalarBanding = ScalarBanding.provisional(
            100f, InsightRange(0f, 500f), InsightBand.Sharp, InsightBand.Soft,
        )
    }

    private fun coordinator(scores: Map<PhotoId, Float>): InsightCoordinator = InsightCoordinator(
        bindings = listOf(InsightCoordinator.Binding(FakeProvider(providerId, scores), medianBanding)),
        parentJob = null,
        // Unconfined so the fire-and-forget analyze pass runs inline (the fake provider never suspends).
        dispatcher = Dispatchers.Unconfined,
    )

    private val a = photo("a")
    private val b = photo("b")
    private val c = photo("c")

    @Test fun `before analysis the resolver read is gated to null and status is NotAnalyzed`() = runTest {
        val coord = coordinator(mapOf(a.id to 10f, b.id to 50f, c.id to 90f))
        assertEquals(InsightAnalysisStatus.NotAnalyzed, coord.status.value)
        assertNull(coord.bandedValue(providerId, a))
    }

    @Test fun `analyze bands against the folder distribution and flips status to Analyzed`() = runTest {
        val coord = coordinator(mapOf(a.id to 10f, b.id to 50f, c.id to 90f))
        coord.analyze(listOf(a, b, c))

        assertEquals(InsightAnalysisStatus.Analyzed, coord.status.value)
        // Median (0.5 percentile, lower interpolation) of [10,50,90] is 50 → a Soft, b/c Sharp.
        assertEquals(InsightBand.Soft, (coord.bandedValue(providerId, a) as InsightValue.Scalar).band)
        assertEquals(InsightBand.Sharp, (coord.bandedValue(providerId, b) as InsightValue.Scalar).band)
        assertEquals(InsightBand.Sharp, (coord.bandedValue(providerId, c) as InsightValue.Scalar).band)
    }

    @Test fun `an unknown provider read is null (graceful)`() = runTest {
        val coord = coordinator(mapOf(a.id to 10f))
        coord.analyze(listOf(a))
        assertNull(coord.bandedValue(InsightProviderId("faces"), a))
    }

    @Test fun `staleness flips when the photo set diverges and back when it matches`() = runTest {
        val coord = coordinator(mapOf(a.id to 10f, b.id to 50f))
        coord.analyze(listOf(a, b))
        assertEquals(InsightAnalysisStatus.Analyzed, coord.status.value)

        coord.refreshStaleness(setOf(a.id)) // b removed
        assertEquals(InsightAnalysisStatus.Stale, coord.status.value)

        coord.refreshStaleness(setOf(a.id, b.id))
        assertEquals(InsightAnalysisStatus.Analyzed, coord.status.value)
    }

    @Test fun `refreshStaleness is a no-op before any analysis`() = runTest {
        val coord = coordinator(mapOf(a.id to 10f))
        coord.refreshStaleness(setOf(a.id, b.id))
        assertEquals(InsightAnalysisStatus.NotAnalyzed, coord.status.value)
    }

    @Test fun `the panel path bands provisionally before analysis and precisely after`() = runTest {
        val coord = coordinator(mapOf(a.id to 200f))
        // Pre-analysis: provisional absolute band (threshold 100) → 200 is Sharp.
        assertEquals(InsightBand.Sharp, (coord.computeBandedValue(providerId, a) as InsightValue.Scalar).band)

        coord.analyze(listOf(a))
        assertTrue(coord.isAnalyzed)
    }

    @Test fun `reset clears banding, analyzed set and status`() = runTest {
        val coord = coordinator(mapOf(a.id to 10f))
        coord.analyze(listOf(a))
        coord.reset()
        assertEquals(InsightAnalysisStatus.NotAnalyzed, coord.status.value)
        assertNull(coord.bandedValue(providerId, a))
    }
}
