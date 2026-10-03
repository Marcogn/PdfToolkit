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
- `domain/` models with no Android dependencies (`PdfTool`, `ThemeMode`, `domain/edit/`:
  `EditSession`, `PageItem`, `SaveFailure`; `domain/fill/`: overlays, `FieldValue`, `FormField`,
  `TextBlock`, `MarkShape`; `domain/signature/`: `InkStroke`/`InkWidth`, `BackgroundRemoval`).
- `data/` DataStore (`data/settings/ThemePreferences`, `ReadingPreferences`), Room
  (`data/recents/`: `AppDatabase` (v2, `MIGRATION_1_2`), `RecentDocument`, `RecentsRepository`, `ThumbnailStore`),
  signatures (`data/signatures/`: `Signature`, `SignatureDao`, `SignatureRepository`,
  `SignatureRendering`), background save (`data/save/`: `SaveScheduler`, `SaveWorker`, `PdfSaver`).
  Room schemas are exported to `app/schemas/` and committed.
- `pdf/render` (phase 1): `PdfDocumentRenderer`, `RenderScheduler`/`RenderPlanner`, `PageThumbnails`, caches, and
  the geometry shared by all phases (`DocumentLayout`, `Viewport`, `PageCoordinateMapper`).
  `pdf/edit` (phase 2: `PdfEditor`, `PdfBoxEditor`; phase 4: `FillWriter`, `FontSource`, `FontCoverage`),
  `pdf/forms` (phase 4: `FormReader`), `pdf/text` from phase 5 (spec §12). `ui/fill/` is the
  "Fill and sign" pane of `EditScreen`; `ui/signatures/` is "My signatures" plus the creation flow
  (draw, import) and the picker sheet that `EditScreen` reuses.
- `di/` Hilt modules, when needed.

## Non-obvious rules
- One page open at a time per `PdfRenderer` instance: one mutex per document, rendering on
  `Dispatchers.Default`, page always closed after rendering (ADR 0001).
- All writes go through `PdfEditor`; the UI never touches PdfBox (ADR 0002). `PdfBoxEditor`
  rearranges the open document in place (keeps fonts, annotations, forms) and materialises the
  inheritable page attributes (`MediaBox`, `CropBox`, `Resources`, `Rotate`) before detaching pages.
- Saving: source copy → result in `cacheDir/work/` → verified → copied over the destination with
  mode `"wt"`; runs in `SaveWorker` (WorkManager, ADR 0003). The page list travels as
  `EditSession.encode()` in a JSON file, not in WorkManager `Data`.
- `PageItem.rotation` is the rotation the user *added*; the page's own `/Rotate` is added on write.
  Grids draw the source thumbnail and turn it, so thumbnails are never re-rendered for a rotation.
- After an *overwrite* nothing may keep the old file open: the nav graph opens the result with
  `popUpTo<Home>`.
- Signatures, text, check marks and dates are written into the page content stream, not as
  annotations. Overlays are stored in **PDF user space** (`OverlayBox`: centre, size, angle), so they
  turn with their page; screen and writer both go through `OverlayGeometry` + `PdfPageSpace`, and
  text layout through `TextBlock` (Noto Sans metrics as constants, kerning/ligatures off on screen).
- Form controls in the fill pane are opaque: the rendered page already contains each widget's
  appearance (with the saved value), and the control is the only picture of the field.
- Overlay gestures (`ui/fill/OverlayGestures`): the overlay handler sits after `detectZoomPanFling`
  and tells it to stand down through `OverlayGrab.active` (`suppressed` parameter). Live changes are
  a local copy in `FillPage`, committed once on lift (one undo step); the maths is
  `OverlayGeometry.transformed` + `UserTransform`, in user space.
- Search highlights are overlay only, never written into the PDF.
- No `INTERNET` permission in product phase 1: the manifest removes it with `tools:node="remove"`
  and CI checks the packaged manifest. Don't add dependencies that need it.
- Every `navigate()`/`popBackStack()` goes through the `lifecycleIsResumed()` guard of the entry
  that owns the callback (double tap during a transition). Home from the drawer:
  `popUpTo<Home>{inclusive}`. Exception: navigation from an activity result (the SAF picker),
  which arrives before the entry is RESUMED again; the tap that launches the picker is guarded.
