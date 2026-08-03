package com.vishalgupta.photoselector.presentation.navigation

import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import java.nio.file.Path

/**
 * Identity of a retained grid: one live [com.vishalgupta.photoselector.presentation.grid.GridViewModel]
 * and one scroll position are kept per (root, scope) for the session, so a Grid -> Browser -> Grid
 * round trip returns to the grid exactly as it was left rather than rebuilding (and re-anchoring,
 * which lost the precise offset and fought the regroup reshape). Keyed by [Path] (not [RootFolder])
 * so it stays a stable value key across rescans.
 */
data class GridRetentionKey(val rootPath: Path, val scope: CategoryScope)

sealed interface Screen {
    data object RootPicker : Screen
    data class Grid(
        val root: RootFolder,
        val scope: CategoryScope = CategoryScope.AllPhotos,
        val initialScrollIndex: Int = 0,
        val lastViewedPhotoId: PhotoId? = null,
        val returnScrollIndex: Int? = null,
        // A photo to scroll into view on arrival, independent of whether the keyboard ring is showing
        // — the "resume where I was looking" / "Show in All Photos" intent. Distinct from
        // [lastViewedPhotoId], which is ONLY the passive underline marker: a retained grid is returned
        // to as-left, so scroll resume can't piggy-back on the marker (a mouse-only user has no ring,
        // and the marker also leaks across scopes). Null leaves the retained scroll untouched.
        val revealPhotoId: PhotoId? = null,
        // Whether reaching [revealPhotoId] should also seat the keyboard ring on it. True only for the
        // deliberate "Show in All Photos" jump (so the photo is unmistakable among thousands); a passive
        // same-scope resume scrolls without spawning a ring, leaving the mouse-only ring rules alone.
        val focusRevealedPhoto: Boolean = false,
    ) : Screen
    data class Browser(
        val root: RootFolder,
        val initialIndex: Int = 0,
        val scope: CategoryScope = CategoryScope.AllPhotos,
        // Carried through so backing out of the browser into a category grid, then to All
        // Photos, restores the All-Photos scroll position (PR #32 review Q#2).
        val returnScrollIndex: Int? = null,
    ) : Screen
    /**
     * Inspect: look at a fixed set of [indices] (the photos selected in the grid, or a group
     * opened for review, or a current+next pair from the browser) together. Each index points
     * into the scoped photo list (the same list the browser pages through). Inspect has two
     * presentations behind one toggle — an overview *grid* (the keepers laid out fit-to-cell) and
     * a *browse* mode (the full-screen single-photo browser walking only this set). Up to
     * [MAX_INSPECT_GRID_PHOTOS] it opens on the grid; a larger set (e.g. a long burst) opens
     * straight into browse with the grid disabled, since dozens of tiles are too small to pick
     * between. [returnScrollIndex] restores the source's scroll on exit; [origin] decides whether
     * exit lands back on the grid or the full-screen browser (the active photo).
     */
    data class Inspect(
        val root: RootFolder,
        val scope: CategoryScope = CategoryScope.AllPhotos,
        val indices: List<Int>,
        val returnScrollIndex: Int? = null,
        val origin: InspectOrigin = InspectOrigin.Grid,
    ) : Screen

    /**
     * People: name the clusters the face scan found, so each named person becomes a smart category.
     *
     * A sibling of [Grid], **not** a [CategoryScope] — a scope stays closed and predicate-blind (a
     * person's *photos* ride `CategoryScope.Category`, like any other bucket; this screen is the
     * naming surface, which is a different job). It renders full-screen with its own top bar, so the
     * library rail (mounted only inside the Grid branch) leaves composition while it is up.
     *
     * [returnScope] is the grid scope to land back on. No scroll index travels either way: the grid's
     * view model and scroll state are both retained across this round trip, so returning without one
     * is a *warm* return — the same rule the rail's own navigation follows.
     */
    data class People(
        val root: RootFolder,
        val returnScope: CategoryScope = CategoryScope.AllPhotos,
    ) : Screen
}

/** Where an [Screen.Inspect] was opened from, so it can return there on exit. */
enum class InspectOrigin { Grid, Browser }

/**
 * Up to this many photos, Inspect opens on its overview *grid* (every tile pinned and decoded at
 * its cell size); beyond it, a set opens straight into *browse* mode with the grid disabled, where
 * one photo decodes at a time. The bound exists because the grid is non-lazy and beyond a dozen the
 * tiles are too small to choose between — but a large set (a long burst) is no longer declined, it
 * just skips the grid. So a stray `Cmd+A` then `C` can't freeze the app or blow the image cache.
 */
const val MAX_INSPECT_GRID_PHOTOS = 12
