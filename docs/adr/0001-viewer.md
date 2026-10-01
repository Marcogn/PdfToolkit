# ADR 0001 — Viewer on PdfRenderer with our own Compose UI

Date: 2026-10-01. Status: accepted.

## Context

The viewer is the most used part of the app (spec §1, §5). It needs continuous and single-page
modes, zoom up to 5x with a re-render of the visible area, a scrubber, thumbnails and, later,
overlays for search, form filling and signatures.

Two options were considered: the Jetpack `androidx.pdf` library, or our own viewer written in
Compose on top of `android.graphics.pdf.PdfRenderer`, which is a platform API.

## Decision

Our own Compose viewer on top of `PdfRenderer`.

As of today `androidx.pdf` is in beta: the latest version is 1.0.0-beta01 from 26 August 2026,
with no stable release. The release notes of that version say that `PdfViewer`, `PdfViewerState`,
`EditablePdfViewerFragment`, `AnnotationsView` and `OcrProvider` are marked `@ExperimentalPdfApi`
(opt-in required). It has no paged single-page mode, which is a requirement, and it would take
away control over animations and transitions.

## Consequences

- Rendering is our code: one mutex per document (only one page open at a time per `PdfRenderer`
  instance), rendering on `Dispatchers.Default`, an LRU cache, tiling above a maximum bitmap size
  (spec §3.1, §5).
- On devices without the Android 15 APIs (or `PdfRendererPreV` through the SDK extension) the
  system renderer draws neither annotations nor form field values. That's why everything the user
  adds is written into the page content (ADR 0002, spec §6.5).
- Text search and selection don't come for free: search goes through PdfBox (spec §5.1).
- To be reconsidered when `androidx.pdf` goes stable: it could replace the viewer and bring text
  search and selection.

## Implementation notes (phase 1a)

Checked against the AOSP source of `PdfRenderer` (android14-release): the constructor requires a
seekable descriptor and throws `IllegalArgumentException` otherwise, `SecurityException` for a
password or unsupported security; the class is not thread safe and allows one open page at a time;
`Page.render` takes an affine matrix from page points to bitmap pixels, which is how tiles are
rendered; the destination must be ARGB_8888 and initialising the bitmap is left to the caller (we
erase it to white first). In that version pdfium calls are serialised process-wide by a static
lock.

## Sources

- `androidx.pdf` release notes: https://developer.android.com/jetpack/androidx/releases/pdf
  (checked on 2026-10-01)
- PDF viewer on Android, `PdfRenderer` and `PdfRendererPreV`:
  https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer
- `PdfRenderer` source:
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/graphics/java/android/graphics/pdf/PdfRenderer.java
