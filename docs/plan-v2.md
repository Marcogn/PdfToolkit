# Product phase 2 (V2): plan

Status on 2026-10-04: product phase 1 is released as **1.0.0**. Of product phase 2 (spec §7) only
what phase 1 asked for exists: the disabled "Soon" tools on Home and the `CloudTarget` interface
(`domain/cloud/`, no implementation, nothing uses it). No phase 2 dependency is in the project.

Development continues the numbering of spec §13 (phases 7–11), so "phase 2" keeps meaning the edit
session. Order chosen by the author: **highlight and draw first** (closes the annotation thread),
**then scan**, then the rest. Models follow the same rule as phase 1 (CLAUDE.md): Sonnet by default,
Opus only for the cores where a wrong design is expensive later, with an **a** (Opus) / **b**
(Sonnet) split and a handoff note.

| Order | Sub-phase | Model | Spec |
|---|---|---|---|
| 1 | 7a Annotation core | **Opus** | §7.4 prerequisites, highlight write path |
| 2 | 7b Highlight complete | Sonnet | §7.4 highlight |
| 3 | 8a Freehand core | **Opus** | §7.4 freehand |
| 4 | 8b Freehand complete | Sonnet | §7.4 freehand |
| 5 | 9 Scan | Sonnet | §7.1 |
| 6 | 10a OCR core | **Opus** | §7.2 |
| 7 | 10b OCR complete | Sonnet | §7.2 |
| 8 | 11 ODF export | Sonnet | §7.5 |
| 9 | 12 Cloud (WebDAV) | Sonnet, Opus review of the credential store | §7.3 |

Proposed releases: **1.1** after 7b+8b (annotations), **1.2** after 10b (scan + OCR), **1.3** after 11,
**2.0** after 12 (the first build with `INTERNET`). To confirm with the author.

## Facts checked before planning (2026-10-04)

- **PdfBox-Android 2.0.27.0** (the version in use) has `PDAnnotationTextMarkup` (Highlight, Underline,
  StrikeOut, Squiggly; `setQuadPoints`), `PDAnnotationMarkup` with `SUB_TYPE_INK` and `setInkList`,
  `constructAppearances(PDDocument)` on both, and the appearance handlers
  (`PDHighlightAppearanceHandler`, `PDInkAppearanceHandler`, …). Checked with `javap` on the AAR from
  Maven Central. Whether the generated appearances look right in other readers is **not** verified:
  that is a 7a task.
