# Review instructions

Read by Claude Code Review on pull requests and by the `architecture-reviewer` agent. General
project context is in `CLAUDE.md`; this file only says what to check and how hard.

## What Important means here

A finding is Important when it would break behaviour the author tests on the phone, lose or corrupt
a user's PDF, or break one of the rules below. Style, naming and refactoring ideas are Nit at most.

## Always check

- **Coordinates** go only through `PageCoordinateMapper` and `PdfPageSpace`. Any hand-written
  conversion between page points, layout px, screen px or user space in `ui/` is Important.
  Overlays and annotations are stored in PDF user space.
- **PdfBox** stays in `pdf/` (plus `PDFBoxResourceLoader.init` in `PdfToolkitApplication`), and every
  write goes through `PdfEditor` (ADR 0002). An import of `com.tom_roush.pdfbox` from `ui/`,
  `domain/` or `data/` is Important.
- **Renderer**: one page open at a time per `PdfRenderer`, under the document's mutex, on
  `Dispatchers.Default`, page always closed (ADR 0001). A page not closed on every path is Important.
- **Saving**: source copied, result written in `cacheDir/work/`, verified, then copied over the
  destination with mode `"wt"`, inside `SaveWorker` (ADR 0003). After an overwrite nothing may keep
  the old file open.
- **Permissions**: no `INTERNET` (or any network permission) and no dependency that needs it, until
  sub-phase 12. Important.
- **Navigation**: every `navigate()`/`popBackStack()` behind the entry's `lifecycleIsResumed()`
  guard, except from an activity result.
- **SavedStateHandle** keys never reuse a route argument name (`uri`, `tool`, `mergeWith`,
  `autoSave`, `uris`, `page`).
- **Strings**: no hardcoded UI text; every new string in both `values/` (Italian) and `values-en/`.
- **Domain** (`domain/`) has no Android imports.
- **Docs**: a user-visible change without a `CHANGELOG.md` entry under `[Unreleased]` is a Nit; a
  change that makes `CLAUDE.md` or an ADR false is Important.

## Verification bar

Behaviour claims need a `file:line` citation in the source, not an inference from a name.

## Do not report

- Anything CI already enforces: Android Lint findings, compile errors, failing tests.
- `app/schemas/` (Room-generated JSON), `gradle/wrapper/`, `CHANGELOG.md` wording.
- Version numbers in `gradle/libs.versions.toml` unless a version is alpha/beta/RC without a note
  in `docs/decisions.md`.

## Shape

At most five Nits per review; mention the rest as a count. After the first review of a PR, report
Important findings only. Open the summary with a tally (`N important, M nits`).
