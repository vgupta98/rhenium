# Code map

<!--
  Read this first when you need to find where something lives, so you open the
  right two files instead of grepping the tree. Paths are from repo root; line
  numbers are deliberately omitted (they rot fastest). The behavioural rules,
  gotchas, and the grid invariants live in /CLAUDE.md, not here.

  KEEP THIS CURRENT: when a file under src/main is added, deleted, renamed, or
  repurposed, refresh this map so it doesn't drift from the tree. See CLAUDE.md
  -> Conventions.
-->

Source root: `src/main/kotlin/com/vishalgupta/photoselector/`. Clean
architecture, single Gradle module: `domain` (pure) → `data` (impls) →
`presentation` (Compose), wired by hand in `di/AppContainer.kt`.

## Entry points

- `Main.kt` — process entry, window bootstrap.
- `App.kt` — root composable; owns the `Screen` back-stack and top-level state.
- `di/AppContainer.kt` — **manual DI; all wiring lives here.** Start here to
  trace a dependency or to add a screen/repository/decoder.

## domain/ — pure, no framework deps

- `model/` — entities: `Photo`, `PhotoId`, `RootFolder` (also owns the per-root sidecar
  file names, incl. `peopleFile`), `Category` (now carries a
  `kind: CategoryKind` (`MANUAL | SMART`, orthogonal to `builtIn`) and a nullable
  `rule: CategoryRule`; seeds `smartRaw()`/`smartSeeds`), `CategoryId`, `CategoryRule`
  (`RawFiles | Person(personId)` + the `CategoryRuleResolver` seam, the pure
  `RawFilesResolver` — extension classification over an injected RAW-extension set — and
  `CompositeCategoryRuleResolver`, which chains resolvers "first to claim the rule wins"),
  `PhotoGroup` (`Single | Burst`; `Burst.keyIndex` = representative frame),
  `DecodedImage`, `ScanProgress`.
- `repository/` — interfaces: `PhotoRepository`, `CategoriesRepository`,
  `BrowsePositionRepository`, `AppPreferencesRepository`, `PhotoExporter`, `PhotoTrash`,
  `PeopleRepository`.
- `usecase/` — `ScanRootFolderUseCase`, `CopyPhotosToFolderUseCase`,
  `ExportPhotosTxtUseCase`, `ExportPhotosXmpUseCase`, `MovePhotosToTrashUseCase`.
- `grouping/` — the grouping seam: `PhotoGrouper` (an interface with one suspend
  `group(...)` method), `BurstGrouper` (object; time + camera), `SimilarityGrouper`
  (object; visual — `ThresholdRule` seam, `Adaptive` per-event cut is the default,
  `fixed()` the legacy constant floor; plus a `JoinRule` seam, `timeBoosted`
  default adds same-moment frames via the capture-time gap, `VisualOnly` the
  no-time fallback), `CaptureMetadata` + `CaptureMetadataSource`.
- `faces/` — the face pipeline's pure half, mirroring `grouping/`: `Face.kt` (`FaceId`,
  `FaceRef` — a face id plus its normalised box and detector score, what a `Person` carries and the
  sidecar stores, so a crop needs neither the models nor the face cache —
  `FaceBox`/`FacePoint`, `FaceDetection`, the `FaceEmbedding` value class, `PhotoFaces`),
  `Person.kt` (`PersonId`, `Person`; `name` + `dismissed` are the user-authored fields, with
  `isAnchor`/`coverFace` derived from them), the `FaceDetector` / `FaceEmbedder` seams, and three
  algorithm objects: `YuNetPostProcessing` (grid decode + NMS + letterbox mapping, ported from
  OpenCV's `face_detect.cpp`), `FaceAlignment` (5-point similarity transform onto SFace's
  112x112 template), `FaceClusterer` (average-linkage agglomerative over cosine distance behind a
  `ThresholdRule` seam; a recluster re-matches every **anchor** — named *or* dismissed — by centroid
  first, so neither a name nor a "not a person" verdict is lost to a rescan).
  `PersonCategoryRuleResolver` bridges a person to a smart category.
