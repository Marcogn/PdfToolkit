# PdfToolkit — project memory

Android app to read and edit PDFs locally. The full specification is `docs/spec.md` (in Italian):
this file doesn't repeat it, it holds commands, non-obvious rules, status and decisions.

## Fixed rule
At the end of every task, update `README.md`, `CLAUDE.md` and `CHANGELOG.md` if something changed.
Every user-visible change goes into `CHANGELOG.md` right away under `## [Unreleased]`, in the form
`- **Summary.** detail` (`release.yml` reads it for the release notes).

## Phase glossary
"Phase" means two different things in `docs/spec.md`:
- **Development phases 0–6** (spec §13): the order in which the code gets written. "Next phase"
  always means the next development phase.
- **Product phase 1 / product phase 2** (spec §1, §7, §14): product phase 1 is everything in
  development phases 0–6; product phase 2 is the future features (scan, OCR, cloud, highlight,
  draw, ODF export). Spec sentences like "in Fase 1 nessun permesso INTERNET" or "Fase 2
  (pianificata, non implementare)" refer to the **product**.

So development phase 2 (edit session and page management) has nothing to do with product
phase 2. Never implement product phase 2 features; only the disabled "Soon" entries and the
interfaces the spec asks for.

## Session protocol
When the author says "go on with the next phase" (or similar):
1. Read **Current status** below to find the next development phase.
2. Read that phase in spec §13 and the spec sections it points to, and its row in the table below.
3. Check the prerequisites (previous phase merged, open questions answered) before writing code.
   If something is missing or unclear, stop and ask.
4. Do **only** that phase. Anything that belongs to a later phase goes into Current status as a
   note, not into the code.
5. Close with: phase acceptance criteria met, lint + unit tests + `assembleDebug` green, README,
   CLAUDE.md (Current status, Decisions) and CHANGELOG updated, commit, PR.
6. End by listing the **device checks** for the phase (table below): there is no emulator in the
   cloud environment, so the author tests on a phone before merging.
7. Don't start the following phase in the same session unless the author asks.

## Phases: model and device checks
Model choice to save tokens: Sonnet by default; Opus only for the parts where a wrong design is
expensive to fix later. Split those phases into two sessions: an Opus session that builds the
core with tests and writes a short handoff in Current status, then a Sonnet session for the rest.
Haiku is not recommended for code in this project.

| Phase | Model | What needs Opus | Check on the device |
|---|---|---|---|
| 1 Viewer | **Opus** for the core, then Sonnet | `pdf/render` (`PdfRenderer` + mutex, LRU cache, ±2 prefetch, two-level render, tiling) and zoom/pan state with gestures; first version of `PageCoordinateMapper` (page ↔ screen with zoom and pan) with tests. Sonnet: SAF, intents, Room recents, thumbnail bar, scrubber, last page, password, settings | 200-page PDF opens in < 1 s; smooth scroll; pinch, double tap, pan; continuous ↔ single page; open from file manager and from "share"; password PDF; resume last page; memory with a large PDF |
| 2 Edit session and pages | Sonnet | Nothing, unless the background save choice (WorkManager vs service) gets stuck | Remove/reorder/rotate on a 100-page PDF, save as copy and overwrite, open the result in another reader; undo/redo; rotate the screen during editing |
| 3 Add pages and merge | Sonnet | Nothing | Images in both modes (EXIF rotation, HEIC); mixed A4/Letter PDF; merge 3 PDFs with reordering, check in another reader |
| 4 Fill and sign | **Opus** for the core, then Sonnet | AcroForm reading and field overlay alignment, writing text/signatures into the content stream at the right coordinates (extends `PageCoordinateMapper`), flatten. Sonnet: signature archive, drawing canvas, image import, free-fill UI, legal note | Signature and text land exactly where placed, visible in the app and in another reader; form with fields; backup exclusion of signatures |
| 5 Text search | **Opus** for the core, then Sonnet | `pdf/text`: `PDFTextStripper` subclass with positions, NFD normalisation keeping the index mapping, line breaks as spaces, rotated pages through `PageCoordinateMapper`. Sonnet: search UI, progressive indexing, highlights | Results appear while indexing a 200-page PDF; "perche" finds "perché"; highlights in the right place on rotated pages; scanned PDF message |
| 6 Polish | Sonnet | Nothing | Release build with R8 on the main flows; animations; TalkBack. The baseline profile needs a device or emulator to generate: run it locally |

## Commands
```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM tests, Robolectric for UI (sdk=34, see robolectric.properties)
./gradlew lintDebug            # Android Lint
./gradlew buildEnvironment     # check the resolved Kotlin/KSP plugin versions
```
In this cloud environment: Android SDK in `/opt/android-sdk` (installed with `sdkmanager`,
platform `android-37.0`; `local.properties` is gitignored), run Gradle with `LC_ALL=C.UTF-8`.
Maven Central often answers 429 or fails to resolve: retry, preferably with `--max-workers=2`.
Robolectric downloads `android-all-instrumented` at test time; if that gets a 429, download it
with `curl` into `~/.m2/repository/org/robolectric/...` and rerun.

## Architecture
Package `com.marcogn.pdftoolkit`, same layering as ThePatientGamerHelper and KartLog (details in
`docs/plan.md`):
- `ui/<feature>/` Compose screens and ViewModels; `ui/navigation/` `@Serializable` routes
  (`Destination`), NavHost and drawer; `ui/theme/` palette (`Color.kt`) and theme.
