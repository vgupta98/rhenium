package com.vishalgupta.photoselector.presentation.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.domain.grouping.CaptureMetadata
import com.vishalgupta.photoselector.domain.grouping.CaptureMetadataSource
import com.vishalgupta.photoselector.domain.insight.LabeledInsight
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.BrowsePosition
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.presentation.StateHolder
import com.vishalgupta.photoselector.presentation.common.CategoryToggle
import com.vishalgupta.photoselector.presentation.common.InsightCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

data class BrowserUiState(
    val photos: List<Photo>,
    val currentIndex: Int,
    val currentPhoto: Photo?,
    val currentBitmap: ImageBitmap?,
    val isLoadingBitmap: Boolean,
    val isCurrentFavourite: Boolean,
    val readOnly: Boolean,
    /** All categories for this root, Favourites first — the HUD legend. */
    val categories: List<Category> = emptyList(),
    /** Which categories the current photo belongs to — which HUD chips are lit. */
    val currentMemberships: Set<CategoryId> = emptySet(),
    /**
     * Capture facts (time + camera) for the current photo, read lazily off-thread through the shared
     * memoized [CaptureMetadataSource]; null until loaded (or when nothing is readable). Feeds the
     * details panel only.
     */
    val captureMetadata: CaptureMetadata? = null,
    /**
     * Per-photo AI insights (label + banded value) for the details panel, computed lazily off-thread only
     * while the panel is open. Empty until loaded (or when nothing is assessable). Rendered generically
     * over the taxonomy, not hardcoded to sharpness.
     */
    val insights: List<LabeledInsight> = emptyList(),
) {
    companion object {
        fun initial(photos: List<Photo>) = BrowserUiState(
            photos = photos,
            currentIndex = 0,
            currentPhoto = photos.firstOrNull(),
            currentBitmap = null,
            isLoadingBitmap = photos.isNotEmpty(),
            isCurrentFavourite = false,
            readOnly = false,
        )
    }
}

