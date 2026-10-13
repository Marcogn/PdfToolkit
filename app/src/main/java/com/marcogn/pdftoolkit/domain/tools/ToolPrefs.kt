package com.marcogn.pdftoolkit.domain.tools

import com.marcogn.pdftoolkit.domain.annotate.FreehandKind
import com.marcogn.pdftoolkit.domain.annotate.FreehandOptions

/**
 * What the viewer's tools keep across restarts (plan T-b): colours and sizes as positions in their palettes,
 * the partial eraser's mode and size, and the tool last used in the text and drawing groups.
 */
data class ToolStyleSettings(
    val highlightColor: Int = 0,
    val lineColor: Int = 0,
    val penColor: Int = FreehandOptions.defaultColorIndex(FreehandKind.PEN),
    val penWidth: Int = FreehandOptions.defaultWidthIndex(FreehandKind.PEN),
    val markerColor: Int = FreehandOptions.defaultColorIndex(FreehandKind.HIGHLIGHTER),
    val markerWidth: Int = FreehandOptions.defaultWidthIndex(FreehandKind.HIGHLIGHTER),
    val partialEraser: Boolean = false,
    val eraserWidth: Int = FreehandOptions.defaultWidthIndex(FreehandKind.PEN),
    val lastMarkup: DrawerTool = DrawerTool.HIGHLIGHT,
    val lastBrush: DrawerTool = DrawerTool.PEN,
)

/** Everything of the tool drawer that is kept: the quick slots, the two one-time teachings and the style. */
data class ToolPrefs(
    val slots: QuickSlots = QuickSlots(),
    /** A tool was pinned once: the drawer stops showing the hint about dragging (plan T-b). */
    val pinHintDone: Boolean = false,
    /** The three-step tutorial was seen (replayed from Settings and About by clearing this). */
    val tutorialSeen: Boolean = false,
    val style: ToolStyleSettings = ToolStyleSettings(),
)