- `format/` — `PhotoDecoder`, `PhotoFormat`, `PhotoFormatRegistry` interfaces.
- `update/` — the notify-only update checker: `AppVersion` (tolerant SemVer +
  `rolloutBucket`), `UpdateManifest`/`UpdateStatus`, the `UpdateRepository` seam, and
  `CheckForUpdateUseCase` (pure eligibility: newer / OS floor / staged-rollout wave /
  mandatory floor).

## data/ — implementations

- `filesystem/` — `FileSystemPhotoRepository` (scans a root into `Photo`s),
  `PathFilters` (include/exclude rules).
- `categories/` — `JsonCategoriesRepository` (membership persistence + v2
  migration; also the one place stored + rule-computed membership merge — seeds the
  `smart-raw` category, resolves smart rules off-thread on an injected scope, folds
  `(ruleMatches ∪ pins) \ excludes`, and prunes redundant overrides on rescan),
  `CategoriesFile` (on-disk schema; `CategoryDto` gained additive `rule` +
  `excluded`, still v2 via `ignoreUnknownKeys`), `MembershipResolver`.
- `browse/` — `JsonBrowsePositionRepository` (persists last scroll position).
- `image/` — decode + cache: `SkikoImageLoader`, `ImageLoader`/`ImageCache`,
  `DiskThumbnailCache`.
- `format/` — per-format decoders (`JpegDecoder`, `PngDecoder`, `HeicDecoder`,
  `RawDecoder`), `DefaultPhotoFormatRegistry`, `SkiaImageDecoding`. Capture
  metadata (time + camera) is read per-format and chained: `ExifReader`
  (JPEG-only) backs `ExifCaptureMetadataSource`; `HeicCaptureMetadataSource`
  reads HEIC via `MacImageIO`; `CompositeCaptureMetadataSource` returns the first
  non-NONE; `CachingCaptureMetadataSource` memoises the lot. Raw→domain shaping is
  shared in `ExifCaptureMetadataSource.kt` (`toCaptureMetadata`).
- `format/macos/` — `MacImageIO` (JNA→ImageIO bridge: decodes HEIC *and* RAW, and
  reads HEIC capture metadata via `CGImageSourceCopyPropertiesAtIndex`; macOS only).
- `ai/` — similarity pipeline: `EmbeddingModel` seam → `OnnxEmbeddingModel`
  (default) / `DownscaleGrayEmbeddingModel` (fallback), `GrayBuffer`,
  `SharpnessScorer`, `PhotoFeatureExtractor` (+ `PhotoFeatures`), `EmbeddingCache`,
  `SimilarityPhotoGrouper` (adapter onto `PhotoGrouper`); `GroupingResultCache` +
  `CachingPhotoGrouper` (memoize the computed grouping so lens re-entry is instant).
- `faces/` — the face pipeline's impure half, mirroring `ai/`: `OnnxFaceDetector` (YuNet) and
  `OnnxFaceEmbedder` (SFace) — same `Loader.fromResource()` / probed-`dimensions` /
  null-on-failure shape as `OnnxEmbeddingModel`, lazily constructed in DI; `FaceCache` (per-photo
  detections + embeddings); `FaceScanner` (the bounded-parallel whole-root pass, structured like
  `SimilarityPhotoGrouper.group`); `JsonPeopleRepository` + `PeopleFile` (the v1
  `.photo-selector-people.json` sidecar, unknown fields carried through a rewrite; `FaceRefDto`
  gained additive `box`/`score` and `PersonDto` an additive `dismissed`, still v1. `isUnreadable` is
  the can't-read-so-every-write-is-discarded signal the UI must surface; `deleteAll` is the one
  mutation that deliberately overrides the refuse-to-write posture, for the purge).
- `prefs/` — `JsonAppPreferences` (global one-off flags: the first-run Similarity
  coachmark "seen" bit, plus the update checker's opt-out / skipped-version / stable
  rollout install-id; one small JSON via `AtomicJsonWriter`). Per-root
  `JsonXmpSyncPreferences` (the XMP-sync on/off bit, stored in the root's own
  `.photo-selector-xmp-sync.json`) implements the `XmpSyncPreferences` seam.
