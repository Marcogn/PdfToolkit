# Decisions

The choices that are still in force, by topic, with the date they were taken. Superseded ones are
removed (git history keeps them); the big ones have an ADR in `docs/adr/`. "Author" means the author
decided or approved it.

## Process and project

- 2026-10-01 · Documentation and code comments in English, app UI in Italian with an English
  translation (author). The spec stays in Italian, in `docs/spec.md`.
- 2026-10-01 · One model per sub-phase (author): Sonnet by default, Opus for the cores where a wrong
  design is expensive (**a** sub-phases, with a handoff note for **b**). Every session starts with a
  model check. One session and one branch per sub-phase, named after it.
- 2026-10-01 · Toolchain and libraries on the latest stable versions (author); steps in `docs/plan.md`.
  Hilt ≥ 2.59 requires AGP 9, so the two move together. `compileSdk`/`targetSdk` 37, the maximum for AGP 9.4.
  Gradle wrapper with `distributionSha256Sum`.
- 2026-10-01 · Release keystore dedicated to this app (RSA 2048, 10,000 days, alias `pdftoolkit`, PKCS12),
  handed to the author, never committed.
- 2026-10-04 · CI uploads no debug APK (author; spec §13 phase 0 asked for one): it needed uninstalling
  the app and wasn't minified. "Build APK" (signed release, R8) is the APK for device checks. The
  workflows now come from the kit (see 2026-10-09), with the per-project values in the callers.
- 2026-10-04 · Product phase 2 is numbered as development phases 7–12, so "phase 2" keeps meaning the
  edit session. Order (author): highlight and draw, then scan, then OCR, ODF, cloud last. Inserted series:
  usability (U-a, U-b) and viewer editing (V-a…V-c) before 9, updates (A) after 10b, partial eraser and tool
  drawer (T-a, T-b) before 11.
- 2026-10-08 · Claude Code setup shared by the author's Android projects (author): skills, a reviewer
  agent, a SessionStart hook that installs the Android SDK in cloud sessions, `@claude` and review
  workflows on the Claude subscription, Dependabot (`docs/claude.md`). The `.claude/` files are
  **copied** into each project, not packaged as a plugin: cloud sessions don't load plugins a
  repository enables. Per project: `CLAUDE.md` and `REVIEW.md`.
- 2026-10-08 · Unit test coverage with AGP's built-in JaCoCo, opt-in (`-Pcoverage`) so local runs stay
  fast; CI uploads the report. Not a gate: a number to look at, not a threshold. Kover not used (no
  documented support for AGP 9 built-in Kotlin found).
