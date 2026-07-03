# Photo Selector — Project Guide for Claude

A Kotlin Compose Desktop app (macOS-targeted) for browsing, favouriting and
exporting photos from a local folder. This file captures the non-obvious
context. Read the source for everything else.

## Knowledge base

The rules you must follow, and the non-obvious *why*, live in this file. The
explorable *reference* — a file-by-file code map, the release machinery, and the
test harnesses — lives in `.agents/knowledge/`, read on demand:

- **`.agents/knowledge/code-map.md`** — read this *before* grepping the tree: a
  package/file/symbol index plus a by-task "open these files first" table.
- `.agents/knowledge/testing.md` — the screenshot + recomposition test harnesses.
- `.agents/knowledge/release.md` — the full release workflow and its recovery steps.

## Architecture

Clean architecture, single Gradle module, package
`com.vishalgupta.photoselector`:

- `domain/` — entities (`Photo`, `RootFolder`, `PhotoId`, `Category`,
  `CategoryId`, `PhotoGroup`), repository interfaces, use cases. No framework
  dependencies. `grouping/` holds grouping behind one `PhotoGrouper` seam
  (`suspend (List<Photo>, onProgress) -> List<PhotoGroup>` of `Single | Burst`,
  the callback reporting per-photo progress for the grid's bar): the
  pure `BurstGrouper` (time + camera, over a `CaptureMetadataSource`) and
  the pure `SimilarityGrouper` (visual, over precomputed embeddings +
  sharpness, with capture time as a corroborating boost) both feed it, so the
  heuristic swaps without touching the grid.
  `PhotoGroup.Burst.keyIndex` is the representative frame — middle by default,
  the suggested-sharpest for a similarity cluster.
- `data/` — repository implementations: `filesystem/`, `categories/`,
  `image/` (decoding), `format/` (per-format `PhotoDecoder`s; `macos/`
  holds the JNA→ImageIO bridge that backs `HeicDecoder`, `RawDecoder` *and*
  HEIC capture-metadata reads — see **Known gotchas**. Capture time + camera
  for burst grouping come through the `CaptureMetadataSource` seam per format:
  `ExifReader`→`ExifCaptureMetadataSource` (JPEG) and
  `MacImageIO`→`HeicCaptureMetadataSource` (HEIC), chained by
  `CompositeCaptureMetadataSource` and memoized per session by
  `CachingCaptureMetadataSource`), `ai/` (on-device
  visual-similarity grouping: the `EmbeddingModel` seam — the learned
  `OnnxEmbeddingModel` (MobileNetV3-Small via ONNX Runtime, default) and the
  classical, dependency-free `DownscaleGrayEmbeddingModel` (load-failure
  fallback) — plus `SharpnessScorer`, an `EmbeddingCache` mirroring
  `DiskThumbnailCache`, `PhotoFeatureExtractor`, and the `SimilarityPhotoGrouper`
  adapter; see **Known gotchas**), `export/`,
  `trash/` (move-to-Trash via `java.awt.Desktop.moveToTrash`), plus `io/`
  (the shared `AtomicJsonWriter`).
- `presentation/` — Compose UI + view models, organised by screen
  (`rootpicker/`, `grid/`, `browser/`, `inspect/`, `survey/`), plus
  `navigation/` and `common/` (non-UI plumbing: file dialogs, system
  actions, hover).
- `presentation/designsystem/` — the Atomic Design system. `theme/`
  (tokens: `AppColors`/`Spacing`/`Dimens` plus `AppTypography`/`AppShapes`),
  then `atom/`, `molecule/`, `organism/`. Screens are the "pages" tier. Build
  UI from these and add shared tokens/components here rather than inlining
  literals in screens. **Read every token through `AppTheme`** — the
  app-specific `AppTheme.colors`/`spacing`/`dimens` *and* the Material-mapped
  `AppTheme.colorScheme`/`typography`/`shapes` (which `AppTheme` re-exposes as
  delegates). `MaterialTheme` is used only inside the `AppTheme` provider, never
  at a call site, so the design system has one accessor. (Most of the tree
  pre-dates this and still reads `MaterialTheme.*` directly — migrate a file's
  reads to `AppTheme.*` when you touch it.)
- `di/AppContainer.kt` — manual DI container. **No DI framework.** Add new
  wiring here.
- Navigation is a sealed `Screen` interface (`RootPicker | Grid | Browser |
  Inspect`). `Screen.Grid` carries a `CategoryScope` (`AllPhotos |
  Category(id)`); each category opens as its *own* `Screen.Grid` (own scroll
  state) from the library rail, never toggled in place. `Screen.Inspect`
  holds a *fixed set* of selected photos (`indices`) and shows them two ways
  behind one toggle: an overview *grid* (the `survey/` facet) and a full-screen
  *browse* mode (`browser/` reused over just that set, one shared cursor). It
  opens via `C` — from the grid (a 2+ selection or a group's Review CTA) or the
  browser (current + next); a set up to `MAX_INSPECT_GRID_PHOTOS` lands on the
  grid, a larger one (e.g. a long burst) opens browse-only, and `InspectOrigin`
  decides whether `Esc` returns to the grid or the browser's active photo.
  `InspectViewModel` lazily builds the two facet view models — a grid-first set
  never decodes the browser until the first toggle, a browse-only set never
  builds the grid.
- **The grid is framed by `LibraryRail` (left nav: scopes + category CRUD) and a
  slim `GridTopBar` (identity + lens toggle + `ExportMenu`).** `App` mounts the
  rail **outside** the per-scope `key(GridRetentionKey)`, backed by a root-scoped
  `LibraryRailViewModel` (retained per root in `AppContainer`) — so a switch
  re-keys only the grid and the rail stays mounted; inside the key it rebuilt
  every switch and flickered. `GridScreen` owns only the collapse toggle
  (`railCollapsed`/`onToggleRail`), hoisted to `App` so it survives scope switches
  and a Grid → Browser → Grid round trip. Two traps: rail rows must stay
  **non-keyboard-focusable** or they steal the grid's focus ring; and the rail
  sets no `returnScrollIndex` (back-out lands on the warm All Photos grid). The
  rail's **footer** hosts the root-scoped **XMP sidecar sync** toggle — a
  persistent mode, deliberately off the crowded top bar (see Known gotchas).
- **The grid is grouping/presentation only — mind the three index spaces.** The
  toolbar's segmented control picks a lens (`GridUiState.groupingMode`: `Off |
  Time | Similarity`, Time default); a non-`Off` mode regroups off-thread behind
  a determinate progress bar. **Time** regroups inline on the grid view model
  (`GridUiState.grouping`). **Similarity** is decoupled from the displayed lens:
  the cold, minute-long pass is owned by a folder-scoped `GroupingCoordinator`
  (`presentation/common/`) that, once started, runs to completion across a lens
  switch *or* navigation (its result is cached, so the work is never wasted) and
  dies only on a root change. Its progress (`GridUiState.similarityProgress`,
  mirrored from the coordinator) drives the always-visible cues — a determinate
  ring on the Similar tab in any lens, the framing banner when Similarity is
  shown, and the off-grid `BackgroundGroupingChip` overlaid by `App`. Switching
  the lens cancels only the *display* wiring (`similarityApplyJob`), never the
  coordinator's pass. Adjacent
  burst frames collapse into one `PhotoGroup.Burst` tile; clicking expands it in
  place (`GridDisplayModel` explodes `expandedBurstId` into per-frame tiles
  fenced by a header/footer — one burst open at a time, `Esc` peels selection →
  collapse → grid-back; collapsed `F` files the whole burst, expanded `F` the
  focused frame). Two invariants here are recurring bug sources:
  - Focus, multi-select and keyboard filing run over `displayGroups` (tile-index
    space, shared via `GridDisplayModel`); browser/Inspect nav and every
    persisted scroll index (`BrowsePosition.lastIndex`) stay **flat photo
    indices**. The grid is the *sole* translator (`tileIndexForFlat`) — never put
    a tile index on the nav wire or a flat index into grid focus. These two
    spaces are now distinct `@JvmInline value class`es (`grid/GridIndex.kt`):
    `FlatIndex` (nav/persistence) and `TileIndex` (focus/selection), so mixing
    them is a compile error. Conversions are deliberate `.value` / `FlatIndex(…)`
    calls that cluster at the grid's App-facing edges; the third space — the
    LazyGrid render-item index — stays a plain `Int`.
  - Re-anchor focus by **photo identity** on every reshape
    (`GridViewModel.refocus`), never a bare index — a regroup renumbers tiles
    under the cursor, so an index silently slides onto a different burst.
- Photos live in N flat per-root categories; **Favourites** is the built-in one
  (fixed id `favourites`, not renamable/deletable). Memberships persist to
  `<root>/.photo-selector-categories.json` (v2; a legacy
  `.photo-selector-favourites.json` migrates in on first read, renamed `.bak`).
  `CategoriesRepository` exposes one `observeMemberships` map flow — scope and
  `slice()` stay predicate-blind for a future smart category.
- State plumbing: `StateFlow` for screen state, `SharedFlow` / `Channel`
  for one-shot events (toasts etc).

## Branching

- `develop` is the working branch. Day-to-day commits land here.
- `main` is the release branch and the GitHub default branch.
- Never commit directly to `main`. It only receives merges from
  `release/vX.Y.Z` branches opened by the **Draft Release** workflow.

## Build & Run

JDK 17 (Zulu or JBR — either works). Gradle wrapper checked in.

| Task | Command |
| --- | --- |
| Launch the app | `./gradlew run` |
| Type-check only | `./gradlew compileKotlin` |
| Run unit tests | `./gradlew test` |
| Build a macOS DMG (dev) | `./gradlew packageDmg` (output under `build/compose/binaries/`) |
| Build the minified release DMG | `./gradlew packageReleaseDmg` (ProGuard-shrunk; what releases ship) |

`run` is the fastest signal for UI work. `compileKotlin` is enough when you
just want to verify a refactor builds — but it does NOT compile the `src/jmh/`
benchmark source set. After changing a public API/interface a benchmark touches
(e.g. `EmbeddingModel`), also run `./gradlew compileJmhKotlin`; otherwise the
break stays invisible until the `release-perf` CI job.

### Test harnesses

Two desktop-friendly harnesses (no Layout Inspector here): **headless screenshot
tests** (`dumpScreenshot()` → inspectable PNGs, the preferred way to verify a UI
change without a live window) and **recomposition checks** (compiler stability
reports + `RecompositionTracker`/`GridRecompositionTest`). Mechanics, the exact
Gradle invocations, and the hard-won gotchas: `.agents/knowledge/testing.md`.

## Release process

Three workflows in `.github/workflows/` drive it: **`draft-release.yml`**
(manual; derives the SemVer bump from `main..develop` Conventional Commits and
opens the `release/vX.Y.Z` PR), **`release-perf.yml`** (posts a JMH cross-branch
diff on the release PR), and **`release.yml`** (tags + builds the DMG + publishes
the GitHub Release on merge; the shipped DMG is the **minified**
`packageReleaseDmg` — see the ProGuard gotcha below). The `version` in
`build.gradle.kts` is the single source of truth, and after every release you
must back-merge `main` into `develop` or the next draft refuses the version.

Full mechanics — bump rules, the required repo setting, the local dry-run, and
recovering a half-finished run — are in `.agents/knowledge/release.md`.

## Conventions

- **Conventional Commits everywhere.** Release versioning depends on
  subjects parsing correctly (`feat:`, `fix:`, `feat!:`, etc).
- **Commit flow.** When asked to commit: stage the relevant files by name
  (never `git add -A`), then invoke the `/commit staged` skill — do not
  run `git diff`/`status`/`log` first; the skill handles that.
- **Reuse first; don't grow the code.** Before adding a file, composable,
  helper, parser, or test fake, look for an existing one to extend — the
  default is *extend, not fork*:
  - **UI:** compose from existing `atom/`/`molecule/`/`organism/` pieces;
    add a parameter to a component before writing a near-twin; reuse a
    whole screen where the flow fits (a burst opens the existing
    Inspect, not a new viewer).
  - **Parsing/decoding:** extend the existing reader/registry (`ExifReader`,
    `DefaultPhotoFormatRegistry`) rather than writing a parallel one.
  - **Logic:** the second time the same logic appears, extract one helper
    (e.g. `fileIdsInto`) instead of copy-pasting.
  - **Tests:** shared fakes live in `src/test/.../testing/` — reuse them,
    never re-declare a private copy.
  Keep navigation/state on one source of truth and layer presentation over
  it (the grid groups the flat photo list rather than duplicating it). A
  file growing materially, or a new sibling that overlaps an existing one,
  is the signal to extract/extend. When something genuinely new is needed,
  pick the smallest seam — a param, a new `PhotoDecoder`, a strategy behind
  an existing interface.
- **UI-touching changes must include or update a screenshot test.** Any
  change that affects what the user sees on screen — new composables,
  layout tweaks, theming, a decode/render path feeding an existing
  composable — is not done until a `dumpScreenshot()`-backed test
  exercises it and the resulting PNG has been eyeballed. Unit pixel
  assertions on intermediate buffers don't count: they don't catch
  `ContentScale`/`Modifier` interactions or bitmap-conversion bugs in
  the Compose pipeline. The only carve-out is features that genuinely
  need a live window (native file dialogs, DMG packaging) — say so
  explicitly and fall back to `./gradlew run`. See
  `.agents/knowledge/testing.md` for the mechanics.
- **Structural changes mean re-reading the docs.** Adding or removing a
  top-level package, changing the DI wiring shape, renaming a public
  API, splitting a screen, changing how navigation/state is plumbed,
  modifying the build/release flow — any of these obliges you to
  re-read `CLAUDE.md` and `README.md` end-to-end and propose updates
  for anything they now misrepresent. "Propose" means show the diff in
  chat and wait for go-ahead before staging. Stale docs are worse than
  no docs because they actively mislead the next session. Make this an
  end-of-work self-check: whenever a change adds new source under
  `src/main`, pause before wrapping up and confirm both halves — (1) you
  *extended* an existing component/helper/parser/test-fake rather than
  forking a near-twin, and (2) any package, public-API, or
  navigation/state change is reflected in `CLAUDE.md` / `README.md`.
  Likewise refresh `.agents/knowledge/code-map.md` whenever a file under
  `src/main` is added, renamed, moved, or repurposed, so the map stays in
  step with the tree.
- **Capturing a learning in `CLAUDE.md` has a high bar.** If the session
  surfaced something durable, team-relevant, and not derivable from the
  current code (a sharp edge, a workflow that has to happen in a
  specific order, a class of bug that keeps recurring), propose a
  `CLAUDE.md` edit with a one-line justification of *why* it's durable
  rather than session-specific. Per-user preferences and per-session
  context belong in the auto-memory system, not here. When in doubt,
  don't write the bullet — `CLAUDE.md` only stays useful while it
  stays short.
- **`README.md` is user-facing.** Only propose edits to it when the
  change touches something the README already documents: build
  commands, install steps, the architecture overview, supported
  platforms, or the release flow visible from the outside. Don't add
  internal LLM-discipline rules or in-progress design notes — those
  live in `CLAUDE.md` or stay out of the repo entirely.
- **No `Co-Authored-By: Claude`** lines in commit messages.
- **No emojis** in code, commits, or documentation unless explicitly
  requested.

## Known gotchas

- **macOS trackpad pinch zoom** does not reach Compose Desktop with stock
  JDK builds. We support two-finger scroll + `+` / `-` / `0` keys +
  double-click reset instead. Reflective bridges into Apple's gesture
  classes were tried and abandoned — don't reintroduce them.
- **`packageDmg` only runs on macOS.** CI uses `macos-latest`; locally you
  need to be on a Mac.
- **A new JDK module must be added to the jpackage `modules(...)` list.** The
  release bundles a trimmed jlink runtime, so code reaching an unlisted module
  (the update checker's `HttpClient` → `java.net.http`) throws
  `ClassNotFoundException` in the packaged app but **not** under `./gradlew run`
  — validate against the packaged app, like the ProGuard keeps below.
- **The update checker is notify-only, driven by a hosted feed (not an in-app
  flag).** It GETs `update-manifest.json` from the `homebrew-tap` repo (URL in
  the generated `BuildConfig`) and only notifies, never installs. The feed
  decides everything — `rollout` (staged, bucketed locally from a never-sent
  install id), `minimumVersion`, `mandatory`; pull a bad build via `rollout: 0`.
  Homebrew installs are muted; silent install needs signing/notarization (later).
- **The release DMG is ProGuard-minified — keep-rules are load-bearing.**
  `packageReleaseDmg` tree-shakes the whole classpath, so anything reached only
  via reflection/JNI/codegen (ONNX's native bindings, the JNA HEIC/RAW bridge,
  kotlinx.serialization's `$$serializer`s) survives only because
  `proguard-rules.pro` keeps it. A missing keep builds clean and breaks at
  *runtime* — and ONNX fails silently (falls back to the classical embedder). So
  validate keep changes against the packaged release app (Similarity lens +
  HEIC/RAW decode + favourite-relaunch), not `./gradlew run`, which skips ProGuard.
- **skiko cannot decode HEIC/HEIF.** Verified by probe on the bundled
  skiko (`Image.makeFromEncoded` throws). There is no maintained
  cross-platform JVM HEIC library on Maven (`org.bytedeco:libheif` does
  not exist; FFmpeg was rejected for DMG bloat). HEIC is decoded via a
  JNA bridge into the macOS ImageIO frameworks
  (`data/format/macos/MacImageIO.kt`) and registered in `AppContainer`
  **only on macOS**, behind the `PhotoDecoder` interface. A future
  Windows build adds its own decoder there — don't reintroduce a search
  for a cross-platform lib without re-checking Maven first.
- **Camera RAW decodes through the macOS ImageIO bridge (`RawDecoder` →
  `MacImageIO`), not libraw** — macOS-only, nothing bundled; a bundled native
  lib was considered and rejected. The one trap, which `MacImageIO` documents:
  RAW must decode by **file path** (`decodeFileToBgra`), not from a byte buffer
  like HEIC — handed bytes, Sony ARW returns an empty source and Nikon NEF
  downgrades to its embedded thumbnail. Don't unify the two paths onto
  `decodeToBgra`.
- **Burst grouping reads capture time from EXIF/ImageIO, and never falls
  back to mtime.** `BurstGrouper` deliberately treats a frame with no
  readable capture time as ungroupable — it stays a `Single` — rather than
  leaning on file mtime, because a bulk copy flattens mtime and over-groups
  unrelated photos (the original mtime fallback shipped exactly that bug). So
  any EXIF-less file (a PNG, a stripped JPEG) never groups; do **not** add an
  mtime fallback to fix it. Capture time is read per-format behind the
  `CaptureMetadataSource` seam: JPEG via the JVM `ExifReader` (`ExifReader` is
  JPEG-only), HEIC via the macOS ImageIO bridge
  (`HeicCaptureMetadataSource` → `MacImageIO.readCaptureInfo`, the same bridge
  that decodes HEIC pixels), chained by `CompositeCaptureMetadataSource`
  (first non-NONE). So HEIC **does** group on macOS now; off macOS (no bridge)
  it has no capture time and stays ungroupable. A future Windows reader slots
  into the composite the same way. Grouping can also be switched off or to
  another lens from the grid toolbar (`GridUiState.groupingMode`), and is
  recomputed off-thread on every re-slice, which is why
  `CachingCaptureMetadataSource` exists — keep it in the wiring.
- **Similarity grouping merges only *adjacent* frames and never crosses a folder
  boundary** (same contiguity rule as `BurstGrouper`; the expand-in-place burst
  UI fences a contiguous run, and a folder is an event boundary). The cosine cut
  is **adaptive per event, not a fixed floor**: `SimilarityGrouper` derives each
  contiguous folder-run's threshold from that run's own adjacent-pair cosine
  distribution (`Adaptive` = run median + 0.07, clamped to [0.78, 0.95]) behind a
  `ThresholdRule` seam (`fixed()` keeps the old constant floor for the eval sweep
  and unit tests). Measured on a labelled real-wedding set: F1 0.61 -> 0.70, better precision
  *and* recall; unsupervised (it reads only the run's cosine spread, never the
  labels). **Capture time is also used, as corroborating evidence** behind a
  second seam, `JoinRule`: the shipped `timeBoosted` rule joins two adjacent frames
  when the cosine clears the cut *or* they were taken within ~3s and clear a relaxed
  0.65 floor — recovering same-moment bursts whose embedding drifted (a framing/zoom
  shift) that the visual cut alone split. The 3s window is deliberately tight: a
  wider one over-merges "clean" events the cosine already handles (it *regressed*
  them in leave-one-event-out validation across the 4 event folders), so the boost
  only fires on genuine rapid bursts. A frame with no EXIF time (HEIC, EXIF-less)
  has a null gap and falls back to visual-only, so time can only ever *add* a join,
  never block one. `SimilarityPhotoGrouper` reads the time from the same memoized
  `CaptureMetadataSource` the Time lens uses. `VisualOnly` is the seam's no-time
  default (the pure grouper stays usable without a clock). Per-photo
  embeddings + sharpness are cached to disk (`EmbeddingCache`, keyed by content +
  model id, invalidated on source edit or model swap). The *grouping result* is
  also memoized — `GroupingResultCache`, wrapped around the grouper by
  `CachingPhotoGrouper` and wired in `AppContainer` — so re-entering the lens on
  an unchanged folder is instant rather than re-running the pass; it stores only
  the lightweight group structure (frame ids + key frame), is content+model-id
  keyed exactly like the embedding cache, and a cancelled pass is never written.
  Bump its `FORMAT_VERSION` if the stored shape **or the grouping algorithm**
  changes — the cache holds the algorithm's *output*, so a logic change (e.g. the
  adaptive-threshold switch took it to v3, the capture-time boost to v4) must
  invalidate it or stale groupings get served. The shipped embedder is `OnnxEmbeddingModel` — a
  MobileNetV3-Small backbone (classifier stripped) bundled at
  `src/main/resources/models/mobilenetv3-small.onnx` (~6 MB); `dimensions` (1024)
  is probed from the graph at load, so a model swap needs no caller change.
  Regenerate the blob via `tools/embedding-model/` (pinned timm/torch,
  Apache-2.0) and **bump `OnnxEmbeddingModel`'s `id` whenever the vectors change**
  so the on-disk cache re-keys. Don't bake model assumptions into callers.
- **Sharpness (the suggested key frame) is scored on a dedicated 768px canonical canvas, not the 224px embedding decode** — variance-of-Laplacian is per-pixel, so sharing the embedding decode hid focus differences and scoring at native size let the *lowest-res* copy win; don't unify the two decodes or drop `scaleUpToLongEdge` (bump `EmbeddingCache.FORMAT_VERSION` if the score changes).
- **ONNX Runtime is a bundled native dependency.** The
  `com.microsoft.onnxruntime:onnxruntime` JAR ships a JNI `.dylib` that jpackage
  rolls into the DMG. The published jar is fat (all-platform natives + debug
  symbols), so `build.gradle.kts` depends on the `slimOnnxRuntime` task — which
  repackages it down to the macOS dylibs only — rather than the artifact
  directly; if a Windows build is ever added it needs its own slimmed jar, not
  the fat one. Unlike the HEIC bridge (which loads system frameworks by name and
  bundles nothing), this is real native code in the app bundle — so DMG
  signing/notarization has to cover it, and `OnnxEmbeddingModel` construction
  must stay fail-soft (it falls back to the classical embedder) in case the
  runtime can't initialise on a given host.

- **XMP sidecar sync enable = a FULL whole-root reconcile, never a write-only
  pass.** The rail-footer sync toggle (`XmpSyncCoordinator`, root-scoped and
  retained per root, mirroring `GroupingCoordinator`) keeps RAW sidecars in step
  with Favourites/Rejects. On enable it must walk **every** root photo
  (`photosForRoot()`), not just the current favourites/rejects — because a photo
  that *left* both buckets is only visited (and its stamped rating cleared) by a
  full pass. Build enable as "write the current buckets" and the un-decide clear
  silently never fires: an un-favourited photo keeps its stale 5-star sidecar.
  Clears and overwrites are guarded by the `rhenium:managedRating` ownership stamp
  (only a rating whose on-disk value still equals our stamp is touched — a foreign
  Lightroom rating is never destroyed). Live changes while enabled write only the
  delta; disable stops watching and leaves sidecars in place. Per-root on/off
  persists to `.photo-selector-xmp-sync.json`. Export stays RAW-only (JPEG/HEIC
  are counted as skipped, not embedded).

## Files worth knowing

The file-by-file index lives in `.agents/knowledge/code-map.md` (package map +
by-task table). The two you'll reach for most: `build.gradle.kts` (version,
Compose Desktop config, DMG packaging) and
`di/AppContainer.kt` (all DI wiring — start here for a new screen or repository).

## Agent skills

Compose and Kotlin agent skills are vendored under `.claude/skills/`
from [chrisbanes/skills](https://github.com/chrisbanes/skills) (Apache
2.0). They are **model-invoked**, not hook-triggered: before writing or
reviewing Compose UI or non-trivial Kotlin (state, side effects,
recomposition, flows, value classes), check whether one matches and
invoke it via the Skill tool. They can also be invoked manually as
`/<skill-name>` (e.g. `/compose-recomposition-performance`) when a task
clearly calls for one. See `.claude/skills/README.md` for the list and
for how to re-vendor from upstream.
