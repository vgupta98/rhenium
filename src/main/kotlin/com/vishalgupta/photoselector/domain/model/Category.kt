package com.vishalgupta.photoselector.domain.model

/**
 * A flat, per-root bucket of photos. [builtIn] is true for the categories the app always
 * provides — Favourites and Rejects, the two sides of the cull — which cannot be renamed or
 * deleted (see [BUILT_IN_IDS]).
 *
 * Ship exactly these three fields. Future additions (colour, icon, a smart-filter
 * `kind`) are additive — they decode through `ignoreUnknownKeys` and construct via
 * named args — so do NOT overload [builtIn] or a built-in id as a "smartness"
 * signal; when `kind` arrives it is an orthogonal enum defaulting to MANUAL.
 */
data class Category(
    val id: CategoryId,
    val name: String,
    val builtIn: Boolean,
) {
    companion object {
        /** Fixed id of the built-in Favourites category, stable across roots and versions. */
        val FAVOURITES_ID: CategoryId = CategoryId("favourites")
        const val FAVOURITES_NAME: String = "Favourites"

        /** Fixed id of the built-in Rejects category (the cull's "reject" half), stable across roots/versions. */
        val REJECTS_ID: CategoryId = CategoryId("rejects")
        const val REJECTS_NAME: String = "Rejects"

        fun favourites(): Category = Category(FAVOURITES_ID, FAVOURITES_NAME, builtIn = true)
        fun rejects(): Category = Category(REJECTS_ID, REJECTS_NAME, builtIn = true)

        /**
         * The built-in categories, in canonical display order: Favourites (keep) then Rejects
         * (reject). The single source of which buckets the app always provides — the repository
         * seeds and normalises against this, so adding a third built-in is one entry here.
         */
        val builtIns: List<Category> = listOf(favourites(), rejects())

        /** Ids of the built-in categories — none can be renamed or deleted. */
        val BUILT_IN_IDS: Set<CategoryId> = builtIns.mapTo(LinkedHashSet()) { it.id }
    }
}