- `update/` — `UpdateManifestDto` (feed JSON shape) + `HttpUpdateRepository` (the app's
  only outbound call: a JDK-`HttpClient` GET of the hosted manifest; every failure → null).
- `export/` — `CopyPhotoExporter`, `TxtPhotoExporter`, `XmpSidecarPhotoExporter`
  (Phase 1, RAW-only: writes `xmp:Rating` sidecars next to RAW originals for a
  Bridge / Lightroom / Capture One handoff; reject-wins `decisionFor`, non-RAW
  counted unsupported) over `XmpDocument` (the pure merge-not-clobber helper:
  parses an existing sidecar via the JDK DOM, mutates only `xmp:Rating` + our
  `rhenium:managedRating` ownership stamp, re-serializes),
  `CompositePhotoExporter`.
- `trash/` — `DesktopPhotoTrash` (move-to-Trash via AWT Desktop).
- `io/` — `AtomicJsonWriter` (shared atomic JSON write; categories + browse) and
  `ShardedBlobCache` (the shared hash -> shard -> atomic-write -> size-capped-eviction mechanics
  behind `EmbeddingCache` / `GroupingResultCache` / `FaceCache`; each cache keeps its **own** key
  composition, which is byte-pinned by golden-key tests. `clear()` is the all-or-nothing purge
  primitive — keys are hashes this class never inspects, so nothing can be selected per root).

## presentation/ — Compose + view models, by screen

- `navigation/` — `Screen` (sealed: `RootPicker | Grid | Browser | Inspect | People`),
  `InspectOrigin`, `CategoryScope`.
