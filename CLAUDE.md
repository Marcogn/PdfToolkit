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
  released as 1.0.0), then 7–12 for product phase 2 (`docs/plan-v2.md`), with two inserted series:
  U-a/U-b (usability, `docs/plan-usability.md`) and V-a/V-b/V-c (editing in the viewer,
  `docs/plan-viewer-editing.md`). "Next phase" means the next development sub-phase.
- **Product phase 1 / 2** (spec §1, §7, §14): the feature sets. Spec lines like "Fase 2 (pianificata,
  non implementare)" or "in Fase 1 nessun permesso INTERNET" refer to the product. Implement a product
  phase 2 feature only inside its own sub-phase.
- Code comments cite plan items as "plan U7", "plan V-b": U-items are in `docs/plan-usability.md`,
  V-items in `docs/plan-viewer-editing.md`.

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
4. Close when "Done when" holds: lint + unit tests + `assembleDebug` green; docs updated; commit; PR.
5. An **a** sub-phase writes a handoff (≤ 15 lines) in Current status for its **b**.
6. End with the sub-phase's **device checks**: there is no emulator here, the author tests on a phone.
7. Don't start the next sub-phase in the same session unless asked.

Session title `<sub-phase> <Name>`, branch the same in kebab-case (e.g. `9-scan`), set at the start.
The cloud environment may fix the branch name: then use the one it gives.

| Sub-phase | Model | Scope | Done when | Check on the device |
|---|---|---|---|---|
| 9 Scan | Sonnet | `docs/plan-v2.md` 9: ML Kit Document Scanner, availability, open/add pages | Plan 9; packaged manifest still without `INTERNET` | Scan, save, add to open PDF; airplane mode |
| 10a OCR core | **Opus** | `docs/plan-v2.md` 10a: Text Recognition v2, invisible text layer in user space | Unit tests on line geometry and search after OCR; handoff written | OCR'd scan searchable in app and other reader |
| 10b OCR complete | Sonnet | `docs/plan-v2.md` 10b: UI, progress, cancellation, background | Plan 10b | 20-page scan; cancel halfway |
| 11 ODF export | Sonnet | `docs/plan-v2.md` 11 | Plan 11 | Result opens in LibreOffice / Collabora |
| 12 Cloud (WebDAV) | Sonnet (Opus reviews the credential store) | `docs/plan-v2.md` 12; adds `INTERNET` | Plan 12 | Nextcloud upload, wrong password, no network |

Done: 0, 1a, 1b, 2, 3, 4a, 4b, 5a, 5b, 6 (except the baseline profile), 7a, 7b, 8a, 8b, U-a, U-b,
V-a, V-b, V-c. Haiku is not recommended for code here.

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
  fill bundles), `ViewportFill` (fill geometry across pages), `ViewerToolBar`.
- `ui/edit/`: `EditScreen` = "Organize pages" (and merge), `EditViewModel`, save dialogs, `SaveRunner`
  (shared by both save UIs). `ui/merge/`: merge list.
- `ui/annotate/`: annotation drawing (`AnnotationLayer`, `drawAnnotations`), text selection
  (`TextSelectionState`, handles, gestures), tool state and Style menu (`AnnotateTools.kt`), freehand
  on `androidx.ink` (`FreehandGestures`, `FreehandInk`, `ViewportInkLayer`).
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
  reader, geometry, eraser, markup factory, freehand geometry, `DocumentStrokes`.
