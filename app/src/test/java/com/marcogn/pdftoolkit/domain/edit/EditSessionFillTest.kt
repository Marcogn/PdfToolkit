package com.marcogn.pdftoolkit.domain.edit

import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.ImageOverlay
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkOverlay
import com.marcogn.pdftoolkit.domain.fill.OverlayBox
import com.marcogn.pdftoolkit.domain.fill.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Overlays and form values in the edit session (spec §6.1, §6.5). */
class EditSessionFillTest {

    private val box = OverlayBox(100f, 200f, 40f, 12f, 90f)
    private val text = TextOverlay("t1", "p1", box, "Mario Rossi, città", 12f)
    private val tick = MarkOverlay("m1", "p0", box, MarkKind.CHECK)

    @Test
    fun `an overlay is a change, and undo takes it away`() {
        val session = EditSession.of(3).addOverlay(text)
        assertEquals(listOf(text), session.fill.overlays)
        assertTrue(session.isModified)
        val undone = session.undo()
        assertTrue(undone.fill.overlays.isEmpty())
        assertFalse(undone.isModified)
        assertEquals(listOf(text), undone.redo().fill.overlays)
    }

    @Test
    fun `overlays only go on pages of the session and ids are unique`() {
        val session = EditSession.of(2)
        assertSame(session, session.addOverlay(text.copy(pageId = "nope")))
        val one = session.addOverlay(tick)
        assertSame(one, one.addOverlay(tick.copy(box = box.copy(centerX = 1f))))
    }

    @Test
    fun `update and remove`() {
        val session = EditSession.of(2).addOverlay(text)
        val edited = session.updateOverlay(text.copy(text = "Luigi"))
        assertEquals("Luigi", (edited.fill.overlays.single() as TextOverlay).text)
        assertSame(edited, edited.updateOverlay(text.copy(text = "Luigi")))
        assertSame(edited, edited.removeOverlay("unknown"))
        assertTrue(edited.removeOverlay("t1").fill.overlays.isEmpty())
        assertEquals("Mario Rossi, città", (edited.undo().fill.overlays.single() as TextOverlay).text)
    }

    @Test
    fun `overlays follow their page through moves and survive its removal for undo`() {
        val session = EditSession.of(3).addOverlay(text).move(1, 2)
        assertEquals("p1", session.pages[2].id)
        assertEquals(listOf(text), session.fill.overlaysOn("p1"))
        val removed = session.remove(setOf("p1"))
        assertEquals(listOf(text), removed.fill.overlays) // written only if the page is still there
        assertEquals(3, removed.undo().pageCount)
    }

    @Test
    fun `page operations keep the overlays and undo restores both`() {
        val session = EditSession.of(3).addOverlay(tick).rotate(setOf("p0"), 90)
        assertEquals(listOf(tick), session.fill.overlays)
        val back = session.undo()
        assertEquals(0, back.pages[0].rotation)
        assertEquals(listOf(tick), back.fill.overlays)
        assertTrue(back.undo().fill.overlays.isEmpty())
    }

    @Test
    fun `typing in one field is a single undo step`() {
        var session = EditSession.of(1)
        for (typed in listOf("M", "Ma", "Mar", "Mario")) session = session.setField("name", FieldValue.Text(typed), typing = true)
        assertEquals(FieldValue.Text("Mario"), session.fill.fields["name"])
        assertTrue(session.undo().fill.fields.isEmpty())
    }

    @Test
    fun `typing in another field, or any other change, starts a new step`() {
        var session = EditSession.of(1)
            .setField("name", FieldValue.Text("Mario"), typing = true)
            .setField("city", FieldValue.Text("Roma"), typing = true)
            .setField("city", FieldValue.Text("Roma!"), typing = true)
        assertEquals(mapOf("name" to FieldValue.Text("Mario")), session.undo().fill.fields)
        session = session.setField("agree", FieldValue.Toggle(true)).setField("city", FieldValue.Text("Rome"), typing = true)
        assertEquals(FieldValue.Text("Roma!"), session.undo().fill.fields["city"])
    }

    @Test
    fun `null brings a field back to the file's value`() {
        val session = EditSession.of(1).setField("agree", FieldValue.Toggle(true))
        val cleared = session.setField("agree", null)
        assertTrue(cleared.fill.fields.isEmpty())
        assertFalse(cleared.isModified)
        assertSame(cleared, cleared.setField("agree", null))
    }

    @Test
    fun `fill content survives encoding, accents and separators included`() {
        val image = ImageOverlay("i1", "p0", OverlayBox(1f, 2f, 3f, 4f), "file:///data/x;y,z.png")
        val session = EditSession.of(2)
            .addOverlay(text)
            .addOverlay(tick)
            .addOverlay(image)
            .setField("a.b", FieldValue.Text("perché; sì,\nno"))
            .setField("c", FieldValue.Choice(null))
            .setField("d", FieldValue.Toggle(false))
        val decoded = EditSession.decode(session.encode(), 2, session.encodeFill())
        assertNotNull(decoded)
        assertEquals(session.fill, decoded!!.fill)
        assertTrue(decoded.fill.hasSignature)
    }

    @Test
    fun `an empty fill encodes as nothing and garbage is rejected`() {
        val session = EditSession.of(2)
        assertEquals("", session.encodeFill())
        assertNotNull(EditSession.decode(session.encode(), 2, ""))
        assertNull(EditSession.decode(session.encode(), 2, "{not json"))
        // A box with a negative size fails its own check.
        assertNull(EditSession.decode(session.encode(), 2, """{"overlays":[{"type":"mark","id":"m","pageId":"p0","box":{"centerX":0,"centerY":0,"width":-1,"height":1},"kind":"CHECK"}]}"""))
    }
}
