# Tool drawer and partial eraser (T-a, T-b): plan

Status on 2026-10-13: **planned**. Requested by the author on 2026-10-13, after testing 1.1.3 on a Galaxy
Tab S7+ (freehand now draws under the pen). Two inserted sub-phases, **T-a Partial eraser core** (Opus) and
**T-b Tool drawer** (Sonnet), in that order, so the drawer is designed once with every tool in it. Code
comments cite the items as "plan T-a", "plan T-b".

| Sub-phase | Model | Scope | Done when | Check on the device |
|---|---|---|---|---|
| T-a Partial eraser core | **Opus** | An eraser that removes only what the finger passes over: pen and marker strokes (also Ink from other apps) and text markup (highlight, underline, strikeout); the cut pieces stay as annotations; one undo step per gesture | Unit tests below; ADR 0004 addendum; lint, unit tests, `assembleDebug` green | Cut strokes and highlights in the middle and at an end, on turned pages, undo; save and reopen here and in another reader |
| T-b Tool drawer | Sonnet | Fixed bottom bar: "Tools" drawer with every tool, two quick slots filled by drag and drop, Undo/Redo, Pages; long press on a tool opens a bubble with colour and size; freehand highlighter as a tool of its own; stroke eraser and partial eraser as two tools | Tests below; lint, unit tests, `assembleDebug` green | Nothing in the bar moves when arming a tool; drag tools onto the dashed quick slots, kept after restart; bubble; landscape rail; Home "Highlight"/"Draw" |

## What the author asked for (2026-10-13)

1. Tapping Pen shifts the bar a little to make room for the colour swatch on the right. Better: a bubble with
   colour and stroke size.
2. Highlighting also where there is no text (freehand highlighting).
3. An eraser that erases only where the finger passes, not the whole stroke.
4. Rather than today's bar, a small tool drawer (a floating button or one big button), with the option to
   put a couple of quick tools in the bottom bar.

What causes 1: `ViewerToolBar` puts the Style button (`StyleButton`, a coloured dot) in the `trailing` slot of
`ToolStrip` only while a markup or drawing tool is armed, and Undo/Redo appear there only once there is
something to undo; the row is re-laid out each time, so the other buttons slide.

About 2: a freehand highlighter already exists. It is **Marker** (`AnnotateTool.MARKER`, an Ink annotation
that multiplies like a highlight), reached through Draw → Style. It is hard to find, not missing.

## Decisions (author's answers, 2026-10-13)

