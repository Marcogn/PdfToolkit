# PdfToolkit — project memory

Android app to read and edit PDFs on the phone, offline. The specification is `docs/spec.md`
(Italian); decisions still in force are in `docs/decisions.md`, the big ones as ADRs in `docs/adr/`.
This file holds what a session needs to work: protocol, commands, where things are, rules, status.

## Fixed rule
At the end of every task, update `README.md`, `CLAUDE.md` and `CHANGELOG.md` if something changed.
Every user-visible change goes into `CHANGELOG.md` right away under `## [Unreleased]`, as
`- **Summary.** detail` (`release.yml` turns that section into the release notes). New decisions go
into `docs/decisions.md`.

## Phases
- **Development phases** are the order the code is written in: 0–6 (spec §13, product phase 1,
  released as 1.0.0), then 7–12 for product phase 2 (`docs/plan-v2.md`), with inserted series:
  U-a/U-b (usability, `docs/plan-usability.md`), V-a/V-b/V-c (editing in the viewer,
  `docs/plan-viewer-editing.md`), A (updates, `docs/plan-updates.md`) and T-a/T-b (partial eraser and
  tool drawer, `docs/plan-tools.md`). "Next phase" means the next development sub-phase.
- **Product phase 1 / 2** (spec §1, §7, §14): the feature sets. Spec lines like "Fase 2 (pianificata,
  non implementare)" or "in Fase 1 nessun permesso INTERNET" refer to the product. Implement a product
  phase 2 feature only inside its own sub-phase.
- Code comments cite plan items as "plan U7", "plan V-b": U-items are in `docs/plan-usability.md`,
  V-items in `docs/plan-viewer-editing.md`, T-items in `docs/plan-tools.md`.

## Session protocol
The `/next-phase` and `/close-phase` skills (`.claude/skills/`) run these steps; this list is the source.
When the author says "go on" / "next phase":
0. **Model check first.** Find the next sub-phase (Current status) and its model (table). Check the
   model you run on (system prompt; in a claude.ai cloud session the `get_session` tool). If it
   differs or you can't tell, stop and reply in one line: sub-phase, model needed, how to switch
   (`/model opus`, `/model sonnet`, or a new session). Go ahead only if the author says so.
1. Read the sub-phase row, then the plan section and spec sections it points to.
2. Check prerequisites (previous sub-phase merged, open questions answered); if unclear, ask.
3. Do only that sub-phase. Open `bug` issues touching it may be included (the author picks). Bugs and
   ideas found along the way become GitHub issues once the author agrees; Current status keeps only
   status, handoffs and limits.
4. Close when "Done when" holds: lint + unit tests + `assembleDebug` green; independent review of the diff
   (`self-review`, required before any PR: `.claude/hooks/review-gate.sh` enforces it); docs updated; commit; PR.
5. An **a** sub-phase writes a handoff (≤ 15 lines) in Current status for its **b**.
6. End with the sub-phase's **device checks**: there is no emulator here, the author tests on a phone.
7. Don't start the next sub-phase in the same session unless asked.

Session title `<sub-phase> <Name>` (other work: `PdfToolkit <what>`), set at the start with `set_session_title`
and again if the task changes. Branches Claude creates are `<type>/<what-and-where>` (`phase/9-scan`,
`fix/crash-on-rotation`; `docs/claude.md`, "Names"). The cloud environment may fix the branch name: then use
the one it gives.

| Sub-phase | Model | Scope | Done when | Check on the device |
|---|---|---|---|---|
| 11 ODF export (the author may drop it) | Sonnet | `docs/plan-v2.md` 11 | Plan 11 | Result opens in LibreOffice / Collabora |
| 12 Cloud (WebDAV) | Sonnet (Opus reviews the credential store) | `docs/plan-v2.md` 12; reuses `INTERNET` (ADR 0006) | Plan 12 | Nextcloud upload, wrong password, no network |

Done (scope, model and checks in their plan files): 0–6 (except the baseline profile), 7a–8b, U-a/U-b, V-a–V-c,
9, 10a, 10b, A, T-a, T-b. Haiku is not recommended for code here.

