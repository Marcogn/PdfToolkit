package com.marcogn.pdftoolkit.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes, as in the reference projects. */
sealed interface Destination {

    @Serializable
    data object Home : Destination

    /**
     * Viewer (spec §4.2) on the document at [uri] (`content://` from SAF, or `file://`). [tool] is the name of the
     * [com.marcogn.pdftoolkit.domain.model.PdfTool] armed on opening (Highlight or Draw tapped on Home, plan V-b), or null.
     */
    @Serializable
    data class Viewer(val uri: String, val tool: String? = null) : Destination

    /**
     * The edit screen (spec §6): "Organize pages" on the document at [uri], or with [tool] set to
     * `FILL_AND_SIGN` (the name of a [com.marcogn.pdftoolkit.domain.model.PdfTool]) "Fill and sign".
     *
     * A merge (spec §6.6) is an edit whose [mergeWith] lists the other PDFs, in order, after the
     * main one at [uri]; with [autoSave] it asks where to save the result straight away.
     *
     * Opened from the viewer, [page] is the page being read (index in the main document, -1 from Home):
     * the Fill pane starts on it and the insertion dialogs default to "after" it.
     */
    @Serializable
    data class Edit(
        val uri: String,
        val tool: String? = null,
        val mergeWith: List<String> = emptyList(),
        val autoSave: Boolean = false,
        val page: Int = -1,
    ) : Destination

    /** Merge list (spec §6.6): the PDFs at [uris] in the order picked, to reorder, add to and combine. */
    @Serializable
    data class Merge(val uris: List<String> = emptyList()) : Destination

    /** Tool opened from Home; [tool] is the name of a [com.marcogn.pdftoolkit.domain.model.PdfTool]. */
    @Serializable
    data class Tool(val tool: String) : Destination

    @Serializable
    data object Recents : Destination

    @Serializable
    data object Signatures : Destination

    @Serializable
    data object Settings : Destination

    @Serializable
    data object About : Destination
}
