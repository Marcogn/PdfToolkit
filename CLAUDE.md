# PdfToolkit — project memory

Android app to read and edit PDFs locally. The full specification is `docs/spec.md` (in Italian):
this file doesn't repeat it, it holds commands, non-obvious rules, status and decisions.

## Fixed rule
At the end of every task, update `README.md`, `CLAUDE.md` and `CHANGELOG.md` if something changed.
Every user-visible change goes into `CHANGELOG.md` right away under `## [Unreleased]`, in the form
`- **Summary.** detail` (`release.yml` reads it for the release notes).

## Phase glossary
"Phase" means two different things in `docs/spec.md`:
- **Development phases 0–6** (spec §13): the order in which the code gets written. "Next phase"
  always means the next development phase.
- **Product phase 1 / product phase 2** (spec §1, §7, §14): product phase 1 is everything in
  development phases 0–6; product phase 2 is the future features (scan, OCR, cloud, highlight,
  draw, ODF export). Spec sentences like "in Fase 1 nessun permesso INTERNET" or "Fase 2
  (pianificata, non implementare)" refer to the **product**.

So development phase 2 (edit session and page management) has nothing to do with product
phase 2. Never implement product phase 2 features; only the disabled "Soon" entries and the
interfaces the spec asks for.

## Session protocol
Phases 1, 4 and 5 are split into sub-phases **a** (Opus) and **b** (Sonnet); the others are a
single sub-phase run by Sonnet. The table below is the plan; **Current status** names the next
sub-phase. When the author says "go on" / "next phase" (or similar):

0. **Model check, before anything else.** Find the next sub-phase in Current status and the model
   assigned to it in the table. Check which model you are running on (stated in your system
   prompt; in a claude.ai cloud session, the `get_session` tool reports it). If it is not the
   assigned model, or you can't tell, **stop**: reply in one line with the sub-phase, the model it
   needs and how to switch (`/model opus` or `/model sonnet`, or a new session with that model).
   Don't read the spec or the code first. If the author explicitly says to go ahead anyway, do so.
1. Read that sub-phase's row below, then spec §13 and the spec sections it points to.
2. Check the prerequisites (previous sub-phase merged, handoff note read, open questions
   answered). If something is missing or unclear, stop and ask.
3. Do **only** that sub-phase, within its "Scope". Anything outside goes into Current status as a
   note, not into the code.
4. Close when its "Done when" holds: lint + unit tests + `assembleDebug` green; README, CLAUDE.md
   (Current status, Decisions) and CHANGELOG updated; commit; PR.
5. An **a** sub-phase also writes a short **handoff** in Current status for the **b** session: what
   exists, public APIs to use, known limits, what is left. Keep it under ~15 lines.
6. End by listing the **device checks** of the sub-phase: there is no emulator in the cloud
   environment, so the author tests on a phone before merging.
7. Don't start the next sub-phase in the same session unless the author asks.

## Sub-phases: model, scope, device checks
Sonnet by default; Opus only for the cores where a wrong design is expensive to fix later.
Haiku is not recommended for code in this project.

