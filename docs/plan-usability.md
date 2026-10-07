# Usability review and plan

Review of the app as of 8b (branch of PR "8b Freehand complete", 2026-10-07), asked by the author:
superfluous taps, unclear dialogs and flows. This is a plan, nothing here is implemented yet.
Items marked **[SPEC]** change something `docs/spec.md` asks for, or a recorded decision in
`CLAUDE.md`, and need the author's yes before they are built.

## Method
- Every flow was walked through in the code (`ui/navigation`, `ui/home`, `ui/viewer`, `ui/edit`,
  `ui/fill`, `ui/annotate`, `ui/signatures`, strings), counting taps, swipes and dialogs.
  No device: the counts are from the code, the author's device test is the real check.
- Yardstick: Nielsen's ten usability heuristics
  ([NN/g](https://www.nngroup.com/articles/ten-usability-heuristics/)), mainly "user control and
  freedom", "recognition rather than recall", "flexibility and efficiency of use", "aesthetic and
  minimalist design"; and Material's guidance that "dialogs are purposefully interruptive, so they
  should be used sparingly" ([Material Design 2, Dialogs](https://m2.material.io/components/dialogs);
  the M3 page renders only with JavaScript and could not be read here).

## What already works and stays as it is
- Undo/redo for every edit, plus "Undo" in the snackbar after removing or adding pages.
- New pages highlighted and scrolled to after an addition.
- Background save with non-blocking progress; "Open / Share" after saving a copy.
- The save dialog remembers copy vs overwrite (DataStore) and explains both options.
- Back peels one layer at a time (selection → tool → pane → leave), and asks before losing changes.
- Tick and cross stay armed for several boxes in a row; a placed signature is selected right away.
- Search (bar, counter, reveal), scrubber, thumbnail bar, last page per file, recents.
- Error and protected-PDF messages say what happened and that the original is untouched.
- Markup tools "arm, select, apply" (2026-10-05 decision): a wrong selection costs nothing.

## Findings, by impact

### High impact

**U1. Edit tools always start on page 1 and have no way to jump.** `FillPane` and `AnnotatePane`
use `rememberPagerState { pages.size }` (initial page 0) and `Destination.Edit` carries no page.
Reading page 37, then Edit → Highlight, lands on page 1, and the only way back is 36 swipes: no
"go to page", no thumbnails, no scrubber. Signing usually happens on the last page, so "Fill and
sign" on a 10-page contract costs 9 swipes every time.
- Pass the viewer's current page in `Destination.Edit(page = …)` (page index of the main document;
  map it to the session's `PageItem` id at open) and start the panes' pagers there.
- Make the "Page X of N" chip of both panes tappable → the viewer's `GoToPageDialog` (reuse), and
  optionally the `ThumbnailBar` (already exists in the viewer).
- Insertion dialogs (`BlankPagesDialog`, `ImagesDialog`, `PickedPagesDialog`): when opened from the
  viewer, default to "After page N" instead of "At the end".

**U2. Pane opened from Home: back leaves the whole edit, so tools can't be combined.**
`openedOnTool` makes back from a pane call `requestExit()`. "Fill and sign" from Home, then wanting
to also highlight or remove a page: impossible without saving first. Proposal: back from a pane
goes to the hub when the session has changes (the hub shows the result and has Save); with no
changes it still leaves directly, as now.

**U3. "Save" from the unsaved-changes dialog doesn't leave.** Back → "Save" → save dialog →
"Save" → file picker → the copy is saved, and the user is still in the edit screen and must press
back again (the save flow has no "leave afterwards"). Proposal: when the save started from the
exit dialog, leave once the copy is saved (overwrite already navigates to the result), showing the
"PDF saved · Open · Share" snackbar on the screen below, or open the copy in the viewer.

**U4. Rotate is hidden inside "Remove pages"; remove and reorder are two separate panes.**
The hub grid is read-only (tap does nothing). Rotating a page means entering "Remove pages",
selecting it and finding the rotate icon there, or "Reorder pages" → the ⋮ menu of the page.
Two tools and one hidden action for what is one job, "organize pages". **[SPEC]** (spec §4.1 lists
Remove and Reorder as separate tools; §6.3/§6.4 describe their gestures.) Proposal:
- The hub grid becomes the page organizer: tap selects (contextual top bar: count, select all,
  Rotate, Delete, Move to start/end), a **drag handle** on each cell reorders immediately (no long
  press), long press keeps meaning "range anchor" as in §6.3.
- Home keeps one tool, "Organize pages" (or keeps both labels, both opening the hub, if the author
  prefers the spec's names); the hub tool bar loses Remove/Reorder and keeps Add pages, Insert
  images, Fill and sign, Highlight, Draw.
- Cost: medium (gesture split between handle and cell in `PagesGrid`; Reorderable supports a
  plain `draggableHandle`, to be checked against the pinned 3.1.0 API).

**U5. Highlighting from the viewer means selecting twice.** In the viewer a long press selects
text, but the only action is Copy; to highlight the same words the user must go Edit → Highlight,
swipe to the page (U1) and select again. **[SPEC]**-ish: the 2026-10-05 decision kept the viewer to
"selection + Copy" because annotating there would need a second save path. Proposal that keeps one
save path: a "Highlight" action next to Copy opens `Destination.Edit(tool = HIGHLIGHT, page = p,
selection = key + glyph range)`; the Annotate pane restores it (selection state is already saved
as page key + glyph range) and the user taps the colour and "Highlight". Depends on U1.

### Medium impact

**U6. Overwrite asks twice.** Save icon → choose "Overwrite" → "Save" → "Overwrite the original?"
→ "Overwrite". **[SPEC]** §6.7 asks for "conferma esplicita". Proposal: the save dialog itself
is the explicit confirmation when "Overwrite" is selected: the confirm button reads "Overwrite"
and the irreversible-warning line appears under the option (error colour). One dialog instead of
two; the choice stays remembered.

**U7. The Annotate pane's chrome eats the page.** With Pen armed the bottom area stacks a hint
banner, a colour row, a width row and the tool bar, plus the top bar: roughly 250 dp of a
~700 dp phone screen, while drawing is where room matters most. Proposal:
- the hint banner shows only the first times a tool is armed (or as a one-shot snackbar), not
  permanently; the selection "Cancel / Highlight" row keeps its place;
- colours and widths in one row (swatches + width dots, or a single "current style" button that
  opens them), shown when the armed tool is tapped again, as in common note-taking apps.
Same idea for the fill pane's hint row (smaller).

**U8. Signature: the picker sheet even when there is nothing to pick.** Tap "Signature" with an
empty archive opens a sheet that only says "no signatures" + "New signature": go straight to the
creation flow. With exactly one signature, arm it directly (a long press on "Signature", or a
"Change" chip in the hint, opens the sheet). **[SPEC]**-light: §6.5 says "si sceglie una firma
salvata (o se ne crea una al volo)"; with one signature the choice is implicit.

**U9. Home tools that pick twice show an explanation dialog every time.** "Add pages" / "Insert
images" from Home: explanation dialog → PDF picker → source dialog → second picker → position
dialog. The first dialog is useful once, then it is a tap to dismiss. Proposal: show it the first
time only (DataStore flag, like the signature note), or with a "don't show again" checkbox.

**U10. Misleading hint in Fill and sign.** With an overlay selected the hint says "Tieni premuto e
trascina per spostare", but a selected overlay moves with a plain drag (`OverlayGestures`: only
*unselected* ones need the long press). Text: "Trascina per spostare; con due dita ridimensiona e
ruota" (and the English one).

**U11. Resize and rotate need two fingers.** An overlay (signature, text) can only be scaled and
turned by pinching, hard with one hand or on a small tick. Proposal: one corner handle on the
selected overlay that scales (proportions locked, as now) and turns, like most PDF signers.
Geometry exists (`OverlayGeometry.transformed`, `UserTransform`); cost medium.

**U12. Date asks for confirmation it rarely needs.** "Date" → tap → dialog prefilled with today
→ OK. Proposal: place today's date directly and select it (Edit is one tap away in the tool bar).
Saves one tap per date; text stays with its dialog.

### Low impact / polish

**U13. The page indicator is not a control.** In the viewer "Page X of N" in the top bar could
open "Go to page" on tap (common in readers), saving the ⋮ menu step. Keep the menu entry.

**U14. Recents: swipe to remove.** Spec §4.1 says "swipe o long press"; only long press exists on
Home (the Recents screen has an ✕). Low priority: long press is enough for a horizontal row,
where a vertical swipe-to-dismiss would be awkward. Proposed to **leave as is** and align the spec.

**U15. Naming.** The pane opened by "Evidenzia" and "Disegna" is titled "Annota"; the hub/Home use
the tool names. Either title the pane after the armed family ("Evidenzia" / "Disegna") or rename
both entries to "Annota" with the tool preselected. Small, but it removes a "where am I?" moment.
"Colori dello sfondo" (dynamic colour in Settings) is ambiguous; "Colori dal sistema" or
"Colori dinamici" matches its summary better.

**U16. Save as an icon only.** The primary action of the edit screens is an icon among undo/redo;
the hub shows it only once something changed, the panes show it disabled. A text button "Salva"
(same visibility rule everywhere) is easier to find. Minor.

**U17. "Coming up" tools on Home.** Spec §4.1 wants them visible; as features land the section
shrinks by itself (Highlight and Draw already left it). No change proposed.

### Considered and not proposed
- **Highlight by dragging over text** (no long press, no Apply), as some readers do: it would
  conflict with one-finger pan, and the "arm, select, apply" decision of 7b was made for good
  reasons. Revisit only if the author finds selection slow on the device.
- **Dropping the "Photos / Files" source dialog** for images: the photo picker and the document
  picker show different things (gallery vs any file); the choice is real.
- **Skipping the save dialog** (save straight with the last choice): the dialog also carries the
  "make final" options, which change the result; keep it.

## Flows before / after (taps from the code; swipes in brackets)

| Flow | Now | After |
|---|---|---|
| Sign page 10 of 10 from the viewer, one saved signature, save as copy | Edit, Fill and sign, (9 swipes), Signature, pick, place, Done, Save, Save, picker Save = 9 + 9 swipes | Edit, Fill and sign (on page 10), Signature (armed), place, Done, Save, Save, picker Save = 8, no swipes (U1, U8) |
| Highlight a sentence read on page 37 | long press, Copy is the only option; then Edit, Highlight, (36 swipes), long press, handles, Highlight, Save… | long press, handles, Highlight → pane on page 37 with the selection, Highlight, Save… (U1, U5) |
| Overwrite after an edit | Save, (Overwrite), Save, Overwrite = 3–4 | Save, (Overwrite), Overwrite = 2–3 (U6) |
| Leave with changes, keep a copy | back, Save, Save, picker Save, back = 5 | back, Save, Save, picker Save = 4 (U3) |
| Remove page 5 and rotate page 2 from the viewer | Edit, Remove pages, 5, Delete, 2, Rotate (hidden there), back = 7 | Edit, 5, Delete, 2, Rotate = 5, and rotate is visible (U4) |

## Proposed order
As a development phase between 8b and 9 (product phase 2 order unchanged otherwise), in two
sub-phases so each fits one session and one device check:

| Sub-phase | Model | Items | Check on the device |
|---|---|---|---|
| U-a Flows | Sonnet | U1, U2, U3, U5 (after the author's yes), U13 | Edit from page 37 opens on 37 in Fill/Annotate; page chip jumps; Home tool → change → back lands on hub; exit-dialog Save leaves; viewer selection → Highlight |
| U-b Screens | Sonnet | U4 (after yes), U6 (after yes), U7, U8, U9, U10, U11, U12, U15, U16 | Organize pages in the hub (select, rotate, delete, drag handle); overwrite in one dialog; drawing with the compact bar; signature with 0/1/many saved; one-hand resize |

No Opus needed: nothing here touches PDF geometry or the writer, except U11's handle, which
reuses `OverlayGeometry`. U5 depends on U1 (page in the route).

## Questions for the author
1. U4: merge Remove and Reorder into the hub ("Organize pages")? Keep the two names on Home?
2. U5: "Highlight" from the viewer selection, going through the edit screen (one save path)?
3. U6: is the save dialog with an "Overwrite" button enough as the explicit confirmation of §6.7?
4. U8: arm the only saved signature directly, without the sheet?
5. Insert this as a phase before 9, or after the product phase 2 features?
