# ADR 0003 — Background save with WorkManager; one edit screen on one session

Date: 2026-10-01. Status: accepted.

## Context

Phase 2 writes PDFs (spec §6.7): saving a large document must survive the app going to the
background, and the edit hub and the page tools must work on the same in-memory `EditSession`.
The spec leaves the choice between WorkManager and a foreground service to the implementer.

## Decision

**Saving: WorkManager** (`androidx.work:work-runtime-ktx` 2.12.0), one expedited
`CoroutineWorker` (`SaveWorker`) per save, `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`.
- It is the platform's recommended API for work that must finish even if the app is left, and it
  survives process death (the system re-runs the worker). A hand-written foreground service would
  need its own restart and lifecycle handling.
- Before Android 12 expedited work runs as a foreground service: `getForegroundInfo()` provides
  the notification (type `dataSync`, declared in the manifest with the two
  `FOREGROUND_SERVICE*` permissions). `POST_NOTIFICATIONS` is not requested: without it the
  notification is simply not shown on Android 13+, the work still runs.
- The pages of a long document don't fit WorkManager's 10 KB input limit, so the request is a JSON
  file in `cacheDir/work/` and the worker gets its path. The worker gets `PdfSaver` through a Hilt
  `@EntryPoint`, which avoids the extra `hilt-work` dependency and a custom `WorkerFactory`.
- Writing always goes source copy → result in `cacheDir/work/` → verified (re-opened, page count
  checked) → copied over the destination with mode `"wt"`. The original is touched only by that
  last copy.

**One `EditScreen`, panes instead of a nested graph.** The hub, "Remove" and "Reorder" are panes
of a single destination `Destination.Edit(uri, tool)`. They share one `EditViewModel` (session,
renderer for thumbnails, save state) without a nested navigation graph, whose arguments can't be
supplied when a child destination is opened directly (Home → tool). System back goes pane → hub →
exit, asking about unsaved changes.

**Reordering: Reorderable** (`sh.calvin.reorderable:reorderable` 3.1.0, Apache 2.0, checked on
Maven Central; it builds against Compose 1.7 and the project uses a newer BOM). A whole drag is one
undo step: the grid drags a local copy of the list and commits one `move` on release.

## Consequences

- After an **overwrite** the screens that held the old file open (viewer below the edit screen,
  the edit screen's own renderer) are dropped: the app opens the result on top of Home.
- A save in progress is not cancellable from the UI (the worker can't be stopped half-way without
  leaving the destination half-written, and the copy step is short).
- The source is read twice when saving (to the cache, then by PdfBox): disk space of about twice
  the document is needed in `cacheDir`.
- Known limit: PdfBox rearranges the open document, so pages removed by the user can still be
  present as unreachable-from-the-page-tree objects when something else (a bookmark, a link) points
  at them. Don't rely on "remove page" to redact sensitive content.

## Sources

- WorkManager, long-running and expedited work: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- Foreground service types (`dataSync`): https://developer.android.com/develop/background-work/services/fgs/service-types
- Reorderable: https://github.com/Calvin-LL/Reorderable (licence read from the POM on Maven Central)
