package com.marcogn.pdftoolkit.pdf.annotations

import com.marcogn.pdftoolkit.domain.annotate.AnnotationColor
import com.marcogn.pdftoolkit.domain.annotate.AnnotationRef
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.AnnotationStyle
import com.marcogn.pdftoolkit.domain.annotate.ExistingAnnotation
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.NewAnnotation
import com.marcogn.pdftoolkit.domain.annotate.Quad
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.domain.fill.UserRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a tap of the eraser takes (spec §7.4, phase 7b). */
class AnnotationEraserTest {

    private val style = AnnotationStyle(AnnotationColor.YELLOW)

    /** Upright line of text from x 100 to 160, y 200 to 214. */
    private fun line(x: Float = 100f, y: Float = 200f) = AnnotationShape.TextMarkup(
        MarkupKind.HIGHLIGHT,
        listOf(Quad(UserPoint(x, y + 14), UserPoint(x + 60, y + 14), UserPoint(x, y), UserPoint(x + 60, y))),
    )

    private fun added(id: String, shape: AnnotationShape = line()) = NewAnnotation(id, "p0", shape, style)

    private fun existing(index: Int, shape: AnnotationShape? = line(), bounds: UserRect = UserRect(100f, 200f, 160f, 214f)) =
        ExistingAnnotation(AnnotationRef(0, 0, index, "Highlight:$index"), if (shape == null) "Stamp" else "Highlight", shape, style, bounds)

    private val inside = UserPoint(130f, 207f)

    @Test
    fun `a tap on nothing picks nothing`() {
        assertNull(AnnotationEraser.pick(listOf(added("a")), listOf(existing(0)), emptySet(), UserPoint(10f, 10f), tolerance = 3f))
    }

    @Test
    fun `a tap inside a highlight of this session picks it`() {
        assertEquals(ErasePick.Added("a"), AnnotationEraser.pick(listOf(added("a")), emptyList(), emptySet(), inside, 3f))
    }

    @Test
    fun `a highlight of the file, made by any app, can be picked`() {
        val file = existing(2)
        assertEquals(ErasePick.Existing(file.ref), AnnotationEraser.pick(emptyList(), listOf(existing(1, line(x = 400f)), file), emptySet(), inside, 3f))
    }

    @Test
    fun `the tolerance reaches just outside the text`() {
        val near = UserPoint(130f, 216f)
        assertNull(AnnotationEraser.pick(listOf(added("a")), emptyList(), emptySet(), near, tolerance = 1f))
        assertEquals(ErasePick.Added("a"), AnnotationEraser.pick(listOf(added("a")), emptyList(), emptySet(), near, tolerance = 3f))
    }

    @Test
    fun `new annotations are above the ones of the file, later above earlier`() {
        val file = existing(0)
        assertEquals(ErasePick.Added("b"), AnnotationEraser.pick(listOf(added("a"), added("b")), listOf(file), emptySet(), inside, 3f))
        val second = existing(1)
        assertEquals(ErasePick.Existing(second.ref), AnnotationEraser.pick(emptyList(), listOf(file, second), emptySet(), inside, 3f))
    }

    @Test
    fun `an annotation of the file that is erased already is skipped`() {
        val top = existing(1)
        val below = existing(0)
        assertEquals(ErasePick.Existing(below.ref), AnnotationEraser.pick(emptyList(), listOf(below, top), setOf(top.ref), inside, 3f))
        assertNull(AnnotationEraser.pick(emptyList(), listOf(below, top), setOf(top.ref, below.ref), inside, 3f))
    }

    @Test
    fun `one the app cannot draw is hit inside its rectangle`() {
        val stamp = existing(0, shape = null, bounds = UserRect(300f, 300f, 360f, 340f))
        assertEquals(ErasePick.Existing(stamp.ref), AnnotationEraser.pick(emptyList(), listOf(stamp), emptySet(), UserPoint(330f, 320f), 3f))
        assertNull(AnnotationEraser.pick(emptyList(), listOf(stamp), emptySet(), UserPoint(330f, 400f), 3f))
    }

    @Test
    fun `an ink stroke is hit near its line`() {
        val ink = AnnotationShape.Ink(listOf(listOf(UserPoint(0f, 0f), UserPoint(100f, 0f))), width = 2f)
        assertEquals(ErasePick.Added("i"), AnnotationEraser.pick(listOf(added("i", ink)), emptyList(), emptySet(), UserPoint(50f, 2f), 3f))
        assertNull(AnnotationEraser.pick(listOf(added("i", ink)), emptyList(), emptySet(), UserPoint(50f, 30f), 3f))
    }
}
