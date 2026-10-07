package com.marcogn.pdftoolkit.domain.annotate

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compact form of freehand polylines in the saved session (phase 8a). */
class CompactPolylineSerializerTest {

    private val json = Json

    private val ink = AnnotationShape.Ink(
        strokes = listOf(listOf(UserPoint(100.25f, 200f), UserPoint(99.5f, 210.75f), UserPoint(-3.01f, 0.07f))),
        width = 2f,
        outlines = listOf(listOf(UserPoint(1f, 1f), UserPoint(2f, 1f), UserPoint(2f, 3.33f))),
        highlighter = true,
    )

    @Test
    fun `first point, then steps, in hundredths of a point`() {
        val points = listOf(UserPoint(100.25f, 200f), UserPoint(99.5f, 210.75f), UserPoint(-3.01f, 0.07f))
        assertEquals("10025 20000 -75 1075 -10251 -21068", CompactPolylineSerializer.encode(points))
        assertEquals(points, CompactPolylineSerializer.decode(CompactPolylineSerializer.encode(points)))
        assertEquals(emptyList<UserPoint>(), CompactPolylineSerializer.decode(""))
    }

    @Test
    fun `an ink shape survives a JSON round trip and is much shorter than a list of points`() {
        val text = json.encodeToString(AnnotationShape.serializer(), ink)
        assertEquals(ink, json.decodeFromString(AnnotationShape.serializer(), text))
        // 200 points of an outline: a third or less of the {"x":..,"y":..} form.
        val outline = (0 until 200).map { UserPoint(CompactPolylineSerializer.round(300f + it * 0.37f), CompactPolylineSerializer.round(400f - it * 0.21f)) }
        val compact = CompactPolylineSerializer.encode(outline).length
        val verbose = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UserPoint.serializer()), outline).length
        assertTrue("compact $compact vs $verbose", compact * 3 <= verbose)
    }

    @Test
    fun `ink written by 7a, points as objects and no outlines, is still read`() {
        val old = """{"type":"ink","strokes":[[{"x":10.0,"y":20.0},{"x":40.5,"y":60.0}]],"width":3.0}"""
        val shape = json.decodeFromString(AnnotationShape.serializer(), old) as AnnotationShape.Ink
        assertEquals(listOf(listOf(UserPoint(10f, 20f), UserPoint(40.5f, 60f))), shape.strokes)
        assertTrue(shape.outlines.isEmpty())
        assertEquals(false, shape.highlighter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an odd count of numbers is refused`() {
        CompactPolylineSerializer.decode("1 2 3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `something that isn't a number is refused`() {
        CompactPolylineSerializer.decode("1 x")
    }

    @Test
    fun `rounding matches what decoding gives back`() {
        listOf(0.004f, 0.005f, 123.456f, -77.777f, 841.89f).forEach { value ->
            val rounded = CompactPolylineSerializer.round(value)
            val back = CompactPolylineSerializer.decode(CompactPolylineSerializer.encode(listOf(UserPoint(rounded, value)))).single()
            assertEquals(rounded, back.x)
            assertEquals(rounded, CompactPolylineSerializer.round(back.y))
        }
    }
}