- 2026-10-09 · The kit lives in its own repository,
  [claude-skill-android-kit](https://github.com/Marcogn/claude-skill-android-kit) (author), not in this
  one: reusable workflows (callers here pin `@v1`, secrets passed by name, optional `BUILD_ENV`), the
  `.claude/` files and templates copied by the `android-kit` skill (a launcher on the author's claude.ai
  account that follows the kit's `INSTALL.md`). Same workflows and secret names in every project.
- 2026-10-09 · GitHub issues only for bugs and ideas, not for the plan (author): the plan stays in the
  docs, the single source Claude reads. `/next-phase` offers open `bug` issues, `/close-phase` closes
  fixed ones with `Fixes #n`. No epics or Projects board for now.
- 2026-10-14 · Kit updated to 5fb47c3 (`.claude/kit-version`): review gate before PRs (`self-review`), branch-name
  hook, `deps-triage`, Dependabot cooldown, Release `bump` choice and cleanup of old runs. Kept locally: the extra
  `Read(./local.properties)` deny in `.claude/settings.json`; `release-build-args` left empty.
- 2026-10-18 · Kit updated to 1721624 (0c21768 + simpler branch-name hook): `issues-triage` skill, branch-name hook rewritten (accepts `ccr-…` and base branches, checks plain one-line commands only),
  Release cleans old runs only after a successful release, SHA-1 printed too. No local changes to reconcile.
- 2026-10-08 · No detekt for now: only 2.0.0 supports Kotlin 2.4 with AGP 9 built-in Kotlin, and it is
  still alpha (stable versions only). Revisit at 2.0.0 stable.
- 2026-10-03 · `CloudTarget` (spec §7.3) is a minimal interface (`displayName`, `suspend upload(...)`):
  the spec names it without a shape and nothing uses it yet.

## Updates and About (plan A, ADR 0006)

- 2026-10-11 · ThePatientGamerHelper's update package is ported (the pilot for all six apps) instead of designing
  one here (author). Updates from this repository's GitHub Releases at startup only, frequency Never / Every
  start / Daily / Weekly (default Weekly), "Not now" only closes, the dialog shows the Release body; install
  through `PackageInstaller` with the system confirmation.
- 2026-10-11 · `INTERNET` comes in with sub-phase A, for the update check only (author), ahead of phase 12:
  amends the product phase 1 rule, the CI `forbidden-permissions` check (now empty) and the README/About
  privacy text (ADR 0006).

- 2026-10-12 · Ported from ThePatientGamerHelper at 95f0b97 (release 1.1.2): `domain/update`, `data/update`,
  `ui/update`, `ui/about` and their tests. The pilot's fixes after its plan apply here: no version code in
  About, credits "Fatto con il ❤️ da Marcogn" / "Made with ❤️ from Marcogn" (author, 2026-10-12).
- 2026-10-12 · Preferences on DataStore in `data/settings/UpdatePreferences.kt` (author: the plan, not the
  pilot's SharedPreferences). `UpdateManager` reads them with `first()` inside its own scope; the store is a
  constructor parameter so tests use their own file.
- 2026-10-12 · One `UpdateDialogHost` above the nav graph, as in the pilot (author). It never navigates, so the
  `lifecycleIsResumed()` guards are untouched.
- 2026-10-12 · **Installing waits for file writes** (author). Installing replaces the app and ends the process, so a
  `SaveWorker` or `OcrWorker` copying over the destination would be cut off. Both jobs carry
  `FILE_WRITE_WORK_TAG`; the scan flow's in-process copy to its `CreateDocument` file goes through
  `UserFileWrites.track`. `FileWriteGate` combines the two; while it is busy `UpdateManager.install()` returns
  `InstallStart.Busy` and the dialog shows "Install after saving", disabled, with the reason. Download and
  verification are not blocked. The ready message asks to save pending edits first (unsaved viewer edits are
  not a write in progress and are lost when the app restarts).
- 2026-10-12 · A committed session waiting for the system confirmation is **abandoned** when a write starts (the
  flow goes back to "Ready to install") or when the user closes the "Installing" dialog: the confirmation runs
  in its own task and could otherwise be accepted later, mid-write. The state is `Installing` *before* the
  commit (found in review): the status broadcast can arrive before `install()` resumes, and was dropped.
  Whether the system installer screen reacts to an abandoned session is a device check.
- 2026-10-12 · About keeps the check result as data (`UpdateCheckResult`); the screen turns it into text with
  `LocalResources`. Below API 33 an application context does not follow the per-app language (the pilot
  formats it in the ViewModel with the application context).
- 2026-10-12 · **ML Kit telemetry off** (author, ADR 0006): the manifest removes datatransport's `cct` backend
  registration, checked by `TelemetryOffTest`. Not a documented switch, so every ML Kit update needs the test
  and a traffic capture on a device.
- 2026-10-12 · The parser fixtures are the pilot's real `releases/latest` responses (v1.0.6), kept as captured;
  a test also parses the body of this repository's `v1.1.1` release.

## Viewer and rendering (ADR 0001)

- 2026-10-01 · `PageCoordinateMapper`, `DocumentLayout` and `Viewport` live in `pdf/render` (spec §12
  suggests `pdf/forms`): they start with the viewer and every later phase extends them.
- 2026-10-01 · Zoom 1 = fit width of the widest page (one scale per document; narrower pages centred).
  Minimum zoom = fit page, maximum 5x. Double tap keeps the tapped point under the finger
  (`ViewportBounds.doubleTapTarget`).
- 2026-10-01 · Render cache: half of `memoryClass`, 32–256 MB, 2/3 pages and 1/3 tiles; a page bitmap
  is capped at 1/4 of the page cache (max 32 MB), above it tiles take over. Tiles of 512 px at zoom
  levels quantised to quarter octaves. Since API 26 bitmap pixels are in the native heap
  ([source](https://developer.android.com/topic/performance/graphics/manage-memory)), so `memoryClass`
  measures the device, it isn't a hard limit.
- 2026-10-01 · One render worker reading a "wanted list" (`RenderScheduler`) instead of a queue: fast
  scrolling never piles up stale renders. `PdfRenderer` serialises pdfium globally anyway.
- 2026-10-01 · Non-seekable sources are copied to `cacheDir/open/` and deleted on close (`PdfRenderer`
  needs a seekable descriptor); only copies older than 1 hour are cleaned on open.
- 2026-10-01 · Reading mode is one global preference; the viewer menu and Settings change the same value.
- 2026-10-01 · Passwords use `PdfRenderer(fd, LoadParams)`, API 35+. Below 35 a protected file fails with
  a message. The password lives only in memory.
- 2026-10-01 · Recents: Room table, max 10, thumbnails as JPEGs in `cacheDir/thumbnails/` named by the
  SHA-256 of the URI; the database is excluded from backup.
- 2026-10-01 · Intents `VIEW` and `SEND` for `application/pdf` open the viewer above Home, only on a fresh
  launch (rotation doesn't reopen it).
- 2026-10-01 · Scrubber: only the thumb takes touches, linear page mapping. Drawer swipe disabled in the viewer.
- 2026-10-04 · The app draws every annotation itself; the platform renderer draws none (ADR 0004). On API
  35+ it does draw form widgets, with the value saved in the file.

## Editing and saving (ADR 0002, ADR 0003)

- 2026-10-01 · Saving runs in WorkManager (expedited, `dataSync` foreground fallback); the request
  travels as a JSON file; the worker reaches `PdfSaver` through a Hilt `EntryPoint` (ADR 0003).
- 2026-10-01 · The picker asks for write and persistable grants (`OpenPdfContract`), which is what makes
  "overwrite" possible; overwrite is offered only if `checkUriPermission(WRITE)` passes. Files from
  `VIEW`/`SEND` are usually copy-only.
- 2026-10-01 · `cacheDir/work/` is cleaned at startup of files older than 1 hour (spec says "emptied"):
  a save re-run by WorkManager still needs its request file.
- 2026-10-01 · Encrypted PDFs are not edited (PdfBox gets no password; spec §14).
- 2026-10-01 · Drag and drop with Reorderable 3.1.0 (Apache 2.0). One drag = one undo step.
- 2026-10-03 · Every document of a save is loaded with `MemoryUsageSetting.setupTempFileOnly()`: with 25
  PDFs (938 MB, 2000 pages) the Java heap held went from 516 MB to 121 MB at the same speed, and a 320 MB
  heap no longer fails. Single-document readers keep `setupMixed`.
- 2026-10-07 · The save dialog with an "Overwrite" button is the explicit confirmation (spec §6.7); no
  second dialog (author, U6).
- 2026-10-07 · A save started from the exit dialog of the edit screen closes it with a Toast, not the
  Open/Share snackbar (it would die with the screen).

## Pages: organize, add, merge

- 2026-10-07 · One "Organize pages" tool replaces Remove, Reorder, Add pages and Insert images (author,
  U4; spec §4.1 deviation). The edit screen opens on it; it has no hub since V-b.
- 2026-10-02 · No "Merge" on an open document: there it is "Add → from another PDF" (author). Merge is
  its own list screen from Home (`Destination.Merge`), which opens `Edit` with `mergeWith`.
- 2026-10-01 · Picked images are copied to `cacheDir/images/` (Photo Picker grants are temporary) and
  cleaned after 24 h at startup, not when the screen closes: a background save may still need them.
- 2026-10-01 · A photo's DPI is used for "original size" only if ≥ 100, else 150 DPI (cameras write 72).
  Images are decoded at most 8000 px on the long side. Pages added at the start take the next page's
  size, others the previous one's (visible size).
- 2026-10-01 · `ImageDecoder` applies EXIF orientation (API 28+); below, the orientation is applied by
  hand with Glide's `TransformationUtils` matrices. `androidx.exifinterface` for DPI and orientation.
- 2026-10-01 · Protected PDFs are rejected when added or merged (a password per file would have to be
  kept until the save). To revisit with the author.

## Scan (plan 9, spec §7.1)

- 2026-10-09 · The scanner is `play-services-mlkit-document-scanner` 16.0.0 (the UI and models run in
  Google Play services). Home "Scan" asks for a **PDF** and then `CreateDocument` ("Scansione
  yyyy-MM-dd HHmm.pdf"); cancelling the picker offers Save or Discard rather than losing the scan. "Add
  → From scanner" in "Organize pages" asks for **JPEG** pages and reuses the images dialog.
- 2026-10-09 · Availability: Play services is checked up front (`GoogleApiAvailability`) and dims the
  tool with an explanation; low RAM is not guessed from `totalMem` (marketing GB differ from what the
  system reports) but reported when the scanner itself fails with `MlKitException.UNSUPPORTED`.
- 2026-10-09 · `ACCESS_NETWORK_STATE` is **kept** (author). The 2026-10-04 plan assumed the scanner brought
  it; the manifest merger report shows `work-runtime` 2.12.0 (in use since 1.0) and `transport-runtime`
  declare it too, and removing it could break WorkManager's network trackers. It gives no network access.
  (`INTERNET` was removed and forbidden in CI until A, ADR 0006.)
- 2026-10-09 · The scanned PDF is first copied to `cacheDir/work/` by `ScanViewModel`, then written to the
  `CreateDocument` file there (in `viewModelScope`, a failed write deletes the empty file). This is a plain
  copy of a finished file, not an edit, so it does not go through `SaveWorker` (ADR 0003); a rotation does
  not cut it short.

- 2026-10-09 · `proguard-rules.pro` keeps the no-argument constructor of every Firebase
  `ComponentRegistrar`. R8 full mode removed `CommonComponentRegistrar.<init>()` (firebase-components'
  rule names no members), so ML Kit's components were never registered and the release build crashed on
  "Scan" with a `NullPointerException` in `getClient()`; debug builds are not minified and were fine.
- 2026-10-09 · A scanner that can't start shows a dialog with the reason and one selectable line naming
  the error (ML Kit code and message, or exception type, message and cause; author). The full stack trace
  goes to logcat (tag `PdfToolkitScan`); R8 traces are read with `retrace` and the build's `mapping.txt`.

## OCR (plan 10a, spec §7.2)

- 2026-10-09 · Text recognition is ML Kit Text Recognition v2 **bundled**, `com.google.mlkit:text-recognition`
  16.0.1, Latin script (author): the model is in the APK (Google gives about 4 MB per script per architecture),
  available at once, offline and without Play services. The unbundled 19.0.1 would have been ~260 KB but
  waits for Play services to download the model.
- 2026-10-09 · The layer is text render mode 3 (invisible) in Noto Sans (subset, with PdfBox's `/ToUnicode`),
  appended to the page content in user space through `PdfPageSpace`. Per ML Kit **line**: one font size (the
  font's ascent-to-descent made as tall as the line box, the same extent the extractor gives a glyph) and one
  angle (the box's bottom edge). Per ML Kit **word** (element): its own start along the baseline and its own
  `Tz` stretch to its box, with a space stretched over the gap to the next word. The plan said one stretch per
  line; with Noto Sans not being the scanned font, that drifts words off their image and can shrink the gaps
  under the extractor's word-break threshold (0.15 line heights), joining words.
- 2026-10-09 · Pages are rendered with `PdfRenderer` at 200 dpi (body text gets the 16–24 px per character
  ML Kit asks for), at most 3000 px on the long side; one bitmap at a time. A page with any non-blank
  character (visible or not, e.g. an earlier OCR) is not recognised again.
- 2026-10-11 · Recognition runs as `OcrWorker` (WorkManager, expedited, `dataSync` foreground like the save, plan
  10b, ADR 0003): the request is a JSON file, the source is copied to `cacheDir/work/`, `OcrProcessor` writes the
  result there, and only a finished file is copied over the destination ("wt"). The destination is untouched
  unless text was written. Cancel stops between pages (ML Kit finishes the page in hand); the last step, the copy
  over the destination, always completes, so the UI disables Cancel from 96% and the job checks for a cancel just
  before it starts.
- 2026-10-11 · Viewer: the "no searchable text" notice gets a "Recognise text" button (hidden for password-protected
  files and while there are unsaved edits, since it works on the saved file). Copy or overwrite is asked with the
  save dialog; a copy picked with `CreateDocument` is deleted again if nothing was written (nothing to
  recognise, failure, cancel). Overwrite reopens the viewer on the result, as a save does.
- 2026-10-11 · Scan: after the scan the app asks "Make the scan searchable?" every time (no stored preference:
  one more setting for something that costs one tap). The recognition output goes to a second staged file; if
  there is none (cancel, nothing found, failure) the raw scan is kept and the user is told, except on cancel.
- 2026-10-11 · `PageText.failed` marks a page whose text PdfBox could not read; `OcrProcessor.needsRecognition`
  skips such pages, since empty glyphs there mean "unknown", not "scan" (issue #31). Search is unaffected.

## Fill and sign (spec §6.5)

- 2026-10-02 · Overlays and form values live in `EditSession` (one undo history), not as a separate
  argument of `applySession`; `WriteOptions(flattenForm)` carries the save-time choice.
- 2026-10-02 · Overlays are stored in PDF user space with an angle: independent of zoom and of later
  rotations, and the writer needs no conversion. Visible box = `/CropBox` ∩ `/MediaBox`, `/Rotate` in
  quarter turns, as pdfium ([source](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/core/fpdfapi/page/cpdf_page.cpp)).
- 2026-10-02 · Noto Sans Regular 2.015 (OFL 1.1) in `assets/`, read by PdfBox and Android. Subset for
  overlays, full when a form field needs it. Characters it lacks are dropped (screen and PDF alike).
  Ticks and crosses are stroked paths (Noto Sans has no ✓/✗).
- 2026-10-02 · "Make final" defaults to on when a signature was placed (spec §6.5). Dynamic XFA: a message,
  free filling only; static XFA: the AcroForm is filled and `/XFA` removed so readers use the new values.
- 2026-10-02 · Signature drawing canvas is our own Compose code (variable width from finger speed),
  rendered to a transparent PNG cropped to the ink (max 1600 px). No new dependency.
- 2026-10-02 · Signatures are created from a dialog flow (not nav destinations), saved at once with a
  default name and renamed from the archive. The legal note shows once (DataStore flag) and is in About.
- 2026-10-02 · "Remove background" is a luminance threshold with a soft ramp (default 0.75), then a
  trim to the ink.
- 2026-10-02 · Gestures on overlays: a touch on the selected one (20 dp margin) moves/pinches it; a long
  press on any other selects and drags it (author). Proportions locked; text scales by font size.
- 2026-10-07 · With one saved signature "Signature" arms it directly, with none it opens the creation,
  with several the picker; a long press always opens the picker (author, U8). "Date" places today's
  date without a dialog (U12). Corner handle for one-finger resize and turn (U11).
- 2026-10-08 · V-c: fill and sign happens in the viewer (ADR 0005, "V-c"); the Fill pane is removed.
  The form is read the first time Fill is armed; fields with a pending value stay drawn (as pictures)
  after the tool is put down, because the renderer draws the file's value under them.

## Search and text (spec §5.1, §12)

- 2026-10-03 · Folding: NFKD + drop `Mn` and format characters + case folding + typographic quotes and
  dashes to ASCII + white space runs to one space, the same for index and query. NFKD also splits
  ligatures; "l'anno" typed on a phone finds "l’anno".
- 2026-10-03 · Word breaks from glyph geometry (same line, gap > 0.15 line heights), not from PdfBox's
  separators, which split words on pages turned by 90°/270°. Positions from `TextPosition.textMatrix`
  plus the crop box corner, heights from the font descriptor (PdfBox-Android reports the substitute
  font's matrix). Index kept compact in memory, no disk cache.
- 2026-10-03 · Indexing runs once to the end and stays while the document is open; a new query replaces
  the old. The first result is the first in the document; results only append while a query stays.
  Revealing centres the result, zoom kept. The search bar replaces the top bar.

## Annotations and drawing (ADR 0004, spec §7.4)

- 2026-10-04 · Our own appearance streams from `AnnotationGeometry` (axis-aligned quads), not PdfBox's
  handlers. Existing annotations are referenced by `/Annots` index + fingerprint (subtype and `/Rect` to
  0.1 pt); a mismatch skips the removal. Removing one also removes its pop-up and replies (`/IRT`).
- 2026-10-04 · Underline 1/14 of the line height (min 0.5 pt), strikeout through the middle, squiggly
  half-waves a quarter line high. Highlight blends with Multiply; on screen only from API 29.
- 2026-10-05 · Highlight colours are light (they multiply): yellow, green, light blue, pink, orange;
  underline and strikeout share one strong colour (red, blue, black, dark green).
- 2026-10-05 · The eraser also takes annotations the app can't draw (notes, stamps) by their rectangle;
  undo brings them back.
- 2026-10-05 · Freehand on `androidx.ink`, outline appearance, one Ink annotation per stroke,
  compact polyline encoding in saved state, "make final" as a save-dialog checkbox, off by default.
- 2026-10-13 · The app draws the stroke being drawn itself (`WetStroke`: `androidx.ink`'s `InProgressStroke`
  with the stroke's own brush, drawn by `CanvasStrokeRenderer` in the viewport's Canvas), not `InProgressStrokes`
  (author). Its front-buffered layer (a `SurfaceView` above the window on API 33+) showed nothing until the pen
  lifted on a Galaxy Tab S7+ (a phone was fine), and 1.1.0-alpha09, with its maskPath fix, didn't change that;
  `ink-authoring` has no switch to turn the layer off (1.0.0's `useHighLatencyRenderHelper` is gone in 1.1.0).
  Cost: about a frame of latency, no prediction. Back on the stable `androidx.ink` 1.0.0, without `ink-authoring`.
- 2026-10-13 · With the eraser armed a tap acts at once: double tap doesn't zoom then, so the tap doesn't wait
  out the double-tap timeout (author).
- 2026-10-05 · Draw vs zoom: one finger or a stylus draws, a second finger cancels the stroke and
  zooms/pans; the stylus ignores other touches while drawing; pages don't turn while a brush is armed.
  Strokes entirely off the page are dropped.
- 2026-10-07 · The stylus draws only with Pen/Marker armed; with the markup tools it selects like a
  finger (author).
- 2026-10-07 · Pen colours black, red, blue, green, orange; marker colours = highlight palette. Sizes
  1/2/4/6 pt (pen), 8/12/18/26 pt (marker). Kept across restarts since T-b (UI and usability, T-b).
- 2026-10-13 · Partial eraser (plan T-a, `docs/plan-tools.md`, author): removes only what the finger passes over,
  on pen/marker strokes (also other apps' Ink) and on text markup; the cut pieces become new annotations, one
  undo step per gesture. Geometry in `pdf/annotations`, without Android's `Path.op`. Sizes = the pen's, default
  2 pt. Cutting another app's annotation is allowed, with a one-time notice.
- 2026-10-13 · T-a: polygon clipping with Clipper2-java 2.0.1b from JitPack, the repository limited to its group
  (author, ADR 0004 "Partial eraser"); integer coordinates in hundredths of a point.
- 2026-10-13 · T-a: Ink with outlines or of one stroke becomes one annotation per piece; several strokes and text
  markup stay one annotation. The centre line of outlined ink is cut where the eraser crosses it and shortened by
  half the width; ink without outlines is cut by the swept area grown by half the width. An annotation is "the app's"
  when its `/NM` is an app id ("a" + 8 hex digits); others get the one-time notice (once per viewer session).

## Editing in the viewer (ADR 0005)

- 2026-10-08 · Page tools (highlight, draw, erase, fill and sign) work in the viewer; "Pages" (organize,
  add, merge) stays in the edit screen and asks to save or discard first; there is never a shared
  unsaved session (author, option "C").
- 2026-10-08 · The viewer's session never changes the page list and is drawn over the saved file.
  Freehand strokes are in document points; the page is the one under the first point. Editing waits for
  the annotations and is off for password PDFs. After a copy the viewer stays on the original (session
  marked saved), after an overwrite it reopens.
- 2026-10-08 · Single-page paging is off while a brush is armed; opening search puts the tool down.
- 2026-10-08 · The tools bar is always there (a rail overlaying the page in landscape); a tap on the page
  hides it with the top bar. After a save-first, the viewer is replaced by one on the saved file with the
  edit screen above it.
- 2026-10-08 · With a markup tool armed the mark is made when the finger lifts, with an "Undo" snackbar
  (author, replacing 7b's "arm, select, apply"); the floating Copy/Highlight bar is for reading only.
- 2026-10-08 · V-c: "Fill" arms fill mode and the strip switches to the fill tools (Text, Date, Tick,
  Cross, Signature, or Edit/Delete/Done for a selected item), as a contextual bar. Home "Fill and sign"
  opens the viewer armed.

## UI and usability

- 2026-10-01 · "Coming up" tools on Home with a "Soon" badge; a tap shows a snackbar.
- 2026-10-03 · No in-app "reduce motion" switch: the system setting is followed (spec §9).
- 2026-10-07 · Hints are transient messages over the page (4.5 s), not rows; in landscape the tool strip is a
  rail; touch targets at least 48 dp (U7, U18, U19). Colour and size are in each tool's bubble (T-b).
- 2026-10-07 · A tap on the page toggles full screen, unless it clears a selection (U20). The bars float
  over the page, which never resizes (it flickered when it did).
- 2026-10-01 · Icon in `mipmap-anydpi-v26/`: lint reports `ObsoleteSdkInt`, but aapt2 didn't find the
  icon in `mipmap-anydpi/`.
- 2026-10-01 · Robolectric Compose tests set the Italian locale explicitly (`@Config(qualifiers = "it-...")`).
- 2026-10-13 · Tool drawer (plan T-b, author): the viewer's bar has fixed places (Tools drawer, two quick slots
  the user pins, Undo, Redo, Pages) so nothing moves when a tool is armed; the freehand highlighter (Marker)
  is a tool of its own. Touches: drawer button tap opens a fan-shaped drawer, long press nothing; in the drawer
  tap = use, hold still = colour/size bubble, hold and move = drag to a quick slot; quick slot tap = use, tap on
  the armed one = bubble; Back or "Leggi" puts the tool down; a tutorial on the first drawer opening. No
  default quick tools: the drawer hints at dragging a tool onto the empty (dashed) slots, until one is filled.
  Colours and sizes are kept across restarts.
- 2026-10-13 · T-b: the pieces of the drawer as built. `DrawerTool` has eight tools (the two erasers are two tools
  over one `AnnotateTool.ERASER` plus the `partialEraser` mode); `QuickSlots` holds the pins. "Hold still" opens the
  bubble when the finger is **lifted** after the long-press time without moving; moving after the hold is the drag
  (moving before it is neither, so the bar still scrolls). Dropping a quick tool on the other slot **swaps** the two
  (plan said "replaces"; a swap loses nothing); a drop on the bar outside the slots leaves it. A tap on an empty
  slot opens the drawer. The long press on the Tools button is swallowed. The fan is arcs around a point just
  above the bar (beside the rail in landscape): items 64 dp, 72 dp apart along an arc, as many rings as needed
  (`FanLayout`, pure); with the system's animations removed it only fades. The bubble is a `DropdownMenu` with
  the tool's name, its colours and (pen, marker, partial eraser) sizes. The drawer and its scrim sit **under** the
  bar. Kept in `ViewerToolPreferences` (DataStore `viewer_tool_prefs`): slots, "a tool was pinned once" (the hint
  never returns), tutorial seen (Settings and About clear it), colours, sizes, eraser mode and size, last tool of
  the text and drawing groups; applied once to `AnnotatePaneState` when read, without arming anything.

