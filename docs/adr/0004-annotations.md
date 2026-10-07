# ADR 0004 — Annotations: drawn by the app, written as standard annotations with our own appearance

Date: 2026-10-04. Status: accepted.

## Context

Product phase 2 starts with highlighting and freehand drawing (spec §7.4, `docs/plan-v2.md` 7a).
Unlike signatures and text (spec §6.5, written into the page content), highlights and ink must stay
removable, also in other readers, so they are **annotations** (Highlight, Underline, StrikeOut,
Squiggly, Ink) with an appearance stream. The app must also show the annotations already in a file,
and let the user remove them, including ones made by other apps. Text selection is the prerequisite
for highlighting.

## Decision

**The app draws every annotation itself.** The renderer call the viewer uses,
`Page.render(bitmap, clip, matrix, RENDER_MODE_FOR_DISPLAY)`, draws no annotations on any Android
version:
- API 35+ (and the mainline module): the four-argument `render` builds `RenderParams` with no
  annotation flags (`android-37.0` sources of `android.graphics.pdf.PdfRenderer`), and the native
  side (`pdfClient/page.cc`, `utils/annot.cc` in `platform/packages/providers/MediaProvider`) hides,
  with `FPDF_ANNOT_FLAG_HIDDEN`, every annotation whose type isn't asked for; only form widgets are
  kept, since we target API 35+.
- Below API 35 the platform renderer doesn't pass `FPDF_ANNOT` (spec §3.1).

So there is no risk of drawing anything twice. `PdfBoxAnnotationReader` reads the annotations with
PdfBox (`pdf/annotations`), and `ui/annotate/AnnotationPainter` draws text markup and ink over the
page from their geometry. Other kinds (notes, stamps, shapes, free text) are listed, so they can be
erased and are kept on save, but not drawn. Hidden/NoView ones, links, widgets, pop-ups and printer
marks are not listed.

