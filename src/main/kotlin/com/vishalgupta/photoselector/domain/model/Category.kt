package com.vishalgupta.photoselector.domain.model

import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightProviderId

/**
 * How a category's membership is decided. Orthogonal to [Category.builtIn] — a category can be
 * built-in or custom independently of being manual or smart.
 *
 * - [MANUAL]: membership is exactly the photos the user filed (the stored set). Favourites,
 *   Rejects, and every user-created bucket are manual.
 * - [SMART]: membership is a [CategoryRule] resolved over the photos, with manual pins/excludes
 *   layered on top by the repository. The "RAW files" demo category is the first smart one.
 */
enum class CategoryKind { MANUAL, SMART }

/**
 * A flat, per-root bucket of photos. [builtIn] is true for the categories the app always
 * provides — Favourites and Rejects, the two sides of the cull — which cannot be renamed or
 * deleted (see [BUILT_IN_IDS]).
 *
 * [kind] is orthogonal to [builtIn] (see [CategoryKind]): a [SMART][CategoryKind.SMART] category
 * carries a non-null [rule] describing how it self-fills; a [MANUAL][CategoryKind.MANUAL] one has
 * `rule == null`. Do NOT overload [builtIn] or an id as a "smartness" signal — read [kind]. Future
 * additions (colour, icon) stay additive: they decode through `ignoreUnknownKeys` and construct via
 * named args.
 */
data class Category(
    val id: CategoryId,
    val name: String,
    val builtIn: Boolean,
    val kind: CategoryKind = CategoryKind.MANUAL,
    val rule: CategoryRule? = null,
) {
    companion object {
        /** Fixed id of the built-in Favourites category, stable across roots and versions. */
        val FAVOURITES_ID: CategoryId = CategoryId("favourites")
        const val FAVOURITES_NAME: String = "Favourites"

        /** Fixed id of the built-in Rejects category (the cull's "reject" half), stable across roots/versions. */
        val REJECTS_ID: CategoryId = CategoryId("rejects")
        const val REJECTS_NAME: String = "Rejects"

        /** Fixed id of the always-provided "RAW files" smart category, stable across roots/versions. */
        val SMART_RAW_ID: CategoryId = CategoryId("smart-raw")
        const val SMART_RAW_NAME: String = "RAW files"

        /** Fixed id of the always-provided "Sharp" insight smart category, stable across roots/versions. */
        val SMART_SHARP_ID: CategoryId = CategoryId("smart-sharp")
        const val SMART_SHARP_NAME: String = "Sharp"

        fun favourites(): Category = Category(FAVOURITES_ID, FAVOURITES_NAME, builtIn = true)
        fun rejects(): Category = Category(REJECTS_ID, REJECTS_NAME, builtIn = true)

        /** The "RAW files" smart category: custom (not built-in) but rule-resolved, never AI. */
        fun smartRaw(): Category = Category(
            id = SMART_RAW_ID,
            name = SMART_RAW_NAME,
            builtIn = false,
            kind = CategoryKind.SMART,
            rule = CategoryRule.RawFiles,
        )

        /**
         * The "Sharp" insight smart category: rule-resolved from the bundled sharpness provider's banded
         * output (`sharpness in Sharp`). The first *insight-backed* smart category — it self-fills like
         * [smartRaw] but only after an explicit folder Analyze (its raw pass is gated), so pre-analysis it
         * reads as not-yet-populated rather than empty. See the insight-provider platform note.
         */
        fun smartSharp(): Category = Category(
            id = SMART_SHARP_ID,
            name = SMART_SHARP_NAME,
            builtIn = false,
            kind = CategoryKind.SMART,
            rule = CategoryRule.Leaf(
                providerId = InsightProviderId("sharpness"),
                comparator = InsightComparator.InBand,
                operand = InsightBand.Sharp,
            ),
        )

        /**
         * The built-in categories, in canonical display order: Favourites (keep) then Rejects
         * (reject). The single source of which buckets the app always provides — the repository
         * seeds and normalises against this, so adding a third built-in is one entry here.
         */
        val builtIns: List<Category> = listOf(favourites(), rejects())

        /**
         * The always-provided smart categories, seeded per root just like [builtIns]: the extension-based
         * "RAW files" and the insight-backed "Sharp". A future rule (AI or otherwise) is one more entry
         * here plus its resolver / provider.
         */
        val smartSeeds: List<Category> = listOf(smartRaw(), smartSharp())

        /** Ids of the built-in categories — none can be renamed or deleted. */
        val BUILT_IN_IDS: Set<CategoryId> = builtIns.mapTo(LinkedHashSet()) { it.id }

        /** Ids of the seeded smart categories — reseeded every load, so they can't be renamed or deleted. */
        val SMART_SEED_IDS: Set<CategoryId> = smartSeeds.mapTo(LinkedHashSet()) { it.id }
    }
}
