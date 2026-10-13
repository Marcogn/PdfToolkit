package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationEdits
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The partial eraser's geometry (plan T-a): what is left of strokes and text markup, in user space. */
class PartialEraserTest {

    private fun p(x: Float, y: Float) = UserPoint(x, y)

    /** A pen stroke along y = 100 from x 100 to 200, 2 pt wide, with its outline as the app draws it. */
    private val pen = AnnotationShape.Ink(
        strokes = listOf(listOf(p(100f, 100f), p(150f, 100f), p(200f, 100f))),
        width = 2f,
        outlines = listOf(listOf(p(99f, 99f), p(201f, 99f), p(201f, 101f), p(99f, 101f))),
    )

    /** An eraser drag straight down across y = 100 at [x]. */
    private fun across(x: Float, radius: Float = 1f) = EraserSweep.along(listOf(p(x, 110f), p(x, 90f)), radius)!!

    private fun ink(shape: AnnotationShape) = shape as AnnotationShape.Ink
    private fun xs(shape: AnnotationShape.Ink) = shape.outlines.flatten().map { it.x }
    private fun centreXs(shape: AnnotationShape.Ink) = shape.strokes.flatten().map { it.x }

    /** Every point of the centre line is inside the outline (what other readers show) of the same piece. */
    private fun assertCentreInsideOutline(piece: AnnotationShape.Ink) {
        for (point in piece.strokes.flatten()) {
            assertTrue("$point inside its outline", AnnotationGeometry.hits(piece.copy(strokes = piece.strokes), point, 0.02f))
        }
    }

    @Test
    fun `a stroke cut in the middle leaves two pieces, each with its own centre line inside its outline`() {
        val pieces = PartialEraser.cut(pen, across(150f))!!.map(::ink)
        assertEquals(2, pieces.size)
        val (left, right) = pieces.sortedBy { xs(it).min() }
        assertEquals(149f, xs(left).max(), 0.05f)
        assertEquals(151f, xs(right).min(), 0.05f)
        // The centre line stops half a width earlier, so a line drawn from it ends where the eraser passed.
        assertEquals(148f, centreXs(left).max(), 0.05f)
        assertEquals(152f, centreXs(right).min(), 0.05f)
        assertEquals(100f, centreXs(left).min(), 0.001f)
        assertEquals(200f, centreXs(right).max(), 0.001f)
        pieces.forEach(::assertCentreInsideOutline)
        pieces.forEach { assertEquals(2f, it.width) }
    }

    @Test
    fun `a stroke cut at an end leaves one piece`() {
        val pieces = PartialEraser.cut(pen, across(200f))!!.map(::ink)
        assertEquals(1, pieces.size)
        assertEquals(199f, xs(pieces[0]).max(), 0.05f)
        assertCentreInsideOutline(pieces[0])
    }

    @Test
    fun `an eraser over all of a stroke leaves nothing`() {
        val sweep = EraserSweep.along(listOf(p(95f, 100f), p(205f, 100f)), 3f)!!
        assertEquals(emptyList<AnnotationShape>(), PartialEraser.cut(pen, sweep))
    }

    @Test
    fun `a stroke crossed twice in one drag leaves three pieces`() {
        val sweep = EraserSweep.along(listOf(p(130f, 110f), p(130f, 90f), p(170f, 90f), p(170f, 110f)), 1f)!!
        val pieces = PartialEraser.cut(pen, sweep)!!.map(::ink).sortedBy { xs(it).min() }
        assertEquals(3, pieces.size)
        assertEquals(129f, xs(pieces[0]).max(), 0.05f)
        assertEquals(131f, xs(pieces[1]).min(), 0.05f)
        assertEquals(169f, xs(pieces[1]).max(), 0.05f)
        assertEquals(171f, xs(pieces[2]).min(), 0.05f)
        pieces.forEach(::assertCentreInsideOutline)
    }

    @Test
    fun `an eraser beside a stroke changes nothing`() {
        assertNull(PartialEraser.cut(pen, EraserSweep.along(listOf(p(100f, 120f), p(200f, 120f)), 1f)!!))
        // Inside the box around the stroke, but not on it.
        val diagonal = pen.copy(
            strokes = listOf(listOf(p(100f, 100f), p(200f, 200f))),
            outlines = listOf(listOf(p(99f, 101f), p(101f, 99f), p(201f, 199f), p(199f, 201f))),
        )
        assertNull(PartialEraser.cut(diagonal, EraserSweep.along(listOf(p(180f, 110f)), 2f)!!))
    }

    @Test
    fun `a marker keeps its highlighter flag, colour width and all`() {
        val marker = pen.copy(width = 12f, highlighter = true, outlines = listOf(listOf(p(94f, 94f), p(206f, 94f), p(206f, 106f), p(94f, 106f))))
        val pieces = PartialEraser.cut(marker, across(150f))!!.map(::ink)
        assertEquals(2, pieces.size)
        pieces.forEach {
            assertTrue(it.highlighter)
            assertEquals(12f, it.width)
            assertCentreInsideOutline(it)
        }
    }

