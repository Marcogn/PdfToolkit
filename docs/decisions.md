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
  the app and wasn't minified. "Build APK" (signed release, R8) is the APK for device checks. The four
  workflows are generic, with the per-project values in their `env` block (`docs/ci.md`).
- 2026-10-04 · Product phase 2 is numbered as development phases 7–12, so "phase 2" keeps meaning the
  edit session. Order (author): highlight and draw, then scan, then OCR, ODF, cloud last (it brings
  `INTERNET`). Usability (U-a, U-b) and viewer editing (V-a, V-b, V-c) were inserted before 9.
- 2026-10-03 · `CloudTarget` (spec §7.3) is a minimal interface (`displayName`, `suspend upload(...)`):
  the spec names it without a shape and nothing uses it yet.

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
- 2026-10-05 · Freehand on `androidx.ink` 1.0.0, outline appearance, one Ink annotation per stroke,
  compact polyline encoding in saved state, "make final" as a save-dialog checkbox, off by default.
- 2026-10-05 · Draw vs zoom: one finger or a stylus draws, a second finger cancels the stroke and
  zooms/pans; the stylus ignores other touches while drawing; pages don't turn while a brush is armed.
  Strokes entirely off the page are dropped.
- 2026-10-07 · The stylus draws only with Pen/Marker armed; with the markup tools it selects like a
  finger (author).
- 2026-10-07 · Pen colours black, red, blue, green, orange; marker colours = highlight palette. Sizes
  1/2/4/6 pt (pen), 8/12/18/26 pt (marker). Not kept across app restarts.

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
- 2026-10-07 · Hints are transient messages over the page (4.5 s), not rows; style (colour, size) is one
  menu button; in landscape the tool strip is a rail; touch targets at least 48 dp (U7, U18, U19).
- 2026-10-07 · A tap on the page toggles full screen, unless it clears a selection (U20). The bars float
  over the page, which never resizes (it flickered when it did).
- 2026-10-01 · Icon in `mipmap-anydpi-v26/`: lint reports `ObsoleteSdkInt`, but aapt2 didn't find the
  icon in `mipmap-anydpi/`.
- 2026-10-01 · Robolectric Compose tests set the Italian locale explicitly (`@Config(qualifiers = "it-...")`).
