# Sheet-music fork: re-evaluation on the app as of 1.1.0

Status: **analysis, nothing built** (2026-10-08). Replaces the analysis of 2026-10-05 (draft PR #16),
written before the viewer could edit. Since then PdfToolkit gained freehand (8a/8b), simpler screens
(U-a/U-b) and editing in the viewer (V-a…V-c, ADR 0005), and was released as 1.1.0. This document starts
again from that app.

The target, from the author's answers: a **choir singer** reading **scanned scores** (rarely
born-digital, all different, one PDF per piece) on **any Android tablet**, mostly in **rehearsal**,
turning pages **by touch**. It has to be **free, built in-house, easy to use, and complete in the few
things it does**: library, mark (underline / highlight / draw / symbols), save, export. Other apps
(MobileSheets, forScore) are inspiration only.

Effort is relative (S / M / L), not time. **[TO VERIFY]** marks what only a device test can settle.

---

## 1. What changed, and what it means

| Needed for scores | In PdfToolkit 1.1.0 | Gap |
|---|---|---|
| Open and read a score | Viewer, single page or continuous, full screen with a tap, last page remembered | Tap zones to turn; screen kept on |
| Draw with pen or finger | **Done** in the viewer: pen, marker, eraser, colours, sizes, undo/redo, stylus | Finger turns pages while the pen draws (today a brush blocks paging) |
| Highlight a passage | **Marker** (freehand highlighter) in the viewer | None for scans. Text highlight/underline need a text layer: useless on scans |
| Underline | Text underline only | A straight line drawn with the pen is enough (§3.5) |
| Musical symbols | — | Breath mark, dynamics, fermata… (§3.5) |
| Erase marks made elsewhere | **Done** | — |
| Save | **Done**, explicit: dialog (copy / overwrite), exit asks | Automatic for scores in the library (§3.4) |
| Export / share | **Done**: share sheet, save as copy, "make final" | — |
| Library | Recents (10) | A real list of pieces (§3.1) |
| Margins cropped | — | **The main reading gap** (§3.3) |
| Fix a scan: rotate, remove, reorder, add a page | **Done** in "Organize pages" | — |
| Fill forms, signatures, search, merge, scan/OCR/ODF/cloud | Done or planned | Not needed: removed in the fork (§4) |

**Conclusion.** About two thirds of the earlier plan now exists in the base app, and the riskiest
part of it (marks in the reader, with their geometry and gestures) is done and device-checked. What is
left is **five features**, none of which needs a new core of the size of 7a or 8a. The earlier 13
sub-phases (setlists, sound, half-page turns, straightening, exchange, e-ink, layers…) are dropped;
§6 keeps them as "later, only if missed in use".

---

## 2. Fork or not, re-evaluated

- **A mode in the base app** (a "Scores" section) would avoid maintaining two apps. But it puts
  score-specific behaviour (tap zones, autosave over the file, crop written into the file) next to a
  general PDF tool, where it would surprise users of the other half; and the singer's UI would still
  carry forms, signatures and search.
- **A separate fork** (the author's choice): a smaller app, a Home that is a library, no tool the
  singer doesn't need. Cost: base fixes are ported by hand (`git cherry-pick` from an `upstream`
  remote; it stays cheap while the fork mostly **deletes** code and **adds** new files).

The fork remains the better fit for "easy to use". **When**: now. It needs nothing still open in the
base plan: 9 Scan is not needed (the scores arrive as PDFs), 10–12 are dropped.

---

## 3. The five features

### 3.1 Library — Sonnet, M

- **Import, don't link.** A piece added from the file picker (or "Open with" / share) is **copied into
  app storage** (`filesDir/library/`). This one choice simplifies everything else: the file is always
  writable (autosave and crop write into it), always available (no expiring permissions, no limit on
  persisted grants), and the original stays untouched as a backup.
