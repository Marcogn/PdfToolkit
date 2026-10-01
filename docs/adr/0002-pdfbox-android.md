# ADR 0002 — Editing with PdfBox-Android behind the PdfEditor interface

Date: 2026-10-01. Status: accepted.

## Context

Every write (merging, importing/removing/reordering pages, images, AcroForm, text and signatures
in the page content) needs a PDF library that runs on the device, under a licence compatible with
an app distributed as an APK.

## Decision

PdfBox-Android (`com.tom-roush:pdfbox-android`), a port of Apache PDFBox 2.0.x, Apache 2.0 licence.

The latest release is v2.0.27.0, published on 2 January 2025 on Maven Central. The project is not
very active, but it covers what we need. For this reason the library stays isolated behind a
`PdfEditor` interface (package `pdf/edit/`), with `applySession(session, overlays, destination)`
as the main operation: the UI never touches PdfBox and the library can be replaced.

iText and MuPDF are excluded: both are AGPL (or commercial), which doesn't fit the intended use.

## Consequences

- `PDFBoxResourceLoader.init(context)` must be called at startup (`PdfToolkitApplication`).
- The R8 release build must be tested with the rules given in the library's README (phase 6).
- PdfBox-Android doesn't handle HEIC/HEIF/AVIF: images are always decoded with `ImageDecoder` and
  PdfBox receives a bitmap (spec §6.2).
- Signatures, text, check marks and dates are written into the content stream
  (`PDPageContentStream` in append mode), not as annotations, so they are visible with the system
  renderer on older devices too (ADR 0001).
- The dependency enters the project with the first phase that writes PDFs (phase 2), not earlier.

## Sources

- PdfBox-Android releases: https://github.com/TomRoush/PdfBox-Android/releases (checked on
  2026-10-01)
