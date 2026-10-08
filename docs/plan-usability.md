# Usability review (U-a, U-b): record

Review of the app as of 8b (2026-10-07), asked by the author: superfluous taps, unclear dialogs and
flows. Every flow was walked through in the code, counting taps and dialogs, against Nielsen's
heuristics ([NN/g](https://www.nngroup.com/articles/ten-usability-heuristics/)) and Material's advice to
use dialogs sparingly ([Material 2, Dialogs](https://m2.material.io/components/dialogs)). The author
approved the findings on 2026-10-07; **U-a** and **U-b** are done (device checks passed 2026-10-08).
Code comments cite the items as "plan Un". Later, V-a…V-c (`docs/plan-viewer-editing.md`) moved the
page tools into the viewer, which replaced some of what is below (marked "→ V").

| Item | Problem | What was done |
|---|---|---|
| U1 | Edit tools started on page 1, no way to jump | Edit opened on the page being read; page chip → "Go to page"; insertion defaults to "after the page read" (still true in Organize) → V: the tools are on the page itself |
| U2 | A tool opened from Home left the whole edit on back | Back went to the hub when there were changes → V: no hub |
| U3 | "Save" in the exit dialog didn't leave | Leaves once the copy is written, with a Toast |
| U4 | Rotate hidden in "Remove"; remove and reorder separate | One "Organize pages" tool: select, rotate, delete, drag handle, Add (spec §4.1 deviation) |
| U5 | Highlighting from the viewer meant selecting twice | Highlight next to Copy → V: marks in place |
| U6 | Overwrite asked twice | The save dialog's "Overwrite" button is the confirmation |
| U7 | Annotate chrome took the page | Transient hints over the page, one Style button |
| U8 | Signature picker even with 0 or 1 signatures | 0 → create, 1 → armed, many → picker; long press → picker; reminder in "My signatures" |
| U9 | Home tools that pick twice explained it every time | Gone with U4 |
| U10 | Misleading hint in Fill and sign | Fixed text |
| U11 | Resize and rotate needed two fingers | Corner handle (`UserTransform.about`) |
| U12 | Date asked for confirmation | Placed directly, selected, "Edit" one tap away |
| U13 | Page indicator not a control | "Page X of N" opens "Go to page" |
| U14 | Recents: no swipe to remove (spec §4.1) | Left as is: long press fits a horizontal row |
| U15 | Naming ("Annota", "Colori dello sfondo") | Titles follow the tool; "Colori dinamici" |
| U16 | Save as an icon only | "Save" as a word wherever there is something to save |
| U17 | "Coming up" tools on Home | No change (spec wants them; the list shrinks by itself) |
| U18 | Tools took the page height in landscape | Tool strip as a rail at the end (`ToolStrip(side)`) |
| U19 | Touch targets under 48 dp | Tool buttons 56 dp, swatches and sizes 48 dp |
| U20 | No full-screen reading | A tap on the page hides the bars; bars float, the page never resizes |
| U21 | Main actions at the top, out of reach | Undo/Redo at the end of the tool strip; floating Copy/Highlight bar by the selection |

Considered and not done: highlight by dragging without a long press (conflicts with one-finger pan);
dropping the Photos/Files choice for images (they show different things); skipping the save dialog
(it carries the "make final" options).
