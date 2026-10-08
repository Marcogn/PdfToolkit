# ADR 0005 — Editing in the viewer: a page-fixed session drawn over the saved file

Date: 2026-10-08. Status: accepted. Amends ADR 0003 ("one edit screen on one session").

## Context

The author asked that page operations (highlight, draw, erase, fill and sign) happen in the viewer,
on the page being read, and that document operations (organize, add, merge) stay in the edit
screen (`docs/plan-viewer-editing.md`, option "C", answers of 2026-10-08). Until now every edit went
through `EditScreen` and its `EditViewModel`, with one page at a time in a pager; the viewer only
selected and copied text. The viewer renders the file with `PdfRenderer` (ADR 0001), continuous or
single page, tiled, at any zoom.

## Decision

**The viewer has its own edit session, and it never changes the page list.** `ViewerEditSession`
wraps an `EditSession` over the document's own pages, in order, unturned, with the usual ids
`p<index>`: annotations (and, from V-c, fill content) are bound to them, undo and redo are the
session's. Since the pages are the file's, the viewer keeps rendering the file as it is saved and only
draws what is pending on top: `AnnotationLayer.of(document, edits)` is the file's annotations minus
the removed ones plus the added ones, drawn by `PdfViewport` (clipped to each page). Organizing pages
stays in `EditScreen`, which works on the saved file; the two sessions are never open on the same
unsaved changes (V-b asks to save first; until then the Edit button hides while the viewer holds
changes).

**Editing waits for the annotations.** The page tools need every page's user space (`PageBox`), which
`AnnotationReader` reads with the annotations; the tools are available once the read succeeds and has a
box per rendered page (`EditAvailability.READY`). A password-protected document stays read-only
(PdfBox gets no password when saving, ADR 0003 / spec §14), and so does one whose annotations can't be
read: erasing by reference would otherwise be unsafe.

**Freehand strokes in document points.** The viewport shows many pages, so the ink layer can't use one
page's display points as stroke space (8a). Strokes are made in *document points*: the continuous
layout at one unit per page point (`DocumentStrokes`). Every page is only translated relative to it,
so brush sizes stay points of the page and the brush keeps its on-screen orientation, as in 8a. The
page a stroke belongs to is the page under its **first point** (`DocumentLayout.pageAt`: in a gap, the
page above), read from the finished stroke itself; then the stroke is translated to that page's display
points and goes to user space through `PdfPageSpace.displayToUser` exactly as before
(`FreehandGeometry`). Nothing per stroke is remembered between the start and the hand-over, which the
ink library may batch (several finished strokes in one callback). The pointer → stroke matrix is the
inverse of document → screen, set as each stroke starts (`detectFreehandGestures` now passes the down
position). Draw vs scroll is 8a's arbitration unchanged: the gesture detectors move from the canvas to
the viewport's box so the ink layer is a child and the freehand detector sees events in the initial
pass first. In single-page mode only the settled page has an ink layer and the pager doesn't turn while
a brush is armed.

**One save engine, two save UIs.** `SaveRunner` (enqueue in `SaveScheduler`, observe, map to
`SaveUiState`) is shared by `EditViewModel` and `ViewerViewModel`; the request is the same
`SaveRequest` (pages = the document's own, unchanged). The viewer reuses the save dialog (copy or
overwrite, "make final" for ink), the exit dialog (Save / Discard / Cancel) and the "Saved" snackbar.
After an **overwrite** the viewer is replaced by a new one on the same URI (`popUpTo<Home>`), since it
holds the old file open; after a copy the viewer stays on the original with the session marked saved,
and "Open" shows the copy.

**State.** The edits go into the viewer entry's `SavedStateHandle` (`viewerFill`,
`viewerAnnotations`): rotation and process death keep them, not the undo history. Which session was
last saved is kept in memory only: after a process death a session saved as a copy counts as unsaved
again (the exit asks once more, never the opposite).

## Consequences

- A copy is written from the original plus the session: after "save as copy" the viewer still shows the
  original with the edits drawn on top until the reader opens the copy; further edits are saved again
  on top of the original, so a second copy contains everything.
- Freehand coordinates are floats in document points: on very long documents (beyond about a thousand
  A4 pages) their resolution drops to about 0.1 pt, the same order as the viewer's own scroll offset at
  maximum zoom. Not visible in practice.
- The viewer reads the annotations of every document it opens (already true since 7a): editing becomes
  available about when the first pages show.
- The edit screen's Annotate pane still exists until V-b removes it; it doesn't share the viewer's
  session.
