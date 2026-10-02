package com.marcogn.pdftoolkit.pdf.forms

import com.marcogn.pdftoolkit.domain.fill.FieldOption
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FieldWidget
import com.marcogn.pdftoolkit.domain.fill.FormDocument
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.PageBox
import com.marcogn.pdftoolkit.domain.fill.UserRect
import com.marcogn.pdftoolkit.domain.fill.XfaKind
import com.marcogn.pdftoolkit.pdf.render.PdfPageSpace
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/** Reads what "Fill and sign" needs from a PDF (spec §6.5); the UI never touches PdfBox (ADR 0002). */
interface FormReader {
    /**
     * Page boxes and, with [withFields], the AcroForm fields of the PDF read from [open]. Null if
     * it can't be read (missing, damaged, password-protected).
     */
    suspend fun read(open: () -> InputStream?, withFields: Boolean = true): FormDocument?
}

/**
 * [FormReader] on PdfBox-Android. Fields it can fill: text, check box, radio group, combo and list
 * box (one value). Push buttons and digital signature fields are left out.
 */
class PdfBoxFormReader @Inject constructor() : FormReader {

    override suspend fun read(open: () -> InputStream?, withFields: Boolean): FormDocument? = withContext(Dispatchers.IO) {
        try {
            val stream = open() ?: return@withContext null
            stream.use {
                PDDocument.load(it, MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)).use { document -> read(document, withFields) }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    companion object {
        private const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024

        /** US Letter: pdfium's size for a page without a usable `/MediaBox`. */
        private val LETTER = PDRectangle(0f, 0f, 612f, 792f)

        private val FONT_SIZE = Regex("""(\d+(?:\.\d+)?)\s+Tf""")

        /** Reads an open document; also used by the editor's tests. */
        fun read(document: PDDocument, withFields: Boolean): FormDocument {
            val pages = document.pages.toList()
            val boxes = pages.map(::pageBox)
            val form = document.documentCatalog.acroForm
            val xfa = when {
                form == null || !form.hasXFA() -> XfaKind.NONE
                form.xfaIsDynamic() -> XfaKind.DYNAMIC
                else -> XfaKind.STATIC
            }
            val fields = if (withFields && form != null) readFields(form, pages) else emptyList()
            return FormDocument(boxes, fields, xfa)
        }

        /**
         * The box pdfium shows: `/CropBox` clipped to `/MediaBox` (PdfBox's `getCropBox` already
         * clips), the media box if that is empty, Letter if both are.
         */
        fun pageBox(page: PDPage): PageBox {
            val crop = page.cropBox
            val media = page.mediaBox
            val box = when {
                crop.width > 0f && crop.height > 0f -> crop
                media.width > 0f && media.height > 0f -> media
                else -> LETTER
            }
            return PageBox(box.lowerLeftX, box.lowerLeftY, box.width, box.height, PdfPageSpace.normalizeRotation(page.rotation))
        }

        private fun readFields(form: PDAcroForm, pages: List<PDPage>): List<FormField> {
            // Widgets are found through the pages' /Annots (identity of the dictionaries): the
            // widget's own /P is optional and sometimes wrong.
            val pageOf = HashMap<COSDictionary, Int>()
            pages.forEachIndexed { index, page ->
                page.annotations.forEach { pageOf.putIfAbsent(it.cosObject, index) }
            }
            return form.fieldTree.mapNotNull { field -> toFormField(field, pageOf, pages) }
        }

        private fun toFormField(field: PDField, pageOf: Map<COSDictionary, Int>, pages: List<PDPage>): FormField? {
            val widgetPairs = field.widgets.mapNotNull { widget -> widget(widget, pageOf, pages)?.let { widget to it } }
            if (widgetPairs.isEmpty()) return null
            val widgets = widgetPairs.map { it.second }
            val name = field.fullyQualifiedName ?: return null
            val label = field.alternateFieldName?.takeIf { it.isNotBlank() }
            return when (field) {
                is PDTextField -> FormField.Text(
                    name, label, field.isReadOnly, widgets,
                    FieldValue.Text(field.value.orEmpty()),
                    multiline = field.isMultiline,
                    maxLength = field.maxLen.takeIf { it > 0 },
                    fontSize = field.defaultAppearance?.let { FONT_SIZE.find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f,
                )
                is PDCheckBox -> FormField.CheckBox(name, label, field.isReadOnly, widgets, FieldValue.Toggle(field.isChecked))
                is PDRadioButton -> {
                    // What selects each widget, as PDButton.setValue expects it: the /Opt export
                    // value when there is one, otherwise the widget's "on" appearance state.
                    val exports = field.exportValues
                    val all = field.widgets
                    val values = widgetPairs.map { (widget, _) -> exports.getOrNull(all.indexOf(widget)) ?: onState(widget) }
                    val current = field.value.takeIf { it != COSName.Off.name }
                    FormField.Radio(name, label, field.isReadOnly, widgets, FieldValue.Choice(current), values)
                }
                is PDChoice -> {
                    val exports = field.optionsExportValues
                    val labels = field.optionsDisplayValues
                    val options = exports.mapIndexed { i, value -> FieldOption(value, labels.getOrNull(i) ?: value) }
                    FormField.Choice(name, label, field.isReadOnly, widgets, FieldValue.Choice(field.value.firstOrNull()), options)
                }
                else -> null // push buttons, signature fields, non-terminal nodes
            }
        }

        private fun widget(widget: PDAnnotationWidget, pageOf: Map<COSDictionary, Int>, pages: List<PDPage>): FieldWidget? {
            if (widget.isHidden || widget.isNoView) return null
            val pageIndex = pageOf[widget.cosObject] ?: widget.page?.let { page -> pages.indexOf(page).takeIf { it >= 0 } } ?: return null
            val rect = widget.rectangle ?: return null
            if (rect.width <= 0f || rect.height <= 0f) return null
            return FieldWidget(pageIndex, UserRect(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY))
        }

        private fun onState(widget: PDAnnotationWidget): String =
            widget.appearance?.normalAppearance?.takeIf { it.isSubDictionary }?.subDictionary?.keys
                ?.firstOrNull { it != COSName.Off }?.name.orEmpty()
    }
}
