package com.marcogn.pdftoolkit.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes, as in the reference projects. */
sealed interface Destination {

    @Serializable
    data object Home : Destination

    /** Viewer (spec §4.2). A placeholder in phase 0. */
    @Serializable
    data object Viewer : Destination

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
