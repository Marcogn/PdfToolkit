# PdfToolkit

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](app/build.gradle.kts)

Android app to read and edit PDF files on the phone. Everything runs on the device: no account,
no cloud, no network access.

## Status

The project is at phase 0 of the plan (`docs/spec.md` §13): the app skeleton is in place, with
Home, side menu, light/dark theme and Italian/English language. The viewer and the editing tools
come in the next phases; for now the buttons open a placeholder screen.

## Features

Planned for the first version:

- reading with zoom, continuous or single-page scrolling, scrubber and thumbnails
- text search
- merging several PDFs
- adding pages (from another PDF, blank, from images), removing, reordering, rotating
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
  data/      preferences (DataStore), later Room and file access
  pdf/       PDF rendering and editing (from the next phases)
docs/
  spec.md    functional and technical specification (Italian)
  plan.md    plan, alignment with the reference projects, dependency upgrade notes
  adr/       architecture decisions
```

## Privacy

The app does not declare the `INTERNET` permission: files stay on the phone and nothing leaves the
device. CI checks on every build that no dependency brings the permission back.

## Libraries and licences

Kotlin, AndroidX, Jetpack Compose and Dagger Hilt, all under the Apache 2.0 licence. Later phases
add PdfBox-Android (Apache 2.0) for editing; the viewer uses Android's `PdfRenderer`. The reasons
are in [`docs/adr/`](docs/adr/).

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
