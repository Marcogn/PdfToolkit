# Product phase 2 (V2): plan

Status on 2026-10-08: 1.0.0 is released; 7a, 7b, 8a and 8b are done, and so are the inserted
series U-a/U-b (`docs/plan-usability.md`) and V-a…V-c (`docs/plan-viewer-editing.md`), which moved
every page tool into the viewer. 9 Scan is done too (device checks passed 2026-10-09). 10a OCR core is
written (2026-10-09, device checks pending). **Next: 10b OCR complete.**

Development continues the numbering of spec §13 (phases 7–12), so "phase 2" keeps meaning the edit
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

Proposed releases: **1.1** now (annotations, drawing, usability, editing in the viewer), **1.2** after
10b (scan + OCR), **1.3** after 11, **2.0** after 12 (the first build with `INTERNET`). To confirm with
the author.

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
  without it. `ACCESS_NETWORK_STATE` was already merged in by `work-runtime` and stays (checked in the
  merger report, 2026-10-09). Whether the scanner works with the app lacking
  `INTERNET` is expected (the flow runs in Play services) but **not verified**: device check of phase 9.
- **ML Kit Text Recognition v2**: unbundled `play-services-mlkit-text-recognition` (19.0.1, model
  downloaded by Play services, ~260 KB) or bundled `com.google.mlkit:text-recognition` (16.0.1, ~4 MB
  per script, works without Play services); minSdk 23; result as blocks → lines → elements → symbols
  with boxes and angles ([guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)).
- The system renderer (`PdfRenderer`, `RENDER_MODE_FOR_DISPLAY`) does not draw annotations below
  API 35 (spec §3.1), so 7a needs its own annotation layer.

## Questions for the author

Answered on 2026-10-04: highlight and draw first went into an "Annotate" pane of the edit screen, then
(2026-10-08) into the viewer; Google Play services is accepted for phases 9 and 10, and
`ACCESS_NETWORK_STATE` was first to be removed like `INTERNET`, then kept (2026-10-09, `docs/decisions.md`).

Still open:
1. **ODF: ODG or ODT** (spec §7.5, before 11).
2. Release numbering above.

Answered on 2026-10-09: OCR is **bundled** (`docs/decisions.md`, OCR).

## Sub-phases

### 7a, 7b, 8a, 8b — done
Annotations read in user space and drawn by the app, text selection and Copy, highlight / underline /
strikeout / eraser (also on other apps' annotations), freehand pen and marker on `androidx.ink` saved as
Ink annotations with their outline as appearance, "make final". Design in ADR 0004; all device checks
passed (7b on 2026-10-05, 8a and 8b on 2026-10-07). Since V-a…V-c these tools live in the viewer.

### 9 Scan — Sonnet
Scope: ML Kit Document Scanner; availability check (Play services up front, which dims the tool with an explanation; low RAM is reported when the scanner itself refuses, `UNSUPPORTED`); result: open the scanned PDF in the viewer (save as with `CreateDocument`)
and "Add → from scanner" in "Organize pages"; manifest stays without `INTERNET` (CI check unchanged).
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
Done 2026-10-09: `pdf/ocr` (`OcrGeometry`, `OcrProcessor`, ML Kit bundled), `PdfEditor.addTextLayer`
(`OcrTextWriter`); words placed one by one inside the line (decisions). Temporary entry: Home "Scan" runs
OCR before the save picker.

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
