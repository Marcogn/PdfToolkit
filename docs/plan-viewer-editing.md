# Editing in the viewer (V-a, V-b, V-c): record

Author's request, 2026-10-08, while testing U-b: with a document open, **page** operations
(highlight, draw, erase, fill and sign) happen in the viewer, on the page being read, from a tool bar;
**document** operations (organize, add, merge) stay in the edit screen. Option "C" of three discussed
(A: a two-section menu on the Edit button; B: two buttons). Design: ADR 0005. All three sub-phases are
done; code comments cite them as "plan V-a" etc.

## Author's answers (2026-10-08)
1. Fill and sign also in the viewer, not as a separate pane.
2. Save first: with unsaved viewer changes, "Pages" asks to save or discard before opening the edit
   screen; the two never share an unsaved session.
3. No edit hub: the edit screen opens straight on "Organize pages".

## The rule that keeps it simple
The viewer edits only what lies **on** the pages of the saved file: annotations, overlays, form
values. It never changes the page list, so it keeps rendering the file with `PdfRenderer` and draws
what is pending on top. One save engine (`SaveScheduler` → `SaveWorker` → `PdfSaver` → `PdfEditor`),
two save UIs.

## Sub-phases
| Sub-phase | Model | What it did | Status |
|---|---|---|---|
| V-a Viewer editing core | Opus | Page-fixed `ViewerEditSession`; pending edits drawn in `PdfViewport`; markup, freehand on any page of the viewport (document points), eraser; save from the viewer; back guard; ADR 0005 | Done, device checks passed 2026-10-08 |
| V-b Viewer tools UI | Sonnet | `ViewerToolBar` (bar / landscape rail): Highlight, Draw, Eraser, Fill, Pages; Style; "Pages" with save-first; FAB, hub and Annotate pane removed; Home Highlight/Draw open the viewer armed; markup applied on release with Undo | Done, device checks passed 2026-10-08 |
| V-c Fill and sign in the viewer | Opus | Overlays and form controls in the viewport (continuous and single page, any zoom); overlay gestures vs scroll; corner handle; signature shortcut; date; "make final" in the viewer's save dialog; Fill pane removed; Home "Fill and sign" opens the viewer armed | Done, device checks passed 2026-10-08 |

## Open points settled
- Single-page mode: paging is off while a brush is armed (two fingers still zoom and pan).
- Opening search puts the armed tool down.
- Password-protected PDFs are viewable, not editable: the tools are dimmed and a tap says why.
- A stroke belongs to the page under its first point, also when it crosses the gap (clipped there).
- With Fill armed, a long press grabs an overlay instead of selecting text.
- Memory: one `PdfRenderer`, the text readers for selection, and the form read only once Fill is
  needed; PdfBox writes only at save time, in the worker.
