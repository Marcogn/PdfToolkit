package com.marcogn.pdftoolkit.domain.tools

/** The groups of the tool drawer, in the order it shows them (plan T-b). */
enum class DrawerGroup { TEXT, DRAW, ERASE, FILL }

/**
 * Every tool the drawer offers (plan T-b). The two erasers are two tools here although the viewer keeps one
 * eraser with a mode; "Leggi" (no tool) is an entry of the drawer, not a tool.
 */
enum class DrawerTool(val group: DrawerGroup) {
    HIGHLIGHT(DrawerGroup.TEXT),
    UNDERLINE(DrawerGroup.TEXT),
    STRIKEOUT(DrawerGroup.TEXT),
    PEN(DrawerGroup.DRAW),
    MARKER(DrawerGroup.DRAW),
    ERASER_STROKE(DrawerGroup.ERASE),
    ERASER_PARTIAL(DrawerGroup.ERASE),
    FILL(DrawerGroup.FILL),
}

/**
 * The two quick slots of the tool bar (plan T-b): which tool, if any, sits in each. Immutable; every change
 * returns the new value. The place a tool is dropped is a slot index, or `null` for anywhere else.
 */
data class QuickSlots(val tools: List<DrawerTool?> = List(SLOT_COUNT) { null }) {

    init {
        require(tools.size == SLOT_COUNT) { "exactly $SLOT_COUNT slots" }
    }

    val anyFilled: Boolean get() = tools.any { it != null }

    operator fun get(slot: Int): DrawerTool? = tools[slot]

    fun slotOf(tool: DrawerTool): Int? = tools.indexOf(tool).takeIf { it >= 0 }

    /**
     * A tool let go of. From the drawer ([from] null) a drop on a slot pins it there, replacing what was there
     * (and moving it if it was pinned in the other slot), and a drop anywhere else does nothing. From a slot
     * ([from] its index) a drop on another slot swaps the two, a drop on the bar outside the slots
     * ([overBar]) leaves it, and a drop off the bar unpins it.
     */
    fun drop(tool: DrawerTool, from: Int?, to: Int?, overBar: Boolean): QuickSlots {
        if (from == null) {
            if (to == null) return this
            val without = tools.map { if (it == tool) null else it }
            return QuickSlots(without.toMutableList().also { it[to] = tool })
        }
        return when {
            to == from -> this
            to != null -> QuickSlots(tools.toMutableList().also {
                it[from] = it[to]
                it[to] = tool
            })
            overBar -> this
            else -> QuickSlots(tools.toMutableList().also { it[from] = null })
        }
    }

    companion object {
        const val SLOT_COUNT = 2

        /** Stored as tool names; an unknown name (renamed constant, hand-edited file) or a repeat is an empty slot. */
        fun of(names: List<String?>): QuickSlots {
            val seen = mutableSetOf<DrawerTool>()
            return QuickSlots(
                List(SLOT_COUNT) { slot ->
                    names.getOrNull(slot)?.let { name -> DrawerTool.entries.firstOrNull { it.name == name } }?.takeIf { seen.add(it) }
                },
            )
        }
    }
}
