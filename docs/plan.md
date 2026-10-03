# PdfToolkit plan and alignment with the reference projects

Written in phase 0 (2026-10-01) after reading the two reference projects locally:
[ThePatientGamerHelper](https://github.com/Marcogn/ThePatientGamerHelper) (TPGH) and
[KartLog](https://github.com/Marcogn/KartLog). KartLog is itself aligned with TPGH and is the more
recent of the two: where they differ, the table says which one was followed and why. Where the
specification (`docs/spec.md`) explicitly asks for something different, the specification wins
(spec §0.4).

## What comes from where

| Item | Reference | PdfToolkit |
|---|---|---|
| Gradle root | `settings.gradle.kts` with `FAIL_ON_PROJECT_REPOS`, `google()` + `mavenCentral()`; root `build.gradle.kts` with plugins `apply false` only (same in both) | Same, `rootProject.name = "PdfToolkit"`. No `kotlin-android` plugin: AGP 9 compiles Kotlin itself (see "Dependency upgrade") |
| Gradle wrapper | Gradle 8.13 | Gradle 9.8.0, with `distributionSha256Sum` so the wrapper verifies the download |
| `gradle.properties` | Same in both | Copied |
| Version catalog | `gradle/libs.versions.toml` (same structure in both) | Same structure, latest stable versions (see "Dependency upgrade"). Libraries used only by the references are left out (WorkManager, Credential Manager, Play Services, Coil, Palette). Room became a dependency in phase 1b (`RecentDocument`) |
| Toolchain | `compileSdk`/`targetSdk` 36, `minSdk` 26, Java/Kotlin 17 | `compileSdk`/`targetSdk` 37, the highest API level supported by AGP 9.4 (spec §3.3). `minSdk` 26 (spec: as the reference, not below 26). Java/Kotlin bytecode 17 |
| Signing | `signingConfigs.release` from the environment variables `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; release left unsigned when missing | Same, same names. Dedicated keystore for this app, like TPGH (RSA 2048, 10,000 days, alias = app name), never committed |
| Build types | `release` with `isMinifyEnabled = false` | R8 and resource shrinking on since phase 6; PdfBox-Android ships its own consumer rules, `proguard-rules.pro` only silences the optional JPEG 2000 decoder |
| Packages and layering | `com.marcogn.<app>` with `ui/<feature>/`, `data/`, `domain/`, `di/` | `com.marcogn.pdftoolkit`, same scheme plus `pdf/` (spec §12). Packages are created when code that uses them arrives |
| `applicationId` | `com.marcogn.thepatientgamerhelper`, `com.marcogn.kartlog` | `com.marcogn.pdftoolkit` (spec §2) |
| Hilt | `@HiltAndroidApp` on the Application, `@AndroidEntryPoint` on `MainActivity` | Same. `hiltViewModel()` now comes from `androidx.hilt:hilt-lifecycle-viewmodel-compose` |
| Navigation | `Destination` sealed interface with `@Serializable` routes, `ModalNavigationDrawer` with `drawerState` at NavGraph level, `lifecycleIsResumed()` guard on every navigation, Home from the drawer with `popUpTo<Home>{inclusive}` (KartLog fix) | Same. **Differs on purpose (spec §9)**: 250 ms enter / 200 ms exit transitions with a short slide and fade, instead of a 300 ms full-width slide |
| Drawer | TPGH: Material `ModalDrawerSheet` with `NavigationDrawerItem`. KartLog: custom graphic drawer | Material like TPGH, with the current entry highlighted. Entries from spec §4: Home, Recent files, My signatures, Settings, About |
| Home | KartLog: hamburger on the left, large square tiles; TPGH: simple chooser | KartLog's structure with its own look: "Open PDF" card, Recents, grid of square buttons (`GridCells.Adaptive(100.dp)`, three columns on a 360 dp phone), product phase 2 tools in a separate "Coming up" section with a "Soon" badge |
| Theme | `ThemeMode` (System/Light/Dark) on Preferences DataStore, `ThemeViewModel`, `MainActivity` picks dark/light | Same. Plus dynamic colour, off by default and switchable in Settings (spec §9), stored in the same DataStore |
| Palette | TPGH: Compose's default purple palette + dynamic colour. KartLog: custom bright palette | Own petrol blue palette with amber accent (spec §9, **[ASSUMPTION]**, approved by the author), isolated in `ui/theme/Color.kt` |
| Language | `values/` Italian + `values-en/`, `locales_config.xml`, `AppLanguage` + `setApplicationLocales()`, `autoStoreLocales` in the manifest, `MainActivity : AppCompatActivity`, AppCompat XML theme | Same |
| Icon | Adaptive icon with a raster foreground | Vector adaptive icon (sheet with folded corner and a pen) with `monochrome` for Android 13+ (spec §9 asks for it, the references don't have it) |
| CI | `android-ci.yml`: lint, tests, assembleDebug on push/PR to `main`; KartLog also uploads the debug APK | Copied from KartLog (with the debug APK upload, required by phase 0 acceptance). Plus a check that the packaged manifest doesn't contain `INTERNET` |
| Build APK | `build-apk.yml`: manual, keystore from a base64 secret, `assembleRelease` | Copied from KartLog (without TPGH's `signingReport` step, which was there for Google OAuth) |
| Release | `release.yml`: manual with a `version` input, cuts CHANGELOG and version, signed build, publishes the release, then commits the bump to `main` | Copied from KartLog, only the asset name (`PdfToolkit-x.y.z.apk`) and title changed |
| Actions cleanup | KartLog only: manual `cleanup-runs.yml` | Copied, translated to English |
| Dependabot | TPGH only; KartLog removed it on purpose | Not included, like KartLog |
| `.gitignore` | Standard Android + keystores and credentials | Like KartLog, without its seed/Python entries |
| Robolectric | `sdk=34` in `robolectric.properties` (CI runs on JDK 17) | Same |
| Docs language | TPGH in English, KartLog in Italian | **English** for all documentation and code comments (author's decision). App UI stays Italian with English translation |
| `CLAUDE.md` | Project memory updated every session | Same mechanism, content from spec §11.2 |
| `SECURITY.md`, `LICENSE` | Private reports through GitHub Security Advisories; MIT | Same mechanism, rewritten scope; MIT like the references |
| Versioning | KartLog started at `versionCode 1` / `0.1.0` | Same starting point |

## Not from the references

- No `INTERNET` permission (spec §1): the manifest has `tools:node="remove"` and CI checks the
  packaged manifest. Both references declare it.
- Backup rules: `data_extraction_rules.xml` and `backup_rules.xml` already exclude
  `filesDir/signatures/` (spec §6.5). They get verified properly in phase 4, when signatures exist.

## Dependency upgrade (2026-10-01)

The reference projects pin versions from late 2024. At the author's request PdfToolkit moves to
the latest stable versions, and the same upgrade will later be applied to TPGH and KartLog, so the
steps are written to be repeatable.

Versions were read from the `maven-metadata.xml` files on Google Maven, Maven Central and the
Gradle Plugin Portal, keeping only stable versions (no alpha, beta, RC).

| Component | Before (references) | Now |
|---|---|---|
| Gradle | 8.13 | 9.8.0 |
| Android Gradle Plugin | 8.13.0 | 9.4.1 |
| Kotlin | 2.0.21 | 2.4.20 |
| KSP | 2.0.21-1.0.28 | 2.3.12 |
| Hilt | 2.52 | 2.60.1 |
| `androidx.hilt` (Compose integration) | `hilt-navigation-compose` 1.2.0 | `hilt-lifecycle-viewmodel-compose` 1.4.0 |
| Compose BOM | 2024.12.01 | 2026.09.00 |
| Navigation Compose | 2.8.5 | 2.10.2 |
| Lifecycle | 2.8.7 | 2.11.0 |
| Activity Compose | 1.9.3 | 1.13.0 |
| Core KTX | 1.13.1 | 1.19.1 |
| Room | 2.6.1 | 2.8.5 |
| DataStore | 1.1.1 | 1.2.1 |
| AppCompat | 1.7.0 | 1.8.0 |
| kotlinx.serialization | 1.7.3 | 1.11.0 |
| kotlinx.coroutines | 1.9.0 | 1.11.0 |
| Robolectric | 4.16.1 | 4.17 |
| Espresso | 3.6.1 | 3.7.0 |
| `compileSdk` / `targetSdk` | 36 | 37 |

### Facts that drive the upgrade

From the official release notes (checked on 2026-10-01):

- **AGP 9.4** needs Gradle 9.6.0 or later and JDK 17, and supports up to API level 37
  ([AGP release notes](https://developer.android.com/build/releases/gradle-plugin)).
- **AGP 9.0** turns on built-in Kotlin and the new DSL by default. The
  `org.jetbrains.kotlin.android` plugin must be removed (it is not compatible with the new DSL),
  and `kotlinOptions { }` becomes `kotlin { compilerOptions { } }`. AGP brings KGP 2.2.10 and
  KSP 2.2.10-2.0.2 as minimums; a higher version declared in the build wins
  ([AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)).
- **Hilt 2.59** added AGP 9 support to the Hilt Gradle plugin and made AGP 9 a requirement; 2.59.1
  set the minimum AGP to 9.0.0 ([Dagger releases](https://github.com/google/dagger/releases)).
  So a Hilt upgrade past 2.58 and the AGP 9 upgrade must happen together.

### Steps (to repeat on TPGH and KartLog)

1. Upgrade the wrapper while the project still configures with the old AGP:
   `./gradlew wrapper --gradle-version 9.8.0 --distribution-type bin --gradle-distribution-sha256-sum <sha>`.
   Take the checksum from `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip.sha256`
   (use `curl -L`: without it you get a redirect page instead of the checksum). After the AGP
   upgrade, run the same command again so the wrapper jar and scripts come from Gradle 9.8.
2. In the version catalog: update the versions, remove the `kotlin-android` plugin entry.
3. In the root `build.gradle.kts`: remove `alias(libs.plugins.kotlin.android) apply false`. The
   compose and serialization plugins at 2.4.20 put KGP 2.4.20 on the classpath; check with
   `./gradlew buildEnvironment` that `kotlin-gradle-plugin:2.2.10 -> 2.4.20` and
   `symbol-processing-gradle-plugin:2.2.10-2.0.2 -> 2.3.12`.
4. In `app/build.gradle.kts`: remove `alias(libs.plugins.kotlin.android)`, replace
   `kotlinOptions { jvmTarget = "17" }` with `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`
   (`import org.jetbrains.kotlin.gradle.dsl.JvmTarget`), raise `compileSdk`/`targetSdk` to 37.
   Keep `buildConfig = true` where `BuildConfig` is used. TPGH also uses `resValue(...)`: with
   AGP 9 `resValues` is off by default and needs `buildFeatures { resValues = true }` (from the
   AGP 9.0 notes, not tried here because PdfToolkit doesn't use it).
5. Replace `androidx.hilt:hilt-navigation-compose` with
   `androidx.hilt:hilt-lifecycle-viewmodel-compose` and change the import of `hiltViewModel` to
   `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel` (the old one is deprecated).
6. In Compose UI tests, `androidx.compose.ui.test.junit4.createComposeRule` is deprecated: use
   `androidx.compose.ui.test.junit4.v2.createComposeRule`. It uses `StandardTestDispatcher`, so
   tests that relied on immediate execution may need explicit synchronisation.
7. New lint error with Compose 2026.09: reading resources through `LocalContext.current` inside
   a composable (`LocalContextGetResourceValueCall`). Use `LocalResources.current`.
8. Run lint, unit tests, `assembleDebug` and `assembleRelease`. With `targetSdk` 37, check the
   Android 17 behaviour changes on a device.

### Known leftovers

- Gradle prints a deprecation for `Configuration.setVisible(boolean)` (removed in Gradle 11). It
  comes from AGP itself (`BasePlugin.createAndroidJdkImageConfiguration`), not from this project.
- Lint `ObsoleteSdkInt`: with `minSdk` 26 the icon could live in `mipmap-anydpi/` instead of
  `mipmap-anydpi-v26/`, but after moving it aapt2 no longer found `@mipmap/ic_launcher`. Left as
  in the references.
- The manifest merger warns that `tools:node="remove"` on `INTERNET` has nothing to remove.
  Expected: it is a safeguard.

## Phases

Each phase closes with a green debug build, green tests, and README, CLAUDE.md and CHANGELOG up to
date. What each phase contains is in spec §13; here only operational notes.

| Phase | Content | Notes |
|---|---|---|
| 0 | Skeleton: Gradle, Hilt, theme, languages, navigation, drawer, Home, signing, CI, docs, ADR 0001–0002 | Done |
| 1 | Viewer, opening from SAF and intents, recents (Room), passwords | 1a done (render core, zoom/pan, continuous mode); 1b done (single page, scrubber, thumbnails, intents, Room recents, passwords, settings). `app/schemas/` committed as in KartLog |
| 2 | `EditSession` with undo/redo, edit hub, removal, reordering, rotation, saving | PdfBox-Android comes in (ADR 0002) with `PDFBoxResourceLoader.init`. Reasoned choice between WorkManager and a foreground service for saving (spec §6.7). Evaluate Reorderable (licence and compatibility with the Compose version in use) |
| 3 | Adding pages (PDF, blank, images) and Merge PDFs | Unit tests on page sizes |
| 4 | Fill and sign, signature archive | Noto Sans font (OFL) bundled; verify the backup rules already in place |
| 5 | Text search | `PageCoordinateMapper` with tests on rotated pages |
| 6 | Polish | Animations (including the system "remove animations" setting), baseline profile, accessibility, R8, `CloudTarget` interface (spec §7.3). Done except the baseline profile (needs a device) |

## Decisions taken with the author (2026-10-01)

1. **Documentation language**: English, always. Code comments too.
2. **Specification**: renamed to `docs/spec.md`. The repository tree in spec §12 was updated to
   match; nothing else in the spec changed.
3. **Signing**: same setup as the references, with a dedicated keystore generated for this app
   and handed to the author. The repository needs the secrets `RELEASE_KEYSTORE_BASE64`,
   `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` and
   `RELEASE_PUSH_TOKEN`.
4. **Dependencies**: upgraded to the latest stable versions (section above).
5. **Palette**: approved after a test on a device.

Still open, no impact on code: the spec gives `pdf-toolkit` as the repository slug, while the
repository is `PdfToolkit`.