- Edit hub, "Remove pages" and "Reorder pages" are panes of one `EditScreen` (`Destination.Edit(uri,
  tool)`), sharing one `EditViewModel`; don't turn them into a nested nav graph (ADR 0003).
- Single-page mode is a `HorizontalPager` of one-page layouts: each page has its own
  `PdfViewportState` and `PdfViewport(pageIndexOffset = page, requestSource = page)`, so keys carry
  the real page index and several viewports can share one `RenderScheduler` (it merges the lists
  per source). A one-finger horizontal drag the page can't absorb is left unconsumed for the pager.
- Viewer coordinates go only through `PageCoordinateMapper` (page points top-left ↔ layout px ↔
  screen px; user space through `PdfPageSpace`, whose matrices follow pdfium). Don't convert by hand
  in the UI.
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
- ADRs: `docs/adr/0001-viewer.md`, `docs/adr/0002-pdfbox-android.md`,
  `docs/adr/0003-background-save-and-edit-session.md`.

## Current status
<!-- Update at the end of every session. -->
- **Phase 0 done (2026-10-01)**: skeleton, theme, languages, navigation, drawer, Home, signing,
  CI, docs, ADR 0001–0002. Toolchain and libraries upgraded to the latest stable versions
  (`docs/plan.md`). Verified with lint, JVM/Robolectric tests, debug and release builds; the
  author installed the phase 0 APK (pre-upgrade build) and confirmed it works.
- PR #1 merged.
- **1a Viewer core done (2026-10-01)**, PR #3 merged; device checks passed (author). Follow-up fix:
  system back no longer shrinks the screen (predictive pop transitions).
- **1b Viewer complete done (2026-10-01)**, PR #5; lint, 63 unit tests and `assembleDebug` green;
  device checks passed (author).
- **Phase 2 Edit session and pages done (2026-10-01)**, PR #6; lint (0 errors), 95 unit tests and
  `assembleDebug` green; device checks passed (author). Follow-up fix: page thumbnails with no added
  rotation were laid out with zero height (blank cells).
- **Phase 3 Add pages and merge done (2026-10-01)**, PR #7 merged; lint (0 errors), 144 unit tests
  and `assembleDebug` green; device checks passed (author).
- **4a Fill and sign core done (2026-10-02)**, PR #8; lint, 194 unit tests and `assembleDebug`
  green; device checks passed (author). Follow-up fix: form controls on a turned page now turn with
  it (they showed the text across the field).
