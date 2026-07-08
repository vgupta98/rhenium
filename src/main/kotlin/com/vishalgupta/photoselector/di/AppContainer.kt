package com.vishalgupta.photoselector.di

import com.vishalgupta.photoselector.BuildConfig
import com.vishalgupta.photoselector.data.browse.JsonBrowsePositionRepository
import com.vishalgupta.photoselector.data.export.CompositePhotoExporter
import com.vishalgupta.photoselector.data.export.CopyPhotoExporter
import com.vishalgupta.photoselector.data.export.TxtPhotoExporter
import com.vishalgupta.photoselector.data.export.XmpSidecarPhotoExporter
import com.vishalgupta.photoselector.data.categories.JsonCategoriesRepository
import com.vishalgupta.photoselector.data.filesystem.FileSystemPhotoRepository
import com.vishalgupta.photoselector.data.prefs.JsonAppPreferences
import com.vishalgupta.photoselector.data.prefs.JsonXmpSyncPreferences
import com.vishalgupta.photoselector.data.update.HttpUpdateRepository
import com.vishalgupta.photoselector.data.ai.CachingPhotoGrouper
import com.vishalgupta.photoselector.data.ai.DownscaleGrayEmbeddingModel
import com.vishalgupta.photoselector.data.ai.EmbeddingCache
import com.vishalgupta.photoselector.data.ai.EmbeddingModel
import com.vishalgupta.photoselector.data.ai.GroupingResultCache
import com.vishalgupta.photoselector.data.ai.OnnxEmbeddingModel
import com.vishalgupta.photoselector.data.ai.PhotoFeatureExtractor
import com.vishalgupta.photoselector.data.ai.SimilarityPhotoGrouper
import com.vishalgupta.photoselector.data.trash.DesktopPhotoTrash
import com.vishalgupta.photoselector.data.format.CachingCaptureMetadataSource
import com.vishalgupta.photoselector.data.format.CompositeCaptureMetadataSource
import com.vishalgupta.photoselector.data.format.DefaultPhotoFormatRegistry
import com.vishalgupta.photoselector.data.format.ExifCaptureMetadataSource
import com.vishalgupta.photoselector.data.format.HeicCaptureMetadataSource
import com.vishalgupta.photoselector.data.format.HeicDecoder
import com.vishalgupta.photoselector.data.format.JpegDecoder
import com.vishalgupta.photoselector.data.format.PngDecoder
import com.vishalgupta.photoselector.data.format.RawDecoder
import com.vishalgupta.photoselector.data.format.SkiaImageDecoding
import com.vishalgupta.photoselector.data.image.DiskThumbnailCache
import com.vishalgupta.photoselector.data.image.ImageLoader
import com.vishalgupta.photoselector.data.image.SkikoImageLoader
import com.vishalgupta.photoselector.domain.format.PhotoFormatRegistry
import com.vishalgupta.photoselector.domain.grouping.PhotoGrouper
import com.vishalgupta.photoselector.domain.grouping.SimilarityGrouper
import com.vishalgupta.photoselector.domain.model.DecodedImage
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.BrowsePosition
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.AppPreferencesRepository
import com.vishalgupta.photoselector.domain.repository.BrowsePositionRepository
import com.vishalgupta.photoselector.domain.repository.PhotoExporter
import com.vishalgupta.photoselector.domain.repository.PhotoRepository
import com.vishalgupta.photoselector.domain.repository.PhotoTrash
import com.vishalgupta.photoselector.domain.repository.XmpSyncPreferences
import com.vishalgupta.photoselector.domain.update.AppVersion
import com.vishalgupta.photoselector.domain.update.CheckForUpdateUseCase
import com.vishalgupta.photoselector.domain.update.UpdateRepository
import com.vishalgupta.photoselector.domain.update.rolloutBucket
import com.vishalgupta.photoselector.domain.usecase.CopyPhotosToFolderUseCase
import com.vishalgupta.photoselector.domain.usecase.ExportPhotosTxtUseCase
import com.vishalgupta.photoselector.domain.usecase.ExportPhotosXmpUseCase
import com.vishalgupta.photoselector.domain.usecase.MovePhotosToTrashUseCase
import com.vishalgupta.photoselector.domain.usecase.ScanRootFolderUseCase
import com.vishalgupta.photoselector.presentation.browser.BrowserViewModel
import com.vishalgupta.photoselector.presentation.common.GroupingCoordinator
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.common.XmpSyncCoordinator
import com.vishalgupta.photoselector.presentation.common.MacSystemActions
import com.vishalgupta.photoselector.presentation.common.SystemActions
import com.vishalgupta.photoselector.presentation.grid.GridViewModel
import com.vishalgupta.photoselector.presentation.grid.LibraryRailViewModel
import com.vishalgupta.photoselector.presentation.inspect.InspectMode
import com.vishalgupta.photoselector.presentation.inspect.InspectViewModel
import com.vishalgupta.photoselector.presentation.navigation.CategoryScope
import com.vishalgupta.photoselector.presentation.navigation.GridRetentionKey
import com.vishalgupta.photoselector.presentation.navigation.MAX_INSPECT_GRID_PHOTOS
import com.vishalgupta.photoselector.presentation.navigation.Screen
import com.vishalgupta.photoselector.presentation.navigation.activeCategoryId
import com.vishalgupta.photoselector.presentation.navigation.slice
import com.vishalgupta.photoselector.presentation.rootpicker.RootFolderPickerViewModel
import com.vishalgupta.photoselector.presentation.survey.SurveyViewModel
import com.vishalgupta.photoselector.presentation.update.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AppContainer {

    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // Width of the shared decode pool. Reused to size the cold Similarity pass's parallel feature
    // extraction (SimilarityPhotoGrouper) — there it bounds how many frames are in flight at once (a
    // memory/CPU ceiling, since each frame decodes a 768px sharpness canvas and runs an inference).
    // The pass runs on Dispatchers.IO, not this pool; the shared width just keeps both sized to cores.
    private val decodeParallelism =
        Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(2)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val imageDecodeDispatcher = Dispatchers.IO.limitedParallelism(decodeParallelism)

    private val appScope = CoroutineScope(SupervisorJob() + imageDecodeDispatcher)
    private var _folderJob = SupervisorJob(appScope.coroutineContext[Job])
    val folderJob: Job get() = _folderJob

    private val formatRegistry: PhotoFormatRegistry = DefaultPhotoFormatRegistry(
        // HEIC and RAW are macOS-only for now (both ride the ImageIO bridge); off macOS they're
        // simply unregistered, so those files fall through the scanner rather than registering a
        // throwing decoder.
        decoders = buildList {
            add(JpegDecoder())
            add(PngDecoder())
            if (HeicDecoder.isSupportedOnThisPlatform()) add(HeicDecoder())
            if (RawDecoder.isSupportedOnThisPlatform()) add(RawDecoder())
        },
    )

    private val cacheDir: Path =
        Path.of(System.getProperty("user.home"), "Library", "Caches", "Rhenium")

    private val diskThumbnailCache = DiskThumbnailCache(cacheDir = cacheDir)
        .also { it.startEviction(appScope) }

    val imageLoader: ImageLoader = SkikoImageLoader(
        registry = formatRegistry,
        decodeDispatcher = imageDecodeDispatcher,
        diskCache = diskThumbnailCache,
    )

    // Shared across grids so each photo's capture metadata (time + camera) is read at most once per
    // session; the cache is what makes burst re-grouping on every re-slice cheap. JPEG goes through the
    // JVM EXIF reader; HEIC (macOS only) through the ImageIO bridge, so HEIC bursts now group too.
    private val captureMetadataSource = CachingCaptureMetadataSource(
        CompositeCaptureMetadataSource(
            buildList {
                add(ExifCaptureMetadataSource())
                if (HeicCaptureMetadataSource.isSupportedOnThisPlatform()) add(HeicCaptureMetadataSource())
            },
        ),
    )

    // Visual-similarity grouping (GroupingMode.Similarity). The learned MobileNetV3-Small embedder
    // (OnnxEmbeddingModel) is the on-device default; if its bundled blob can't load we fall back to
    // the classical, dependency-free DownscaleGrayEmbeddingModel so the lens still works. Either way
    // embeddings are derived from a small decode and persisted, keyed by content + model id, so the
    // cost is paid once and survives a restart (and a model swap re-keys the cache automatically).
    private val embeddingModel: EmbeddingModel = loadEmbeddingModel()
    private val embeddingCache = EmbeddingCache(cacheDir = cacheDir, modelId = embeddingModel.id)
        .also { it.startEviction(appScope) }
    // Memoizes the computed grouping (frame ids + key frame, not pixels) so re-entering the Similarity
    // lens on an unchanged folder is instant instead of re-running the model pass. Content+modelId
    // keyed, so a source edit or model swap re-keys it automatically (same discipline as embeddingCache).
    private val groupingResultCache = GroupingResultCache(cacheDir = cacheDir, json = json)
        .also { it.startEviction(appScope) }
    private val similarityGrouper: PhotoGrouper = CachingPhotoGrouper(
        delegate = SimilarityPhotoGrouper(
            PhotoFeatureExtractor(
                model = embeddingModel,
                cache = embeddingCache,
                decodeForEmbedding = ::decodeForEmbedding,
                decodeForSharpness = ::decodeForSharpness,
            ),
            concurrency = decodeParallelism,
            // Capture time corroborates the visual cut: the time-boosted rule also joins frames taken
            // within seconds of each other, recovering same-moment bursts the embedding alone missed.
            // Reuses the same memoized source as the Time lens; a frame with no capture time (a PNG,
            // a stripped JPEG, or HEIC off macOS) falls back to the visual cut.
            captureMetadataSource = captureMetadataSource,
            joinRule = SimilarityGrouper.timeBoosted(),
        ),
        cache = groupingResultCache,
        modelId = embeddingModel.id,
    )

    // Owns the one background Similarity pass, decoupled from any grid's displayed lens so it runs to
    // completion across lens switches and navigation. Process-lifetime parent (appScope) with [reset]
    // on a root change, so the stable [groupingActivity] flow can be collected once by the navigation
    // host for the off-grid hint. Runs on Dispatchers.IO, matching the grid's old grouping dispatcher.
    private val groupingCoordinator = GroupingCoordinator(
        grouper = similarityGrouper,
        parentJob = appScope.coroutineContext[Job],
        dispatcher = Dispatchers.IO,
    )

    /** Progress of the background Similarity pass (null when idle), for the navigation host's hint. */
    val groupingActivity: StateFlow<GroupingCoordinator.Progress?> get() = groupingCoordinator.progress

    private fun loadEmbeddingModel(): EmbeddingModel = try {
        OnnxEmbeddingModel.Loader.fromResource()
    } catch (t: Throwable) {
        System.err.println("ONNX embedder unavailable, falling back to classical embedder: ${t.message}")
        DownscaleGrayEmbeddingModel()
    }

    // Embedding and sharpness decode separately, at different sizes, on purpose — don't merge them
    // into one 768 decode to save the second pass. OnnxEmbeddingModel squashes its input to 224 with
    // a cheap 2-tap bilinear, so feeding it a 768 image would alias on that 768->224 step and degrade
    // the embedding; decoding embedding straight to 224 lets the decoder do a high-quality downscale.
    // Sharpness needs the opposite (a larger canonical canvas) — see decodeForSharpness.
    private suspend fun decodeForEmbedding(photo: Photo): DecodedImage? = try {
        formatRegistry.decoderFor(photo.absolutePath)?.decode(photo.absolutePath, EMBEDDING_EDGE_PX)
    } catch (_: Throwable) {
        null
    }

    private suspend fun decodeForSharpness(photo: Photo): DecodedImage? = try {
        // decode caps large frames at SHARPNESS_EDGE_PX; scaleUpToLongEdge brings smaller ones up to
        // it, so every frame is scored on one canonical canvas (rationale in scaleUpToLongEdge's kdoc).
        formatRegistry.decoderFor(photo.absolutePath)
            ?.decode(photo.absolutePath, SHARPNESS_EDGE_PX)
            ?.let { SkiaImageDecoding.scaleUpToLongEdge(it, SHARPNESS_EDGE_PX) }
    } catch (_: Throwable) {
        null
    }

    private val photoRepository: PhotoRepository = FileSystemPhotoRepository(formatRegistry)
    private val categoriesRepository: CategoriesRepository =
        JsonCategoriesRepository(json, scannedPhotos = { root -> photosFor(root) })
    private val browsePositionRepository: BrowsePositionRepository = JsonBrowsePositionRepository(json)
    // Global one-off flags (the first-run Similarity coachmark "seen" bit). One small JSON doc in the
    // cache dir, written through the shared AtomicJsonWriter.
    private val appPreferences: AppPreferencesRepository =
        JsonAppPreferences(cacheDir.resolve("preferences.json"), json)
    // Per-root "keep RAW XMP sidecars in sync" toggle, persisted in a per-root dotfile through the same
    // shared AtomicJsonWriter as categories/position (not the global cache-dir preferences.json).
    private val xmpSyncPreferences: XmpSyncPreferences = JsonXmpSyncPreferences(json)

    // Notify-only update checker. One shared HttpClient (the app's only network use) reads the hosted
    // manifest; the use case decides eligibility. The manifest URL is baked into BuildConfig at build time.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val updateRepository: UpdateRepository =
        HttpUpdateRepository(BuildConfig.UPDATE_MANIFEST_URL, json, httpClient)
    private val checkForUpdateUseCase = CheckForUpdateUseCase(updateRepository)

    private val exporter: PhotoExporter =
        CompositePhotoExporter(TxtPhotoExporter(), CopyPhotoExporter(), XmpSidecarPhotoExporter())
    private val photoTrash: PhotoTrash = DesktopPhotoTrash()

    private val scanUseCase = ScanRootFolderUseCase(photoRepository)
    private val exportTxtUseCase = ExportPhotosTxtUseCase(exporter)
    private val exportXmpUseCase = ExportPhotosXmpUseCase(exporter)
    private val copyPhotosUseCase = CopyPhotosToFolderUseCase(exporter)
    private val movePhotosToTrashUseCase = MovePhotosToTrashUseCase(photoTrash)

    val systemActions: SystemActions = MacSystemActions()

    val currentScreen = MutableStateFlow<Screen>(Screen.RootPicker)
    val currentPhotoPath = MutableStateFlow<Path?>(null)

    private var scannedPhotos: List<Photo> = emptyList()
    private var scannedRoot: RootFolder? = null

    // Live grids kept alive per (root, scope) for the session. A Grid -> Browser -> Grid round trip
    // reuses the retained view model (and the App keeps its scroll position) so the grid returns
    // exactly as it was left, instead of rebuilding and re-anchoring. Cleared on a root change.
    private val retainedGrids = mutableMapOf<GridRetentionKey, GridViewModel>()

    // The library rail is root-level chrome (same for every scope), so unlike the grids it is kept
    // per *root* — the navigation host mounts it outside the per-scope retention key, so one instance
    // serves every scope of a root and survives category switches. Cleared on a root change.
    private val retainedRails = mutableMapOf<Path, LibraryRailViewModel>()

    // Live XMP-sidecar sync is per root (a folder-level cull decision), so its coordinator is kept per
    // root alongside the rail — one instance owns the reconcile + live delta writes and the rail footer
    // reads its state. Parented to the folder job; reset + dropped on a root change.
    private val retainedXmpSync = mutableMapOf<Path, XmpSyncCoordinator>()

    // The grouping lens the user last picked, remembered for the session so the first grid built for
    // each (root, scope) opens in that lens rather than the default. Retained grids keep their own
    // lens across navigation; this seed only covers a freshly built one (a first visit, or a new scope).
    private var lastGroupingMode: GroupingMode = GroupingMode.Time

    private fun photosFor(root: RootFolder): List<Photo> =
        if (scannedRoot?.path == root.path) scannedPhotos else emptyList()

    fun loadBrowsePosition(root: RootFolder): BrowsePosition = browsePositionRepository.load(root)

    private fun setScanResult(root: RootFolder, photos: List<Photo>) {
        scannedRoot = root
        scannedPhotos = photos
    }

    /**
     * Drops just-trashed photos from the scan snapshot so any screen built *after* the delete
     * (navigation rebuilds every view model from [photosFor]) is constructed without them. The
     * live view model that triggered the delete updates its own in-memory copy separately.
     */
    private fun removeScannedPhotos(ids: Set<PhotoId>) {
        if (ids.isEmpty()) return
        scannedPhotos = scannedPhotos.filterNot { it.id in ids }
        // Keep every retained grid's own photo list in step: a delete from the browser must not
        // reappear when the user returns to a grid that is now reused rather than rebuilt. The grid
        // that originated the delete already pruned itself, so its own notification is a no-op.
        retainedGrids.values.forEach { it.removePhotos(ids) }
    }

    fun goTo(screen: Screen) {
        if (screen !is Screen.Browser) currentPhotoPath.value = null
        currentScreen.value = screen
    }

    /**
     * App-lifetime update-banner holder. Built once and parented to [appScope] (not a folder job) so the
     * single launch check survives root and screen changes. The whole feature is muted for a
     * Homebrew-managed install, which updates through `brew upgrade` — the in-app updater must not fight it.
     */
    fun updateViewModel(): UpdateViewModel = UpdateViewModel(
        checkForUpdate = checkForUpdateUseCase,
        currentVersion = AppVersion.parseOrNull(BuildConfig.VERSION) ?: AppVersion(0, 0, 0),
        osVersion = AppVersion.parseOrNull(System.getProperty("os.version").orEmpty()),
        rolloutBucket = rolloutBucket(appPreferences.installId()),
        autoCheckEnabled = appPreferences.isAutoUpdateCheckEnabled() && !isHomebrewManaged(),
        skippedVersion = { appPreferences.skippedUpdateVersion() },
        onSkipVersion = { appPreferences.setSkippedUpdateVersion(it) },
        openUrl = { systemActions.openUrl(it) },
        parentJob = appScope.coroutineContext[Job],
    )

    // Heuristic: a Homebrew cask records the app under a Caskroom prefix (arch-dependent). If one for
    // Rhenium exists, the install is brew-managed and self-update would desync brew's metadata, so we
    // defer to `brew upgrade` and suppress the in-app checker entirely.
    private fun isHomebrewManaged(): Boolean =
        sequenceOf("/opt/homebrew/Caskroom/rhenium", "/usr/local/Caskroom/rhenium")
            .any { Files.exists(Path.of(it)) }

    fun rootPickerViewModel(): RootFolderPickerViewModel = RootFolderPickerViewModel(
        scanRootFolder = scanUseCase,
        onScanComplete = { root, photos ->
            imageLoader.evictAll()
            setScanResult(root, photos)
            val position = browsePositionRepository.load(root)
            val scrollIndex = position.lastIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
            goTo(Screen.Grid(root, initialScrollIndex = scrollIndex, lastViewedPhotoId = position.lastPhotoId))
        },
        parentJob = appScope.coroutineContext[Job],
    )

    fun browserViewModel(
        root: RootFolder,
        initialIndex: Int,
        scope: CategoryScope,
    ): BrowserViewModel = BrowserViewModel(
        root = root,
        photos = photosForScope(root, scope),
        initialIndex = initialIndex,
        categories = categoriesRepository,
        moveToTrash = movePhotosToTrashUseCase,
        imageLoader = imageLoader,
        captureMetadataSource = captureMetadataSource,
        isReadOnly = categoriesRepository.isReadOnly(root),
        parentJob = folderJob,
        onPhotosDeleted = { ids -> removeScannedPhotos(ids) },
        // Only All Photos owns the per-root scroll index; a category view (its own
        // pushed instance) just records the last photo so a re-open lands near it.
        onPositionChanged = when (scope) {
            CategoryScope.AllPhotos -> { position ->
                appScope.launch { browsePositionRepository.save(root, position) }
            }
            is CategoryScope.Category -> { position ->
                appScope.launch { browsePositionRepository.saveLastPhotoId(root, position.lastPhotoId) }
            }
        },
    )

    /**
     * Inspect over the photos at [indices] in scope. Both facets run on a *subset* list (the selected
     * photos, re-indexed 0..n-1) so browse pages only this set and the grid's "n / N" reads as position
     * within it. The grid facet is built only when the set fits [MAX_INSPECT_GRID_PHOTOS]; a larger set
     * is browse-only (the factory returns null for the grid). The browse facet neither persists a scroll
     * position (it's an ephemeral set, not the All-Photos reel) nor deletes (move-to-Trash is disabled
     * while embedded), so both its scan hooks are null.
     */
    fun inspectViewModel(
        root: RootFolder,
        scope: CategoryScope,
        indices: List<Int>,
    ): InspectViewModel {
        val scoped = photosForScope(root, scope)
        val subset = indices.mapNotNull { scoped.getOrNull(it) }
        val isReadOnly = categoriesRepository.isReadOnly(root)
        val gridAvailable = subset.size in 2..MAX_INSPECT_GRID_PHOTOS

        val makeGrid: ((Int) -> SurveyViewModel)? = if (gridAvailable) {
            { initialActive ->
                SurveyViewModel(
                    root = root,
                    photos = subset,
                    indices = subset.indices.toList(),
                    categories = categoriesRepository,
                    imageLoader = imageLoader,
                    isReadOnly = isReadOnly,
                    parentJob = folderJob,
                ).also { it.setActive(initialActive) }
            }
        } else {
            null
        }

        val makeBrowse: (Int) -> BrowserViewModel = { initialIndex ->
            BrowserViewModel(
                root = root,
                photos = subset,
                initialIndex = initialIndex,
                categories = categoriesRepository,
                moveToTrash = movePhotosToTrashUseCase,
                imageLoader = imageLoader,
                captureMetadataSource = captureMetadataSource,
                isReadOnly = isReadOnly,
                parentJob = folderJob,
                onPositionChanged = null,
                // Embedded browse disables move-to-Trash (BrowserScreen gates it on !embedded), so
                // there is no delete path to purge from the scan here.
                onPhotosDeleted = null,
            )
        }

        return InspectViewModel(
            makeGrid = makeGrid,
            makeBrowse = makeBrowse,
            initialMode = if (gridAvailable) InspectMode.Grid else InspectMode.Browse,
            parentJob = folderJob,
        )
    }

    private fun photosForScope(root: RootFolder, scope: CategoryScope): List<Photo> {
        val members = categoriesRepository.observeMemberships(root).value[scope.activeCategoryId].orEmpty()
        return scope.slice(photosFor(root), members)
    }

    /**
     * The grid for (root, scope), reused across navigation for the session. A retained instance is
     * returned as-is (only its last-viewed marker is refreshed); the first visit builds and caches
     * one. Reuse is what makes the grid survive a Grid -> Browser -> Grid round trip with its groups,
     * focus and (via the App's held scroll state) scroll position intact.
     */
    fun gridViewModel(
        root: RootFolder,
        scope: CategoryScope,
        lastViewedPhotoId: PhotoId? = null,
    ): GridViewModel {
        val key = GridRetentionKey(root.path, scope)
        retainedGrids[key]?.let { retained ->
            retained.setLastViewed(lastViewedPhotoId)
            return retained
        }
        return GridViewModel(
            root = root,
            allPhotos = photosFor(root),
            categoryScope = scope,
            lastViewedPhotoId = lastViewedPhotoId,
            categories = categoriesRepository,
            exportTxt = exportTxtUseCase,
            copyToFolder = copyPhotosUseCase,
            moveToTrash = movePhotosToTrashUseCase,
            imageLoader = imageLoader,
            captureMetadataSource = captureMetadataSource,
            groupingCoordinator = groupingCoordinator,
            initialGroupingMode = lastGroupingMode,
            // Global flag, read at build time; the in-memory cache keeps later grids coherent once
            // it's been dismissed. The callback persists the dismissal so it never reappears.
            hasSeenSimilarityCoachmark = appPreferences.hasSeenSimilarityCoachmark(),
            onSimilarityCoachmarkSeen = { appPreferences.markSimilarityCoachmarkSeen() },
            parentJob = folderJob,
            onScrollIndexChanged = { index ->
                appScope.launch { browsePositionRepository.saveIndex(root, index) }
            },
            onGroupingModeChanged = { lastGroupingMode = it },
            onPhotosDeleted = { ids -> removeScannedPhotos(ids) },
        ).also { retainedGrids[key] = it }
    }

    /**
     * The library rail for [root], reused across every scope (and Grid -> Browser -> Grid round trip)
     * for the session. Root-scoped on purpose: the rail is identical for All Photos and each category,
     * so the host mounts one instance above the per-scope grid retention key.
     */
    fun libraryRailViewModel(root: RootFolder): LibraryRailViewModel =
        retainedRails.getOrPut(root.path) {
            LibraryRailViewModel(
                root = root,
                categories = categoriesRepository,
                moveToTrash = movePhotosToTrashUseCase,
                photosForRoot = { photosFor(root) },
                xmpSync = xmpSyncCoordinator(root),
                onPhotosDeleted = { ids -> removeScannedPhotos(ids) },
                parentJob = folderJob,
            )
        }

    /**
     * The live XMP-sidecar sync coordinator for [root], reused for the session. Owns the enable
     * reconcile + live delta writes and the persisted per-root toggle; the rail footer drives it.
     * Parented to the folder job, so a root change tears it down (and [resetForNewRoot] resets it).
     */
    private fun xmpSyncCoordinator(root: RootFolder): XmpSyncCoordinator =
        retainedXmpSync.getOrPut(root.path) {
            XmpSyncCoordinator(
                root = root,
                categories = categoriesRepository,
                exportXmp = exportXmpUseCase,
                preferences = xmpSyncPreferences,
                photosForRoot = { photosFor(root) },
                parentJob = folderJob,
                dispatcher = Dispatchers.IO,
            )
        }

    suspend fun resetForNewRoot() {
        // Flush each retained grid's pending scroll save, then drop them: a new root means a new set
        // of grids, and folderJob.cancel below tears their scopes down anyway.
        retainedGrids.values.forEach { it.onClear() }
        retainedGrids.clear()
        // Rails hold no pending writes to flush; folderJob.cancel below tears their scopes down, so
        // just drop the references for the new root.
        retainedRails.clear()
        // Stop any in-flight XMP sync and drop the per-root coordinators; folderJob.cancel tears their
        // scopes down too, but reset() cancels the observe/reconcile promptly and clears surfaced state.
        retainedXmpSync.values.forEach { it.reset() }
        retainedXmpSync.clear()
        // Drop any in-flight background Similarity pass and clear its progress; the coordinator object
        // itself stays (the host collects its flow), only the per-root work is reset.
        groupingCoordinator.reset()
        _folderJob.cancel()
        _folderJob = SupervisorJob(appScope.coroutineContext[Job])
        categoriesRepository.clearContext()
        imageLoader.evictAll()
        scannedRoot = null
        scannedPhotos = emptyList()
    }

    private companion object {
        // Edge length of the decode feeding the embedder. Sized to the learned model's 224px square
        // input so OnnxEmbeddingModel (and the classical fallback) downscales rather than upscales.
        const val EMBEDDING_EDGE_PX = 224

        // Canonical canvas every frame's sharpness is scored on (see decodeForSharpness): large
        // frames are decoded down to it and smaller frames scaled up to it. A few-pixel focus/motion
        // blur is sub-pixel at 224px (so adjacent frames would tie), and scoring frames at their
        // differing native sizes lets a low-resolution copy win on pixel-grid steepness — 768px on a
        // shared canvas avoids both while bounding the one-time cold-pass decode cost.
        const val SHARPNESS_EDGE_PX = 768
    }
}