**One geometry for screen and file.** `AnnotationGeometry` turns a shape (quads or ink strokes, in
PDF user space) into filled polygons and stroked polylines; the painter draws them on screen and
`AnnotationWriter` writes them into the appearance stream (a form with `/BBox` = `/Rect` and the
identity matrix, so its space is the page's user space). Quads are handled on their own axes, so any
text angle and any page rotation work the same way. We don't use PdfBox's appearance handlers:
`PDHighlightAppearanceHandler` (2.0 branch) only treats quads that are horizontal or vertical
(its own comment: "if it is diagonal then... uh..."), and starts its bounds from `Float.MIN_VALUE`,
which is positive, so negative coordinates break it. A highlight multiplies with the page
(`/BM /Multiply`, as Acrobat), opacity is `/CA`.

**Model in user space, bound to pages.** `domain/annotate`: `NewAnnotation` is bound to the
`PageItem.id` (it moves with its page, like overlays); an existing annotation is an `AnnotationRef`:
document, page, index in `/Annots`, and a fingerprint (subtype + `/Rect` rounded to 0.1 pt).
`EditSession` keeps `AnnotationEdits(added, removed)` in the same undo history as pages and fill,
encoded as JSON next to them (`SavedStateHandle`, `SaveRequest.annotations`).
`/QuadPoints` use Acrobat's order (upper-left, upper-right, lower-left, lower-right of the text),
which is what readers expect, not the counterclockwise order ISO 32000-1 §12.5.6.10 describes.

**Removal before anything else touches `/Annots`.** References are indices into the array as it
was read, so `PdfBoxEditor` removes first (before filling the form, which can flatten widgets
away), on the source pages of the main and the added PDFs. The fingerprint must still match,
otherwise the reference is skipped (a file changed since it was read never loses the wrong
annotation). Pop-ups of what goes and replies (`/IRT`, recursively) go too, as in Acrobat. The page
gets a new `/Annots` array, since an array can be shared by several pages.

**Text selection on the search extractor.** `TextSelection` works on the glyphs of
`PositionedTextStripper` (positions on the display, phase 5a): word under a point, boundary under a
handle, text for the clipboard (newline at line breaks), and one `LineRun` per line, a parallelogram
on the first glyph's axes covering the tallest glyph, turned into a `Quad` in user space through
`PdfPageSpace`. `PdfTextExtractor.reader()` keeps one document open for single pages (four cached),
since selecting touches the same page many times; the search index can't be used, it keeps one
axis-aligned box per glyph to stay small.

## Consequences

- An annotation made elsewhere is shown from its geometry, not from its own appearance: custom
  appearances (a highlight drawn as a rounded shape, a stamp image) look plain or not at all.
- The viewer reads the annotations of every document it opens, in the background: PdfBox parses the
  whole file once more (as search does when opened).
- Before API 29 the screen can't multiply: highlights are drawn translucent on top, the text under
  them is lighter than in the file.
- Thumbnails and the edit screens' page grids don't show annotations.

## Freehand ink (phase 8a, 2026-10-05)

**Library.** `androidx.ink` 1.0.0, the stable line (`ink-authoring-compose`, `-brush`, `-geometry`,
`-strokes`). Its `InProgressStrokes` composable draws the stroke under the finger with low latency
(front-buffered); on lift it hands over a `Stroke` whose `PartitionedMesh` has the brush **outlines**
(closed polygons, filled with the nonzero rule, which is how its own `CanvasStrokeRenderer` paths
them). Stock brushes with pinned versions: `pressurePen(V1)` for the pen,
`highlighter(SelfOverlap.DISCARD, V1)` for the marker (DISCARD so a stroke crossing itself paints
once, as a PDF fill does). Brush size is in points of the page; epsilon is 0.1 screen pixel at the
zoom the stroke starts at.

**Stroke space = display points of the page as shown.** The pointer→stroke matrix is the inverse of
`PageCoordinateMapper.pageToScreenTransform`, set as each stroke starts. The page on screen is
then only scaled and moved relative to stroke space, so the brush keeps its on-screen orientation
(the marker's chisel tip) on turned pages too. The finished stroke goes to user space through
`PdfPageSpace.displayToUser` of the page as shown (file rotation + user rotation), like every other
annotation (`FreehandGeometry`).

**Model.** `AnnotationShape.Ink` gains `outlines` (user space) and `highlighter`. One stroke is one
Ink annotation, so the eraser and undo work stroke by stroke. Outlines and centre line are simplified
(Douglas–Peucker at the brush epsilon) and rounded to 0.01 pt. They are serialized compactly
(`CompactPolylineSerializer`: integer steps in hundredths of a point in one string, about a third of
`{"x":..,"y":..}`), because the session goes into the saved instance state, whose limit is about 1 MB.
The 7a array form is still read.

**Write path.** `/InkList` holds the centre line and `/BS /W` the brush size, so a reader that
rebuilds the appearance still has a sensible line. The appearance stream fills the outlines in one
path (`f`). A marker sets `/BM /Multiply`, and the reader recognises it by that blend mode in its
appearance. "Make final" (`WriteOptions.flattenInk`, a save dialog checkbox, off by default) draws
the same paths into the page content (one appended stream per page, `q … Q`) instead of adding
annotations; text markup is never flattened. Drawing code is shared through a small `PathCanvas`
interface, since PdfBox 2.0's `PDPageContentStream` and `PDAppearanceContentStream` have no public
common type.

**Gestures.** `detectFreehandGestures` runs in the **initial** pass of the page, before the ink layer
(a child) sees the events, and decides by consuming (the ink layer cancels a stroke whose events
arrive consumed). One finger or a stylus draws; a second finger cancels the stroke and the fingers
zoom and pan; while a stylus draws, other touches are consumed (palm). The page's own zoom/pan
detector stands down through `FreehandGrab.active`, as with `SelectionGrab`.

**Consequences.**
- Reading a file back gives the centre line only: the app draws reopened ink with an even width,
  while other readers show the outline from the appearance stream. Reading our own appearance would
  need a content stream path parser; left out.
- The marker is translucent while wet (the ink layer can't multiply) and multiplies once lifted.
- About 5.4 MB of native libraries in a universal APK (`libink`, `libgraphics-core`, four ABIs, stored
  uncompressed), about 1.5 MB on one ABI.
- The ink classes need their native library, so the unit tests cover everything after the `Stroke`
  (`FreehandGeometry`, writer, serialization); the step from `Stroke` to `FreehandStroke` is checked
  on the device.