- `domain/` models with no Android dependencies (`PdfTool`, `ThemeMode`; later `EditSession`,
  `PageItem`).
- `data/` DataStore (`data/settings/ThemePreferences`), later Room and SAF.
- `pdf/render`, `pdf/edit`, `pdf/forms`, `pdf/text` from phases 1–5 (spec §12).
- `di/` Hilt modules, when needed.

## Non-obvious rules
- One page open at a time per `PdfRenderer` instance: one mutex per document, rendering on
  `Dispatchers.Default`, page always closed after rendering (ADR 0001).
- All writes go through `PdfEditor`; the UI never touches PdfBox (ADR 0002).
- Signatures, text, check marks and dates are written into the page content stream, not as
  annotations.
- Search highlights are overlay only, never written into the PDF.
- No `INTERNET` permission in product phase 1: the manifest removes it with `tools:node="remove"`
  and CI checks the packaged manifest. Don't add dependencies that need it.
- Every `navigate()`/`popBackStack()` goes through the `lifecycleIsResumed()` guard of the entry
  that owns the callback (double tap during a transition). Home from the drawer:
  `popUpTo<Home>{inclusive}`.
- Navigation transitions 200–250 ms, never above 300 (spec §9).
- `MainActivity` is an `AppCompatActivity`: `setApplicationLocales()` needs it for the per-app
  language.
- No hardcoded UI strings: `values/` (Italian, default) and `values-en/`.
- AGP 9 built-in Kotlin: no `org.jetbrains.kotlin.android` plugin, compiler options in
  `kotlin { compilerOptions { } }`. In composables read resources with `LocalResources.current`,
  not `LocalContext.current` (lint error).

## Conventions
- Documentation and code comments in English. App UI in Italian with English translation.
  Identifiers in English.
- `PT` prefix only where needed to avoid name clashes.
- Choices marked **[ASSUNZIONE]** in the spec: implemented as written and kept isolated (e.g. the
  palette in `ui/theme/Color.kt`).
- If a requirement is unclear or not feasible, stop and ask.

## References
- Specification: `docs/spec.md`. Plan, alignment with the references, upgrade steps:
  `docs/plan.md`.
- ADRs: `docs/adr/0001-viewer.md`, `docs/adr/0002-pdfbox-android.md`.

## Current status
<!-- Update at the end of every session. -->
- **Phase 0 done (2026-10-01)**: skeleton, theme, languages, navigation, drawer, Home, signing,
  CI, docs, ADR 0001–0002. Toolchain and libraries upgraded to the latest stable versions
  (`docs/plan.md`). Verified with lint, JVM/Robolectric tests, debug and release builds; the
  author installed the phase 0 APK (pre-upgrade build) and confirmed it works.
- PR #1 merged. Next: development phase 1 (viewer), starting with an Opus session for the
  renderer core (see "Phases: model and device checks").
- The author still has to add the signing secrets to the repository.

## Decisions
<!-- One line per decision: date, what, why. Append, don't rewrite. -->
- 2026-10-01 · Product phase 2 tools in a separate "Coming up" section on
  Home, with a "Soon" badge; a tap shows a snackbar and doesn't navigate.
  `GridCells.Adaptive(100.dp)`.
- 2026-10-01 · "My signatures" from Home uses the same navigation as the drawer entry, so the
  screen never appears twice on the back stack.
- 2026-10-01 · Backup rules excluding `filesDir/signatures/` already in phase 0 (no cost).
- 2026-10-01 · Room in the version catalog but not a dependency until there is an entity (phase 1).
- 2026-10-01 · Robolectric Compose tests: Italian locale set explicitly with
  `@Config(qualifiers = "it-...")`, otherwise Robolectric starts in English and loads `values-en/`.
- 2026-10-01 · Icon in `mipmap-anydpi-v26/` as in the references. Lint reports `ObsoleteSdkInt`,
  but after moving it to `mipmap-anydpi/` aapt2 no longer found `@mipmap/ic_launcher`: left as is.
- 2026-10-01 · Documentation and code comments in English (author's decision; overrides the
  initial Italian choice).
- 2026-10-01 · Spec moved to `docs/spec.md` (author's decision); spec §12 tree updated to match.
- 2026-10-01 · Upgrade to the latest stable toolchain and libraries (author's request; the same
  will be applied to TPGH and KartLog): steps and sources in `docs/plan.md`. Hilt ≥ 2.59 requires
  AGP 9, so the two go together. `compileSdk`/`targetSdk` 37, the maximum for AGP 9.4.
- 2026-10-01 · Gradle wrapper with `distributionSha256Sum` (official checksum from
  services.gradle.org, cross-checked with the downloaded file).
- 2026-10-01 · Release keystore: dedicated to this app, generated once (RSA 2048, 10,000 days,
  alias `pdftoolkit`, PKCS12), handed to the author, never committed. Same scheme as TPGH.
- 2026-10-01 · Model per phase and device checks added to this file (author's request, to save
  tokens): Sonnet by default, Opus only for the cores of phases 1, 4 and 5. `PageCoordinateMapper`
  starts in phase 1 (zoom and pan already need page ↔ screen conversion) and is extended in
  phases 4 and 5 (rotation, PDF bottom-left origin); spec §12 wants a single class for all of it.
