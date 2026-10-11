# In-app updates and About: plan

Status on 2026-10-11: **planned**, not started. Requested by the author on 2026-10-11 for all six of
their Android apps. One inserted sub-phase, **A Updates and About** (Sonnet), placed **after 10b and
before 11** (author's choice).

The design is shared with the sibling apps and is designed once in **ThePatientGamerHelper**, the
pilot (its Phase 9, Opus core + Sonnet UI). This sub-phase copies the pilot's `update` package. The
shared design, the facts checked before planning and their sources are in the pilot's plan:
[`ThePatientGamerHelper/docs/plan-phase-9-updates.md`](https://github.com/Marcogn/ThePatientGamerHelper/blob/main/docs/plan-phase-9-updates.md).

| Sub-phase | Model | Scope | Done when | Check on the device |
|---|---|---|---|---|
| A Updates and About | Sonnet | Port of the pilot's `update` package; `INTERNET` + `REQUEST_INSTALL_PACKAGES`; update dialog; frequency in Settings; About gains version check, "made with ❤", rewritten Privacy | Tests below; ADR 0006; packaged manifest has `INTERNET` and no other new permission | Old release → dialog → download → install; airplane mode; "Never"; About texts |

## What the author asked for

1. At startup, at a configurable frequency, check GitHub for a newer release; show its changelog, ask
   whether to download it, then ask the user to install it.
2. The current version in the app, with the needed disclaimers and "fatto con il cuore da marcogn".
   `ui/about/AboutScreen` already shows the version and the Privacy, Signature, Development and
   Licenses sections: this sub-phase extends it.

## Decisions (author's answers, 2026-10-11)

- **`INTERNET` is added now, for the update check only.** This amends the product phase 1 rule
  "no `INTERNET`" (spec §2 "Principio guida", CLAUDE.md, README "Privacy", CI
  `forbidden-permissions`), ahead of phase 12, which was going to be the first build with it.
  Recorded as ADR 0006 in this sub-phase. Phase 12 then reuses the permission instead of adding it.
- **Pilot + port**: the code is copied from ThePatientGamerHelper, not designed here.
- Frequency Never / Every start / Daily / Weekly, **default Weekly**; "Check now" in About.
- "Not now" only closes the dialog; the next due check asks again.
- The dialog shows the Release body (the CHANGELOG headlines `release.yml` publishes) and a
  "Full changelog" link.

## A Updates and About (Sonnet)

**Prerequisites:** 10b merged; ThePatientGamerHelper Phase 9 merged and its device checks passed.

**Scope**
- `domain/update/` and `data/update/` copied from the pilot (`AppVersion`, `ReleaseInfo` parser, APK
  asset picker, body cleanup, `UpdateCheckPolicy`; `GitHubReleaseClient` on `HttpURLConnection`,
  `ApkDownloader` with SHA-256 against the asset `digest`, `ApkVerifier`, `ApkInstaller` on
  `PackageInstaller`, `UpdateManager`). Adaptations:
  - owner/repo `Marcogn/PdfToolkit` as `BuildConfig` fields;
  - preferences in a new `data/settings/UpdatePreferences.kt` on DataStore (one file per area, like
    the others);
  - the asset is picked by its `.apk` suffix (releases also carry `PdfToolkit-x.y.z-mapping.txt`);
  - the download goes to `cacheDir/updates/`, apart from `cacheDir/work/` (the save area, ADR 0003);
  - the update dialog is shown from the Home/viewer host without breaking the
    `lifecycleIsResumed()` navigation guard; installing never interrupts a save in progress
    (`SaveWorker` running → "Install after saving" or wait).
- Manifest: drop the `tools:node="remove"` on `INTERNET`, declare `INTERNET` and
  `REQUEST_INSTALL_PACKAGES`. `android-ci.yml`: `forbidden-permissions` emptied (or set to the
  permissions that must still never appear, if the author wants some). No new dependency.
- Startup check only; debug builds never check automatically.
- Settings: "Updates" with the frequency.
- About: "Check for updates" with its result; version + `VERSION_CODE`; "Fatto con ❤ da marcogn" /
  "Made with ❤ by marcogn"; GitHub link; **Privacy rewritten**: no accounts, documents stay on the
  phone, the only connection the app makes is the update check to GitHub (turn it off with "Never"),
  the scanner runs in Google Play services. The other sections stay.
- Docs: ADR 0006 (`docs/adr/0006-internet-for-updates.md`); spec §2 and the §7.3 line amended;
  README "Privacy"; CLAUDE.md rule "No `INTERNET`" rewritten; `docs/plan-v2.md` 12 no longer adds the
  permission; CHANGELOG "Network permission" entry (spec §7.3 asks for it in CHANGELOG and About).

**Done when**
- JVM tests of `domain/update` (copied, green); Robolectric tests for the dialog/ViewModel state
  mapping and the frequency preference.
- **Network audit**: with `INTERNET` declared, the libraries already in the APK may start using it
  (plan-v2 notes that ML Kit's `transport-backend-cct` declares `INTERNET` for Firelog telemetry, so
  far blocked by the removal). Check on the device which hosts the app contacts (e.g. a capture with
  PCAPdroid, or the network log) during scan and OCR; if anything other than GitHub shows up, stop and
  ask the author before closing (the About text must stay true).
- lint (0 errors), unit tests, `assembleDebug` and `assembleRelease` green; CI green; packaged release
  manifest checked by hand for the two new permissions and nothing else new.

**Device checks:** install an older signed release, start → dialog → download → "install unknown
apps" the first time → system installer → the app reopens on the new version; airplane mode at
startup (nothing shown, no crash); "Never" (no prompt, "Check now" works); open document and a save
in progress when the dialog appears; About texts in both languages; the network audit above.

**Out of scope:** silent install, pre-releases, background checks, any other network feature (phase
12 stays as planned).

## Open questions

- Release numbering: plan-v2 proposed **2.0** as "the first build with `INTERNET`"; with this
  sub-phase after 10b that would be the build with scan + OCR + updates. To confirm with the author.
- The result of the network audit (above), if ML Kit sends telemetry once `INTERNET` is allowed.
- Disclaimer and Privacy wording: proposed above, to confirm in the PR.

## Risks

- Same as the pilot's: GitHub API or asset changes (parser fails closed), `digest` missing (size +
  signature check), OEM differences in the unknown-apps screen.
- The "offline" promise changes for users who installed 1.x because of it: the CHANGELOG entry and
  About must say it plainly.