| Sub-phase | Model | Scope | Done when | Check on the device |
|---|---|---|---|---|
| 1a Viewer core | **Opus** | `pdf/render` (`PdfRenderer` + mutex per document, LRU cache sized on `memoryClass`, ±2 prefetch, two-level render, tiling); zoom/pan state and gestures (pinch, double tap, pan, limits); continuous mode; first `PageCoordinateMapper` (page ↔ screen with zoom and pan) with unit tests. Minimal entry: "Open PDF" → SAF → viewer | Unit tests for the mapper and the cache; a PDF opens from Home and scrolls/zooms; handoff written | 200-page PDF opens in < 1 s; smooth scroll; pinch, double tap, pan; memory with a large PDF |
| 1b Viewer complete | Sonnet | Single-page mode and remembered preference, scrubber, thumbnail bar, top bar menu, error screen, intents (`VIEW`, `SEND`), Room `RecentDocument` and Home recents, last page per file, password PDFs, viewer settings | Spec §13 phase 1 acceptance | Continuous ↔ single page; open from file manager and from "share"; recents; resume last page; password PDF |
| 2 Edit session and pages | Sonnet | Spec §13 phase 2. Ask Opus only if the background save choice (WorkManager vs service) gets stuck | Spec §13 phase 2 acceptance | Remove/reorder/rotate on a 100-page PDF, save as copy and overwrite, open the result in another reader; undo/redo; rotate the screen while editing |
| 3 Add pages and merge | Sonnet | Spec §13 phase 3 | Spec §13 phase 3 acceptance | Images in both modes (EXIF, HEIC); mixed A4/Letter PDF; merge 3 PDFs with reordering, check in another reader |
| 4a Fill and sign core | **Opus** | AcroForm reading and Compose controls aligned to field rectangles; writing text, check marks, dates and signature images into the content stream at the right coordinates (extend `PageCoordinateMapper`: PDF bottom-left origin, page rotation); flatten; Noto Sans embedding. A plain test signature image is enough | Unit tests on coordinates incl. rotated pages; filled form and placed image saved correctly; handoff written | Text and image land exactly where placed, in the app and in another reader; form with fields |
| 4b Fill and sign complete | Sonnet | Signature archive (Room, `filesDir/signatures/`), drawing canvas, import with background removal, free-fill UI (move/resize/rotate), legal note | Spec §13 phase 4 acceptance | Draw/import/save signatures; signature placement; backup exclusion of signatures |
| 5a Search core | **Opus** | `pdf/text`: `PDFTextStripper` subclass with positions, NFD normalisation keeping the index mapping, line breaks as spaces, match → rectangles through `PageCoordinateMapper` (rotated pages) | Unit tests on normalisation, line-break matches, rotated pages; handoff written | (none, verified by tests) |
| 5b Search complete | Sonnet | Search UI, progressive indexing with cancellation, overlay highlights, scanned-PDF message | Spec §13 phase 5 acceptance | Results appear while indexing a 200-page PDF; "perche" finds "perché"; highlights in the right place on rotated pages; scanned PDF message |
| 6 Polish | Sonnet | Spec §13 phase 6 | Spec §13 phase 6 acceptance | Release build with R8 on the main flows; animations; TalkBack. The baseline profile needs a device or emulator to generate: run it locally |

## Commands
```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM tests, Robolectric for UI (sdk=34, see robolectric.properties)
./gradlew lintDebug            # Android Lint
./gradlew buildEnvironment     # check the resolved Kotlin/KSP plugin versions
```
In this cloud environment: Android SDK in `/opt/android-sdk` (installed with `sdkmanager`,
platform `android-37.0`; `local.properties` is gitignored), run Gradle with `LC_ALL=C.UTF-8`.
Maven Central often answers 429 or fails to resolve: retry, preferably with `--max-workers=2`.
Robolectric downloads `android-all-instrumented` at test time; if that gets a 429, download it
with `curl` into `~/.m2/repository/org/robolectric/...` and rerun.

## Architecture
Package `com.marcogn.pdftoolkit`, same layering as ThePatientGamerHelper and KartLog (details in
`docs/plan.md`):
- `ui/<feature>/` Compose screens and ViewModels; `ui/navigation/` `@Serializable` routes
  (`Destination`), NavHost and drawer; `ui/theme/` palette (`Color.kt`) and theme.
- `domain/` models with no Android dependencies (`PdfTool`, `ThemeMode`; later `EditSession`,
  `PageItem`).
- `data/` DataStore (`data/settings/ThemePreferences`), later Room and SAF.
- `pdf/render` (phase 1a): `PdfDocumentRenderer`, `RenderScheduler`/`RenderPlanner`, caches, and
  the geometry shared by all phases (`DocumentLayout`, `Viewport`, `PageCoordinateMapper`).
  `pdf/edit`, `pdf/forms`, `pdf/text` from phases 2–5 (spec §12).
- `di/` Hilt modules, when needed.

## Non-obvious rules
- One page open at a time per `PdfRenderer` instance: one mutex per document, rendering on
  `Dispatchers.Default`, page always closed after rendering (ADR 0001).
- All writes go through `PdfEditor`; the UI never touches PdfBox (ADR 0002).
- Signatures, text, check marks and dates are written into the page content stream, not as
  annotations.
- Search highlights are overlay only, never written into the PDF.
- No `INTERNET` permission in product phase 1: the manifest removes it with `tools:node="remove"`
  and CI checks the packaged manifest. Don't add dependencies that need it.
