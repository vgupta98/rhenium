package com.vishalgupta.photoselector.domain.insight

import kotlin.test.Test
import kotlin.test.assertEquals

class ScalarBandingTest {

    private val provisionalRange = InsightRange(0f, 100f)

    private fun banding(values: List<Float>, percentile: Float) = ScalarBanding.fromDistribution(
        values = values,
        percentile = percentile,
        atOrAbove = InsightBand.Sharp,
        below = InsightBand.Soft,
        provisionalThreshold = 50f,
        provisionalRange = provisionalRange,
    )

    @Test fun `median percentile splits the distribution into Sharp and Soft`() {
        // Scores 10..50; the 0.5 percentile (lower-interpolation) is 30, so >=30 is Sharp, <30 Soft.
        val b = banding(listOf(10f, 20f, 30f, 40f, 50f), percentile = 0.5f)
        assertEquals(InsightBand.Soft, b.band(10f))
        assertEquals(InsightBand.Soft, b.band(29f))
        assertEquals(InsightBand.Sharp, b.band(30f))
        assertEquals(InsightBand.Sharp, b.band(50f))
    }

    @Test fun `a higher percentile makes Sharp more selective`() {
        val b = banding(listOf(10f, 20f, 30f, 40f, 50f), percentile = 0.8f)
        // 0.8 percentile of 5 sorted values → index 3 → 40.
        assertEquals(InsightBand.Soft, b.band(39f))
        assertEquals(InsightBand.Sharp, b.band(40f))
    }

    @Test fun `range spans the observed min and max for the panel bar`() {
        val b = banding(listOf(12f, 88f, 40f), percentile = 0.5f)
        assertEquals(12f, b.range.min)
        assertEquals(88f, b.range.max)
    }

    @Test fun `an empty distribution falls back to the provisional banding`() {
        val b = banding(emptyList(), percentile = 0.5f)
        assertEquals(50f, b.threshold)
        assertEquals(InsightBand.Soft, b.band(49f))
        assertEquals(InsightBand.Sharp, b.band(51f))
    }

    @Test fun `scalar carries the raw value, range and derived band`() {
        val b = banding(listOf(10f, 50f), percentile = 0.5f)
        val scalar = b.scalar(50f)
        assertEquals(50f, scalar.raw)
        assertEquals(InsightBand.Sharp, scalar.band)
        assertEquals(InsightRange(10f, 50f), scalar.range)
    }
}
