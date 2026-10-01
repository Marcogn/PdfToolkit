# PdfToolkit — project memory

Android app to read and edit PDFs locally. The full specification is `docs/spec.md` (in Italian):
this file doesn't repeat it, it holds commands, non-obvious rules, status and decisions.

## Fixed rule
At the end of every task, update `README.md`, `CLAUDE.md` and `CHANGELOG.md` if something changed.
Every user-visible change goes into `CHANGELOG.md` right away under `## [Unreleased]`, in the form
`- **Summary.** detail` (`release.yml` reads it for the release notes).

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
- Next: phase 1 (viewer).
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
