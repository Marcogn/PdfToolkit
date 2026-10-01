package com.marcogn.pdftoolkit.domain.model

/**
 * Tools of Home and of the edit hub (spec §4.1, §4.3), in the order they appear.
 *
 * [comingSoon]: product phase 2 tool (spec §7), shown disabled with the "Soon" badge.
 * [requiresDocument]: with no open document, a tap opens the PDF picker first (spec §4.1).
 */
enum class PdfTool(
    val comingSoon: Boolean = false,
    val requiresDocument: Boolean = true,
) {
    MERGE,
    ADD_PAGES,
    INSERT_IMAGES,
    REMOVE_PAGES,
    REORDER_PAGES,
    FILL_AND_SIGN,
    MY_SIGNATURES(requiresDocument = false),
    SCAN(comingSoon = true, requiresDocument = false),
    CLOUD_UPLOAD(comingSoon = true),
    HIGHLIGHT(comingSoon = true),
    DRAW(comingSoon = true),
    EXPORT_ODF(comingSoon = true),
    ;

    companion object {
        val available: List<PdfTool> = entries.filterNot { it.comingSoon }
        val upcoming: List<PdfTool> = entries.filter { it.comingSoon }
    }
}