    @Test
    fun `a narrow eraser along the middle of a wide marker keeps both sides`() {
        val marker = pen.copy(width = 12f, highlighter = true, outlines = listOf(listOf(p(94f, 94f), p(206f, 94f), p(206f, 106f), p(94f, 106f))))
        val pieces = PartialEraser.cut(marker, EraserSweep.along(listOf(p(80f, 100f), p(220f, 100f)), 1f)!!)!!.map(::ink)
        // The centre line is gone; each side keeps the stretch of it that drew it.
        assertEquals(2, pieces.size)
        pieces.forEach { assertTrue(it.strokes.isNotEmpty() && it.strokes.all { s -> s.size >= 2 }) }
        val bottoms = pieces.map { piece -> piece.outlines.flatten().minOf { it.y } }.sorted()
        assertEquals(94f, bottoms[0], 0.05f)
        assertEquals(101f, bottoms[1], 0.05f)
    }

    @Test
    fun `ink without outlines is cut on its centre line, the gap as wide as the eraser plus the line`() {
        val fileInk = AnnotationShape.Ink(strokes = pen.strokes, width = 2f)
        val pieces = PartialEraser.cut(fileInk, across(150f))!!.map(::ink).sortedBy { centreXs(it).min() }
        assertEquals(2, pieces.size)
        assertEquals(148f, centreXs(pieces[0]).max(), 0.05f)
        assertEquals(152f, centreXs(pieces[1]).min(), 0.05f)
        pieces.forEach { assertTrue(it.outlines.isEmpty()) }
    }

    @Test
    fun `ink of several strokes stays one annotation, untouched strokes as they were`() {
        val other = listOf(p(100f, 150f), p(200f, 150f))
        val handwriting = AnnotationShape.Ink(strokes = pen.strokes + listOf(other), width = 1f)
        val pieces = PartialEraser.cut(handwriting, EraserSweep.along(listOf(p(150f, 105f), p(150f, 95f)), 1f)!!)!!.map(::ink)
        assertEquals(1, pieces.size)
        assertEquals(3, pieces[0].strokes.size)
        assertTrue(other in pieces[0].strokes)
    }

    @Test
    fun `a tap takes a dot out of a stroke`() {
        // A little wider than the stroke: one exactly as wide only touches its edges, which hold it together.
        val pieces = PartialEraser.cut(pen, EraserSweep.along(listOf(p(150f, 100f)), 1.5f)!!)!!.map(::ink)
        assertEquals(2, pieces.size)
    }

    @Test
    fun `tiny leftovers are dropped`() {
        // 0.5 pt of stroke would be left of the outline's right end: less than the eraser's radius.
        val pieces = PartialEraser.cut(pen, across(199.5f, radius = 1f))!!.map(::ink)
        assertEquals(1, pieces.size)
        assertTrue(xs(pieces[0]).max() < 199f)
    }

    // --- Text markup ---

    /** Upright text from x 100 to 160, lines from y 200 (bottom) to 214 (top). */
    private val line1 = Quad(p(100f, 214f), p(160f, 214f), p(100f, 200f), p(160f, 200f))
    private val line2 = Quad(p(100f, 198f), p(140f, 198f), p(100f, 184f), p(140f, 184f))

    private fun markup(vararg quads: Quad) = AnnotationShape.TextMarkup(MarkupKind.HIGHLIGHT, quads.toList())
    private fun quads(shapes: List<AnnotationShape>) = (shapes.single() as AnnotationShape.TextMarkup).quads

    private fun assertQuad(expected: Quad, actual: Quad) {
        expected.points.zip(actual.points).forEach { (e, a) ->
            assertEquals("x of $a", e.x, a.x, 0.05f)
            assertEquals("y of $a", e.y, a.y, 0.05f)
        }
    }

    @Test
    fun `a highlight cut in the middle keeps both ends of the line, at full height`() {
        val sweep = EraserSweep.along(listOf(p(130f, 220f), p(130f, 208f)), 1f)!!
        val left = quads(PartialEraser.cut(markup(line1), sweep)!!)
        assertEquals(2, left.size)
        assertQuad(Quad(p(100f, 214f), p(129f, 214f), p(100f, 200f), p(129f, 200f)), left[0])
        assertQuad(Quad(p(131f, 214f), p(160f, 214f), p(131f, 200f), p(160f, 200f)), left[1])
    }

    @Test
    fun `a highlight cut at an end keeps one quad`() {
        val left = quads(PartialEraser.cut(markup(line1), EraserSweep.along(listOf(p(158f, 207f)), 3f)!!)!!)
        assertQuad(Quad(p(100f, 214f), p(155f, 214f), p(100f, 200f), p(155f, 200f)), left.single())
    }

