package com.marcogn.pdftoolkit.ui.viewer

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanLayoutTest {

    private fun portrait(count: Int) =
        FanLayout.positions(count, startDeg = 90f, endDeg = 0f, itemSpacingDp = 72f)

    @Test
    fun `every item gets a place`() {
        for (count in 0..12) assertEquals(count, portrait(count).size)
    }

    @Test
    fun `the fan unfolds upward and to the right in the bottom bar`() {
        portrait(9).forEach { assertTrue("x=${it.x}", it.x >= -0.01f); assertTrue("y=${it.y}", it.y <= 0.01f) }
    }

    @Test
    fun `the rail unfolds leftward and downward`() {
        FanLayout.positions(9, startDeg = 180f, endDeg = 270f, itemSpacingDp = 72f)
            .forEach { assertTrue(it.x <= 0.01f); assertTrue(it.y >= -0.01f) }
    }

    @Test
    fun `neighbours keep their distance when the room allows rings`() {
        val items = portrait(9)
        for (a in items.indices) for (b in a + 1 until items.size) {
            val distance = hypot(items[a].x - items[b].x, items[a].y - items[b].y)
            assertTrue("$a-$b at $distance", distance >= 56f)
        }
    }

    @Test
    fun `nine items need more than one ring and the rings grow outward`() {
        val items = portrait(9)
        assertTrue(items.map { it.ring }.distinct().size > 1)
        val radii = items.groupBy { it.ring }.mapValues { (_, v) -> hypot(v.first().x, v.first().y) }
        assertEquals(radii.keys.sorted().map { radii.getValue(it) }, radii.values.sorted())
    }

    @Test
    fun `a single item sits in the middle of the arc`() {
        val only = portrait(1).single()
        assertEquals(only.x, -only.y, 0.5f)
    }

    @Test
    fun `a ring that is not full is centred in the arc`() {
        val last = portrait(9).filter { it.ring == 2 }
        val full = portrait(9).filter { it.ring == 0 }
        assertTrue(last.size < FanLayout.positions(20, 90f, 0f, 72f).count { it.ring == 2 })
        // Its items sit around the middle of the arc, not at the ends (straight up or straight right).
        last.forEach { assertTrue("x=${it.x}", it.x > 1f); assertTrue("y=${it.y}", it.y < -1f) }
        assertTrue(full.isNotEmpty())
    }
}
