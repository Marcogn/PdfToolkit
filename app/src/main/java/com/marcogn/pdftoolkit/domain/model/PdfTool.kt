package com.marcogn.pdftoolkit.domain.model

/**
 * Strumenti della Home e dell'Hub modifiche (SPEC §4.1, §4.3), nell'ordine in cui compaiono.
 *
 * [comingSoon]: strumento di Fase 2 del prodotto (SPEC §7), mostrato disabilitato con l'etichetta
 * "Presto". [requiresDocument]: senza un documento aperto, il tap apre prima il selettore PDF
 * (SPEC §4.1).
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
