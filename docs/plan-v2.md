# Product phase 2 (V2): plan

Status on 2026-10-13: 7a–10b are done (released in 1.1.0 and 1.1.1), and so are the inserted series U-a/U-b
(`docs/plan-usability.md`), V-a…V-c (`docs/plan-viewer-editing.md`), A (`docs/plan-updates.md`) and T-a/T-b
(`docs/plan-tools.md`). Left: **11 ODF export** (the author is considering dropping it) and **12 Cloud**.

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

Releases so far: 1.1.0 (annotations, drawing, usability, editing in the viewer), 1.1.1 (scan, OCR), 1.1.2–1.1.3
(freehand fix, updates and `INTERNET`, ADR 0006), 1.2.0 (partial eraser, tool drawer). The numbers for 11 and 12
are open.

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
  JVM tests are supported on Linux x86_64 only. In use: 1.0.0 without `ink-authoring` (the app draws the wet
  stroke, `docs/decisions.md` 2026-10-13).
- **ML Kit Document Scanner** `play-services-mlkit-document-scanner:16.0.0` (latest on Google Maven):
  UI and models come from Google Play services, output JPEG and/or PDF, needs ≥ 1.7 GB of device RAM
  (else `MlKitException.UNSUPPORTED`), ~300 KB on the APK
  ([guide](https://developers.google.com/ml-kit/vision/doc-scanner/android)). Its POM pulls
  `transport-backend-cct`, whose manifest declares `INTERNET` and `ACCESS_NETWORK_STATE` (Firelog
  telemetry). Since A the app declares `INTERNET` and removes the `cct` backend instead (ADR 0006);
  `ACCESS_NETWORK_STATE` also comes from `work-runtime` and stays (`docs/decisions.md`, Scan).
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
1. **ODF: ODG or ODT** (spec §7.5, before 11), or drop 11.
2. Numbering of the next releases.

Answered on 2026-10-09: OCR is **bundled** (`docs/decisions.md`, OCR).

## Sub-phases

### 7a, 7b, 8a, 8b — done
Annotations read in user space and drawn by the app, text selection and Copy, highlight / underline /
strikeout / eraser (also on other apps' annotations), freehand pen and marker on `androidx.ink` saved as
Ink annotations with their outline as appearance, "make final". Design in ADR 0004; all device checks
passed (7b on 2026-10-05, 8a and 8b on 2026-10-07). Since V-a…V-c these tools live in the viewer.

### 9, 10a, 10b — done
- **9 Scan** (2026-10-09, PR #27): ML Kit Document Scanner; Play services checked up front, low RAM reported when
  the scanner refuses (`UNSUPPORTED`); Home "Scan" saves with `CreateDocument` and opens the viewer; "Add → From
  scanner" in "Organize pages".
- **10a OCR core** (2026-10-09, PR #30): `pdf/ocr` (`OcrGeometry`, `OcrProcessor`, ML Kit bundled),
  `PdfEditor.addTextLayer` (`OcrTextWriter`): an invisible Noto Sans layer in user space, one font size and
  angle per line, words placed one by one inside it (`docs/decisions.md`, OCR).
- **10b OCR complete** (2026-10-11): `OcrWorker`/`OcrJob`/`OcrScheduler`, `OcrRunner`; "Recognise text" in the
  viewer's no-text notice; after a scan, "Make the scan searchable?" with progress and "Save without text";
  closes #31.

Device checks of 10b (counted as done by the author on 2026-10-13; for the next test rounds):
Scan 2–3 pages from Home: the "Make the scan searchable?" dialog; "Recognise text" shows progress, then the save
picker; "Save without text" skips it; cancel from the progress dialog saves the raw scan. A 20-page scan:
measure the time per page (README says only "depends on the phone") and watch memory; leave the app halfway
and come back. Open a scan saved without text in the viewer: search → the notice has "Recognise text"; as
copy (picker, result opens from the snackbar, searchable here and in another reader) and as overwrite (the
viewer reopens on the result); cancel halfway on both (original untouched, no empty copy left); a PDF that
already has text: "nothing changed" and no copy left; a protected PDF: no button. Airplane mode. Release build.

### 11 ODF export — Sonnet
Scope: the format the author chose (ODG: one page image per page; ODT: image + extracted or OCR text),
ZIP written by hand (`mimetype` first and stored), share/save.
Device check: open the result in LibreOffice (desktop and Collabora Office on Android).

### 12 Cloud (WebDAV) — Sonnet, credential store reviewed by Opus
Scope: `CloudTarget` WebDAV implementation (PUT, optional MKCOL), settings screen (URL, user,
password) with the password encrypted through Android Keystore, "Upload" from save snackbar and
viewer; reuses `INTERNET` from sub-phase A (ADR 0006), so About and CHANGELOG say what the new connection is (spec §7.3)
and the network rule in CLAUDE.md, REVIEW.md and ADR 0006 gains the WebDAV server;
TLS only, no plain HTTP unless the author decides otherwise.
Device check: Nextcloud upload, wrong password, no network, self-signed certificate behaviour.