## Commands
```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM tests, Robolectric for UI (sdk=34, robolectric.properties)
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport -Pcoverage  # + JaCoCo report
./gradlew lintDebug            # Android Lint
./gradlew assembleRelease      # R8 build (CI builds it too)
./gradlew buildEnvironment     # resolved Kotlin/KSP plugin versions
```
Cloud environment: the SessionStart hook (`.claude/hooks/android-sdk.sh`) installs the Android SDK,
writes `local.properties`, caps Gradle at 2 workers and sets `LC_ALL=C.UTF-8`; if the SDK is missing
anyway, run that script. The `verify` skill runs the checks. Maven Central often answers 429 or fails
to resolve plugins and artifacts, more so on a cold Gradle cache: retry. If Robolectric's `android-all-instrumented` download fails, fetch it with
`curl` into `~/.m2/repository/org/robolectric/...` and rerun.

## Where things are
Package `com.marcogn.pdftoolkit`, layered like the author's other apps (`docs/plan.md`):
- `ui/<feature>/` screens and view models. `ui/navigation/`: `@Serializable` routes (`Destination`),
  NavHost, drawer. `ui/theme/`: palette (`Color.kt`, an [ASSUNZIONE] of the spec) and theme.
  `ui/common/`: `ToolStrip` (bottom bar or landscape rail), `TransientHint`, page chip.
- `ui/viewer/`: the viewer and all page editing. `ReadyViewer` (bars, modes, tools, save UI),
  `PdfViewport` (one Canvas: tiles, annotations, overlays, highlights, selection; detectors on its
  `Box`; form controls and the ink layer as children), `PdfViewportState`, `ViewerViewModel`,
  `ViewerEditSession` (page-fixed session), `ViewerEditing.kt` (`ViewerPageTools`, back steps, save and
  fill bundles), `ViewportFill` (fill geometry across pages), `ViewerToolBar` (fixed places: Tools, two quick slots,
  Undo, Redo, Pages), `ToolDrawer` (fan, tutorial, drag ghost), `ToolGestures` (tap / hold still / hold and move),
  `FanLayout` (pure geometry). `domain/tools/` (`DrawerTool`, `QuickSlots`, `ToolPrefs`); `data/settings/ViewerToolPreferences`.
- `ui/edit/`: `EditScreen` = "Organize pages" (and merge), `EditViewModel`, save dialogs, `SaveRunner`
  (shared by both save UIs). `ui/merge/`: merge list.
- `ui/scan/`: ML Kit Document Scanner (`DocumentScanner`: PDF or JPEG output, availability), the
  Home "Scan" flow (`ScanToPdf` + `ScanViewModel`: scan → staged copy → "make it searchable?" → OCR in
  the background (optional, cancellable) → `CreateDocument` → viewer). `ui/ocr/`: `OcrRunner` (state of one
  background recognition, shared by the viewer and the scan flow), progress strip, error dialog.
- `ui/annotate/`: annotation drawing (`AnnotationLayer`, `drawAnnotations`), text selection
  (`TextSelectionState`, handles, gestures), tool state and the tool bubble (`AnnotateTools.kt`: `AnnotatePaneState`, `ToolBubble`, `DrawerTool` ↔ `AnnotateTool`), freehand
  on `androidx.ink` (`FreehandGestures`, `FreehandInk`, `ViewportInkLayer` + `WetStroke`), the partial eraser's
  touch (`EraserGestures`).
- `ui/fill/`: fill tools state and buttons (`FillTools.kt`), form controls (`FormFieldControls.kt`),
  `OverlayPainter`, overlay gestures, text dialog. `ui/signatures/`: "My signatures", creation (draw,
  import), picker sheet. `ui/search/`: search bar, notices, highlights.
- `domain/`: no Android. `edit/` (`EditSession`, `PageItem`, `PageSizing`, `SaveFailure`), `fill/`
  (overlays, `FieldValue`, `FormField`, `TextBlock`, `MarkShape`), `annotate/` (`Quad`, shapes,
  `NewAnnotation`, `AnnotationRef`, `AnnotationEdits`, `CompactPolylineSerializer`), `signature/`,
  `cloud/` (`CloudTarget`, interface only).
