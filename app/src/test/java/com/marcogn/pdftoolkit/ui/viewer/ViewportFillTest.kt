package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FieldWidget
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import com.marcogn.pdftoolkit.domain.fill.UserRect
import com.marcogn.pdftoolkit.pdf.render.DocumentLayout
import com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper
import com.marcogn.pdftoolkit.pdf.render.PageSize
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.marcogn.pdftoolkit.pdf.render.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where fill content lands in a viewport of several pages (plan V-c): form controls on their widgets
 * across pages and zooms, on turned pages, in single-page viewports; overlays hit on their own page.
 */
class ViewportFillTest {

    // Widest page 600 pt on a 1200 px viewport: 2 px per point, gap 10 px.
    //   page 0: x 0..1200,   y 10..1610   (600 x 800 pt)
    //   page 1: x 300..900,  y 1620..2420 (300 x 400 pt)
    private val layout = DocumentLayout.continuous(listOf(PageSize(600f, 800f), PageSize(300f, 400f)), viewportWidth = 1200f, gap = 10f)
    private val spaces = listOf(PdfPageSpace.ofSize(600f, 800f), PdfPageSpace.ofSize(300f, 400f))
    private val screen = Size(1200f, 1000f)

    private fun text(name: String, vararg widgets: FieldWidget) = FormField.Text(
        name = name,
        label = null,
        readOnly = false,
        widgets = widgets.toList(),
        value = FieldValue.Text(""),
        multiline = false,
        maxLength = null,
        fontSize = 0f,
    )

    private fun fill(viewport: Viewport, offset: Int = 0, layout: DocumentLayout = this.layout, spaceOf: (Int) -> PdfPageSpace? = { spaces.getOrNull(it) }) =
        ViewportFill(PageCoordinateMapper(layout, viewport), offset, spaceOf)

    @Test
    fun `a control sits on its widget at any zoom and scroll`() {
        // User rect 10..110 x 20..60 on page 1 → display points 10..110 x 340..380.
        val field = text("name", FieldWidget(1, UserRect(10f, 20f, 110f, 60f)))
        val fieldsOn: (Int) -> List<FormField> = { if (it == 1) listOf(field) else emptyList() }

        val atFit = fill(Viewport(1f, Offset(0f, 1000f))).controls(screen, fieldsOn).single()
        assertEquals(1, atFit.page)
        assertEquals(320f, atFit.rect.left, 0.01f)
        assertEquals(1300f, atFit.rect.top, 0.01f)
        assertEquals(520f, atFit.rect.right, 0.01f)
        assertEquals(1380f, atFit.rect.bottom, 0.01f)
        assertEquals(0, atFit.rotation)

        // Zoom 2: layout * 2 - offset.
        val zoomed = fill(Viewport(2f, Offset(600f, 3000f))).controls(screen, fieldsOn).single()
        assertEquals(40f, zoomed.rect.left, 0.01f)
        assertEquals(1600f, zoomed.rect.top, 0.01f)
        assertEquals(440f, zoomed.rect.right, 0.01f)
        assertEquals(1760f, zoomed.rect.bottom, 0.01f)
    }

    @Test
    fun `only the pages on screen get controls`() {
        val first = text("a", FieldWidget(0, UserRect(10f, 10f, 50f, 30f)))
        val second = text("b", FieldWidget(1, UserRect(10f, 10f, 50f, 30f)))
        val fieldsOn: (Int) -> List<FormField> = { listOf(first, second).filter { f -> f.widgets.any { w -> w.pageIndex == it } } }
        // The top of the document: layout y 0..1000, page 0 only.
        val top = fill(Viewport(1f, Offset.Zero))
        assertEquals(0..0, top.visiblePages(screen))
        assertEquals(listOf("a"), top.controls(screen, fieldsOn).map { it.field.name })
        // Both pages.
        val middle = fill(Viewport(1f, Offset(0f, 1000f)))
        assertEquals(0..1, middle.visiblePages(screen))
        assertEquals(listOf("a", "b"), middle.controls(screen, fieldsOn).map { it.field.name })
    }

    @Test
    fun `a field with widgets on two pages gets a control on each`() {
        val field = text("date", FieldWidget(0, UserRect(10f, 10f, 50f, 30f)), FieldWidget(1, UserRect(10f, 10f, 50f, 30f)))
        val controls = fill(Viewport(1f, Offset(0f, 1000f))).controls(screen, { listOf(field) })
        assertEquals(listOf(0 to 0, 1 to 1), controls.map { it.page to it.widgetIndex })
    }

    @Test
    fun `without editing only the fields with a pending value show`() {
        val a = text("a", FieldWidget(0, UserRect(10f, 10f, 50f, 30f)))
        val b = text("b", FieldWidget(0, UserRect(10f, 40f, 50f, 60f)))
        val values = mapOf("b" to FieldValue.Text("x"))
        val controls = fill(Viewport()).controls(screen, { listOf(a, b) }) { it.name in values }
        assertEquals(listOf("b"), controls.map { it.field.name })
    }

