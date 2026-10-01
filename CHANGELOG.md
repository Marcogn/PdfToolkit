# Changelog

All notable changes to PdfToolkit are documented in this file.
The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows the app's `versionName` in `app/build.gradle.kts`.

## [Unreleased]

- **App skeleton.** Home with the "Open PDF" card, the Recents section and the tool grid; upcoming
  tools are visible with a "Soon" badge. Side menu with Home, Recent files, My signatures,
  Settings and About. Viewer and tools open a placeholder screen for now.
- **Theme and language.** Light, dark or system theme, wallpaper colours (Android 12+) that can be
  turned on in Settings, Italian and English with a per-app choice.
- **No network.** The app does not declare the `INTERNET` permission; CI checks that no
  dependency brings it back.
- **Build and release.** CI with lint, tests and debug APK; manual workflows for the signed
  release APK and for publishing releases, as in the reference projects.
- **Up-to-date toolchain.** Gradle 9.8, Android Gradle Plugin 9.4, Kotlin 2.4, target Android 17
  (API 37) and the latest stable libraries.