- **4b Fill and sign complete done (2026-10-02)**, PR #9; lint (0 errors), 220 unit tests and
  `assembleDebug` green; device checks passed (author). Follow-up fix (author's device test):
  a form saved and reopened showed each value twice, the page bitmap's widget appearance under the
  semi-transparent control; controls are now opaque white under their tint. Radio buttons drawn as
  two offset circles in Acrobat: the writer only switches `/AS` (test), the drawings are the
  source PDF's own appearance streams. **Next: phase 5a Search core (Opus).**
- The author still has to add the signing secrets to the repository.

### Notes from phase 4 (fill and sign)
- Model: `EditSession.fill` (`FillContent`: `overlays`, `fields`) is part of the undo history;
  `addOverlay/updateOverlay/removeOverlay`, `setField(name, value, typing)`. `encodeFill()` →
  `SavedStateHandle` (`fill`) and `SaveRequest.fill`.
- Geometry: place with `OverlayGeometry.uprightAt`; gestures through `OverlayGeometry.transformed`
  (move, pinch scale, twist; text scales its font size). `PdfPageSpace.displayAngle/userAngle` for angles.
- Signatures: Room table `signatures` (v2), PNGs in `filesDir/signatures/`; `SignaturesViewModel`
  (Hilt) serves "My signatures" and the picker sheet. Placing copies the PNG to `cacheDir/images/`
  (`EditViewModel.importOverlayImage`), so deleting a signature never breaks an unsaved session.
  Creation state (`SignatureCreationState`) is saved across rotation; the drawing dialog forces
  landscape while open. The legal note shows once (DataStore `signature_prefs`) before the first
  creation, and is in About.
- Known limits: no tiling in the fill pane (one bitmap per page, ≤ 4 Mpx); "Next" moves only within
  the current page; static XFA is removed when fields are filled or flattened; flatten-before-merge
  (spec §6.6) not done yet; fields of PDFs added to the session (not the main one) aren't fillable;
  an overlay can't be resized on one axis (proportions are locked); the crop in the import dialog
  has corner handles only; the signature drawing isn't smoothed beyond the width (straight segments).

### Notes for phase 4 onwards (edit, from phase 3)
- Edit code: `domain/edit/` (`EditSession`: immutable, undo/redo by swapping lists, `isModified`
  against the original; `PageItem.FromPdf` / `Blank` / `FromImage`; `PageSizing` has the §6.2 size
  rules as pure functions; `InsertionPoint`), `pdf/edit/PdfBoxEditor.applySession(session, sources,
  output)` (main PDF rearranged in place; pages of other PDFs through `importPage` with the other
  documents kept open until saved; blank and image pages built at write time; one bitmap alive at a
  time), `pdf/edit/PageImageLoader` (interface; `AndroidPageImageLoader`: `ImageDecoder` on API 28+,
  `BitmapFactory` + EXIF below), `data/save/*` (`SaveRequest.extraSources` lists the added PDFs, the
  saver copies only the ones the final pages use), `data/images/ImageImporter` (picked images are
  copied to `cacheDir/images/`, cleaned after 24 h), `ui/edit/` (`EditScreen`: hub, remove/reorder
  panes, page picker for an added PDF, dialogs in `AddPagesDialogs`), `ui/merge/` (merge list).
- `EditSession.encode()` is a typed format (`P`/`B`/`I` entries, URL-encoded fields); `decode` needs the
  page count of every document (`Map<DocRef, Int>`). Doc ids of added PDFs are `DocRef(index + 1)` of
  the `extras` list kept in `SavedStateHandle`; a merge is `Destination.Edit(uri = first, mergeWith =
  rest, autoSave)` and starts from `EditSession.ofDocuments(...)`.
- `PdfEditor` doesn't take overlays yet (spec §12 has `applySession(session, overlays, destination)`):
  add them in phase 4 (they bind to `PageItem.id`). Destination copy lives in `PdfSaver`, not in the editor.
- Known limits: FAB → hub is the normal slide+fade, not a container transform (phase 6 polish);
  password-protected PDFs can't be edited, added or merged (PdfBox gets no password; the merge list and
  "add pages" report it); a save can't be cancelled; if the app is killed during a save the result of
  a *copy* isn't announced (the work finishes anyway); removed pages can stay in the file as orphan
  objects if a bookmark/link references them; the Edit button hides on scroll in continuous mode only;
  merging drops bookmarks and may break form fields (warned in the merge list; flatten-before-merge,
  proposed by spec §6.6, waits for phase 4a's flatten); HEIC/HEIF need API 28+, AVIF is guaranteed only from Android 14;
  `AndroidPageImageLoader` (both decoding paths) has no unit tests: only on-device checks cover it;
  each image page probes the file twice at save time (size, then decode).
- `SavedStateHandle` also receives the route arguments by name: never reuse an argument name
  (`uri`, `tool`, `mergeWith`, `autoSave`, `uris`) as a state key, and its list arguments are arrays,
  not `ArrayList` (the merge screen crashed on this).
- The hub is the session's pages (`PagesGrid` in `PagesMode.VIEW`, read-only) plus `HubToolBar` at
  the bottom. After an addition the new page ids are highlighted and scrolled to (`highlighted` /
  `scrollToId` in `EditScreen`); the marks clear on the remove pane and after a save.
- Merge is only on Home (`Destination.Merge`); the hub leaves it out, its job there is "Add pages →
  from another PDF". Home's "Add pages" / "Insert images" show a dialog before the PDF picker
  (`toolsPickingTwice` in the nav graph).
- `importPage` keeps link annotations whose destinations point into the source PDF: those page
  objects (and what they reach) are written as unreferenced objects, so a merged file with internal
  links can be larger than the sum of its pages. Valid PDF, not addressed.

### Notes for phase 2 onwards (viewer, from 1b)
- The viewer is `ViewerScreen` (states) → `ReadyViewer` (top bar, `ContinuousPages` / `SinglePages`,
  `PageScrubber`, `ThumbnailBar`). Jumps go through a `Channel<Int>`; `currentPage` is hoisted in
  `ReadyViewer`. The Edit FAB (phase 2) goes in its `Scaffold`; the Search icon (phase 5b) in its top bar.
- `ViewerViewModel` records the opening in `RecentsRepository` and saves the last page (debounced,
  and in `onCleared` through the repository's own scope). `PdfDocumentOpener.open(uri, password)`.
- Known limits: files opened from `VIEW`/`SEND` usually carry only a temporary permission, so they
  show as "unavailable" in recents after the grant expires (no copy into app storage); in single
  page mode zoom 1 is fit width, not fit page (landscape phones scroll vertically); Canvas still has
  no accessibility semantics (phase 6); recents remove by long press only, no swipe.

## Decisions
- 2026-10-02 · Phase 4b: the drawing canvas is my own Compose implementation (spec allows it, or
  `androidx.ink`): per-point width from finger speed (`InkWidth`, smoothed, 0.55–1.25 × base), drawn
  as round-capped segments; strokes are rendered to a transparent PNG cropped to their bounds (max
  1600 px). Avoids a new dependency.
- 2026-10-02 · Phase 4b: the signature archive is created from a dialog flow rather than nav
  destinations, so "create on the spot" from the fill pane returns without touching the nav graph;
  its state is saved across rotation. A new signature is saved straight away with a default name
  ("Firma N") and renamed from the archive, to keep the flow short.
- 2026-10-02 · Phase 4b: "remove background" is a luminance threshold with a soft ramp
  (`BackgroundRemoval`, default 0.75, slider 0.3–0.95) and then trims to the ink; spec §6.5 says
  "soglia sul bianco → trasparente". The result is trimmed only when the background is removed.
- 2026-10-02 · Phase 4b: gestures on overlays: a touch that starts on the selected overlay (20 dp
  margin) drags/pinches it; long press on any other one selects and drags (author's request after
  the 4a device test). Pinch scales with locked proportions (spec), text scales via font size.
- 2026-10-02 · Phase 4b: the legal note is a once-only dialog before the first creation (DataStore
  flag), not a permanent banner; it is also in About (already there from phase 0).
- 2026-10-02 · Phase 4a: overlays and form values live in `EditSession` (one undo history for pages
  and fill) rather than as a separate `overlays` argument of `applySession` (spec §12): the session
  already travels to the save; `WriteOptions(flattenForm)` carries the save-time choice.
- 2026-10-02 · Phase 4a: overlay coordinates in PDF user space with an angle, not screen/display
  space: they don't depend on zoom or on rotations added later, and the writer needs no conversion.
  Visible box = `/CropBox` ∩ `/MediaBox`, `/Rotate` truncated to quarter turns, as pdfium
  (`CPDF_Page::UpdateDimensions`, [source](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/core/fpdfapi/page/cpdf_page.cpp)).
- 2026-10-02 · Phase 4a: Noto Sans Regular 2.015 (static, unhinted, from notofonts.github.io; OFL 1.1,
  licence in `assets/fonts/OFL.txt`) in `assets/`, read by both PdfBox and Android. Embedded as a
  subset for overlays and in full only when a form field needs it (a viewer may regenerate field
  appearances with other characters). Characters it lacks are dropped (screen and PDF alike).
- 2026-10-02 · Phase 4a: ticks and crosses are stroked paths, not glyphs (Noto Sans has no ✓/✗).
- 2026-10-02 · Phase 4a: "Fill and sign" is a pane of `EditScreen` (shares session and save, like the
  other tools), one page at a time in a pager; "make final" is in the save dialog once the form has
  been read, default on when an image (signature) was placed (spec §6.5). Dynamic XFA: message, free
  filling only; static XFA: the AcroForm is filled and `/XFA` removed so readers use the new values.
- 2026-10-02 · Phase 4a: until the signature archive (4b), the Signature tool places any image
  picked with the photo picker (copied to `cacheDir/images/` like page images).
- 2026-10-02 · No "Merge PDFs" in the edit hub (spec §6.6 has "Unisci con altro PDF" there): on an
  open document it duplicates "Add pages → from another PDF" (author's decision after the device
  test). It also never worked from the hub: its navigation came from an activity result and went
  through the `lifecycleIsResumed()` guard, which drops it (see Non-obvious rules).
- 2026-10-01 · Edit hub: page thumbnails of the session with the tools in a bottom bar, instead of
  the tool grid of spec §4.3 (author's request after the phase 3 device test: the tool grid looked
  like Home and didn't show the document being edited).
- 2026-10-01 · Phase 3: images are copied to `cacheDir/images/` when picked (Photo Picker grants are
  temporary and a save resumed by the system must still read them); cleaned after 24 h at startup,
  not when the screen closes, because a background save may still need them. Added PDFs are read from
  their URIs (persistable read grant taken when the provider allows it).
- 2026-10-01 · Phase 3: a photo's DPI is used for "original size" only if it is ≥ 100; below that
  (cameras write 72) 150 DPI is used. This reads spec §6.2's "DPI dei metadati se presenti" together
  with its own remark that 72 DPI would make a 1.4 m page. Original-size images are decoded at most
  8000 px on the long side (the spec sets no cap; this only guards memory). Pages added at the start
  take the *next* page's size, others the previous one, using the visible size (rotation applied).
- 2026-10-01 · Phase 3: protected PDFs are rejected when added or merged (message), consistently
  with the phase 2 decision; spec §6.6 asks for a password prompt per file, which would need the
  password kept until save (not persisted) or a decrypted copy: left out, to revisit with the
  author. Merge is its own `Destination.Merge` list screen; "Merge"/"Merge and edit" then open
  `Edit` with `mergeWith`, so no merge logic lives outside `EditSession`/`PdfEditor` (spec §6.6).
- 2026-10-01 · Phase 3: `ImageDecoder` applies the EXIF orientation and reports oriented sizes
  (AOSP `libs/hwui/hwui/ImageDecoder.cpp`); below API 28 the orientation is applied by hand with
  the matrices of Glide's `TransformationUtils`. `androidx.exifinterface` 1.4.2 added (DPI and
  orientation); `android.media.ExifInterface` is discouraged by lint.
- 2026-10-01 · Phase 3: `PdfDocumentOpener` no longer deletes every file in `cacheDir/open/` on
  each non-seekable open (it would break other open documents); only copies older than 1 hour.
- 2026-10-01 · Phase 2 saving runs in WorkManager (expedited, `dataSync` foreground fallback), not
  a hand-written service; the page list goes through a JSON file; the worker reaches `PdfSaver`
  through a Hilt `EntryPoint` (no `hilt-work`). Reasons in ADR 0003.
- 2026-10-01 · Page drag & drop with Reorderable 3.1.0 (Apache 2.0 from the POM, builds on Compose
  1.7+). One drag = one undo step (local copy while dragging, one `move` on release).
- 2026-10-01 · The picker now requests write + persistable grants (`OpenPdfContract`) and
  `takePersistableAccess` keeps write access when given: that is what makes "overwrite" possible
  (a plain `OpenDocument` only yields read access). Overwrite is offered only if
  `checkUriPermission(WRITE)` passes. `VIEW`/`SEND` files are usually copy-only.
- 2026-10-01 · `cacheDir/work/` startup cleanup removes only files older than 1 hour (spec §8 says
  "emptied at startup"): a save interrupted by the system is re-run by WorkManager and needs its
  request file.
- 2026-10-01 · Encrypted PDFs are not edited (PdfBox isn't given the password; spec §14 excludes
  writing protected files).
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
- 2026-10-01 · Reading mode: one global preference (DataStore `reading_prefs`); the viewer menu and
  the Settings default are the same value (spec §4.2 [ASSUNZIONE]).
- 2026-10-01 · Passwords use `PdfRenderer(fd, LoadParams)`, available from API 35 (checked in the
  SDK's `api-versions.xml`; it also exists with SDK extension 13 on API 31–34, not used). Below 35
  the file fails with `PASSWORD_UNSUPPORTED` and a message. The password is kept only in memory
  (`remember`, not saved instance state).
- 2026-10-01 · Recents: Room table `recent_documents`, max 10 (older ones trimmed on insert, with
  their thumbnails); accessibility checked by opening the descriptor; thumbnails are JPEGs in
  `cacheDir/thumbnails/` named by SHA-256 of the URI. The database is excluded from backup.
- 2026-10-01 · Intents: `VIEW` (content, file) and `SEND` for `application/pdf` on `MainActivity`
  (standard launch mode); the URI opens the viewer on top of Home, only on a fresh launch
  (`savedInstanceState == null`), so rotation doesn't reopen it.
- 2026-10-01 · Scrubber: only the thumb takes touches (the rest of the edge keeps panning and
  doesn't fight the system back gesture); linear page mapping.
