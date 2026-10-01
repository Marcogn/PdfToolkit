package com.marcogn.pdftoolkit.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes, as in the reference projects. */
sealed interface Destination {

    @Serializable
    data object Home : Destination

    /** Viewer (spec §4.2) on the document at [uri] (`content://` from SAF, or `file://`). */
    @Serializable
    data class Viewer(val uri: String) : Destination

    /**
     * Edit hub and page tools (spec §4.3, §6) on the document at [uri]. [tool] is the name of the
     * [com.marcogn.pdftoolkit.domain.model.PdfTool] to open straight on (from Home), or null for the hub.
     */
    @Serializable
    data class Edit(val uri: String, val tool: String? = null) : Destination

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
