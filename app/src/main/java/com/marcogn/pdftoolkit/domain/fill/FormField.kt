package com.marcogn.pdftoolkit.domain.fill

/** A rectangle in PDF user space (points, y up), as a form widget's `/Rect`. */
data class UserRect(val left: Float, val bottom: Float, val right: Float, val top: Float) {
    val width: Float get() = right - left
    val height: Float get() = top - bottom
}

/**
 * One place where a field shows on a page: [pageIndex] is the page of the **source document**
 * (not of the edit session), [rect] its rectangle in that page's user space.
 */
data class FieldWidget(val pageIndex: Int, val rect: UserRect)

/** One option of a radio group, combo box or list: [value] is what gets stored, [label] what is shown. */
data class FieldOption(val value: String, val label: String)

/**
 * An AcroForm field the app can fill (spec §6.5), read from the PDF by `pdf/forms`. [name] is the
 * fully qualified name, the key of [FieldValue]s. [label] is the field's tooltip (`/TU`) if any,
 * for accessibility. [value] is the value in the file.
 */
sealed interface FormField {
    val name: String
    val label: String?
    val readOnly: Boolean
    val widgets: List<FieldWidget>
    val value: FieldValue

    /** [fontSize] from the field's default appearance, 0 for "auto" (fit the box). */
    data class Text(
        override val name: String,
        override val label: String?,
        override val readOnly: Boolean,
        override val widgets: List<FieldWidget>,
        override val value: FieldValue.Text,
        val multiline: Boolean,
        val maxLength: Int?,
        val fontSize: Float,
    ) : FormField

    data class CheckBox(
        override val name: String,
        override val label: String?,
        override val readOnly: Boolean,
        override val widgets: List<FieldWidget>,
        override val value: FieldValue.Toggle,
    ) : FormField

    /** [widgetValues] has, for each widget in [widgets], the value that selects it. */
    data class Radio(
        override val name: String,
        override val label: String?,
        override val readOnly: Boolean,
        override val widgets: List<FieldWidget>,
        override val value: FieldValue.Choice,
        val widgetValues: List<String>,
    ) : FormField

    /** Combo box or list box, one value at a time. */
    data class Choice(
        override val name: String,
        override val label: String?,
        override val readOnly: Boolean,
        override val widgets: List<FieldWidget>,
        override val value: FieldValue.Choice,
        val options: List<FieldOption>,
    ) : FormField
}

/** XFA forms (spec §6.5) aren't supported: [DYNAMIC] has no AcroForm fields to fill at all. */
enum class XfaKind { NONE, STATIC, DYNAMIC }

/**
 * What "Fill and sign" needs to know about one source PDF: the user-space box and rotation of
 * every page (to place overlays and fields) and its form. [pageBoxes] is indexed like the
 * document's pages.
 */
data class FormDocument(
    val pageBoxes: List<PageBox>,
    val fields: List<FormField>,
    val xfa: XfaKind,
) {
    val hasFields: Boolean get() = fields.isNotEmpty()
}

/**
 * A page's visible box in user space (`/CropBox` clipped to `/MediaBox`) and its `/Rotate`,
 * already normalised to 0, 90, 180 or 270.
 */
data class PageBox(val left: Float, val bottom: Float, val width: Float, val height: Float, val rotation: Int)
