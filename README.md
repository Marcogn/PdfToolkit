# PdfToolkit

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](app/build.gradle.kts)

Android app to read and edit PDF files on the phone. Everything runs on the device: no account,
no cloud, no network access.

## Features

- **Reading**: continuous or single-page scrolling, zoom up to 5x with sharp tiles, scrubber,
  thumbnails, "go to page", full-screen reading with a tap, the last page read remembered per file,
  recent files. Opens PDFs from the picker, from "Open with" and from the share sheet; password-protected
  files open on Android 15 and later.
- **Search**: case- and accent-insensitive, results highlighted while the text is read.
- **Editing on the page**, from the viewer's tools bar (a rail at the side in landscape), with undo,
  redo and saving as a copy or over the original:
  - highlight, underline and strike out text; select and copy it;
  - draw with a pen or a marker, finger or stylus;
  - erase annotations, also those made by other apps;
  - fill forms (text, check boxes, radio buttons, lists) and add text, the date, ticks, crosses and
    signatures, which can be moved, resized and turned; signatures are drawn or imported into a private
    archive ("My signatures").
- **Organizing pages**: select, rotate, remove, drag to reorder, add pages from another PDF, blank or
  from images. **Merging** several PDFs.

Planned: document scanning, OCR, export to OpenDocument and upload to a WebDAV server (Nextcloud).
They already show on Home as "Soon"; order and plan in [`docs/plan-v2.md`](docs/plan-v2.md).

## Requirements

Android 8.0 (API 26) or later.

## Building

```bash
./gradlew assembleDebug       # debug APK
./gradlew testDebugUnitTest   # JVM unit tests (Robolectric for the UI ones)
./gradlew lintDebug           # Android Lint
./gradlew assembleRelease     # release APK
```

You need JDK 17 or later and the Android SDK with API 37. The Gradle wrapper (9.8.0) checks the
SHA-256 of the distribution it downloads.

The release build is signed only when it finds the keystore. Credentials are not in the
repository: they come from four environment variables, `RELEASE_KEYSTORE_PATH`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. Without them the
release APK is built unsigned. On GitHub Actions the keystore is the `RELEASE_KEYSTORE_BASE64`
secret (base64), decoded by the "Build APK" and "Release" workflows; the `RELEASE_PUSH_TOKEN`
secret lets "Release" commit the version bump to `main`.

## CI and releases