- Every `navigate()`/`popBackStack()` goes through the `lifecycleIsResumed()` guard of the entry
  that owns the callback (double tap during a transition). Home from the drawer:
  `popUpTo<Home>{inclusive}`. Exception: navigation from an activity result (the SAF picker),
  which arrives before the entry is RESUMED again; the tap that launches the picker is guarded.
- Viewer coordinates go only through `PageCoordinateMapper` (page points top-left ↔ layout px ↔
  screen px). Don't convert by hand in the UI.
- Navigation transitions 200–250 ms, never above 300 (spec §9). `NavHost` needs both the pop and
  the `predictivePop*` transitions: system back uses the latter, and the library default is a
  `scaleOut(0.7f)` towards the centre.
- `MainActivity` is an `AppCompatActivity`: `setApplicationLocales()` needs it for the per-app
  language.
- No hardcoded UI strings: `values/` (Italian, default) and `values-en/`.
- AGP 9 built-in Kotlin: no `org.jetbrains.kotlin.android` plugin, compiler options in
  `kotlin { compilerOptions { } }`. In composables read resources with `LocalResources.current`,
  not `LocalContext.current` (lint error).

## Conventions
- Documentation and code comments in English. App UI in Italian with English translation.
  Identifiers in English.
- `PT` prefix only where needed to avoid name clashes.
- Choices marked **[ASSUNZIONE]** in the spec: implemented as written and kept isolated (e.g. the
  palette in `ui/theme/Color.kt`).
- If a requirement is unclear or not feasible, stop and ask.

## References
- Specification: `docs/spec.md`. Plan, alignment with the references, upgrade steps:
  `docs/plan.md`.
- ADRs: `docs/adr/0001-viewer.md`, `docs/adr/0002-pdfbox-android.md`.

## Current status
<!-- Update at the end of every session. -->
- **Phase 0 done (2026-10-01)**: skeleton, theme, languages, navigation, drawer, Home, signing,
  CI, docs, ADR 0001–0002. Toolchain and libraries upgraded to the latest stable versions
  (`docs/plan.md`). Verified with lint, JVM/Robolectric tests, debug and release builds; the
  author installed the phase 0 APK (pre-upgrade build) and confirmed it works.
- PR #1 merged.
- **1a Viewer core done (2026-10-01)**, PR #3 merged; device checks passed (author). Follow-up fix:
  system back no longer shrinks the screen (predictive pop transitions). **Next: 1b Viewer
  complete (Sonnet).**
- The author still has to add the signing secrets to the repository.

### Handoff 1a → 1b
- Entry: Home "Open PDF" → `rememberOpenPdfLauncher` (SAF, takes the persistable permission) →
  `Destination.Viewer(uri)` → `ViewerScreen` / `ViewerViewModel` (opens with `PdfDocumentOpener`,
  typed `OpenFailure`, owns `PdfDocumentRenderer` and `RenderScheduler`).
- `PdfDocumentRenderer.render(RenderKey)` is the only way to draw a page; reuse it for thumbnails
  (a `PageKey` at thumbnail size; the mutex serialises it with the viewer). `pageSizes` known at open.
- `PdfViewportState`: `viewport`, `layout`, `mapper`, `currentAnchor()` (page + fraction + zoom,
  use it for "last page per file"), `panBy`/`zoomBy`/`launchAnimation`. Current page for the
  "X of N" indicator: `layout.pageAt(...)` on the screen centre via `mapper.screenToLayout`.
- Single-page mode: `DocumentLayout` has only `continuous()`. Suggested: a `HorizontalPager` of
  one-page layouts (`DocumentLayout.continuous(listOf(size), ...)`, its own `PdfViewportState`),
  pager swipe only when zoom ≤ 1 or at the horizontal edge; crossfade on mode change (spec §9).
- Missing for 1b: top bar menu and page indicator, scrubber, thumbnails, full error screen (now a
  minimal message + back), intents `VIEW`/`SEND`, Room recents, last page, password (API 35+
  `LoadParams`; below it `PdfRenderer` throws `SecurityException` → `PASSWORD_PROTECTED`), settings.
- Known limits: Canvas has no accessibility semantics yet (phase 6); `PdfRenderer` runs in the app
  process (AOSP suggests an isolated process for untrusted files, not planned); a document opened
  while the viewer is being closed may leak until GC.