- `data/`: DataStore preferences, Room (`AppDatabase` v2: recents, signatures; schemas in
  `app/schemas/`, committed), `save/` (`SaveScheduler`, `SaveWorker`, `PdfSaver`), `images/`.
- `pdf/render`: `PdfRenderer` wrapper, scheduler/planner, caches, and the shared geometry
  (`DocumentLayout`, `Viewport`, `PageCoordinateMapper`, `PdfPageSpace`, `OverlayGeometry`).
  `pdf/edit`: `PdfEditor`/`PdfBoxEditor`, `FillWriter`, `AnnotationWriter`, fonts. `pdf/forms`:
  `FormReader`. `pdf/text`: extraction, normalisation, index, search, selection. `pdf/annotations`:
  reader, geometry, eraser, markup factory, freehand geometry, `DocumentStrokes`, `PartialEraser` (plan T-a:
  `EraserSweep`, the cut, `PartialEraseStroke` for one drag; Clipper2-java). `pdf/ocr`: model
  (`OcrPage`/`OcrLine`/`OcrWord`, page points), `OcrGeometry` (boxes → text runs in user space),
  `TextRecognizer` (ML Kit bundled), `OcrProcessor` (detect, render, recognise, write); the writer is
  `pdf/edit/OcrTextWriter` behind `PdfEditor.addTextLayer`. `data/ocr/`: `OcrWorker` (WorkManager, like
  `SaveWorker`), `OcrJob` (copy → `OcrProcessor` → copy over the destination), `OcrScheduler`/`OcrQueue`.
- Updates (ported from ThePatientGamerHelper, plan A): `domain/update` (version, release parsing, APK pick,
  release notes, `UpdateCheckPolicy`, states), `data/update` (`GitHubReleaseClient`, `ApkDownloader`,
  `ApkVerifier`, `ApkInstaller`, `UpdateInstallReceiver`, `UpdateManager`, `FileWriteGate`;
  `data/save/UserFileWrites` for in-process writes),
  `data/settings/UpdatePreferences` (DataStore), `ui/update` (dialog host, view model, messages),
  `ui/about` (About + `AboutViewModel`). Repository in `BuildConfig.UPDATE_GITHUB_OWNER`/`_REPO`.
- `.github/workflows/`: short callers of the reusable workflows in
  [claude-skill-android-kit](https://github.com/Marcogn/claude-skill-android-kit) (`@v1`): CI, Build APK,
  Release, cleanup, `@claude`, PR review; only this project's values here (`docs/ci.md`).
  `dependabot.yml` updates dependencies.
- `.claude/`: skills (`verify`, `new-plan`, `next-phase`, `close-phase`, `self-review`, `deps-triage`, `issues-triage`, `steward`), the
  `architecture-reviewer` agent, hooks (SDK, review gate, branch names), permissions: copied from the kit by `/android-kit` (version in
  `.claude/kit-version`); improve them in the kit, not here. Per project only this file and `REVIEW.md`
  (`docs/claude.md`).

## Rules that aren't obvious
- **Renderer**: one page open at a time per `PdfRenderer`, a mutex per document, rendering on
  `Dispatchers.Default`, page always closed (ADR 0001).
- **Coordinates** go only through `PageCoordinateMapper` (page points top-left ↔ layout px ↔ screen
  px) and `PdfPageSpace` (user space, matrices follow pdfium). Never convert by hand in the UI.
  Overlays and annotations are stored in **PDF user space**, so they turn with their page.
- **Writes** go through `PdfEditor`; the UI never touches PdfBox (ADR 0002). `PdfBoxEditor` edits the
  open document in place, materialises inheritable page attributes before detaching pages, and removes
  existing annotations **before** anything else touches `/Annots`.
- **Saving**: copy of the source → result in `cacheDir/work/` → verified → copied over the destination
  with mode `"wt"`, in `SaveWorker` (ADR 0003), or `OcrWorker` for text recognition. The request is a JSON file, not WorkManager `Data`.
  After an **overwrite** nothing may keep the old file open: navigate to the result with `popUpTo<Home>`.
- **Viewer editing** (ADR 0005): `ViewerEditSession` never changes the page list (ids `p<index>`), so
  the viewer renders the saved file and draws pending edits on top. Tools need
  `EditAvailability.READY` (annotations read, not password-protected). Edits are `SavedStateHandle`
  keys `viewerFill`/`viewerAnnotations`. "Pages" with unsaved changes asks to save or discard first.
- **Gesture arbitration**: the selection, overlay and freehand detectors sit after
  `detectZoomPanFling` and stop it through `SelectionGrab`/`OverlayGrab`/`FreehandGrab.active`
  (`suppressed`). Freehand runs in the **initial** pass and arbitrates by consuming. A live overlay
  change is local until the fingers lift (one undo step).
- **Freehand**: strokes in document points (`DocumentStrokes`), page = the one under the first point,
  then page display points → user space (`FreehandGeometry`). One stroke = one Ink annotation; the
  outline is the appearance, `/InkList` the centre line. Ink brush versions pinned (`V1`). The stroke being
  drawn is the app's (`WetStroke` in `ViewportInkLayer`), **not** `ink-authoring`'s `InProgressStrokes`, whose
  front-buffered layer stays blank until lift on some devices (Galaxy Tab S7+); don't bring it back.
- **Partial eraser** (plan T-a, ADR 0004): geometry only in `PartialEraser` (Clipper2 integer paths in hundredths
  of a point = the stored resolution); one drag = one `EditSession.replaceAnnotations` step on lift, the preview is
  `ViewerPartialErase.preview()` drawn in place of the session's annotations. Its gesture consumes everything
  (taps included) and shares `FreehandGrab`. JitPack is an `exclusiveContent` repository for `com.github.micycle1`
  only: don't widen it.
- **Text selection** is one page at a time, in that page's display points, stored as (page, glyph
  range) and resolved again from the text (`ResolveTextSelection`).
