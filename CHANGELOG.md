# Changelog

All notable changes to PdfToolkit are documented in this file.
The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows the app's `versionName` in `app/build.gradle.kts`.

## [Unreleased]

- **Edit pages.** An **Edit** button in the viewer (it hides while you scroll down) opens the edit
  hub, and "Remove pages" and "Reorder pages" on Home pick a PDF and open straight on the tool.
  Remove pages by tapping them (press and hold one, then tap another for a range) with an undo
  from the snackbar; reorder by pressing and holding a page and dragging, or from the menu on each
  page (move to start/end, one step, rotate 90°). Undo and redo for every operation.
- **Save.** Save as a copy (default, suggests `<name>_modificato.pdf`) or overwrite the original when
  the file allows it, after a confirmation. The new file is built in a temporary location and only
  copied over the destination when complete, so a failure leaves the original untouched. The save
  keeps running if you leave the app, with a progress bar that doesn't block the screen; after a
  copy the snackbar offers **Open** and **Share**. Leaving with unsaved changes asks Save / Discard /
  Cancel.
- **Limits.** Password-protected PDFs can't be edited yet. A removed page can still be present in
  the file's data if a bookmark or link points to it: don't use it to hide sensitive content.
- **Permissions.** The app now declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` for
  the background save; still no `INTERNET`.
- **Complete viewer.** Besides continuous scrolling there is now a single-page mode (swipe to turn
  the page, each page zooms on its own); the choice is remembered and can also be set in Settings.
  The top bar shows "page X of N" and a menu with reading mode, go to page, share and document
  information. A draggable scrubber on the right edge jumps between pages, and a thumbnail bar
  opens from the top bar. Switching mode keeps the page you are on.
- **Recent files.** Opened PDFs appear on Home (up to 10, with a first-page preview and date) and
  in the "Recent files" screen of the side menu. Press and hold to remove one; files that are no
  longer readable are shown as unavailable. The last page read is saved per file and reopening
  resumes from it. Settings can clear the list and the preview cache.
- **Open from other apps.** PdfToolkit appears in "Open with" for PDFs in file managers, downloads
  and mail, and as a target of the share sheet.
- **Password-protected PDFs.** On Android 15 and later the app asks for the password; on earlier
  versions it explains that the system can't open them.
- **Clearer error screen.** When a file can't be opened the message says why, with a button back to
  Home (and one to remove a missing file from recents).
- **Backup.** The recents database is excluded from Android backup: it holds file addresses whose
  permission doesn't survive a restore.
- **Back without the shrinking effect.** System back (button or gesture) used Navigation's default
  animation, which shrinks the screen towards the centre; it now uses the same short slide and
  fade as the arrow in the top bar.
- **PDF viewer, first part.** "Open PDF" on Home opens the system file picker and shows the
  document in continuous scrolling: pinch zoom up to 5x, double tap to switch between fit width
  and 2.5x, pan with fling. Pages stay sharp at any zoom (only the visible area is re-rendered),
  nearby pages are prepared in advance, and rotating the screen keeps the reading position.
  Files that can't be opened show a short message instead of crashing.
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
