package com.marcogn.pdftoolkit.ui.navigation

import kotlinx.serialization.Serializable

/** Rotte type-safe di Navigation Compose, come nei progetti di riferimento. */
sealed interface Destination {

    @Serializable
    data object Home : Destination

    /** Viewer (SPEC §4.2). In Fase 0 è un segnaposto. */
    @Serializable
    data object Viewer : Destination

    /** Strumento aperto dalla Home; [tool] è il nome di un [com.marcogn.pdftoolkit.domain.model.PdfTool]. */
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