- **Annotations**: the renderer draws none, so the app draws them (`drawAnnotations`) from the same
  `AnnotationGeometry` the writer uses. **Form widgets** are drawn by the renderer (API 35+) with the
  file's value, so form controls are opaque and a pending value stays drawn when the tool is down.
- **Signatures, text, ticks, dates** are written into the page content stream, not as annotations;
  screen and writer share `OverlayGeometry`, `PdfPageSpace` and `TextBlock` (Noto Sans metrics).
- Search highlights are overlay only, never written. Search is in the viewer only.
- **`INTERNET` only for the update check** (ADR 0006, since A): the app's one connection is GitHub
  (`data/update`). ML Kit telemetry is off: the manifest removes datatransport's `cct` backend and
  `TelemetryOffTest` guards it; after any ML Kit/datatransport update rerun it and capture traffic on a
  device. Don't add dependencies or code that use the network (phase 12, WebDAV, is the exception).
- **Updates** (plan A): `UpdateManager` is the single state (`UpdateState`), `UpdateDialogHost` above the nav
  graph shows it. Installing ends the process, so `install()` waits while a job tagged `FILE_WRITE_WORK_TAG`
  (save, OCR) is queued or running, or a write wrapped in `UserFileWrites.track` (scan copy) runs; a pending
  confirmation is abandoned if one starts. Any new write of a user's file must do one of the two. APKs go to
  `cacheDir/updates/`, never `cacheDir/work/`.
- **Navigation**: every `navigate()`/`popBackStack()` goes through the owning entry's
  `lifecycleIsResumed()` guard, except navigation from an activity result (the picker). Transitions
  200–250 ms; `NavHost` needs both pop and `predictivePop*` transitions. Don't turn the edit screen into
  a nested graph.
- `SavedStateHandle` also holds the route arguments by name: never reuse `uri`, `tool`, `mergeWith`,
  `autoSave`, `uris`, `page` as a state key; list arguments are arrays.
- Single-page mode is a `HorizontalPager` of one-page viewports (`pageIndexOffset = page`); keys carry
  the real page index, viewports share one `RenderScheduler`.
- `PageItem.rotation` is the rotation the user *added*; the page's own `/Rotate` is added on write.
- `MainActivity` is an `AppCompatActivity` (per-app language). No hardcoded UI strings: `values/`
  (Italian, default) and `values-en/`. In composables read resources with `LocalResources.current`.
