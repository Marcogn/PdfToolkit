package com.marcogn.pdftoolkit.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSlotsTest {

    private val empty = QuickSlots()

    @Test
    fun `no tool is pinned at first`() {
        assertFalse(empty.anyFilled)
        assertEquals(null, empty[0])
    }

    @Test
    fun `a drop from the drawer on an empty slot pins the tool`() {
        val pinned = empty.drop(DrawerTool.PEN, from = null, to = 1, overBar = true)
        assertEquals(DrawerTool.PEN, pinned[1])
        assertEquals(null, pinned[0])
        assertTrue(pinned.anyFilled)
    }

    @Test
    fun `a drop from the drawer anywhere else does nothing`() {
        assertEquals(empty, empty.drop(DrawerTool.PEN, from = null, to = null, overBar = true))
        assertEquals(empty, empty.drop(DrawerTool.PEN, from = null, to = null, overBar = false))
    }

    @Test
    fun `a drop on a full slot replaces its tool`() {
        val full = QuickSlots(listOf(DrawerTool.PEN, DrawerTool.MARKER))
        val replaced = full.drop(DrawerTool.HIGHLIGHT, from = null, to = 0, overBar = true)
        assertEquals(listOf(DrawerTool.HIGHLIGHT, DrawerTool.MARKER), replaced.tools)
    }

    @Test
    fun `a tool is never pinned twice`() {
        val pinned = QuickSlots(listOf(DrawerTool.PEN, null)).drop(DrawerTool.PEN, from = null, to = 1, overBar = true)
        assertEquals(listOf(null, DrawerTool.PEN), pinned.tools)
    }

    @Test
    fun `a quick tool dropped off the bar is unpinned`() {
        val full = QuickSlots(listOf(DrawerTool.PEN, DrawerTool.MARKER))
        assertEquals(listOf(null, DrawerTool.MARKER), full.drop(DrawerTool.PEN, from = 0, to = null, overBar = false).tools)
    }

    @Test
    fun `a quick tool dropped on the bar outside the slots stays`() {
        val full = QuickSlots(listOf(DrawerTool.PEN, DrawerTool.MARKER))
        assertEquals(full, full.drop(DrawerTool.PEN, from = 0, to = null, overBar = true))
    }

    @Test
    fun `a quick tool dropped on the other slot swaps the two`() {
        val full = QuickSlots(listOf(DrawerTool.PEN, DrawerTool.MARKER))
        assertEquals(listOf(DrawerTool.MARKER, DrawerTool.PEN), full.drop(DrawerTool.PEN, from = 0, to = 1, overBar = true).tools)
        assertEquals(full, full.drop(DrawerTool.PEN, from = 0, to = 0, overBar = true))
    }

    @Test
    fun `stored names that are unknown or repeated leave an empty slot`() {
        assertEquals(listOf(DrawerTool.PEN, null), QuickSlots.of(listOf("PEN", "PEN")).tools)
        assertEquals(listOf(null, DrawerTool.MARKER), QuickSlots.of(listOf("GONE", "MARKER")).tools)
        assertEquals(empty, QuickSlots.of(emptyList()))
    }

    @Test
    fun `the drawer groups every tool`() {
        assertEquals(setOf(DrawerGroup.TEXT, DrawerGroup.DRAW, DrawerGroup.ERASE, DrawerGroup.FILL), DrawerTool.entries.map { it.group }.toSet())
    }
}
