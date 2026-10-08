# Editing in the viewer: plan (V-a, V-b, V-c)

Author's request, 2026-10-08, while testing U-b: with a document open, page operations (highlight,
draw, fill and sign) happen **in the viewer**, on the page being read, from a tool bar at the
bottom; document operations (organize pages, add, merge) stay in the edit screen. Option "C" of the
three discussed (A: two-section menu on the Edit button; B: two buttons; C: tools in the viewer).

## Author's answers (2026-10-08)
1. **Fill and sign also in the viewer**, not as a separate pane.
2. **Save first**: with unsaved changes made in the viewer, "Pages" asks to save (or discard) before
   opening the edit screen; the two never share an unsaved session.
3. **Remove the edit hub**: the edit screen opens straight on "Organize pages".

## What changes for the user
- The viewer's bottom bar: **Highlight** (markup tools: highlight, underline, strikeout), **Draw**
  (pen, marker), **Eraser**, **Fill and sign** (text, date, tick, cross, signature), **Pages**.
  The Edit button and the hub go away. A single tap hides this bar with the top bar (U20).
- Tools act on the page under the finger, in continuous and single-page mode, at any zoom.
- With changes pending: "Save" in the top bar, Undo/Redo in the tool bar, and back asks
  Save / Discard / Cancel. Saving uses the same dialog (copy or overwrite, make final).
- "Pages" opens "Organize pages"; if the viewer has unsaved changes it first asks to save or discard.
- Home: Highlight, Draw and Fill and sign open the **viewer** with that tool armed; Organize pages and
  Merge open the edit screen as now.

## Consequences on earlier decisions
- 2026-10-05 (7b) "the viewer only gets selection + Copy, one save path" and U5 "Highlight goes through
  the edit screen": replaced. The **save engine** stays one (`SaveScheduler` → `SaveWorker` →
  `PdfSaver` → `PdfEditor`); what the viewer gains is its own save UI on top of it.
- ADR 0003 (edit panes in one `EditScreen`): amended by a new **ADR 0005 "Editing in the viewer"**.
- Spec §4.2 (viewer) and §4.3 (hub): deviation noted in the spec.
- The Annotate and Fill panes of `EditScreen` (and U-b's work on them: strip, rail, Style, hints,
  corner handle, date, signature shortcut) **move** to the viewer rather than being thrown away: the
  bars, `ToolStrip`, `StyleButton`, `TransientHint`, overlay geometry and gestures are reused.

## The model: one rule that keeps it simple
The viewer edits only what lies **on** the pages of the file as it is saved: annotations, overlays,
form values. It never changes the page list (order, rotation, removal, added pages): that is "Pages",
which works on the saved file (answer 2). So the viewer's session is an `EditSession` whose pages are
the main document's own, unchanged; the viewer can keep rendering the file with `PdfRenderer` and
only has to draw what is pending on top.

## Sub-phases

| Sub-phase | Model | Scope | Done when | Check on the device |
|---|---|---|---|---|
| V-a Viewer editing core | **Opus** | Viewer-scoped edit session (annotations + fill content; pages fixed), undo/redo, pending edits drawn in `PdfViewport` (existing minus removed, plus added); markup apply on the viewer selection; freehand in a multi-page viewport (stroke owned by the page under its first point, mapper per page, draw vs scroll arbitration as in 8a); eraser by page hit; save from the viewer (save dialog, overwrite reopens, copy → Open/Share), unsaved-changes guard, rotation/process death; ADR 0005 | Unit tests (page attribution, coordinates at zoom and on turned pages, session save/restore, guard); handoff written | Highlight and draw on several pages in continuous mode at different zooms, save copy and overwrite, other reader; undo/redo; back with changes; rotate the phone mid-edit |
| V-b Viewer tools UI | Sonnet | Bottom tool bar / landscape rail in the viewer (reusing `ToolStrip`, `StyleButton`, `TransientHint`), immersive hides it, "Save" in the top bar, "Pages" with save-first dialog, Edit FAB and hub removed (`EditScreen` opens on Organize, back leaves with the exit dialog), Home tools Highlight/Draw open the viewer armed (`Destination.Viewer(uri, tool)`), Annotate pane removed from `EditScreen` | Plan V-b | Every tool from the bar; Home → Highlight / Draw; Pages with and without pending changes; landscape rail; full-screen tap with a tool armed |
| V-c Fill and sign in the viewer | **Opus** | Overlays and form controls in the continuous viewport (controls aligned to widget rectangles at any zoom, tiling, scrolling), overlay gestures vs scroll, corner handle, signature shortcut, date, flatten on save; Fill pane removed from `EditScreen`; Home "Fill and sign" opens the viewer armed | Unit tests on overlay/control placement across pages and zooms; handoff not needed (last) | Fill a multi-page form while scrolling; place, move, resize, turn a signature; save, other reader; landscape |

Order: U-b (device checks, merge) → V-a → V-b → V-c → 9 Scan → … (the author started V-a right after
U-b, which settles "before 9"). **V-a is done (2026-10-08, device checks passed)**; design in ADR 0005, handoff in
`CLAUDE.md`. **V-b is done (2026-10-08, device checks pending)**: the Fill pane stays in `EditScreen` until V-c, so the
bar's "Fill" button opens it (with save-first) rather than a viewer-side pane.

## Open points settled in V-a (2026-10-08)
- Single-page mode: the pager turns pages with a one-finger swipe; with Draw armed paging is off, as
  in the 8a pane (two fingers still zoom and pan). (Done.)
- Search while editing: search stays available; opening it disarms the tool. (Done.)
- Password-protected PDFs are viewable but not editable (as now): the tool bar shows the tools
  disabled with a message. (V-a: the pen button shows the message; V-b's bar shows them disabled.)
- A freehand stroke belongs to the page under its first point, also when it runs across the gap onto
  the next page (clipped there, as in other readers).
- Memory: the viewer keeps one `PdfRenderer`; the session adds the text readers for selection (already
  there) and PdfBox only at save time, in the worker.