    @Test
    fun `on a page turned by a quarter the control turns with it`() {
        // Page 0 has /Rotate 90: user space 800 x 600, shown 600 x 800.
        val turned = PdfPageSpace(0f, 0f, 800f, 600f, 90)
        val field = text("t", FieldWidget(0, UserRect(100f, 200f, 300f, 250f)))
        val control = fill(Viewport(), spaceOf = { if (it == 0) turned else spaces.getOrNull(it) })
            .controls(screen, { if (it == 0) listOf(field) else emptyList() }).single()
        assertEquals(90, control.rotation)
        // 200 x 50 pt in user space stand 50 x 200 on the page, at 2 px per point.
        assertEquals(100f, control.rect.width, 0.01f)
        assertEquals(400f, control.rect.height, 0.01f)
    }

    @Test
    fun `in a single-page viewport the page keeps its document index`() {
        // Document page 5 alone: 300 pt on 1200 px, 4 px per point.
        val single = DocumentLayout.continuous(listOf(PageSize(300f, 400f)), viewportWidth = 1200f, gap = 10f)
        val field = text("p5", FieldWidget(5, UserRect(10f, 360f, 60f, 390f)))
        val geometry = fill(Viewport(), offset = 5, layout = single, spaceOf = { if (it == 5) PdfPageSpace.ofSize(300f, 400f) else null })
        assertEquals(5..5, geometry.visiblePages(screen))
        val control = geometry.controls(screen, { if (it == 5) listOf(field) else emptyList() }).single()
        assertEquals(5, control.page)
        // Display y 10..40 → layout 10 + 40..160.
        assertEquals(40f, control.rect.left, 0.01f)
        assertEquals(50f, control.rect.top, 0.01f)
        assertEquals(240f, control.rect.right, 0.01f)
        assertEquals(170f, control.rect.bottom, 0.01f)
        // A page that isn't laid out here maps nowhere.
        assertNull(geometry.toUser(4, Offset(10f, 10f)))
    }

    // A 40 x 20 pt tick on page 1 centred at user (150, 200) → display (150, 200) → layout (600, 2020).
    private val onSecond = MarkOverlay("o1", ViewerEditSession.pageId(1), OverlayBox(150f, 200f, 40f, 20f), MarkKind.CHECK)
    private val onFirst = MarkOverlay("o0", ViewerEditSession.pageId(0), OverlayBox(150f, 200f, 40f, 20f), MarkKind.CROSS)

    @Test
    fun `an overlay is hit on its own page only`() {
        val geometry = fill(Viewport(1f, Offset(0f, 1000f)))
        assertEquals(onSecond, geometry.overlayAt(listOf(onFirst, onSecond), Offset(600f, 1020f)))
        // The gap between the pages.
        assertNull(geometry.overlayAt(listOf(onFirst, onSecond), Offset(600f, 615f)))
        // The same user point on page 0 is elsewhere on screen: layout (300, 1210).
        assertEquals(onFirst, geometry.overlayAt(listOf(onFirst, onSecond), Offset(300f, 210f)))
    }

    @Test
    fun `the topmost overlay wins`() {
        val below = onSecond.copy(id = "below")
        val geometry = fill(Viewport(1f, Offset(0f, 1000f)))
        assertEquals(onSecond, geometry.overlayAt(listOf(below, onSecond), Offset(600f, 1020f)))
    }

    @Test
    fun `the selected overlay is grabbed within the margin, measured in its page`() {
        val geometry = fill(Viewport(1f, Offset(0f, 1000f)))
        // Right edge at user x 170 → screen x 640; 10 px past it is 5 pt.
        val beside = Offset(650f, 1020f)
        assertTrue(geometry.grabs(onSecond, beside, marginPx = 20f))
        assertFalse(geometry.grabs(onSecond, beside, marginPx = 0f))
        // At zoom 2 the same 10 px are 2.5 pt: 12 px (3 pt) reach, 8 px (2 pt) don't.
        val zoomed = fill(Viewport(2f, Offset(0f, 3000f)))
        val edge = Offset(640f * 2f, 2020f * 2f - 3000f)
        assertTrue(zoomed.grabs(onSecond, edge + Offset(10f, 0f), marginPx = 12f))
        assertFalse(zoomed.grabs(onSecond, edge + Offset(10f, 0f), marginPx = 8f))
    }

    @Test
    fun `the handle is the bottom-right corner as drawn`() {
        val handle = fill(Viewport(1f, Offset(0f, 1000f))).handle(onSecond)!!
        // Right x 170, bottom user y 190 → display (170, 210) → layout (640, 2040).
        assertEquals(640f, handle.x, 0.01f)
        assertEquals(1040f, handle.y, 0.01f)
        // Not in this viewport: no handle.
        assertNull(fill(Viewport(), offset = 3, layout = layout).handle(onSecond))
    }
}
