# ADR 0006 — `INTERNET` for the update check only, ML Kit telemetry off

Date: 2026-10-12. Status: accepted. Amends the product phase 1 rule "no `INTERNET`" (spec §1, §7.3,
§10, §11.2) ahead of phase 12.

## Context

Product phase 1 promised a fully offline app: the manifest removed `INTERNET` with `tools:node="remove"`
and CI refused a packaged manifest that declared it. The author asked for in-app updates from GitHub
Releases in all six of their apps (`docs/plan-updates.md`), with the code ported from
ThePatientGamerHelper. That needs the network, so `INTERNET` comes in with sub-phase A instead of phase 12.

The APK already carries libraries that use the network once the permission is there. Since phase 9 and
10a the release build bundles ML Kit (Document Scanner, Text Recognition v2 bundled). ML Kit's terms say
the APIs "send metrics about the performance and utilization of the APIs in your app to Google"
([ML Kit terms](https://developers.google.com/ml-kit/terms)). For bundled features the data disclosure lists
per-installation identifiers, device and app information, performance metrics and error codes
([data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)). Neither page offers a way to
turn this off. The recognised text and the images never leave the device (on-device processing, same terms).

Until A this telemetry could not leave the phone, because the permission was missing.

## Decision

**`INTERNET` is declared, and the update check (and the APK download) is the only connection the app
makes.** `REQUEST_INSTALL_PACKAGES` comes with it for the system installer. CI no longer forbids
`INTERNET` (`forbidden-permissions` empty).

**ML Kit's telemetry is cut where it leaves the app.** ML Kit sends its events with `datatransport`
(`Transport.send`). The runtime looks up the event's backend (`cct`, from `transport-backend-cct`), which is
registered by a `backend:…CctBackendFactory` meta-data on the `TransportBackendDiscovery` service. The app
manifest removes that meta-data (`tools:node="remove"`). Checked in the bytecode of the versions in the APK
(`transport-runtime` 2.2.6, `mlkit:common` 18.11.0, `vision-common` 17.3.0,
`play-services-mlkit-text-recognition-common` 19.1.0):
- ML Kit only calls `Transport.send`, which schedules the event with an empty callback;
- `DefaultScheduler` finds no backend for `cct`, reports "Transport backend 'cct' is not registered" to that
  callback and stores nothing;
- `Uploader` deletes any stored batch for an unknown backend ("Unknown backend for %s, deleting event batch
  for it…"), so events queued by 1.x builds (never sent: no permission) are dropped, not sent.

`TelemetryOffTest` (Robolectric, merged manifest) fails if a backend is registered again, for example after a
library update.

**The scanner is outside this.** Google Play services runs the Document Scanner in its own process (plan 9).
What it sends is governed by Play services and attributed to it, not to the app; the app manifest cannot
change it. The About text says the scanner runs in Google Play services.

## Consequences

- About and README say: no accounts, documents and text recognition stay on the phone, the only connection
  is the update check on GitHub (off with "Never"), ML Kit's usage statistics are off, scanning is done by
  Google Play services.
- Users who chose 1.x because it had no network permission are told plainly in the CHANGELOG.
- Phase 12 (WebDAV) reuses the permission instead of adding it.
- The telemetry cut is **not a documented Google switch**. It relies on the runtime handling a missing
  backend, which it does by design (a backend can be absent), and another developer reports the same approach
  ([Box 3.4.5](https://newreleases.io/project/github/jegly/Box/release/3.4.5)). A new ML Kit version could log
  through another channel. So every ML Kit update needs `TelemetryOffTest` to pass and a traffic capture on a
  device (PCAPdroid during scan and OCR: only `api.github.com` and GitHub's download hosts).
- A `network-security-config` restricting TLS to GitHub hosts was considered as a second layer. It was not
  adopted: the official documentation does not specify how an empty set of trust anchors behaves, and it
  could not be verified without a device.

## Alternatives

- **Accept the telemetry and disclose it.** Rejected by the author: the only connection should be the update
  check.
- **Replace ML Kit with Tesseract** (no Google code). It would mean redoing 10a, with different quality and a
  larger APK. Not now.
- **No updates, no `INTERNET`.** Rejected: the author asked for in-app updates in all their apps.
