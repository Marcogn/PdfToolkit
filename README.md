# PdfToolkit

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](app/build.gradle.kts)

Android app to read and edit PDF files on the phone. Everything runs on the device: no account,
no cloud, no network access.

## Status

The viewer (phase 1 of the plan, `docs/spec.md` §13) is complete: PDFs open from the file picker,
from a file manager ("Open with") and from the share sheet, in continuous or single-page mode,
with zoom, scrubber, thumbnail bar, password support (Android 15+), recent files with the last page
read, and the related settings. Page editing (phase 2) is in as well: remove, reorder and rotate
pages with undo/redo, and save as a copy or overwrite, in the background. Adding pages (from another
PDF, blank, from images) and merging PDFs (phase 3) are in too. Fill and sign comes in a later phase
and for now shows "coming up".

## Features

Planned for the first version:

- reading with zoom, continuous or single-page scrolling, scrubber and thumbnails (done)
- text search
- merging several PDFs (done)
- removing, reordering and rotating pages (done); adding pages from another PDF, blank, or from
  images (done)
- form filling and signing, with an archive of signatures saved on the phone

Planned for later: document scanning with OCR, cloud upload (WebDAV), highlighting, freehand
drawing, export to OpenDocument. They already show up on Home as "Soon".

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

## Project structure

```
app/src/main/java/com/marcogn/pdftoolkit/
  ui/        Compose screens, navigation and theme
  domain/    models with no Android dependencies
  data/      preferences (DataStore), recent documents (Room), background save (save/), picked images (images/), later signatures
  pdf/       PDF rendering (render/) and editing (edit/), later forms and text search
  di/        Hilt modules
docs/
  spec.md    functional and technical specification (Italian)
  plan.md    plan, alignment with the reference projects, dependency upgrade notes
  adr/       architecture decisions
```

## Privacy

The app does not declare the `INTERNET` permission: files stay on the phone and nothing leaves the
device. CI checks on every build that no dependency brings the permission back.

## Libraries and licences

Kotlin, AndroidX (including WorkManager), Jetpack Compose and Dagger Hilt, PdfBox-Android (editing),
and Reorderable (page drag and drop), all under the Apache 2.0 licence; the viewer uses Android's
`PdfRenderer`. The reasons are in [`docs/adr/`](docs/adr/).

## Known limits

- Password-protected PDFs can be read (Android 15+) but not edited yet.
- Removing a page doesn't guarantee its data leaves the file: if a bookmark or link still points to
  it, the page's objects stay in the file (not shown by readers). Not a redaction tool.
- PDFs added to another one (add pages, merge) can't be password-protected yet.
- Merging keeps the pages as they are but not the bookmarks, and the form fields of the merged files
  may stop working: the merge list warns when a file has them. Flattening the form before merging
  comes with the fill-and-sign phase.
- Images become pages through Android's own decoder: HEIC/HEIF are read from Android 9 and AVIF
  from Android 14 (where the platform guarantees a decoder, see Android's "supported media formats");
  on Android 8 only the formats `BitmapFactory` reads are expected to work (JPEG, PNG, WebP, GIF). A photo's DPI is used
  for "original size" only when it is 100 or more, otherwise 150 DPI is assumed (cameras often write
  72, which would make a page over a metre wide).
- Overwriting needs a file that grants write access (most local files do, some providers don't);
  otherwise only "save as copy" is offered.

## Documentation

- [`docs/spec.md`](docs/spec.md): specification and development plan
- [`docs/plan.md`](docs/plan.md): what comes from ThePatientGamerHelper and KartLog, and how the
  dependencies were upgraded
- [`docs/adr/`](docs/adr/): architecture decisions
- [`CHANGELOG.md`](CHANGELOG.md): changes per version
- [`CLAUDE.md`](CLAUDE.md): working notes for development

## Development

The app is developed with the help of AI tools (Claude Code), with review and testing by the
author.

## Licence

MIT, see [`LICENSE`](LICENSE).
