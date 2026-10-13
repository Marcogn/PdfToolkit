package com.marcogn.pdftoolkit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.QuickSlots
import com.marcogn.pdftoolkit.domain.tools.ToolStyleSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerToolPreferencesTest {

    private fun store() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ) { File.createTempFile("tool-prefs", ".preferences_pb").also { it.delete() } }

    @Test
    fun `starts with empty slots, nothing seen and the default style`() = runTest {
        val prefs = ViewerToolPreferences(store()).prefs.first()
        assertEquals(QuickSlots(), prefs.slots)
        assertFalse(prefs.pinHintDone)
        assertFalse(prefs.tutorialSeen)
        assertEquals(ToolStyleSettings(), prefs.style)
    }

    @Test
    fun `pinned tools are kept and the first pin turns the hint off for good`() = runTest {
        val preferences = ViewerToolPreferences(store())
        preferences.setSlots(QuickSlots(listOf(null, DrawerTool.MARKER)))
        var prefs = preferences.prefs.first()
        assertEquals(listOf(null, DrawerTool.MARKER), prefs.slots.tools)
        assertTrue(prefs.pinHintDone)
        // Unpinning everything doesn't bring the hint back.
        preferences.setSlots(QuickSlots())
        prefs = preferences.prefs.first()
        assertEquals(QuickSlots(), prefs.slots)
        assertTrue(prefs.pinHintDone)
    }

    @Test
    fun `the tutorial flag can be set and cleared to replay it`() = runTest {
        val preferences = ViewerToolPreferences(store())
        preferences.setTutorialSeen(true)
        assertTrue(preferences.prefs.first().tutorialSeen)
        preferences.setTutorialSeen(false)
        assertFalse(preferences.prefs.first().tutorialSeen)
    }

    @Test
    fun `colours, sizes, eraser mode and last tools are kept`() = runTest {
        val preferences = ViewerToolPreferences(store())
        val style = ToolStyleSettings(
            highlightColor = 2, lineColor = 3, penColor = 4, penWidth = 1, markerColor = 2, markerWidth = 3,
            partialEraser = true, eraserWidth = 3, lastMarkup = DrawerTool.STRIKEOUT, lastBrush = DrawerTool.MARKER,
        )
        preferences.setStyle(style)
        assertEquals(style, preferences.prefs.first().style)
    }

    @Test
    fun `an unknown stored tool name falls back to the default`() = runTest {
        val store = store()
        store.edit {
            it[stringPreferencesKey("last_brush")] = "LASER"
            it[stringPreferencesKey("slot_0")] = "LASER"
        }
        val prefs = ViewerToolPreferences(store).prefs.first()
        assertEquals(DrawerTool.PEN, prefs.style.lastBrush)
        assertEquals(QuickSlots(), prefs.slots)
    }
}