- **androidx.ink**: stable 1.0.0 (2025-12-17), 1.1.0-alpha09 (2026-09-23); modules authoring,
  authoring-compose, brush, geometry, rendering, strokes, storage
  ([release notes](https://developer.android.com/jetpack/androidx/releases/ink)). `ink-authoring` and
  `ink-rendering` 1.0.0 declare `minSdkVersion 23` (AAR manifests), below our 26. Native code (JNI);
  JVM tests are supported on Linux x86_64 only.
- **ML Kit Document Scanner** `play-services-mlkit-document-scanner:16.0.0` (latest on Google Maven):
  UI and models come from Google Play services, output JPEG and/or PDF, needs ≥ 1.7 GB of device RAM
  (else `MlKitException.UNSUPPORTED`), ~300 KB on the APK
  ([guide](https://developers.google.com/ml-kit/vision/doc-scanner/android)). Its POM pulls
  `transport-backend-cct`, whose manifest declares **`INTERNET`** and `ACCESS_NETWORK_STATE`
  (Firelog telemetry): our manifest's `tools:node="remove"` drops `INTERNET`, so the merged APK stays
  without it, but `ACCESS_NETWORK_STATE` would come in. Whether the scanner works with the app lacking
  `INTERNET` is expected (the flow runs in Play services) but **not verified**: device check of phase 9.
- **ML Kit Text Recognition v2**: unbundled `play-services-mlkit-text-recognition` (19.0.1, model
  downloaded by Play services, ~260 KB) or bundled `com.google.mlkit:text-recognition` (16.0.1, ~4 MB
  per script, works without Play services); minSdk 23; result as blocks → lines → elements → symbols
  with boxes and angles ([guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)).
- The system renderer (`PdfRenderer`, `RENDER_MODE_FOR_DISPLAY`) does not draw annotations below
  API 35 (spec §3.1), so 7a needs its own annotation layer.

## Questions for the author (before 7a)

Answered on 2026-10-04: **1** as proposed (Annotate pane, selection and Copy in the viewer);
**2** Play services accepted, `ACCESS_NETWORK_STATE` to be removed (phase 9). 3–5 still open.

1. **Where do highlight and draw live?** Proposal: a new **"Annotate"** pane of `EditScreen`
   (same session, undo history and background save as every other edit, ADR 0003), while the viewer
   gets read-only text selection with **Copy** and shows existing annotations. The alternative is
   annotating directly in the viewer, which needs a second save path or a "save changes?" prompt.
2. **Google Play services** (phases 9 and 10): accepted as a dependency? The app is no longer usable
   for scanning on devices without it (spec §7.1 already says so). And `ACCESS_NETWORK_STATE`, merged
   by the scanner: remove it like `INTERNET` (proposal) or accept it.
3. **OCR bundled or unbundled** (can wait until 10a): bundled works on any PDF without Play services
   and offline from the first use, at +~4 MB; unbundled is lighter but adds a Play services download.
   Proposal: bundled.
4. **ODF: ODG or ODT** (spec §7.5, can wait until 11).
5. Release numbering above.

## Sub-phases

### 7a Annotation core — Opus
Scope:
- `pdf/annotations`: read the page annotations with PdfBox into a domain model in **user space**
  (`domain/annotate/`: highlight/underline/strikeout/squiggly with quads and colour, ink with paths
  and width; other subtypes kept as "foreign, not drawn" but listed, so they survive saves).
- Annotation layer in the viewer and in the edit pane: drawn in overlay through
  `PageCoordinateMapper`/`PdfPageSpace` (rotated pages). On API 35+ decide how to avoid drawing twice
  (renderer flags vs overlay only) and write it down.
- Text selection model on the search index (`PageText` glyphs already carry origin/ascent/descent
  vectors): word at a point, range between two handles, one quad per glyph run per line, text for
  the clipboard. Selection uses display space; quads convert to user space for writing.
- Write path: `EditSession` gains annotation additions/removals (undo history, `encode()`), and
  `PdfEditor`/`PdfBoxEditor` writes them with an appearance stream; removal works on annotations
  made by other apps. Check PdfBox's generated appearances against pdfium (the app's own renderer)
  and decide: PdfBox handlers or our own appearance stream.
- An ADR (0004) for the model and the write path.
Done when: unit tests for selection ranges (incl. rotated pages and line breaks), quads ↔ user space,
read/write round trip, removal of a foreign highlight; a highlight added in code is saved and shows
in the app; handoff written.
Device check: a highlight written by the app shows in another reader (Acrobat, Chrome) and one made
in Acrobat shows in the app and can be removed.

### 7b Highlight complete — Sonnet
Status 2026-10-05: **implemented**, device checks passed (author).
Scope: selection UI (long press, handles, magnifier optional), Copy in the viewer; "Annotate" pane
with highlight (a few colours), underline, strikeout, eraser (tap an annotation); Home and hub tool
"Highlight" enabled; strings IT/EN; README limits.
Device check: select across two lines and on a turned page; copy; highlight/underline/strike, save as
copy and overwrite, open in another reader; erase a highlight made elsewhere; rotate the screen while
selecting.

### 8a Freehand core — Opus
Status 2026-10-05: **implemented** (see CLAUDE.md, Current status, and ADR 0004 "Freehand ink");
device checks below are the author's. Stylus: draws with any freehand tool armed, ignores the palm
while drawing; whether it should also draw with the markup tools is a question for 8b.
Scope: `androidx.ink` (stable line) on top of the page with zoom and pan: gesture arbitration (draw
vs pan/zoom: finger draws in draw mode, two fingers pan; stylus always draws — to confirm), strokes
mapped to user space, saved as Ink annotations with an appearance stream that follows the brush
outline (variable width; `/InkList` keeps the centre line for other readers), "make final" writes
the same outline into the content stream (like `FillWriter`). Freehand highlighter = translucent
brush with multiply blend in the appearance (`/BM /Multiply` in the ExtGState). Tests on geometry.
Done when: unit tests (stroke → user space on rotated pages, appearance bounds), a stroke saved and
visible in the app; handoff written.
Device check: a stroke lands exactly where drawn at several zoom levels and on a turned page, in the
app and in another reader; "make final" result.

### 8b Freehand complete — Sonnet
Scope: tools pen / highlighter / eraser (whole stroke), colours and widths, undo/redo, "make final"
in the save dialog, palm rejection where the library offers it, tool "Draw" enabled.
Device check: draw with finger and (if available) stylus, erase, undo/redo, save, other reader;
rotation while drawing.

### 9 Scan — Sonnet
Scope: ML Kit Document Scanner; availability check (Play services and RAM) with the tool disabled and
an explanation otherwise; result: open the scanned PDF in the viewer (save as with `CreateDocument`)
and "Add pages → from scanner" in the hub; manifest stays without `INTERNET` (CI check unchanged).
Device check: scan 3 pages, save, open; add scanned pages to an open PDF; airplane mode; a device
without Play services if one is at hand.

### 10a OCR core — Opus
Scope: `pdf/ocr`: render each image-only page (or take the scan JPEGs) at a resolution fit for
recognition, run Text Recognition v2, write an invisible text layer (render mode 3) with Noto Sans,
one line per ML Kit line, scaled horizontally to the line box, rotated by the line angle, in user
space of the page (any `/Rotate`); detection of pages that already have text. Tests: line box →
user space on rotated pages, text layer found by our own search (`PdfTextExtractor`).
Device check: an OCR'd scan is searchable in the app and in another reader; text selection lands on
the right line.

### 10b OCR complete — Sonnet
Scope: "Recognise text" from the viewer's scanned-PDF message and as an option after a scan;
progress and cancellation, background run (WorkManager like the save); limits in README.
Device check: 20-page scan; search after OCR; cancel halfway.

### 11 ODF export — Sonnet
Scope: the format the author chose (ODG: one page image per page; ODT: image + extracted or OCR text),
ZIP written by hand (`mimetype` first and stored), share/save.
Device check: open the result in LibreOffice (desktop and Collabora Office on Android).

### 12 Cloud (WebDAV) — Sonnet, credential store reviewed by Opus
Scope: `CloudTarget` WebDAV implementation (PUT, optional MKCOL), settings screen (URL, user,
password) with the password encrypted through Android Keystore, "Upload" from save snackbar and
viewer; adds `INTERNET` (manifest, CI `FORBIDDEN_PERMISSIONS`, About, CHANGELOG as spec §7.3 asks);
TLS only, no plain HTTP unless the author decides otherwise.
Device check: Nextcloud upload, wrong password, no network, self-signed certificate behaviour.
