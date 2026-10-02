package com.marcogn.pdftoolkit.ui.edit

import com.marcogn.pdftoolkit.domain.edit.DocRef
import com.marcogn.pdftoolkit.domain.edit.ImageFit
import com.marcogn.pdftoolkit.domain.edit.PageItem
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FieldWidget
import com.marcogn.pdftoolkit.domain.fill.FormDocument
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.PageBox
import com.marcogn.pdftoolkit.domain.fill.UserRect
import com.marcogn.pdftoolkit.domain.fill.XfaKind
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillDocumentsTest {

    private val rect = UserRect(0f, 0f, 10f, 10f)
    private val field = FormField.CheckBox("agree", null, false, listOf(FieldWidget(1, rect), FieldWidget(1, rect)), FieldValue.Toggle(false))
    private val form = FormDocument(
        pageBoxes = listOf(PageBox(0f, 0f, 600f, 800f, 0), PageBox(10f, 20f, 200f, 300f, 90)),
        fields = listOf(field),
        xfa = XfaKind.NONE,
    )
    private val documents = FillDocuments(mapOf(DocRef.MAIN to form.pageBoxes, DocRef(1) to listOf(PageBox(0f, 0f, 100f, 100f, 180))), form)

    @Test
    fun `a pdf page combines its own rotation with the one the user added`() {
        val item = PageItem.FromPdf("p1", DocRef.MAIN, 1, rotation = 270)
        assertEquals(PdfPageSpace(10f, 20f, 200f, 300f, 0), documents.space(item))
        assertEquals(PdfPageSpace(10f, 20f, 200f, 300f, 90), documents.sourceSpace(item))
        assertEquals(180, documents.space(PageItem.FromPdf("d", DocRef(1), 0))!!.rotation)
    }

    @Test
    fun `blank and image pages have their own size as user space`() {
        assertEquals(PdfPageSpace(0f, 0f, 300f, 400f, 90), documents.space(PageItem.Blank("b", 300f, 400f, 90)))
        assertEquals(PdfPageSpace(0f, 0f, 50f, 60f, 0), documents.space(PageItem.FromImage("i", "x", ImageFit.FIT_PAGE, 50f, 60f)))
    }

    @Test
    fun `unknown documents and pages have no space yet`() {
        assertNull(documents.space(PageItem.FromPdf("x", DocRef(2), 0)))
        assertNull(documents.space(PageItem.FromPdf("x", DocRef.MAIN, 9)))
    }

    @Test
    fun `fields belong to pages of the main document only, once each`() {
        assertEquals(listOf(field), documents.fieldsOn(PageItem.FromPdf("p1", DocRef.MAIN, 1)))
        assertTrue(documents.fieldsOn(PageItem.FromPdf("p0", DocRef.MAIN, 0)).isEmpty())
        assertTrue(documents.fieldsOn(PageItem.FromPdf("d", DocRef(1), 1)).isEmpty())
    }
}