## Decisions
<!-- One line per decision: date, what, why. Append, don't rewrite. -->
- 2026-10-01 · Product phase 2 tools in a separate "Coming up" section on
  Home, with a "Soon" badge; a tap shows a snackbar and doesn't navigate.
  `GridCells.Adaptive(100.dp)`.
- 2026-10-01 · "My signatures" from Home uses the same navigation as the drawer entry, so the
  screen never appears twice on the back stack.
- 2026-10-01 · Backup rules excluding `filesDir/signatures/` already in phase 0 (no cost).
- 2026-10-01 · Room in the version catalog but not a dependency until there is an entity (phase 1).
- 2026-10-01 · Robolectric Compose tests: Italian locale set explicitly with
  `@Config(qualifiers = "it-...")`, otherwise Robolectric starts in English and loads `values-en/`.
- 2026-10-01 · Icon in `mipmap-anydpi-v26/` as in the references. Lint reports `ObsoleteSdkInt`,
  but after moving it to `mipmap-anydpi/` aapt2 no longer found `@mipmap/ic_launcher`: left as is.
- 2026-10-01 · Documentation and code comments in English (author's decision; overrides the
  initial Italian choice).
- 2026-10-01 · Spec moved to `docs/spec.md` (author's decision); spec §12 tree updated to match.
- 2026-10-01 · Upgrade to the latest stable toolchain and libraries (author's request; the same
  will be applied to TPGH and KartLog): steps and sources in `docs/plan.md`. Hilt ≥ 2.59 requires
  AGP 9, so the two go together. `compileSdk`/`targetSdk` 37, the maximum for AGP 9.4.
- 2026-10-01 · Gradle wrapper with `distributionSha256Sum` (official checksum from
  services.gradle.org, cross-checked with the downloaded file).
- 2026-10-01 · Release keystore: dedicated to this app, generated once (RSA 2048, 10,000 days,
  alias `pdftoolkit`, PKCS12), handed to the author, never committed. Same scheme as TPGH.
- 2026-10-01 · Model per phase and device checks added to this file (author's request, to save
  tokens): Sonnet by default, Opus only for the cores of phases 1, 4 and 5, as explicit
  sub-phases 1a/4a/5a with a handoff note for 1b/4b/5b. Every session starts with a model check
  and stops in one line if it isn't its turn. `PageCoordinateMapper`
  starts in phase 1 (zoom and pan already need page ↔ screen conversion) and is extended in
  phases 4 and 5 (rotation, PDF bottom-left origin); spec §12 wants a single class for all of it.
- 2026-10-01 · `PageCoordinateMapper`, `DocumentLayout` and `Viewport` live in `pdf/render`, not
  `pdf/forms` as the spec §12 tree suggests: they start with the viewer and phases 4–5 extend them.
- 2026-10-01 · Zoom 1 = fit width of the widest page (one scale per document, so mixed sizes keep
  their proportions, narrower pages centred). Min zoom = fit page (< 1 only when the page is taller
  than the screen, e.g. landscape), max 5x relative to fit width. Double tap: the tapped point
  stays under the finger (reading of "centrato sul punto toccato", spec §4.2; easy to change in
  `ViewportBounds.doubleTapTarget`).
- 2026-10-01 · Render cache: half of `memoryClass`, 32–256 MB, 2/3 pages and 1/3 tiles in separate
  LRUs; page bitmap capped at 1/4 of the page cache (max 32 MB), above it tiles take over. Tiles
  512 px at zoom levels quantised to quarter octaves, rounded up. Since API 26 bitmap pixels are
  in the native heap ([source](https://developer.android.com/topic/performance/graphics/manage-memory)),
  so `memoryClass` is a measure of the device, not a hard limit.
- 2026-10-01 · One render worker reading a "wanted list" (`RenderScheduler`) instead of a queue:
  fast scrolling never piles up stale renders. `PdfRenderer` serialises pdfium globally anyway
  (static `sPdfiumLock` in AOSP).
- 2026-10-01 · Non-seekable sources (pipes from some providers) are copied to `cacheDir/open/`,
  deleted on close: `PdfRenderer` requires a seekable descriptor (AOSP source).
- 2026-10-01 · Drawer swipe disabled in the viewer (it would fight horizontal pan); the drawer
  still opens from Home and the other drawer screens.