- **Drawer + two quick slots** (not a floating button, not today's bar with fixed slots). The bottom bar has
  fixed places that never move: "Tools", quick slot 1, quick slot 2, Undo, Redo, Pages. "Tools" opens a panel
  with every tool. Undo and Redo are always there (disabled when there is nothing to undo).
- **Long press opens the bubble** (colour, size, the tool's variants), on a quick slot and in the drawer. A
  tap arms the tool, a tap on the armed tool puts it down, as today.
- **Freehand highlighter as a tool of its own** in the drawer, next to text Highlight, instead of being
  hidden behind Draw → Style.
- **Partial eraser on strokes and text markup**: pen and marker strokes (also Ink annotations made by other
  apps) and highlight / underline / strikeout. Signatures, text, ticks and dates (written into the page
  content, not annotations) stay with Fill and sign; notes and stamps of other apps stay with the stroke
  eraser.
- **Two sub-phases, T-a before T-b**: the geometry is the part where a wrong design is expensive (Opus, as
  the other "a" sub-phases); the drawer is UI work (Sonnet).

## T-a Partial eraser core (Opus)

**Goal**: drag the eraser over strokes and markup; only the part under the finger goes.

**Scope**
- Gesture: with the partial eraser armed, one finger or the stylus drags (like a brush: a second finger zooms
  and pans, through the freehand detector's rules), with a circle showing the eraser's size. A tap erases a
  dot. The eraser's sizes are the pen's (1, 2, 4, 6 points of the page), default the pen's default, 2 pt
  (author); the bubble offers the four (T-b; in T-a the Style button).
- Geometry, in `pdf/annotations` (no Android, unit-tested), in user space of the page:
  - **Ink** (`AnnotationShape.Ink`): the swept eraser (a capsule chain of the drag path, eraser radius)
    is subtracted from the stroke's **outlines** (the appearance, `/AP`), and the **centre line**
    (`/InkList`) is split where it enters and leaves the swept area. Each surviving piece becomes its own
    Ink annotation with the same colour, width and `highlighter` flag. Ink of other apps without our
    outlines: the centre line is cut, and the pieces get outlines from `AnnotationGeometry` as today.
  - **Text markup** (`AnnotationShape.TextMarkup`): each quad is cut along its text direction where the
    swept area crosses it (a quad is a line of text: cutting across it keeps the rest of the line). Pieces
    that are left make one markup annotation per original, with the remaining quads.
  - Tiny leftovers (shorter than the eraser radius, or a sliver of a quad) are dropped.
- Another app's annotation: cutting it replaces it with pieces made by this app, so its author, dates and any
  custom appearance are lost on the pieces (author: acceptable). The first time this happens in a document, a
  one-time notice says so ("Questa annotazione è stata creata da un'altra app: i pezzi rimasti saranno
  salvati come annotazioni di PdfToolkit", wording in T-a), with Undo at hand.
- Model and session: the original goes (`removeAnnotation` for this session's, `removeExistingAnnotation`
  for the file's) and the pieces are added, as **one** undo step per gesture (a new `EditSession` operation,
  so undo restores the original whole). Nothing new in the save path: the pieces are ordinary
  `NewAnnotation`s written by `AnnotationWriter`.
- Polygon subtraction: choose in the sub-phase between a small dependency (e.g. a Java port of Clipper2,
  checked for licence, size, maintenance and offline use) and an own implementation limited to "outline minus
  capsules"; record the choice in ADR 0004 with its sources. Android's `Path.op` is out: it is not available
  in JVM tests and `pdf/annotations` has no Android.
- Live feedback: while dragging, the strokes show cut as they will be (computed on the fly for the strokes
  under the eraser), not after lift.

**Prerequisites**: none beyond 1.1.3 (the app-drawn wet stroke, `WetStroke`).

**Done when**
- Unit tests: Ink cut in the middle (two pieces), at an end (one), across all of it (none); a stroke crossed
  twice; centre line and outline stay consistent (each piece's centre line lies inside its outline);
  marker (`highlighter`) keeps its flag; Ink without outlines; markup quad cut in the middle, at an end,
  rotated page (quads not axis-aligned); a multi-line highlight cut on one line only; leftovers dropped; undo
  restores the original (session test); pieces round-trip through `CompactPolylineSerializer` and
  `AnnotationWriter`/reader.
- ADR 0004 addendum (partial eraser, the library choice and why).
- lint, unit tests, `assembleDebug` green; CI green.

**Device checks**: cut a pen stroke in the middle and at an end, with finger and S Pen; same for a marker
stroke and a text highlight spanning two lines; on a turned page; zoomed in and out; undo and redo; a stroke
made by another app (e.g. Samsung Notes export, Xodo); save as annotation, reopen here and in another
reader; "make final" with cut strokes.

**Out of scope**: the drawer and the bubble (T-b). Until T-b, the partial eraser is reached from today's bar:
with the Eraser armed, the Style button offers "Whole stroke" / "Partial" (the eraser has no Style button
today); erasing signatures, text, ticks, dates;
erasing other apps' notes, stamps, shapes partially.

## T-b Tool drawer (Sonnet)

**Goal**: one bar that never moves, every tool one tap away in a drawer, the two the author uses most in the
bar.

**Scope**
- Bottom bar (portrait) and side rail (landscape, plan U18) with fixed places: **Tools**, **quick slot 1**,
  **quick slot 2**, **Undo**, **Redo**, **Pages**. Arming, undoing or picking a colour never changes the
  layout. The armed tool shows as selected wherever it is (quick slot, or the Tools button showing its icon
  when it isn't pinned).
- **Drawer** (a panel above the bar, Material bottom sheet or anchored panel: decided in the sub-phase), in
  groups: Text (Highlight, Underline, Strikeout), Draw (Pen, Marker = freehand highlighter), Erase (Stroke
  eraser, Partial eraser), Fill and sign. A tap arms the tool and closes the drawer.
- **Quick slots by drag and drop** (author): no tool is pinned at first, the two slots are empty. While a slot
  is empty, opening the drawer shows a hint ("Tieni premuto uno strumento e trascinalo in basso per averlo
  sempre a portata di mano", wording in T-b). Holding a tool in the drawer and moving the finger starts a
  drag: the empty slots appear as dashed rectangles ("qui va uno strumento"); dropping on one pins the tool,
  dropping anywhere else does nothing and the hint shows again next time. The hint goes away for good once a
  slot is filled. A held tool released without moving opens the bubble instead. Dragging a quick tool off the
  bar unpins it; dropping a tool on a full slot replaces it.
- **Bubble** on long press (quick slot or drawer entry): colour row and, for Pen and Marker, size row, plus
  the partial eraser's size; replaces `StyleButton` and its `DropdownMenu`. Choices stay per tool as today
  (`AnnotatePaneState`).
- Quick slots, the hint's state, the last tool per group, and **colours and sizes** kept across restarts
  (DataStore, `ViewerToolPreferences`; author: yes if cheap, and it is, the same store as the pins). This
  replaces the 2026-10-07 decision "Not kept across app restarts".
- Fill and sign keeps its own strip (`fillTools`) once armed, as today; the bar's Tools button brings back the
  drawer.
- Home "Highlight" / "Draw" still open the viewer with that tool armed.
- Strings in `values/` and `values-en/`; touch targets 48 dp, tool buttons 56 dp (plan U19); TalkBack
  labels for slots ("Quick tool: Pen"), pin state and the bubble.

**Prerequisites**: T-a merged.

**Done when**
- Robolectric tests: arming a tool or making Undo available does not move any bar button (bounds compared);
  hint shown while a slot is empty and gone for good once one is filled; drop on an empty slot pins, drop
  elsewhere does nothing, drop on a full slot replaces, drag off the bar unpins; pins, colours and sizes
  restored from preferences; long press without moving opens the bubble, tap arms and a second tap puts down; Marker armed from the drawer draws as a highlighter; the bubble's
  choices reach `AnnotatePaneState`.
- README (tools section) and CLAUDE.md updated; lint, unit tests, `assembleDebug` green; CI green.

**Device checks**: arm each tool from the drawer and from the quick slots: no button moves; first open of the drawer
shows the hint; drag a tool onto a dashed slot, drop one elsewhere (hint again), fill both, swap one, drag one
off; restart the app: slots, colours and sizes are still there; long press on a quick slot and in the drawer → bubble, colour and size
apply to the next stroke; landscape rail; Home "Highlight" and "Draw"; Fill and sign in and out; a protected
PDF (tools dimmed, a tap explains why); TalkBack reads the slots.

**Out of scope**: a floating, draggable palette; more than two quick slots; new tools.

## Questions answered (author, 2026-10-13)

1. Default quick slots: **none**; the drawer teaches drag and drop to the dashed slots (T-b scope above).
2. Colours and sizes kept across restarts: **yes**, it costs little (T-b).
3. Partial eraser size: **the pen's default**, 2 pt, with the pen's four sizes on offer (T-a). It may feel
   thin for large areas: the device check judges it, and the bubble has 6 pt.
4. Cutting another app's annotation: **yes**, with a one-time notice per document (T-a).
5. Order: **T-a and T-b before 11**. The author is considering dropping 11 (ODF export) because it doesn't
   look doable well on Android; not decided in this plan.

## Risks

- Polygon subtraction on real strokes (self-intersecting marker outlines, thousands of vertices) can be slow
  or numerically fragile; live feedback has to stay smooth at 120 Hz. T-a measures on the tablet and may
  fall back to cutting on lift if the live preview costs too much.
- Text markup quads from other apps are not always rectangles along the text; cutting them across their
  long side is an approximation (ADR addendum).
- Long press for the bubble is less discoverable than a second tap (author's choice): T-b shows a one-time
  hint ("Hold a tool for colour and size", `TransientHint`).