class BrowserViewModel(
    private val root: RootFolder,
    // Mutable: deleting the current photo drops it here and re-points the index at its neighbour.
    private var photos: List<Photo>,
    private val initialIndex: Int,
    private val categories: CategoriesRepository,
    private val moveToTrash: MovePhotosToTrashUseCase,
    private val imageLoader: ImageLoader,
    private val captureMetadataSource: CaptureMetadataSource,
    private val isReadOnly: StateFlow<Boolean>,
    // The gated insight coordinator, for the details panel's lazy per-photo compute. Nullable so tests /
    // callers without the insight platform omit it (the panel simply shows no insight rows).
    private val insights: InsightCoordinator? = null,
    parentJob: Job? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing,
    private val onPositionChanged: ((BrowsePosition) -> Unit)? = null,
    private val onPhotosDeleted: ((Set<PhotoId>) -> Unit)? = null,
) : StateHolder(parentJob, dispatcher) {

    // The HUD shows every category and toggles any of them; F still maps to the built-in
    // Favourites regardless of which category grid the user paged in from.
    private val categoriesFlow: StateFlow<List<Category>> = categories.observeCategories(root)
    private val membershipsFlow: StateFlow<Map<CategoryId, Set<PhotoId>>> = categories.observeMemberships(root)

    private fun favourites(): Set<PhotoId> = membershipsFlow.value[Category.FAVOURITES_ID].orEmpty()

    /** The categories the given photo currently belongs to, for lighting the HUD chips. */
    private fun membershipsOf(photo: Photo?): Set<CategoryId> {
        if (photo == null) return emptySet()
        return membershipsFlow.value.filterValues { photo.id in it }.keys
    }

    private val _state = MutableStateFlow(
        run {
            val safeIndex = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
            val firstPhoto = photos.getOrNull(safeIndex)
            val favs = favourites()
            BrowserUiState.initial(photos).copy(
                currentIndex = safeIndex,
                currentPhoto = firstPhoto,
                isCurrentFavourite = firstPhoto != null && firstPhoto.id in favs,
                categories = categoriesFlow.value,
                currentMemberships = membershipsOf(firstPhoto),
            )
        },
    )
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _toggleEvents = Channel<CategoryToggle>(Channel.BUFFERED)
    val toggleEvents: Flow<CategoryToggle> = _toggleEvents.receiveAsFlow()

    // One-shot, single-consumer message after a delete (confirmation or failure) — same handoff
    // shape as [toggleEvents]; the screen surfaces it as a transient pill.
    private val _deleteEvents = Channel<String>(Channel.BUFFERED)
    val deleteEvents: Flow<String> = _deleteEvents.receiveAsFlow()

    private var loadJob: Job? = null
    private var metadataJob: Job? = null
    private var insightsJob: Job? = null
    // Whether the details panel is currently latched open; gates the per-photo insight compute so we
    // never decode a browsed frame's sharpness while the panel is closed.
    private var detailsPanelOpen: Boolean = false
    private var positionSaveJob: Job? = null
    private var pendingSavePosition: BrowsePosition? = null
    private var viewportLongEdgePx: Int = 1600

    init {
        combine(categoriesFlow, membershipsFlow, isReadOnly) { cats, members, readOnly ->
            Triple(cats, members, readOnly)
        }
            .onEach { (cats, members, readOnly) ->
                val favs = members[Category.FAVOURITES_ID].orEmpty()
                _state.update {
                    val photo = it.currentPhoto
                    it.copy(
                        isCurrentFavourite = photo != null && photo.id in favs,
                        readOnly = readOnly,
                        categories = cats,
                        currentMemberships = if (photo == null) emptySet()
                        else members.filterValues { ids -> photo.id in ids }.keys,
                    )
                }
            }
            .launchIn(scope)
        scheduleSavePosition()
    }

    fun setViewportLongEdgePx(px: Int) {
        if (px <= 0 || px == viewportLongEdgePx) return
        viewportLongEdgePx = px
        loadCurrent()
        prefetchAround()
    }

    fun next() = jumpTo(_state.value.currentIndex + 1)
    fun previous() = jumpTo(_state.value.currentIndex - 1)

    fun jumpTo(index: Int) {
        if (photos.isEmpty()) return
        val bounded = ((index % photos.size) + photos.size) % photos.size
        if (bounded == _state.value.currentIndex && _state.value.currentBitmap != null) return
        val photo = photos[bounded]
        _state.update {
            it.copy(
                currentIndex = bounded,
                currentPhoto = photo,
                currentBitmap = null,
                isLoadingBitmap = true,
                isCurrentFavourite = photo.id in favourites(),
                currentMemberships = membershipsOf(photo),
                captureMetadata = null,
                insights = emptyList(),
            )
        }
        scheduleSavePosition()
        imageLoader.unpinAllExcept(photo.id)
        imageLoader.pin(photo.id)
        loadCurrent()
        loadCaptureMetadata()
        if (detailsPanelOpen) loadInsights()
        prefetchAround()
    }

    private fun scheduleSavePosition() {
        val save = onPositionChanged ?: return
        val photo = _state.value.currentPhoto ?: return
        val position = BrowsePosition(_state.value.currentIndex, photo.id)
        pendingSavePosition = position
        positionSaveJob?.cancel()
        positionSaveJob = scope.launch {
            delay(500)
            save(position)
            pendingSavePosition = null
        }
    }

    override fun onClear() {
        val pending = pendingSavePosition
        if (pending != null) {
            onPositionChanged?.invoke(pending)
        }
        super.onClear()
    }

    /** Toggle the current photo in [categoryId] (F = Favourites, a digit = a custom category). */
    fun toggleCategory(categoryId: CategoryId) {
        val photo = _state.value.currentPhoto ?: return
        val name = categoriesFlow.value.firstOrNull { it.id == categoryId }?.name ?: return
        scope.launch {
            val added = categories.toggleMembership(root, categoryId, photo.id)
            _toggleEvents.trySend(
                CategoryToggle(
                    categoryName = name,
                    isFavourite = categoryId == Category.FAVOURITES_ID,
                    added = added,
                ),
            )
        }
    }

    /**
     * Moves the current photo to the Trash, removes it from the reel, and lands on its neighbour
     * (the next photo, or the previous one if it was last). Purges it from every category and
     * tells the container. Best-effort: a failure leaves the photo in place and reports via
     * [deleteEvents]. The screen confirms first; this performs the delete.
     */
    fun deleteCurrent() {
        // Snapshot the target and its index together, before the suspend: if the user navigates
        // while the move is in flight, the deleted photo and the landing position must come from
        // the same frame, or focus lands a slot off the reel.
        val snapshot = _state.value
        val photo = snapshot.currentPhoto ?: return
        val idx = snapshot.currentIndex
        scope.launch {
            val report = try {
                moveToTrash.invoke(listOf(photo))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _deleteEvents.trySend("Delete failed: ${t.message}")
                return@launch
            }
            if (report.trashed == 0) {
                _deleteEvents.trySend("Couldn't move to Trash")
                return@launch
            }
            val deletedId = photo.id
            photos = photos.filterNot { it.id == deletedId }
            categories.removeMemberships(root, setOf(deletedId))
            onPhotosDeleted?.invoke(setOf(deletedId))
            if (photos.isEmpty()) {
                imageLoader.unpinAllExcept(null)
                _state.update {
                    it.copy(
                        photos = emptyList(),
                        currentIndex = 0,
                        currentPhoto = null,
                        currentBitmap = null,
                        isLoadingBitmap = false,
                        isCurrentFavourite = false,
                        currentMemberships = emptySet(),
                        captureMetadata = null,
                        insights = emptyList(),
                    )
                }
            } else {
                val newIndex = idx.coerceAtMost(photos.size - 1)
                val newPhoto = photos[newIndex]
                _state.update {
                    it.copy(
                        photos = photos,
                        currentIndex = newIndex,
                        currentPhoto = newPhoto,
                        currentBitmap = null,
                        isLoadingBitmap = true,
                        isCurrentFavourite = newPhoto.id in favourites(),
                        currentMemberships = membershipsOf(newPhoto),
                        captureMetadata = null,
                        insights = emptyList(),
                    )
                }
                imageLoader.unpinAllExcept(newPhoto.id)
                imageLoader.pin(newPhoto.id)
                loadCurrent()
                loadCaptureMetadata()
                if (detailsPanelOpen) loadInsights()
                prefetchAround()
                scheduleSavePosition()
            }
            _deleteEvents.trySend("Moved to Trash")
        }
    }

    private fun loadCurrent() {
        loadJob?.cancel()
        val photo = _state.value.currentPhoto ?: return
        loadJob = scope.launch {
            val bmp = imageLoader.load(photo, viewportLongEdgePx)
            _state.update {
                if (it.currentPhoto?.id == photo.id) {
                    it.copy(currentBitmap = bmp, isLoadingBitmap = false)
                } else {
                    it
                }
            }
        }
    }

    /**
     * Reads the current photo's capture facts off the main thread (the source itself is memoized, so a
     * revisit is a map lookup) and drops them into state for the details panel. Guarded by the photo id
     * on write, so a fast page-through never lands a stale read on the wrong photo.
     */
    private fun loadCaptureMetadata() {
        metadataJob?.cancel()
        val photo = _state.value.currentPhoto ?: return
        metadataJob = scope.launch {
            val meta = withContext(Dispatchers.IO) { captureMetadataSource.metadataFor(photo) }
            _state.update {
                if (it.currentPhoto?.id == photo.id) it.copy(captureMetadata = meta) else it
            }
        }
    }

    /** The details panel's `I` latch; opening it computes the current photo's insights, closing it stops. */
    fun setDetailsPanelOpen(open: Boolean) {
        detailsPanelOpen = open
        if (open) loadInsights() else insightsJob?.cancel()
    }

    /**
     * Computes the current photo's insights off-thread for the panel (one cheap decode each, cache-filled),
     * guarded by the photo id on write so a fast page-through never lands a stale read on the wrong photo.
     * No-op when the insight platform isn't wired.
     */
    private fun loadInsights() {
        insightsJob?.cancel()
        val coordinator = insights ?: return
        val photo = _state.value.currentPhoto ?: return
        insightsJob = scope.launch {
            val rows = withContext(Dispatchers.IO) { coordinator.insightsFor(photo) }
            _state.update {
                if (it.currentPhoto?.id == photo.id) it.copy(insights = rows) else it
            }
        }
    }

    private fun prefetchAround() {
        if (photos.isEmpty()) return
        val idx = _state.value.currentIndex
        val targets = listOfNotNull(
            photos.getOrNull(idx + 1),
            photos.getOrNull(idx - 1),
            photos.getOrNull(idx + 2),
            photos.getOrNull(idx + 3),
        )
        imageLoader.prefetch(targets, viewportLongEdgePx, scope)
    }

    fun loadIfNeeded() {
        if (_state.value.currentBitmap == null && _state.value.currentPhoto != null) {
            loadCurrent()
            prefetchAround()
        }
        // Details-panel facts for the initial photo; cheap + memoized, guarded by id on write.
        if (_state.value.captureMetadata == null && _state.value.currentPhoto != null) {
            loadCaptureMetadata()
        }
    }

    fun photoIdAtCurrent(): PhotoId? = _state.value.currentPhoto?.id
}