Android CI checks every pull request (lint, unit tests, release build with R8) and produces no APK.
To try a branch on the phone, run **Build APK** on it (Actions → Build APK → Run workflow): it gives
the release build signed with the persistent key, which installs over the app already there, plus
R8's `mapping.txt` to read crash stack traces. Releases come from the **Release** workflow. Details,
and where the workflows come from (the shared [kit](https://github.com/Marcogn/claude-skill-android-kit)): [`docs/ci.md`](docs/ci.md). Dependabot opens grouped
dependency PRs every week; `@claude` in an issue or PR and an automatic review of PRs run Claude on
GitHub once the `CLAUDE_CODE_OAUTH_TOKEN` secret is set ([`docs/claude.md`](docs/claude.md)).

## Project structure

```
app/src/main/java/com/marcogn/pdftoolkit/
  ui/        Compose screens (viewer and page tools in ui/viewer), navigation, theme
  domain/    models with no Android dependencies (edit session, fill, annotations)
  data/      preferences (DataStore), recents and signatures (Room), background save, picked images
  pdf/       rendering, editing (PdfBox), forms, text and search, annotations
  di/        Hilt modules
docs/        specification, plans, decisions, ADRs, CI
```

## Privacy

The app does not declare the `INTERNET` permission: files stay on the phone and nothing leaves the
device. CI checks on every pull request that no dependency brings the permission back.

## Libraries and licences

Kotlin, AndroidX (including WorkManager and Ink), Jetpack Compose and Dagger Hilt, PdfBox-Android
(editing), and Reorderable (page drag and drop), all under the Apache 2.0 licence; the viewer uses Android's
`PdfRenderer`. The reasons are in [`docs/adr/`](docs/adr/). Text written into PDFs uses the Noto Sans
font (Regular 2.015, from the Noto project), under the SIL Open Font License 1.1: it is bundled in
`app/src/main/assets/fonts/` with its licence (`OFL.txt`) and embedded in the PDFs the app writes.

## Known limits

- **Protected PDFs** can be read (Android 15+) but not edited, added to another PDF or merged.
- **Removing a page** doesn't guarantee its data leaves the file: if a bookmark or link points to it,
  its objects stay (unseen). This is not a redaction tool.
- **Merging** keeps the pages but not the bookmarks, and form fields of merged files may stop working
  (the merge list warns).
- **Images as pages** go through Android's decoder: HEIC/HEIF from Android 9, AVIF from Android 14,
  on Android 8 only JPEG, PNG, WebP and GIF. A photo's DPI counts for "original size" only when it is
  at least 100, otherwise 150 DPI is assumed (cameras often write 72).
- **Signatures** are images on the page, not certified digital signatures: no legal value as a
  qualified electronic signature (the app says so). The archive is excluded from Android's backup, so a
  new phone starts without it. Background removal is a brightness threshold.
- **Forms**: XFA forms aren't supported (free filling still works; a form that also carries XFA data
  loses it when filled, so other readers show the new values). The keyboard's "Next" moves among the
  fields on screen.
- **Search and text selection** follow PdfBox's extraction order, which may differ from the reading
  order in columns and tables; a hyphenated word is two words; right-to-left scripts aren't handled;
  scans have no text until OCR. The search index is rebuilt each time a document is opened.
- **Annotations** are drawn by the app, because Android's renderer doesn't: one with a custom look may
  look plainer than in the app that made it, and notes, stamps and shapes aren't shown (the eraser can
  still remove them). Thumbnails don't show annotations. On Android 8 and 9 a highlight looks lighter
  (translucent instead of blended). Annotations can't be restyled after adding: erase and redo.
- **Drawing**: in the app a reopened drawing has an even width (other readers show the varying
  outline); the marker is translucent while drawing and blends with the text once lifted; double tap
  doesn't zoom while drawing; in single-page mode pages don't turn while a brush is armed; only a stylus
  ignores a resting palm. The drawing library adds about 5 MB of native code to the APK.
- **Editing in the viewer** starts a moment after a document opens (its annotations are read first).
  After "save as copy" the viewer keeps showing the original with the changes on top ("Open" shows the
  copy). "Pages" asks to save or discard first. In landscape the rail covers the right edge of a page
  at fit width: tap to hide it, or zoom.
- **Saving** works on copies in the app's cache: a large merge needs free space of a few times its size
  while it runs. Overwriting needs a file that grants write access; otherwise only "save as copy" is
  offered.

## Documentation

- [`docs/spec.md`](docs/spec.md): specification (Italian), with notes where the app deviates from it
- [`docs/decisions.md`](docs/decisions.md): decisions in force, by topic; [`docs/adr/`](docs/adr/): the big ones
- [`docs/plan-v2.md`](docs/plan-v2.md): plan of product phase 2 (scan, OCR, ODF, cloud)
- [`docs/plan-usability.md`](docs/plan-usability.md), [`docs/plan-viewer-editing.md`](docs/plan-viewer-editing.md):
  records of the usability review and of moving the page tools into the viewer
- [`docs/plan.md`](docs/plan.md): what comes from the reference projects, and how dependencies were upgraded
- [`docs/ci.md`](docs/ci.md): CI and releases
- [`docs/claude.md`](docs/claude.md): how the repository is set up for Claude Code (skills, agent,
  hooks, GitHub workflows) and how to reuse that setup in another project
- [`CHANGELOG.md`](CHANGELOG.md): changes per version; [`CLAUDE.md`](CLAUDE.md): working notes for development

## Development

The app is developed with the help of AI tools (Claude Code), with review and testing by the
author.

## Licence

MIT, see [`LICENSE`](LICENSE).