- `.github/workflows/`: short callers of the reusable workflows in
  [claude-skill-android-kit](https://github.com/Marcogn/claude-skill-android-kit) (`@v1`): CI, Build APK,
  Release, cleanup, `@claude`, PR review; only this project's values here (`docs/ci.md`).
  `dependabot.yml` updates dependencies.
- `.claude/`: skills (`verify`, `next-phase`, `close-phase`, `steward`), the `architecture-reviewer`
  agent, the SDK hook, permissions: copied from the kit by `/android-kit` (version in
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
  with mode `"wt"`, in `SaveWorker` (ADR 0003). The request is a JSON file, not WorkManager `Data`.
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
  outline is the appearance, `/InkList` the centre line. Ink brush versions pinned (`V1`).
- **Text selection** is one page at a time, in that page's display points, stored as (page, glyph
  range) and resolved again from the text (`ResolveTextSelection`).
- **Annotations**: the renderer draws none, so the app draws them (`drawAnnotations`) from the same
  `AnnotationGeometry` the writer uses. **Form widgets** are drawn by the renderer (API 35+) with the
  file's value, so form controls are opaque and a pending value stays drawn when the tool is down.
- **Signatures, text, ticks, dates** are written into the page content stream, not as annotations;
  screen and writer share `OverlayGeometry`, `PdfPageSpace` and `TextBlock` (Noto Sans metrics).
- Search highlights are overlay only, never written. Search is in the viewer only.
- **No `INTERNET`** in product phase 1: the manifest removes it with `tools:node="remove"` and CI
  checks the packaged manifest. Don't add dependencies that need it (phase 12 is the exception).
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
- AGP 9 built-in Kotlin: no `org.jetbrains.kotlin.android` plugin; options in `kotlin { compilerOptions { } }`.

## Conventions
- English for docs, comments and identifiers; UI Italian with English translation.
- `PT` prefix only to avoid name clashes. Spec choices marked **[ASSUNZIONE]** are implemented as
  written and kept isolated.
- If a requirement is unclear or not feasible, stop and ask.

## Current status (2026-10-08)
- Product phase 1 released as **1.0.0** (2026-10-04). Still open from it: the **baseline profile**
  (needs a device: `androidx.baselineprofile` plugin + a macrobenchmark module, startup / open viewer /
  open organize) and the **signing secrets** in the repository (Build APK and Release need them).
- Done since: 7a–8b (annotations, freehand), U-a/U-b (usability), V-a…V-c (editing in the viewer).
- **V-c Fill and sign in the viewer done (2026-10-08)**, PR #24; lint (0 errors), 440 unit tests,
  `assembleDebug` and CI green; device checks passed (author, 2026-10-08). **Next: 9 Scan (Sonnet).**
- **Claude Code setup (2026-10-08)**, outside the sub-phases: skills, reviewer agent, SDK hook,
  `REVIEW.md`, `@claude`/review workflows, Dependabot, PR and issue templates, opt-in JaCoCo coverage
  (line coverage 16%; `pdf/edit` 0% in JVM tests) (`docs/claude.md`). Waiting
  on the author: the `CLAUDE_CODE_OAUTH_TOKEN` secret and the environment's setup script.
- Open questions for later sub-phases (`docs/plan-v2.md`): OCR bundled or not (10a), ODG or ODT (11),
  release numbering.

### Device checks V-c (author, passed 2026-10-08)
Fill a multi-page form while scrolling in continuous mode (text, check box, radio, list; keyboard
"Next"; a field near the bottom stays above the keyboard); fields filled then Fill put down: the new
values still show; text, date, tick, cross on several pages at different zooms; signature with 0/1/many
saved, place, move, resize and turn (two fingers and the corner handle), long press to grab another;
undo/redo; single-page mode; landscape rail; Home → Fill and sign opens the viewer armed; save as copy
and overwrite with "make final" on and off, open in another reader; back with changes; rotate the
phone mid-edit; password PDF says it can't be edited.

### Technical limits worth knowing
- Form controls are recomposed on every scroll frame for the pages on screen; fine for usual forms,
  not measured on very dense ones. "Next" on the keyboard follows Compose focus order, which only
  reaches controls currently composed (pages on screen).
- A focused text field is kept above the keyboard by panning, which can't go past the end of the
  document (a field at the very bottom of the last page may stay partly covered).
- Freehand coordinates are floats in document points: beyond about a thousand A4 pages their
  resolution drops to about 0.1 pt (not visible in practice).
- Canvas-drawn content (pages, overlays, the corner handle) has no per-element accessibility semantics.
- Reopened ink is drawn from its centre line (even width) in the app; other readers show the outline.
- `AndroidPageImageLoader` and `Stroke → FreehandStroke` have no unit tests (device only).
- Lint warns about `ConfigurationScreenWidthHeight` on `isLandscape()`.
