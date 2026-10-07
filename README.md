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
PDF, blank, from images) and merging PDFs (phase 3) are in too. Fill and sign (phase 4) is complete:
form fields, text, dates, ticks and crosses, and signatures are written into the page; signatures
are drawn or imported into an archive ("My signatures") and every placed item can be moved, resized
and turned with the fingers. Text search (phase 5) is in: case- and accent-insensitive, with results that appear while the
text is read and are highlighted on the page. Polish (phase 6) is done except the baseline profile,
which has to be generated on a device: the Edit button morphs into the edit hub, thumbnails fade in, the viewer
announces the page to screen readers, and the release build is minified (R8). Version 1.0.0 is
released. Product phase 2 has started (`docs/plan-v2.md`): the viewer shows the highlights and
drawings already in a PDF, selects and copies text (7a, 7b), and the "Highlight" tool (7b) highlights,
underlines and strikes out text and erases annotations, also those made by other apps. Freehand
drawing is done (8a, 8b): the "Draw" tool opens the annotate pane with the pen; pen and marker have
their own colours and sizes, and drawings are saved as ink annotations or made final. The usability
review (`docs/plan-usability.md`) is under way: U-a is in (the edit panes open on the page being read and
can jump to any page, Highlight from the viewer selection, back from a Home tool goes to the hub after
changes, saving from the exit dialog leaves); U-b (screens) is next.

## Features

Planned for the first version:

- reading with zoom, continuous or single-page scrolling, scrubber and thumbnails (done)
- text search (done)
- merging several PDFs (done)
- removing, reordering and rotating pages (done); adding pages from another PDF, blank, or from
  images (done)
- form filling and signing (done: forms, text, date, ticks, signatures drawn or imported), with an
  archive of signatures saved on the phone
- selecting and copying text; highlighting, underlining and striking out text, and erasing
  annotations (done, product phase 2 / 7b)
- freehand drawing with a pen and a marker, finger or stylus (done, 8a and 8b)

Planned for later: document scanning with OCR, cloud upload (WebDAV), export to OpenDocument. They already show up on Home as "Soon". Order and plan in
[`docs/plan-v2.md`](docs/plan-v2.md): highlighting and drawing first, then scanning, then OCR,
OpenDocument export and cloud upload.

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
and how to reuse the workflows in another project: [`docs/ci.md`](docs/ci.md).

## Project structure

```
app/src/main/java/com/marcogn/pdftoolkit/
  ui/        Compose screens, navigation and theme
  domain/    models with no Android dependencies
  data/      preferences (DataStore), recent documents and signatures (Room), background save (save/), picked images (images/)
  pdf/       PDF rendering (render/), editing (edit/), forms (forms/), text search (text/) and annotations (annotations/)
  di/        Hilt modules
docs/
  spec.md    functional and technical specification (Italian)
  plan.md    plan, alignment with the reference projects, dependency upgrade notes
  adr/       architecture decisions
```

## Privacy

The app does not declare the `INTERNET` permission: files stay on the phone and nothing leaves the
device. CI checks on every pull request that no dependency brings the permission back.

## Libraries and licences

Kotlin, AndroidX (including WorkManager), Jetpack Compose and Dagger Hilt, PdfBox-Android (editing),
and Reorderable (page drag and drop), all under the Apache 2.0 licence; the viewer uses Android's
`PdfRenderer`. The reasons are in [`docs/adr/`](docs/adr/). Text written into PDFs uses the Noto Sans
font (Regular 2.015, from the Noto project), under the SIL Open Font License 1.1: it is bundled in
`app/src/main/assets/fonts/` with its licence (`OFL.txt`) and embedded in the PDFs the app writes.

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
- Signatures are images placed on the page, not digital signatures with a certificate: they have
  no legal value as a qualified electronic signature (the app says so the first time you create one,
  and in About). The archive is private to the app and excluded from Android's backup, so a new
  phone starts without them. Background removal is a brightness threshold: a photo with uneven
  light may need the slider, or a tighter crop.
- Fill and sign: XFA forms are not supported (free filling
  still works, and a form that also has XFA data loses it when its fields are filled, so other
  readers show the new values); "Next" on the keyboard moves between the fields of one page.
  Pages in "Fill and sign" are not tiled like in the viewer: at high zoom they look softer.
- Text search follows the order in which PdfBox extracts the text, which may differ from the
  reading order in columns and tables: a phrase split across columns may not be found. A word
  hyphenated at the end of a line is two words for the search. Right-to-left scripts aren't handled.
  Scanned PDFs have no text to search until OCR (planned). Reading the text of a long, dense PDF
  takes a while on a phone (results come in as pages are read); the index is not kept on disk, so
  it is rebuilt each time the document is opened and searched.
- Saving works on copies in the app's cache: a large merge needs free storage of a few times the
  total size of its files while it runs (they are deleted afterwards).
- Annotations already in a PDF (highlights, underlines, strikeouts, freehand ink) are drawn by the
  app from their shape, because Android's renderer doesn't draw them: one with a custom look made
  by another app may look plainer than in that app, and notes, stamps and shapes aren't shown yet.
  Page thumbnails don't show annotations. On Android 8 and 9 a highlight is drawn translucent over
  the text instead of blending with it, so the text under it looks lighter.
- Text selection and the highlight tools work on the glyphs PdfBox extracts: a selection is one run in
  extraction order (it can jump oddly in columns), one page at a time, and a page without text (a
  scan) has nothing to select until OCR. Right-to-left scripts aren't handled. In "Highlight" the
  text is selected on the page as its file shows it; the eraser takes whatever is under the finger,
  including notes and stamps the app can't draw. Annotations aren't editable after they are added
  (no colour change): erase and redo. Highlighting is not available on password-protected PDFs (they
  can't be edited yet).
- Drawing: colour and size are chosen per tool and kept while the pane is open (not across app restarts); pages don't turn while the pen or the marker
  is chosen (pick another tool to move to another page); double tap doesn't zoom
  while drawing, two fingers do. A drawing reopened later in the app is drawn with an even width:
  the varying outline is in the file's appearance, which other readers show, while the app draws ink
  from its centre line. While drawing, the marker is translucent; once lifted it blends with the text,
  so it looks slightly different. Only the stylus ignores a resting palm: with a finger, a second
  touch means zoom. The drawing library adds about 5 MB of native code to the APK.
- Overwriting needs a file that grants write access (most local files do, some providers don't);
  otherwise only "save as copy" is offered.

## Documentation

- [`docs/spec.md`](docs/spec.md): specification and development plan
- [`docs/plan.md`](docs/plan.md): what comes from ThePatientGamerHelper and KartLog, and how the
  dependencies were upgraded
- [`docs/plan-v2.md`](docs/plan-v2.md): plan of product phase 2 (annotations, scan, OCR, ODF, cloud)
- [`docs/plan-usability.md`](docs/plan-usability.md): usability review and the changes it proposes
- [`docs/adr/`](docs/adr/): architecture decisions
- [`CHANGELOG.md`](CHANGELOG.md): changes per version
- [`CLAUDE.md`](CLAUDE.md): working notes for development

## Development

The app is developed with the help of AI tools (Claude Code), with review and testing by the
author.

## Licence

MIT, see [`LICENSE`](LICENSE).