- `StateHolder.kt` — base view-model plumbing.
- `rootpicker/` — `RootFolderPickerScreen` + `…ViewModel`.
- `grid/` — **the heart of the app.** `GridViewModel` (focus/select/file; holds
  the `expandedBurstId` state; `refocus` re-anchors by identity), `GridScreen`
  (render; defines `tileIndexForFlat`, the tile↔flat translation),
  `LibraryRailViewModel`
  (root-scoped: feeds the hoisted `LibraryRail` its category+count entries and
  owns create/rename/delete — see organism `LibraryRail`; also owns `PeopleRailState` and
  `startScan()`, which back the rail's People section),
  `GridDisplayModel` (top-level tile-index *helpers*, not a class —
  `displayGroupsFor`/`buildRenderItems` explode the open burst into per-frame
  tiles, plus `renderIndexForTile` etc.), `GridViewportAnchor` (scroll anchoring),
  `GridIndex` (the `FlatIndex` / `TileIndex` value classes that make the
  flat-vs-tile distinction a compile error),
  `GridKeyBindings` (the pure `handleGridKey` dispatcher + `GridKeyContext` /
  `GridKeyActions` — the grid's keyboard model lifted out of `GridScreen` so the
  input→intent mapping is unit-testable; layout/anchor-coupled branches arrive as
  callbacks).
- `browser/` — `BrowserScreen` + `…ViewModel`, `ZoomableImage`, `ZoomState`.
  Reused inside Inspect's browse mode (`embedded`, `onSwitchToGrid`). The VM reads
  the current photo's `captureMetadata` off-thread via the memoized
  `CaptureMetadataSource` (a constructor dep) to feed the `I`-latched
  `BrowserDetailsPanel`.
- `inspect/` — `InspectScreen` + `InspectViewModel`: one fixed photo set viewed
  as an overview grid or full-screen browse, behind one toggle. Reuses the
  `survey/` and `browser/` view models as its two facets.
- `survey/` — `SurveyScreen` + `…ViewModel`: Inspect's overview-grid facet
  (fit-to-cell pick, no zoom).
- `people/` — `PeopleScreen` (full-screen naming surface: cluster cards with a face crop, an inline
  name field and the Skip / "Not a person" verdicts; a "⋯" overflow holds the face-data purge) +
  `PeopleViewModel` (`PersonCard` / `PeopleUiState`; owns the person <-> smart-category join, the
  duplicate-name guard and the three-store purge).
- `update/` — `UpdateViewModel`: app-lifetime, notify-only. Runs one launch check, applies
  the user's skipped-version choice, and turns banner actions into a browser open. Mounted by
  `App` as a bottom-end overlay (`UpdateAvailableBanner`).
- `common/` — non-UI plumbing: `NativeFileDialogs`, `MacSystemActions` /
  `SystemActions`, `CategoryHotkeys`, `CategoryToggle`, `GroupingMode`,
  `BackgroundPassCoordinator<R>` (the shared one-long-pass mechanism: slice dedup,
  supersede-by-generation, the 200 ms progress grace window, monotonic progress coalescing,
  `reset`/`cancel`; owns `Progress`) with two thin typed facades over it —
  `GroupingCoordinator` (the background Similarity pass, decoupled from
  any grid's displayed lens; survives lens switches and navigation and exposes a
  `progress` flow for the off-grid hint) and `FaceScanCoordinator` (the whole-root face scan;
  container-level like grouping, and it resolves the lazily-loaded ONNX models *inside* the pass, so
  `available` latches only once a scan has actually run), `XmpSyncCoordinator` (root-scoped,
  retained per root; when enabled, runs a full whole-root reconcile then live
  delta-writes RAW sidecars on membership changes — mirrors `GroupingCoordinator`'s
  lifecycle; drives `ExportPhotosXmpUseCase`), `HoverOverlay`, `PlatformLabels`,
  `AutoDismiss` (`rememberAutoDismiss` — the shared flow-into-transient-pill collector
  with a per-call timeout + optional reset key; the grid and browser toast/notice pills).

## presentation/designsystem/ — Atomic Design

- `theme/` — tokens: `AppColors`/`Spacing`/`Dimens` read via `AppTheme.*` (those
  three only); `AppTypography`/`AppShapes` go via `MaterialTheme`. Files:
  `Color`, `Spacing`, `Dimens`, `Type`, `Shape`.
- `atom/` — `Buttons`, `FavouriteStar`, `RejectFlag`, `LoadingIndicator`, `FaceCrop` (a face
  blitted out of its photo at the stored normalised box — padded, squared and clamped by the pure
  `faceCropRect`; rides the shared thumbnail cache, no second cache),
  `TileSignal` (the shared overlay-chrome chip `TileSignalChip` that the tile's
  burst/review/category badges all build on, plus the feature-agnostic
  `TileSignal` model + tint role that a tile's bottom-center signal lane renders).
- `molecule/` — incl. `GroupingModeToggle` (lens segments + hover tooltips; the
  Similar segment carries a determinate ring while the background pass runs),
  `GroupingProgressBanner` (cold-pass framing; takes a `label` and an optional `onStop`),
  `BackgroundPassChip` (the off-screen "still working" pill for either long pass — `label` plus an
  optional `onStop`, composed from `PillToast`),
  `UpdateAvailableBanner` (the bottom-end "new version ready" card; notify-only),
  `SimilarityCoachmark` (first-run
  callout), `BurstExpandedHeader`/`Footer`, the `*KeyboardLegend` set,
  `CategoryActionsMenu`, `ExportMenu` (txt + copy-to-folder, the
  exporter plugin-headroom slot), `ConflictPolicyButton`, `PillToast`,
  `LatchedPill` (the shared latch-content-through-the-fade wrapper the grid /
  browser transient pills are built from), `CategoryTogglePill` (the shared
  membership-toggle confirmation pill — label/star/colour derivation over a
  `CategoryToggle`, reused by the grid and the browser),
  `SelectionFileMenu`, `ConfirmDialog`/`CategoryNameDialog`.
- `organism/` — `LibraryRail` (left navigation column: scopes + category CRUD;
  hoisted to `App` beside the grid, backed by `grid/LibraryRailViewModel`),
  `GridTopBar` (slim: rail toggle + identity + view/export) /
  `GridSelectionTopBar`, `BrowserTopBar`/`BrowserCategoryHud`,
  `BrowserDetailsPanel` (the browser's right-anchored file/EXIF facts + an
  "AI insights - coming soon" slot; latched by the browser's `I` key),
  `PhotoThumbnail`, `SurveyTileView` (both take an optional immutable `signals`
  lane), `TopBarScaffold`.

## By task — open these first

| Task | Files |
| --- | --- |
| Grid focus / selection / keyboard filing | `grid/GridViewModel.kt`, `grid/GridDisplayModel.kt`, `grid/GridKeyBindings.kt` (key dispatch) |
| Burst expand-in-place behaviour | `grid/GridViewModel.kt` (`expandedBurstId` state), `grid/GridDisplayModel.kt` (`displayGroupsFor`/`buildRenderItems`), molecule `BurstExpanded*` |
| Grouping lens (Off / Time / Similarity) | `domain/grouping/`, `data/ai/SimilarityPhotoGrouper.kt`, `grid/GridViewModel.kt`, `presentation/common/GroupingCoordinator.kt` (background Similarity), `App.kt` (off-grid hint) |
| Scroll position / index translation | `grid/GridScreen.kt` (`tileIndexForFlat`), `grid/GridViewportAnchor.kt`, `grid/GridDisplayModel.kt`, `data/browse/` |
| Categories / Favourites | `data/categories/`, `domain/repository/CategoriesRepository.kt`, `common/CategoryHotkeys.kt`, `designsystem/organism/LibraryRail.kt` |
| Grid chrome (rail / top bar / collapse) | `designsystem/organism/LibraryRail.kt`, `grid/LibraryRailViewModel.kt`, `designsystem/organism/GridTopBar.kt`, `grid/GridScreen.kt`, `App.kt` (rail mounted beside the grid, `railCollapsed`) |
| Decoding a new format | `domain/format/PhotoDecoder.kt`, `data/format/DefaultPhotoFormatRegistry.kt`, register in `di/AppContainer.kt` |
| HEIC / RAW specifics | `data/format/HeicDecoder.kt`, `data/format/RawDecoder.kt`, `data/format/macos/MacImageIO.kt` |
| Similarity embeddings / model swap | `data/ai/OnnxEmbeddingModel.kt`, `data/ai/EmbeddingCache.kt`, `tools/embedding-model/` |
| Faces / people (detect, cluster, name) | `domain/faces/`, `data/faces/`, `domain/repository/PeopleRepository.kt`, `presentation/people/`, `presentation/common/FaceScanCoordinator.kt`, `designsystem/atom/FaceCrop.kt`, `di/AppContainer.kt` (lazy sessions + the composite rule resolver), `tools/face-models/` |
| Inspect (grid + browse toggle) | `presentation/inspect/`, `presentation/survey/`, `presentation/browser/` |
| Adding a screen | `presentation/navigation/Screen.kt`, `App.kt`, `di/AppContainer.kt` |
| Theming / new shared component | `presentation/designsystem/` (theme → atom → molecule → organism) |
| Auto-update (notify-only) check | `domain/update/`, `data/update/`, `presentation/update/UpdateViewModel.kt`, `App.kt` (banner overlay), `di/AppContainer.kt` (wiring + Homebrew mute), `build.gradle.kts` (`generateBuildConfig`), `.github/workflows/release.yml` (feed publish) |
| Export / trash | `data/export/`, `data/trash/`, matching `domain/usecase/` |
| XMP sidecar export / live sync | `data/export/XmpSidecarPhotoExporter.kt` + `XmpDocument.kt`, `domain/usecase/ExportPhotosXmpUseCase.kt`, `presentation/common/XmpSyncCoordinator.kt` (live sync), `data/prefs/JsonXmpSyncPreferences.kt`, `designsystem/molecule/XmpSyncToggleRow.kt` (rail-footer toggle), `di/AppContainer.kt` (retained per root) |

## Key build files

- `build.gradle.kts` — version (single source of truth), Compose Desktop config,
  DMG packaging.
- `.github/workflows/` — release machinery (see [`release.md`](release.md)).
- `tools/embedding-model/` — reproducible export of the bundled similarity ONNX
  model (pinned `requirements.txt`, `export_mobilenetv3.py`, expected SHA-256 in
  its `README.md`).
- `tools/face-models/` — reproducible *fetch* (not export) of the bundled YuNet + SFace
  blobs from a pinned opencv_zoo commit, verified against that commit's LFS hashes;
  licence provenance and expected SHA-256s in its `README.md`.
- `scripts/dry-run-release.sh` — local dry-run of the release logic.