- Home = the library: grid of first-page thumbnails (the recents thumbnail code exists) with title,
  search by name, sort by name / last opened, rename, delete, "Export…" (share sheet or save a copy to a
  folder of the user's choice, both already exist). Recents become "continue where you left off".
- Room table for the pieces (title, file, last page, crop done). No folders, tags or setlists yet.

### 3.2 Reading mode for scores — Sonnet, S–M

- **Tap zones**: left third = previous page, right two thirds = next; the centre band (or a small
  button) toggles the bars, which today any tap does. Single-page mode and "fit page" by default.
- **Screen on** while a score is open (`FLAG_KEEP_SCREEN_ON`, no permission, cleared in background;
  [Android docs](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)).
- **Turning while marking.** Today a brush blocks paging and the finger draws (decision 2026-10-05).
  For a singer the pen should stay armed for the whole rehearsal, so: a setting **"draw with the stylus
  only"**, default on as soon as a stylus has touched the screen: the stylus draws, the finger turns
  (Android reports the tool type of each pointer,
  [Android docs](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/advanced-stylus-features)).
  Without a stylus the current behaviour stays (finger draws with a brush armed, turn by putting the
  tool down, or with narrow strips at the left and right edges that turn the page even with a brush
  armed). **[TO VERIFY on a tablet
  with and without a stylus]**.

### 3.3 Crop — Sonnet, M (Opus only if the renderer surprises us)

- **Written into the file as `/CropBox`**, through `PdfEditor`, on the library copy. This replaces the
  earlier design (a display-only crop through `DocumentLayout` and `PageCoordinateMapper`, which would
  have touched every coordinate consumer: tiles, annotations, freehand in document points, fill,
  selection). With a real `/CropBox` nothing in the geometry changes: `PdfPageSpace` already takes the
  visible box as `/CropBox` ∩ `/MediaBox`, as pdfium does
  ([source](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/core/fpdfapi/page/cpdf_page.cpp)),
  and `PdfRenderer` is pdfium. Other readers see the same crop. **[TO VERIFY on a device that
  `PdfRenderer` sizes and renders the page by its crop box; if not, this becomes the display-only
  design, an Opus core]**.
- **Reversible**: the original box is kept in the database (or the piece is re-imported from the
  untouched source); "Uncrop" restores it.
- **Auto-detect, always correctable.** Render the page small and greyscale, take the background level
  from its histogram (photocopies are grey), drop dark bands touching the edges and isolated specks,
  add a safety margin. Pure function, unit-tested on generated pages. The crop screen shows the proposal
  on every page; one gesture fixes a page; "same for all pages" is a button, not the default (the scans
  are all different).
- Runs once per piece, offered right after import.

### 3.4 Automatic save — Opus for the design, Sonnet for the rest, M

The base app saves explicitly, by design (spec §6.7, decision 2026-10-07). For a library piece in
rehearsal that is friction: the singer should never see a save dialog.

- Every change of the viewer session is written at once to a small **draft file** per piece (the
  session's annotations already serialise for `SavedStateHandle`), so nothing is lost if the app is
  swiped away.
- The **PDF is written** through the existing save engine (`SaveRunner`, overwrite) when the singer
  leaves the piece (back to the library, another piece) or the app goes to the background; the draft is
  deleted once the save is verified. On opening, a leftover draft is applied again.
- Overwrite is safe here because the file belongs to the app (§3.1); files opened from outside the
  library keep the base app's explicit save.
- Needs an ADR amending ADR 0005 (when the viewer reopens after an overwrite, what happens to a save
  started in the background). That is the only piece where a wrong design is expensive later.

### 3.5 Musical symbols and straight lines — Sonnet, S–M

- **Symbols as Ink annotations** made from fixed vector paths: breath mark (comma, tick), *p mp mf f*,
  hairpins, fermata, accent, a "look at the conductor" sign **[TO VERIFY with the singer which ones]**.
  Being ink, they already save, undo, erase and show in other readers with no new writer. Placed with a
  tap while the symbol is armed; size follows the page zoom.
- **Straight line**: holding the pen still for a moment at the end of a stroke straightens it (or a
  "line" brush). Covers "underline" and brackets on scans.

---

## 4. What the fork removes

Fill and sign (viewer fill mode, `ui/fill`, `pdf/forms`, `FillWriter`), signatures (`ui/signatures`,
`data/signatures`, the Room table: start the fork at a new schema version), search and text markup
tools (scans have no text; text selection can stay for the rare born-digital piece), merge, the "Soon"
tiles and the phase 2 plan (scan, OCR, ODF, cloud). "Organize pages" stays (rotate, remove, reorder,
add a page handed out later). Keep the Kotlin package name so that cherry-picks from PdfToolkit apply.

---

## 5. Plan

| Step | Model | Content | Effort |
|---|---|---|---|
| F0 Fork | Sonnet | New repository with PdfToolkit's history (GitHub doesn't fork into the same account), `upstream` remote, new `applicationId`, name, icon, keystore, CI secrets; removals of §4; own CLAUDE.md | M |
| F1 Library | Sonnet | §3.1 | M |
| F2 Reading mode | Sonnet | §3.2 | S–M |
| F3 Crop | Sonnet | §3.3; first task: check `/CropBox` on the device | M |
| F4a Autosave design | **Opus** | §3.4: ADR, draft store, save triggers, tests | S–M |
| F4b Autosave UI | Sonnet | Remove the save dialog for library pieces, status hint, error path | S |
| F5 Symbols and lines | Sonnet | §3.5 | S–M |

Order: F0 → F1 → F2 → F3 → F4 → F5. After F2 the app is already usable in rehearsal (library, big
pages turned with a tap, pen always armed, explicit save); F3 and F4 make it comfortable; F5 completes
the marking. Six steps, one of them on Opus, against thirteen (four on Opus) in the earlier plan.

---

## 6. Later, only if missed in use

Programs / setlists and "next piece", half-page turns, night mode (inverted page), contrast boost for
faded copies, straightening, starting note, practice tracks, sharing marks with the section, page-turn
pedals. Each is independent of the six steps above and can be added without redesign.

## 7. Open questions

1. Which symbols does the director ask for most (§3.5)?
2. Autosave also for files opened from outside the library, or only library pieces (proposed)?
3. Keep text selection for born-digital pieces, or remove all text features?
