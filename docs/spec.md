# PdfToolkit — Functional and technical specification

> Reference document for the implementation with Claude Code.
> Version 1.1 — 1 October 2026 (added: text search, PDF merge; highlighting planned in Phase 2). Translated from Italian on 2026-10-18 (issue #32); content and section numbers unchanged.
> Names: **PdfToolkit** (display name), **pdf-toolkit** (repo, slug, artifact), **PT** (short prefix for classes/tags where needed).

---

## 0. How to use this document (for the agent)

1. Read the whole document before writing code.
2. Read the two reference projects (§2). If you can't find them in the workspace, **ask for the path or the repo link**. Don't make up their structure.
3. Work in phases (§13). A phase is closed only when its acceptance criteria hold, the debug build passes and README, CLAUDE.md and CHANGELOG are up to date.
4. Where this specification conflicts with the reference projects on stack, Gradle structure, signing, CI and conventions, **the reference projects win**. Where the specification explicitly asks for something different, the specification wins.
5. If a requirement is unclear or not feasible as written, stop and ask. Don't resolve ambiguities silently.
6. Sections marked **[ASSUNZIONE]** (assumption) are default choices the user may change. Implement them as written, but keep them isolated and easy to change.

**Recommended models:** Opus for Phase 0 (analysis of the reference projects and plan), Sonnet for the implementation phases. If Sonnet gets stuck on an architecture problem, go back to Opus for that decision only.

---

## 1. What PdfToolkit is

Android app to read and edit PDF files locally, on the phone, with no account and no cloud in the first phase.

Main blocks:

- **Reading** (the most important feature): zoom, continuous or single-page scrolling, fast navigation between pages.
- **Text search** in the open document.
- **Editing**: merging several PDFs, adding pages (from another PDF, blank pages, images), removing, reordering, filling and signing.

Phase 2 (planned, not to be implemented now): document scanning with OCR, cloud upload, highlighting, freehand drawing, export to OpenDocument format.

**Guiding principle:** everything runs on the device. In Phase 1 the app **did not declare the `INTERNET` permission**. Since sub-phase A (updates, ADR 0006) it declares it for the update check on GitHub only; library telemetry is turned off.

---

## 2. Reference projects

| Project | What to take from it |
|---|---|
| **ThePatientGamerHelper** | Stack (Kotlin, Jetpack Compose, Room, Hilt, MVVM with StateFlow), Gradle structure and version catalog, light/dark/system theme, per-app IT/EN language, fixed keystore, debug/release variants |
| **KartLog** (MKW tracker) | Home structure with big square buttons also reachable from a hamburger menu on the left, navigation pattern |
| **Both** | CI/CD workflows (GitHub Actions or whatever the repos use), build and release scripts, handling of README and CHANGELOG |

The two apps look different but have a similar structure. **Keep the structure** (modules, packages, layering, naming, pipeline), and give PdfToolkit a look of its own (§9).

Concrete actions:
- Copy the CI workflows from the reference repos, adapting names, applicationId and artifacts.
- Reuse the same signing setup (fixed keystore, same SHA1 across builds). Credentials stay out of the repo, as in the reference projects.
- `applicationId`: the same prefix as the reference projects followed by `.pdftoolkit`. If the prefix can't be derived, ask.

---

## 3. Main technical decisions

### 3.1 Rendering engine: `android.graphics.pdf.PdfRenderer` with a custom Compose viewer

We write the viewer ourselves in Compose on top of `PdfRenderer` (platform API).

Why not the Jetpack `androidx.pdf` library:
- It is in **beta** (1.0.0-beta01, August 2026) and the Compose APIs (`PdfViewer`, `PdfViewerState`) and `EditablePdfViewerFragment` are still marked `@ExperimentalPdfApi`.
- It has no paged single-page mode, which is a requirement.
- We need fine control over animations and transitions for the "modern and snappy" goal.

Record this choice in `docs/adr/0001-viewer.md`. Reconsider it when `androidx.pdf` reaches stable: it could replace the viewer and give text search and selection for free.

`PdfRenderer` constraints to respect:
- **Only one page open at a time** per instance. Serialise access (one `Mutex` per document) and always close the page after rendering.
- Rendering happens on `Dispatchers.Default`, never on the main thread.
- On devices without the Android 15 APIs (or the `PdfRendererPreV` backport through the SDK extension), the system renderer **draws neither annotations nor form field values**. In practice: everything the user adds (signature, text, stamps) is written **into the page content**, not as an annotation. See §6.5.

### 3.2 Editing engine: PdfBox-Android

All writes go through **PdfBox-Android** (`com.tom-roush:pdfbox-android`, a port of Apache PDFBox 2.0.x, Apache 2.0 licence).

- Latest release: 2.0.27.0. The project has seen little activity for some years. It is fine for what we need (importing/removing/reordering pages, images, AcroForm, writing into the content stream), but it goes behind an interface (`PdfEditor`) so it can be replaced.
- Call `PDFBoxResourceLoader.init(context)` at startup.
- Check the R8/ProGuard rules in the library's README: the release build must work (test it).
- **Excluded**: iText and MuPDF (AGPL licence, incompatible with how we want to use them).

### 3.3 Stack

As in ThePatientGamerHelper. Unless the reference project says otherwise:

- Kotlin, Jetpack Compose, Material 3, Navigation Compose
- Hilt, Room, DataStore (preferences), Coroutines/Flow, MVVM with `StateFlow`
- `minSdk`: as in the reference project, but not below 26 **[ASSUNZIONE]**
- `targetSdk` / `compileSdk`: the latest stable one supported by the AGP in use
- Tests: JUnit for the editing logic (edit session, page size computation, coordinate mapping), Compose UI tests only for the main flows

### 3.4 File access

- Opening through the Storage Access Framework (`ActivityResultContracts.OpenDocument` with `application/pdf`). No storage permission.

  > **Note (2026-10-18):** all files access is asked for, optionally, when a PDF arrives from another app with a temporary grant, so recents can reopen it by its path (ADR 0007, issue #47). Opening through the picker still needs no permission.
- Call `takePersistableUriPermission` so recent files can be reopened.
- The app registers as a PDF viewer: an `intent-filter` for `ACTION_VIEW` with `mimeType="application/pdf"` (schemes `content` and `file`), and `ACTION_SEND` to receive a shared PDF.
- Images: Photo Picker (`PickVisualMedia`) plus SAF for files not in the gallery.
- Password-protected PDFs: ask for the password and open them if the available APIs allow it. If that isn't possible on the device, a clear message, no crash.

---

## 4. Navigation and screen structure

```
                ┌────────────────────┐
  start with  ─►│   Home (Tools)     │◄── hamburger from every screen
  no document   └─────────┬──────────┘
                          │ "Open PDF" / tap on a tool
                          ▼
  start with  ─►┌────────────────────┐   "Edit" FAB       ┌──────────────────┐
  PDF intent    │       Viewer       │ ─────────────────► │     Edit hub     │
                └────────────────────┘                    └────────┬─────────┘
                                                                   ▼
                          Pages (add / remove / reorder) · Images · Fill and sign
                                                                   │
                                                                   ▼
                                                        Save (copy / overwrite)
```

Drawer (hamburger on the left), always reachable: Home, Recent files, My signatures, Settings, About.

### 4.1 Home (start with no document)

Structure as in KartLog: a grid of big square buttons.

- At the top, a prominent **"Open PDF"** card.
- Below, a **"Recents"** section (horizontal, at most 10, with first-page thumbnail, name, date). Swipe or long press to remove from the list. A file that is no longer accessible is shown as such and can be removed.
- Tool grid:
  - Merge PDFs
  - Add pages
  - Insert images
  - Remove pages
  - Reorder pages
  - Fill and sign
  - My signatures
- Phase 2 tools are already shown, **disabled** with a "Soon" label: Scan, Upload to cloud, Highlight, Draw, Export to ODF. Tap → short snackbar, no navigation.

> **Note (2026-10-13):** Scan, Highlight and Draw are active (sub-phases 7a–9); Upload to cloud and Export to ODF are still "Soon". Remove, Reorder, Add pages and Insert images are a single tool, "Organize pages" (U4).

Tap on a tool with no document open: the PDF picker opens and, once the file is chosen, the app goes **straight to the tool**, not to the viewer.

### 4.2 Viewer

> **Note (2026-10-08, updated 2026-10-13):** highlighting, drawing, erasing and filling and signing happen in the viewer, on the page being read, with their own save (ADR 0005, `docs/plan-viewer-editing.md`). Since T-b the bar has fixed places: Tools (fan drawer with every tool), two quick slots, Undo, Redo, Pages (`docs/plan-tools.md`). "Pages" opens the edit screen (organize, add, merge).

- Top bar: file name (ellipsis in the middle if long), "page X of N" indicator, **Search** icon (§5.1), menu with: scroll mode, go to page, share, document info.
- **Continuous mode** (default): vertical scrolling through all pages.
- **Single-page mode**: one page per screen, horizontal swipe, snapping to the page.
- The chosen mode is remembered (global preference) **[ASSUNZIONE]**.
- **Zoom**: pinch, double tap (toggles fit-width ↔ 2.5x centred on the tapped point), minimum zoom = fit page, maximum 5x. Pan when zoomed.
- **Scrubber**: draggable side bar to jump quickly between pages, with a page-number bubble.
- A thumbnail bar that opens from the bottom to jump to a page.
- The reading page is saved per file and restored on reopening.
- ~~**"Edit" FAB**~~ (removed in V-b, 2026-10-08): in its place the bottom tool bar (at the side in landscape), which a tap on the page hides together with the top bar.

### 4.3 Edit hub

> **Note (2026-10-08):** the hub was removed (V-b) together with the "Edit" FAB. The page tools are in the viewer's bar (§4.2); the edit screen is only "Organize pages".

The same tools as Home, bound to the open document. Presentation: a grid screen with a transition from the FAB (container transform or equivalent). At the top, the document name and page count.

---

## 5. Reading: rendering details

The goal is smooth reading even on PDFs with hundreds of pages.

- **Two-level rendering**: first a bitmap at fit-width resolution, then, when the zoom stops (debounce ~150 ms), a high-resolution re-render **of the visible part only** (`PdfRenderer.Page.render` with a transformation matrix and clip). While zooming, the existing bitmap is scaled.
- An LRU bitmap **cache** sized on the available memory (`ActivityManager.memoryClass`), with prefetch of ±2 pages around the visible one.
- Maximum bitmap size capped (avoid OOM): if a requested render exceeds the limit, it goes to tiles.
- In the continuous list, placeholders already have the page's correct proportions (read at opening) so scrolling doesn't jump.
- Background colour behind the pages differs between light and dark theme. Pages stay white: **no** colour inversion in Phase 1.
- Opening error (corrupt file, unsupported format): an error screen with an understandable message and a button back to Home.

Performance criterion: opening a 200-page PDF with the first page visible in under 1 second on a mid-range device; scrolling without visible stutter.

### 5.1 Text search

**Text extraction**
- One implementation with PdfBox: a `PDFTextStripper` subclass that collects, for each page, the text and the position of the characters (`TextPosition`). This way it works on every supported Android version.
- The platform text APIs (`PdfRenderer.Page` on API 35, `PdfRendererPreV` on earlier versions with the SDK extension) may serve as a later optimisation. Not in Phase 1: two code paths to keep aligned aren't worth the gain.
- The index is built in the background, page by page, the first time the user opens search. Not when the document opens, so reading isn't slowed down.
- The index stays in memory while the document is open. A disk cache (key: URI + size + modification date) is optional: add it only if measurements on large PDFs justify it.

**Interface**
- Tap on **Search**: the top bar becomes a search field with a "3 of 17" counter and previous/next arrows.
- Results arrive page by page as indexing proceeds. No blocking wait; a discreet indicator is shown while indexing.
- Input debounce of about 250 ms. A new query cancels the running one.
- Every match is highlighted on the page with a semi-transparent rectangle drawn as an overlay (not written into the PDF). The current match has a stronger colour. Moving to the next one, the viewer scrolls to its page and centres the result.
- Search ignores case and accents: "perche" finds "perché". Normalise to NFD and strip diacritics from both the index and the query, keeping the mapping to the original positions.
- A line break in the extracted text counts as a space, so a sentence split over two lines is found.
- Closing search (back or X) removes the highlights.

**Limits to handle**
- Scanned PDFs (images only): no text to search. If the index is empty, an explicit message: "This document contains no searchable text. It may be a scan." Phase 2's OCR will solve this case. *(Done in 10b: the message offers "Recognise text".)*
- Rotated pages and transformed text: check `TextPosition`'s coordinate system and cover the conversion to the screen through `PageCoordinateMapper` with tests, including pages rotated by 90° and 180°.
- PdfBox's extraction order may not match the reading order (columns, tables). That's acceptable: list it in the README's known limits.
- Search is available in the viewer only. Not in the edit screens.

---

## 6. Editing

### 6.1 Edit session (common model)

All page edits work on an in-memory **session**, not on the file:

```kotlin
data class EditSession(
    val sourceUri: Uri,
    val pages: List<PageItem>,      // final order
    val history: UndoStack,         // undo / redo
)

sealed interface PageItem {
    val id: String                  // stable, for animations and drag
    data class FromPdf(val docRef: DocRef, val pageIndex: Int, val rotation: Int = 0) : PageItem
    data class Blank(val widthPt: Float, val heightPt: Float) : PageItem
    data class FromImage(val imageUri: Uri, val mode: ImageFit, val widthPt: Float, val heightPt: Float) : PageItem
}
```

- `DocRef` can point to the main document or to an added PDF.
- Undo/redo for every operation.
- Fill and signature overlays (§6.5) are bound to `PageItem.id`, so they survive reordering.
- Leaving with unsaved changes asks for confirmation (Save / Discard / Cancel).
- The session survives rotation and process death as far as reasonable (`SavedStateHandle` for light state; heavy data may be lost, with a warning).

### 6.2 Adding pages

> **Note (U-b, 2026-10-08):** in the app this is part of the single "Organize pages" tool (`docs/plan-usability.md`, U4).

Insertion point chosen by the user: before / after the selected page, at the start, at the end.

**From another PDF**
- Pick the file, then a thumbnail grid with multiple selection (all / none / range).
- Pages keep their original size.

**Blank pages**
- Number of pages to insert (1–50).
- Size: that of the **page next to** the insertion point (the previous one; when inserting at the start, the next one) **[ASSUNZIONE]**. If the document has pages of different sizes, the dialog shows the size that will be used.

**From images**
- Multiple selection from the Photo Picker or from files. Each image becomes a page.
- Two modes, chosen in the dialog and applied to all the selected images:
  - **Fit to page**: the page has the size of the reference page (same rule as blank pages); the image is scaled keeping its proportions and centred, without cropping. The page's orientation follows the image's (a landscape photo on an A4 page becomes landscape A4) **[ASSUNZIONE]**.
  - **Original size**: the page takes the image's dimensions. Pixel → point conversion using the DPI in the metadata if present, otherwise **150 DPI** **[ASSUNZIONE]** (at 72 DPI a 4000 px photo would give a page over 1.4 m wide).
- Respect the EXIF orientation.
- HEIC/HEIF/AVIF: PdfBox-Android doesn't handle them directly. Always decode with Android's `ImageDecoder` and pass the bitmap to PdfBox.
- Compression: JPEG quality 85 for photos, lossless for images with transparency. Long side at most 3000 px in "fit" mode to keep the PDF light **[ASSUNZIONE]**.

### 6.3 Removing pages

> **Note (U-b, 2026-10-08):** in the app this is part of the single "Organize pages" tool (`docs/plan-usability.md`, U4).

- Thumbnail grid, multiple selection by tap, "select all", range selection (long press on a page, then tap on another).
- The last remaining page can't be removed.
- Exit animation for removed thumbnails; undoable from the snackbar and with undo.

### 6.4 Reordering pages

> **Note (U-b, 2026-10-08):** in the app this is part of the single "Organize pages" tool (`docs/plan-usability.md`, U4).

- Thumbnail grid with drag & drop (long press to pick up the page, auto-scroll at the edges).
- For Compose, evaluate the **Reorderable** library (Calvin Liang); check its licence and compatibility with the Compose version in use before adopting it. Otherwise, an own implementation.
- Quick actions: move to start, move to end, rotate 90°.
- Haptic feedback on pick-up and drop.

### 6.5 Fill and sign

**Form with fields (AcroForm)**
- When the tool opens, PdfBox reads the form fields. If there are any, fill mode starts: Compose controls aligned to the fields' rectangles appear over the rendered page (text, checkbox, radio, drop-down).
- Moving between fields with the keyboard's "Next/Previous".
- XFA forms: not supported. An explicit message; free filling can still be used.

**Free filling (PDFs without fields)**
Many forms are "flat" PDFs. Tools available:
- **Text**: a box that can be placed, dragged, resized; adjustable font size.
- **Tick** (✓) and **cross** (✗).
- **Date** (in the app language's format, editable).
- **Signature** (see below).

**Font**: include a freely licensed TTF font (e.g. Noto Sans, OFL) and embed it with `PDType0Font`, so accented letters and special characters always come out right.

**Signature**
- Sources: drawn with the finger, or loaded from an image.
- **Drawing**: full-screen landscape canvas, a stroke whose width varies slightly with speed, black and blue colours, Clear / Undo stroke / Save buttons. The stroke may use `androidx.ink` or an own Compose implementation.
- **From an image**: after picking it, crop and a "remove background" option (threshold on white → transparent) with a preview.
- **Signature store ("My signatures")**: signatures are PNGs with transparency saved in the app's internal storage (`filesDir/signatures/`), not accessible to other apps. Metadata in Room: name, type, date, default. Signatures are **excluded from Android's automatic backup** (`dataExtractionRules` / `fullBackupContent`). They can be renamed, deleted, set as default.
- **Placement**: pick a saved signature (or create one on the fly), it appears on the page, and it can be moved, resized (proportions locked) and rotated with gestures.

**Saving fill and signature**
- Text, ticks, dates and signatures are written **into the page content** (`PDPageContentStream` in append mode), not as annotations. This way they show in any reader, including our renderer on older Android versions (§3.1).
- AcroForm fields: values are set with appearance generation. A **"Make final"** option (form flattening), **on by default if the document contains a signature** **[ASSUNZIONE]**.

**Note to show in the app (once, on the signature screen, and in About):** the inserted signature is an image placed on the document. It is not a digital signature with a certificate and doesn't have the legal value of a qualified electronic signature.

### 6.6 Merging PDFs

A dedicated tool, separate from "Add pages". It combines two or more whole documents into a new file.

**From Home**
- Multiple selection of PDFs from the picker (at least 2). The user can add more afterwards with a "+" button.
- List of files to merge: first-page thumbnail, name, page count. Reordering with drag & drop, removal with swipe.
- **Merge** button: creates an `EditSession` with all the pages in file order and goes to save as a copy. Proposed name: `<first file name>_unito.pdf`.
- Secondary **Merge and edit** button: creates the same session but opens the Edit hub, so pages can be removed or reordered before saving.

**From the viewer (Edit hub)**
- "Merge with another PDF" item: the open document is the first in the list, then the others are picked. Same flow as above.

**Notes**
- No dedicated merge logic in the engine: the tool builds an `EditSession` and writing goes through `PdfEditor.applySession`, as for every other edit.
- Password-protected PDFs: the password is asked for each one when it is added to the list.
- Bookmarks (outline) and form fields of merged files: not preserved in Phase 1. If a source file has AcroForm fields, warn that the fields may not work in the result and offer to make them final before merging **[ASSUNZIONE]**. List it in the README's known limits.

### 6.7 Saving

At the end of any edit:
- **Save as copy** (default): `CreateDocument`, proposed name `<name>_modificato.pdf`.
- **Overwrite**: only if the URI grants write access. Explicit confirmation.
- Always write to a temporary file in `cacheDir` first, then copy over the destination. If anything fails, the original stays intact.
- Long operation: a non-blocking progress indicator; for large documents the write must survive going to the background (WorkManager or a service with a notification, the agent's choice with its reasons).
- After saving: a snackbar with "Open" and "Share".

---

## 7. Phase 2 (planned, do not implement)

> **Note (2026-10-13):** Phase 2 is in progress following `docs/plan-v2.md`: §7.1, §7.2 and §7.4 are implemented (1.1.0–1.2.0); §7.5 (11) and §7.3 (12) remain.

Phase 1 code needs only: the disabled items in Home/Hub and the interfaces already in place where stated. No Phase 2 dependencies in the project.

### 7.1 Scanning
- **ML Kit Document Scanner** (Google Play services): a ready-made UI flow, processing entirely on the device, no camera permission requested by the app, crop/filters/shadow removal, JPEG or PDF output.
- Limit: it needs Google Play services. On devices without them, the item stays disabled with an explanation.

### 7.2 OCR
- **ML Kit Text Recognition v2**, on-device, Latin script (covers Italian).
- Use: create a "searchable" PDF by adding an invisible text layer over the image (invisible rendering mode in PdfBox). Text placement is approximated per line; good enough to search and copy, not to reproduce the layout.

### 7.3 Cloud upload
- First target: **WebDAV** (Nextcloud, personal NAS), with URL, user and password stored encrypted. Upload of PDFs and scans.
- Google Drive only as a secondary option, possibly later.
- `CloudTarget` interface already defined in Phase 1 (interface only, no implementation).
- The `INTERNET` permission is already there since sub-phase A (updates, ADR 0006): the cloud reuses it; the new use of the network goes into the CHANGELOG and About.

### 7.4 Annotation layer: highlighting and freehand drawing

Highlighting and drawing share the same infrastructure, which is why they are together.

**Prerequisites**
- Text selection in the viewer (long press and handles), built on the search's text index (§5.1).
- An annotation layer in the viewer that reads existing annotations with PdfBox and draws them as an overlay. It is needed because the system renderer, on devices without the Android 15 APIs, doesn't show them (§3.1).

**Highlighting**
- On selected text: highlight (a few colours), underline, strike out.
- Saved as standard Highlight/Underline/StrikeOut annotations **with a generated appearance stream**. This way they stay visible, editable and removable in other readers too. Unlike signatures and text (§6.5), here an annotation is the right choice: a highlight written into the page content could no longer be removed.
- An eraser to remove highlights, including those created by other apps.

**Freehand drawing**
- `androidx.ink` for the strokes, with pen, freehand highlighter, eraser, undo/redo.
- Saved as Ink annotations with an appearance stream, consistent with highlighting. A "make final" option to write them into the page content.

### 7.5 OpenDocument export
To be said clearly: **a faithful PDF → ODT conversion (editable text with layout) on the phone is not realistic**. A PDF contains no paragraphs or structure, only positioned glyphs.

Realistic options, to be decided at the start of Phase 2:
- **ODG** (Draw): one page per PDF page, with the page's image. Faithful to the look, not editable as text.
- **ODT** with one image per page plus the extracted text (or OCR text) under each image. Useful to recover the text, layout lost.

The ODF format is a ZIP of XML files (the `mimetype` file must be written first and uncompressed), so it can be generated without heavy libraries.

---

## 8. Persistent data

**Room**
- `RecentDocument(uri PK, displayName, sizeBytes, pageCount, lastPage, lastOpenedAt, thumbnailPath)`
- `Signature(id PK, name, type: DRAWN|IMPORTED, filePath, createdAt, isDefault)`

**DataStore**
- theme (light / dark / system), language (IT / EN / system), reading mode, last "save as copy / overwrite" choice, last image mode.

**Files**
- `filesDir/signatures/` — signature PNGs (excluded from backup)
- `cacheDir/thumbnails/` — recents thumbnails (regenerable)
- `cacheDir/work/` — save temporaries, emptied at startup

---

## 9. Look and animations

Goal: modern, professional, tidy. It must not look like a generic utility app full of banners.

- Material 3 with its own seed colour (proposal: deep petrol blue; amber accent for signing actions) **[ASSUNZIONE]**. Dynamic colour off by default, can be turned on in settings.
- Light / dark / system theme, as in the reference projects.
- Typography: a single font for the interface, clear hierarchy, no widespread upper-case text.
- Simple app icon: a sheet with a folded corner and an element that hints at the tools. Adaptive icon and monochrome icon for Android 13+.
- Home tool buttons: square, rounded corners, large icon and short label, slight elevation; pressed state scales to 0.96.

**Animations** (they must help, not slow down):
- Screen transitions: 200–250 ms, fade-through or short slide. Never over 300 ms.
- FAB → Edit hub: container transform.
- Thumbnails: light staggered entry (first appearance only), repositioning animation while reordering and removing (`animateItem`).
- Reading mode change: crossfade.
- Respect the system setting to reduce animations.

**Snappy navigation** — measurable requirements:
- Tapping a button gives visual feedback within one frame.
- No I/O or rendering work on the main thread during a transition.
- Screens show their skeleton at once (layout and placeholders); content arrives after.
- Baseline profile for the main paths (startup, opening the viewer, edit hub).

**Accessibility**: content descriptions on every icon, touch targets ≥ 48 dp, AA contrast, scrubber and drag & drop usable through alternative actions too ("move" menu).

---

## 10. Languages, settings, about

- Italian and English, per-app selection (as in the reference projects). No hardcoded strings.
- Settings: theme, dynamic colour, language, default reading mode, clear recents history, clear thumbnail cache.
- About: version, open source licences of the dependencies, the signature note (§6.5), AI disclaimer (§11).

---

## 11. Repository documentation

The agent creates these files and **always keeps them up to date**. Updating them is part of every phase: a phase isn't closed if the documentation lags behind.

### 11.1 README.md
Language: the same as the reference projects' README.

Content:
- What PdfToolkit is in two or three sentences.
- Available features and planned features (Phase 2), a short list.
- Requirements (minimum Android version).
- How to build (debug and release), where to put the keystore, required variables.
- Project structure in a few lines.
- Privacy: files stay on the device; the only connection is the update check on GitHub (ADR 0006).
- Licences of the main libraries.
- **Disclaimer**: the app was developed with the help of artificial intelligence tools (Claude Code), with the author's review and testing.

### 11.2 CLAUDE.md
Project memory for future Claude Code sessions. Short and practical:
- build, test, lint commands
- architecture in brief and where things are
- rules that aren't obvious (one page open at a time in the renderer, every write goes through `PdfEditor`, overlays written into the content stream, search highlights only as an overlay and never in the PDF, `INTERNET` only for updates, ADR 0006)
- naming conventions (`PT` prefix only where it avoids clashes)
- **fixed rule**: at the end of every task update README, CLAUDE.md and CHANGELOG if something changed
- links to this specification and to the ADRs

### 11.3 CHANGELOG.md
Format as in the reference projects.

### 11.4 How these documents must be written

The documents must read as written by a person, not generated. Rules:
- Simple, direct sentences. The information first, then any explanation.
- No openings like "Welcome to…", "This project is a powerful solution…".
- No promotional adjectives (powerful, innovative, all-in-one, seamless, robust).
- No emoji in headings, no decorative lists.
- No closing summaries repeating what was already said.
- Lists only where really needed (requirements, commands). The rest in short prose.
- The tone of a developer explaining their project to a colleague.
- The AI disclaimer is one sentence, written plainly.

---

## 12. Architecture and code organisation

Follow the reference projects' layering. Roughly:

```
app/
  ui/            # Compose screens, ViewModels, navigation, theme
    home/  viewer/  search/  edithub/  merge/  pages/  images/  fillsign/  signatures/  settings/
  domain/        # EditSession, PageItem, use cases (no Android dependency where possible)
  data/          # Room, DataStore, repositories, file access (SAF)
  pdf/
    render/      # PdfDocumentRenderer: PdfRenderer + mutex + cache + tiling
    edit/        # PdfEditor (interface) + PdfBoxEditor (implementation)
    forms/       # reading AcroForm fields, PDF ↔ screen coordinate mapping
    text/        # text extraction with positions, search index, normalisation
  di/            # Hilt modules
docs/
  spec.md  plan.md
  adr/
README.md  CLAUDE.md  CHANGELOG.md
```

Points to take care of:
- **Coordinates**: a PDF has its origin at the bottom left in points, the screen at the top left in pixels, with zoom, pan and page rotation in between. A single class (`PageCoordinateMapper`) does these conversions, with unit tests.
- `PdfEditor` exposes high-level operations (`applySession(session, overlays, destination)`); the UI doesn't touch PdfBox.
- Uniform error handling: typed errors in the domain, localised messages in the UI.

---

## 13. Implementation plan

Every phase: debug build green, the phase's tests green, documentation up to date, a commit with a clear message.

**Phase 0 — Analysis and skeleton** (Opus)
- Reading the reference projects, a written plan in `docs/plan.md` with what is taken and from where.
- Gradle project, version catalog, Hilt, theme, languages, navigation, drawer, Home with buttons (tools still empty), signing, CI copied and adapted.
- Initial README, CLAUDE.md, CHANGELOG; ADR 0001 (viewer) and 0002 (PdfBox-Android).
- *Acceptance*: the app installs, Home and drawer work, theme and language change, CI produces the debug APK.

**Phase 1 — Viewer**
- Opening from SAF and from external intents, recents, renderer with mutex/cache/tiling, continuous and single mode, zoom, scrubber, thumbnails, resuming the last page, passwords.
- *Acceptance*: the criteria of §5, opening from a file manager and from "share".

**Phase 2 — Edit session and page management**
- `EditSession` with undo/redo, Edit hub with the transition from the FAB, removal, reordering, rotation, saving (copy and overwrite) through a temporary file.
- *Acceptance*: remove/reorder/rotate on a 100-page PDF and save; the file opens correctly in another reader too.

**Phase 3 — Adding pages and merging**
- From another PDF, blank pages, images in both modes, EXIF, HEIC, compression.
- "Merge PDFs" tool (§6.6) from Home and from the Edit hub.
- *Acceptance*: unit tests on the size computation; mixed PDFs (A4 and Letter pages) handled as in §6.2; merging 3 PDFs with the files reordered, correct result in another reader.

**Phase 4 — Fill and sign**
- Signature store (drawing and import), AcroForm filling, free filling, signature placement, writing into the content stream, flattening, legal note.
- *Acceptance*: signature and text visible after saving both in the app and in another reader; signatures excluded from backup (check the configuration).

**Phase 5 — Text search**
- Extraction with positions, progressive indexing, search UI, overlay highlighting, accent normalisation, handling of PDFs without text (§5.1).
- *Acceptance*: unit tests on normalisation and on a match across a line break; on a 200-page PDF the first results appear while indexing is still running; results placed correctly on rotated pages too.

**Phase 6 — Polish**
- Animations as in §9, baseline profile, accessibility, release build with R8 tested, Phase 2 items disabled, final documentation review.
- *Acceptance*: release build installed and tried on the main flows.

---

## 14. Out of scope (Phase 1)

Text selection and copy, annotations and highlighting (Phase 2, §7.4), text notes, digital signature with a certificate, encryption/password protection when writing, compression of existing PDFs, keeping bookmarks and form fields when merging, everything in §7.

---

## 15. Technical sources checked (1 October 2026)

- Jetpack PDF (`androidx.pdf`), release notes: https://developer.android.com/jetpack/androidx/releases/pdf
- Android PDF viewer and `PdfRenderer`/`PdfRendererPreV`: https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer
- `PdfRendererPreV` (reference): https://developer.android.com/reference/android/graphics/pdf/PdfRendererPreV
- Android 15 DP2, what's new in PdfRenderer: https://android-developers.googleblog.com/2024/03/the-second-developer-preview-of-android-15.html
- PdfBox-Android, releases: https://github.com/TomRoush/PdfBox-Android/releases
- PdfBox-Android, issue on HEIC/HEIF/AVIF (#564): https://github.com/TomRoush/PdfBox-Android/issues
- ML Kit Document Scanner: https://developers.google.com/ml-kit/vision/doc-scanner
- ML Kit Text Recognition v2: https://developers.google.com/ml-kit/vision/text-recognition/v2