    @Test
    fun `markup on a turned page is cut along its own text`() {
        // The line runs up the page (+y), top of the letters towards -x.
        val turned = Quad(p(300f, 100f), p(300f, 160f), p(314f, 100f), p(314f, 160f))
        val sweep = EraserSweep.along(listOf(p(296f, 130f), p(318f, 130f)), 1f)!!
        val left = quads(PartialEraser.cut(AnnotationShape.TextMarkup(MarkupKind.UNDERLINE, listOf(turned)), sweep)!!)
        assertEquals(2, left.size)
        assertQuad(Quad(p(300f, 100f), p(300f, 129f), p(314f, 100f), p(314f, 129f)), left[0])
        assertQuad(Quad(p(300f, 131f), p(300f, 160f), p(314f, 131f), p(314f, 160f)), left[1])
    }

    @Test
    fun `slanted text is cut along its slant`() {
        // A parallelogram: the letters lean right by 2 pt over their height.
        val slanted = Quad(p(102f, 214f), p(162f, 214f), p(100f, 200f), p(160f, 200f))
        val left = quads(PartialEraser.cut(markup(slanted), EraserSweep.along(listOf(p(131f, 207f)), 1f)!!)!!)
        assertEquals(2, left.size)
        // The cut edges lean as the letters do.
        assertEquals(left[0].upperRight.x - left[0].lowerRight.x, 2f, 0.05f)
        assertEquals(left[1].upperLeft.x - left[1].lowerLeft.x, 2f, 0.05f)
    }

    @Test
    fun `a highlight over two lines cut on one keeps the other as it was`() {
        val sweep = EraserSweep.along(listOf(p(130f, 220f), p(130f, 205f)), 1f)!!
        val left = quads(PartialEraser.cut(markup(line1, line2), sweep)!!)
        assertEquals(3, left.size)
        assertEquals(line2, left[2])
    }

    @Test
    fun `markup leftovers shorter than the eraser are dropped, and all of it can go`() {
        val nearEnd = quads(PartialEraser.cut(markup(line1), EraserSweep.along(listOf(p(101.5f, 207f)), 1f)!!)!!)
        assertQuad(Quad(p(102.5f, 214f), p(160f, 214f), p(102.5f, 200f), p(160f, 200f)), nearEnd.single())
        val all = EraserSweep.along(listOf(p(95f, 207f), p(165f, 207f)), 1f)!!
        assertEquals(emptyList<AnnotationShape>(), PartialEraser.cut(markup(line1), all))
    }

    @Test
    fun `markup beside the eraser is untouched`() {
        assertNull(PartialEraser.cut(markup(line1), EraserSweep.along(listOf(p(130f, 230f), p(150f, 230f)), 1f)!!))
    }

    // --- One drag ---

    @Test
    fun `a drag cut move by move leaves what one cut along the whole path leaves`() {
        val path = listOf(p(120f, 110f), p(125f, 100f), p(130f, 90f), p(160f, 95f), p(170f, 100f), p(175f, 110f))
        val stroke = PartialEraseStroke(listOf(EraseTarget(ErasePick.Added("a"), pen)), 1f)
        path.forEach { stroke.moveTo(listOf(it)) }
        val stepwise = stroke.left.getValue(ErasePick.Added("a")).map(::ink).sortedBy { xs(it).min() }
        val atOnce = PartialEraser.cut(pen, EraserSweep.along(path, 1f)!!)!!.map(::ink).sortedBy { xs(it).min() }
        assertEquals(atOnce.size, stepwise.size)
        stepwise.zip(atOnce).forEach { (a, b) ->
            assertEquals(xs(b).min(), xs(a).min(), 0.05f)
            assertEquals(xs(b).max(), xs(a).max(), 0.05f)
        }
    }

    @Test
    fun `a drag lists only what it touched, in the targets' order`() {
        val far = AnnotationShape.Ink(listOf(listOf(p(100f, 300f), p(200f, 300f))), 2f)
        val ref = AnnotationRef(0, 0, 3, "Highlight@1")
        val stroke = PartialEraseStroke(
            listOf(EraseTarget(ErasePick.Existing(ref), markup(line1)), EraseTarget(ErasePick.Added("far"), far), EraseTarget(ErasePick.Added("pen"), pen)),
            1f,
        )
        assertTrue(stroke.moveTo(listOf(p(150f, 110f), p(150f, 90f))))
        assertTrue(stroke.moveTo(listOf(p(130f, 220f), p(130f, 205f))))
        assertEquals(listOf(ErasePick.Existing(ref), ErasePick.Added("pen")), stroke.left.keys.toList())
    }

    @Test
    fun `pieces survive the session's compact serialization unchanged`() {
        val pieces = PartialEraser.cut(pen, across(150f))!! + PartialEraser.cut(markup(line1), EraserSweep.along(listOf(p(130f, 207f)), 1f)!!)!!
        val edits = AnnotationEdits(added = pieces.mapIndexed { i, shape -> NewAnnotation("a$i", "p0", shape, AnnotationStyle(AnnotationColor.BLACK)) })
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(AnnotationEdits.serializer(), json.encodeToString(AnnotationEdits.serializer(), edits))
        assertEquals(edits, back)
    }
}