- **R8 full mode** (release, "Build APK"): a `-keep class` rule without members does not keep the default
  constructor. Libraries that instantiate classes by reflection (ML Kit's `ComponentRegistrar`s) need
  `{ <init>(); }` in `proguard-rules.pro`; debug builds are not minified and hide such bugs.
- AGP 9 built-in Kotlin: no `org.jetbrains.kotlin.android` plugin; options in `kotlin { compilerOptions { } }`.

## Conventions
- English for docs, comments and identifiers; UI Italian with English translation.
- `PT` prefix only to avoid name clashes. Spec choices marked **[ASSUNZIONE]** are implemented as
  written and kept isolated.
- If a requirement is unclear or not feasible, stop and ask.

## Current status (2026-10-13)
- Released: **1.0.0** (product phase 1, 2026-10-04), 1.1.0 (viewer editing), 1.1.1 (scan, OCR), 1.1.2–1.1.3
  (freehand fix, updates), **1.2.0** (partial eraser, tool drawer, 2026-10-13). The current version is the build's `versionName` and `CHANGELOG.md`.
- Device checks passed or counted as done by the author for every sub-phase up to T-a; 10b and A will get more
  test rounds (their lists: `docs/plan-v2.md` 10b, `docs/plan-updates.md`). The app-drawn wet stroke
  (`WetStroke`, 1.1.3) was checked on the Galaxy Tab S7+ before T-a.
- **T-b Tool drawer** merged (PR #40) and released in 1.2.0; its device checks are still pending (list in
  `docs/plan-tools.md`). T-a feedback it addressed: the eraser's mode and the Marker are now drawer tools.
- **Next: 11 ODF export** (the author is considering dropping it), 12, or whatever the author picks.
- Still open from product phase 1: the **baseline profile** (needs a device: `androidx.baselineprofile` plugin +
  a macrobenchmark module, startup / open viewer / open organize).
- Waiting on the author: the `CLAUDE_CODE_OAUTH_TOKEN` secret (no automatic review ran on PR #40) and the cloud
  environment's setup script (`docs/claude.md`). Open issue: #32 (spec in English).
- Open questions for later sub-phases (`docs/plan-v2.md`): ODG or ODT (11), numbering of the next releases.

### Technical limits worth knowing
- Updates: installing restarts the app, so pending (unsaved) viewer edits are lost; the ready dialog says to save
  first, but nothing enforces it. The ML Kit telemetry cut is not a documented Google switch (ADR 0006).
- OCR: the scan flow's staged files live in the app cache and the run state in the view model, so killing the
  app during a scan's recognition loses its save step (the raw scan stays in the cache for an hour). In the
  viewer, a Cancel that lands just as the last copy over an overwrite destination starts can leave the file
  replaced while the run reads as cancelled (the button is disabled from 96%, the window is a few ms); picking a copy and then leaving before the run ends can leave an empty copy behind (the view model that
  deletes it is gone). A failed page (`PageText.failed`) still counts as "no text" for the search notice, so the
  button may be offered on a document that OCR then reports as having text.
- Form controls are recomposed on every scroll frame for the pages on screen; fine for usual forms,
  not measured on very dense ones. "Next" on the keyboard follows Compose focus order, which only
  reaches controls currently composed (pages on screen).
- A focused text field is kept above the keyboard by panning, which can't go past the end of the
  document (a field at the very bottom of the last page may stay partly covered).
- Freehand coordinates are floats in document points: beyond about a thousand A4 pages their
  resolution drops to about 0.1 pt (not visible in practice).
- Canvas-drawn content (pages, overlays, the corner handle) has no per-element accessibility semantics.
- Reopened ink is drawn from its centre line (even width) in the app; other readers show the outline.
- Partial eraser: the live cut runs on the main thread; its cost on many long strokes is not measured (ADR 0004
  addendum; fallback: cut on lift only).
- `AndroidPageImageLoader`, `Stroke → FreehandStroke` and `WetStroke`'s stroke (androidx.ink's native code doesn't
  load in JVM tests; only its input filter is tested) have no unit tests (device only).
- The scanner flow (`ScanToPdf`, `DocumentScanner`) needs Play services and has no JVM test beyond the
  pure helpers (`scanUnavailableFor`, `scanFileName`); the rest is device only.
- Lint warns about `ConfigurationScreenWidthHeight` on `isLandscape()`.
