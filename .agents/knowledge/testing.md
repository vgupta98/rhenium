# Testing harnesses

<!--
  Relocated from /CLAUDE.md (Build & Run). Read this when writing or running the
  screenshot tests or the recomposition-count checks. The *rule* that UI-touching
  changes need a screenshot test stays in CLAUDE.md → Conventions.
-->

## Headless screenshot tests

`src/test/kotlin/.../screenshot/` runs Compose Desktop UIs headlessly via
`createComposeRule()` and dumps PNGs under `build/screenshots/<name>.png`
(gitignored). Use `ComposeContentTestRule.dumpScreenshot("foo")` from
`ScreenshotSupport.kt`. The PNGs are inspectable — open them, diff them
against a golden, or have an LLM session read them back. This is the
preferred way to verify UI changes that don't require the real app window
(theming, layout, simple interactions). For things that need a live window
(native file picker, DMG packaging), fall back to `./gradlew run`.

**The capture window is a fixed 1024x768, and anything past it is silently
clipped.** `ScreenshotSupport.kt` sets no size, so the desktop rule's default
window governs: sizing your test's root `Surface` taller does **not** grow the
frame, it just pushes content off the bottom edge — and the PNG still looks like
a finished screenshot, so it eyeballs as complete. Keep a test root inside
1024x768, and when a screen genuinely has more to show than that (several
stacked sections), give each part its own test and PNG rather than one tall one.

## Checking for unnecessary recompositions

Two complementary tools, both desktop-friendly (no Layout Inspector here):

- **Compiler stability reports (static).** `./gradlew compileKotlin
  -PcomposeReports=true --rerun-tasks` dumps `*-composables.txt` /
  `*-classes.txt` under `build/compose_compiler/`. Read them to spot a
  composable that can't skip or a param/class that turned unstable. Off by
  default (zero build cost). `--rerun-tasks` is required — an up-to-date
  `compileKotlin` won't regenerate them.
- **Recomposition-count tests (dynamic).** `RecompositionTracker` +
  `GridRecompositionTest` assert that flipping one tile's favourite/focus
  recomposes only that tile. Three gotchas baked into that test, learned
  the hard way: (1) `record()` must sit directly in the body of the
  restartable composable you measure — a `@Composable () -> Unit` wrapper
  gets its own restart scope and measures the wrong thing; (2) drive state
  via `runOnIdle { }`, a bare test-thread write isn't observed; (3) use a
  plain layout, not a Lazy one, to isolate component skipping from the lazy
  grid's own item subcomposition.

## Composition / measure loops the screenshot tests DON'T catch

A static screenshot test renders once and dumps a PNG — it measures each tile
**one time**. So it cannot catch a bug that only manifests under *repeated*
remeasure or recomposition (grid scroll, focus moves, a regroup reshape). Two
such bugs bit us in one session; both froze the app, neither failed a screenshot
test:

- **An all-`matchParentSize` Box loops the measure phase.** `matchParentSize`
  sizes a child to the Box *after* the Box's size is known — it does **not**
  contribute to it. A Box whose children are *all* `matchParentSize` (its own
  size coming only from `aspectRatio`/constraints) has no size-determining child;
  under repeated remeasure it never settles. Fix: give the Box at least one
  `fillMaxSize()` (size-participating) child. This is why `PhotoThumbnail`'s
  stacked-deck cover + cards all use `fillMaxSize`, not `matchParentSize`.
- **A custom child in `SegmentedButton`'s `icon` slot loops draw.** That slot is
  reserved for the selection check, which Material animates in/out; driving it
  with a custom always-on glyph keeps the animation from ever settling, so the
  frame clock never goes idle. Fix: leave `icon = {}` and put the glyph in the
  segment's *content* (see `GroupingModeToggle` — icon + label in one `Row`).
  (A separate early cut that *conditionally* emitted the label per selection also
  looped — churning the row's uniform-width measure — so keep the content tree
  stable too.)

**The signal is a hung `waitForIdle`, not a failed assertion.** A test that
spins forever inside `SkikoComposeUiTest.waitForIdle` (often during `setContent`)
is this class of bug. Diagnose it with a thread dump, don't guess:
`jstack <test-worker-pid>` (find it via `pgrep -f GradleWorkerMain`). The `Test
worker` thread sits in `waitForIdle`; the `AWT-EventQueue-0` thread is at ~100%
CPU spinning in `SnapshotStateObserver.clearObsoleteStateReads` — under
`observeMeasureSnapshotReads` → `performMeasure` for a **measure** loop, or under
recomposition for a **composition** loop. That frame tells you which phase, hence
which fix.

**Practical rule:** the headless screenshot suite is necessary but **not
sufficient** for a change to a layout modifier or a tile's composition structure.
Before calling such a change done, run the **dynamic** tests too — at minimum
`GridKeyboardTest` (it scrolls and re-measures) and `GridRecompositionTest` —
because they exercise the repeated-remeasure path a static dump never will. A
green screenshot run alone would have shipped a scroll freeze.
