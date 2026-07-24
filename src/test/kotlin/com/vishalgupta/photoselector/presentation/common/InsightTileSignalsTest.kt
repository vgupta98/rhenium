package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.designsystem.atom.TileSignalTint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InsightTileSignalsTest {

    private val keepers = Category(CategoryId("keepers"), "Keepers", builtIn = false)
    private val categories = listOf(
        Category.favourites(),
        Category.rejects(),
        Category.smartRaw(),   // SMART but rule references no insight provider
        Category.smartSharp(), // SMART + insight-backed
        keepers,               // MANUAL
    )

    private val a = PhotoId("a") // member of the insight-backed Sharp category
    private val b = PhotoId("b") // member of RawFiles (smart, but not insight-backed)
    private val c = PhotoId("c") // member of a manual category
    private val d = PhotoId("d") // member of nothing

    private val memberships = mapOf(
        Category.SMART_SHARP_ID to setOf(a),
        Category.SMART_RAW_ID to setOf(b),
        keepers.id to setOf(c),
        Category.FAVOURITES_ID to setOf(a, b, c),
    )

    @Test fun `emits a chip for an insight-backed smart category member`() {
        val signals = insightTileSignals(a, categories, memberships)
        assertEquals(1, signals.size)
        assertEquals("Sharp", signals.single().label)
        assertEquals(TileSignalTint.Positive, signals.single().tint)
    }

    @Test fun `emits nothing for a RawFiles (non-insight) smart membership`() {
        assertTrue(insightTileSignals(b, categories, memberships).isEmpty())
    }

    @Test fun `emits nothing for a manual membership`() {
        assertTrue(insightTileSignals(c, categories, memberships).isEmpty())
    }

    @Test fun `emits nothing for a photo in no category`() {
        assertTrue(insightTileSignals(d, categories, memberships).isEmpty())
    }

    @Test fun `the set overload agrees with the map overload`() {
        val memberOf = setOf(Category.SMART_SHARP_ID, Category.FAVOURITES_ID)
        assertEquals("Sharp", insightTileSignals(memberOf, categories).single().label)
        assertTrue(insightTileSignals(setOf(Category.SMART_RAW_ID), categories).isEmpty())
    }
}
