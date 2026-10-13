package com.marcogn.pdftoolkit.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.domain.tools.QuickSlots
import com.marcogn.pdftoolkit.domain.tools.ToolPrefs
import com.marcogn.pdftoolkit.domain.tools.ToolStyleSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.toolDataStore: DataStore<Preferences> by preferencesDataStore(name = "viewer_tool_prefs")

private val SLOT_KEYS = List(QuickSlots.SLOT_COUNT) { stringPreferencesKey("slot_$it") }
private val PIN_HINT_DONE = booleanPreferencesKey("pin_hint_done")
private val TUTORIAL_SEEN = booleanPreferencesKey("tutorial_seen")
private val HIGHLIGHT_COLOR = intPreferencesKey("highlight_color")
private val LINE_COLOR = intPreferencesKey("line_color")
private val PEN_COLOR = intPreferencesKey("pen_color")
private val PEN_WIDTH = intPreferencesKey("pen_width")
private val MARKER_COLOR = intPreferencesKey("marker_color")
private val MARKER_WIDTH = intPreferencesKey("marker_width")
private val PARTIAL_ERASER = booleanPreferencesKey("partial_eraser")
private val ERASER_WIDTH = intPreferencesKey("eraser_width")
private val LAST_MARKUP = stringPreferencesKey("last_markup")
private val LAST_BRUSH = stringPreferencesKey("last_brush")

/**
 * The viewer's tool drawer on Preferences DataStore (plan T-b): quick slots, the one-time teachings, colours,
 * sizes, the eraser's mode and size and the last tool of each group. Replaces the 2026-10-07 decision "not kept
 * across restarts". The store is a constructor parameter so tests can give each test its own file.
 */
@Singleton
class ViewerToolPreferences(private val store: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.toolDataStore)

    val prefs: Flow<ToolPrefs> = store.data.map { p ->
        val defaults = ToolStyleSettings()
        ToolPrefs(
            slots = QuickSlots.of(SLOT_KEYS.map { p[it] }),
            pinHintDone = p[PIN_HINT_DONE] ?: false,
            tutorialSeen = p[TUTORIAL_SEEN] ?: false,
            style = ToolStyleSettings(
                highlightColor = p[HIGHLIGHT_COLOR] ?: defaults.highlightColor,
                lineColor = p[LINE_COLOR] ?: defaults.lineColor,
                penColor = p[PEN_COLOR] ?: defaults.penColor,
                penWidth = p[PEN_WIDTH] ?: defaults.penWidth,
                markerColor = p[MARKER_COLOR] ?: defaults.markerColor,
                markerWidth = p[MARKER_WIDTH] ?: defaults.markerWidth,
                partialEraser = p[PARTIAL_ERASER] ?: defaults.partialEraser,
                eraserWidth = p[ERASER_WIDTH] ?: defaults.eraserWidth,
                lastMarkup = tool(p[LAST_MARKUP]) ?: defaults.lastMarkup,
                lastBrush = tool(p[LAST_BRUSH]) ?: defaults.lastBrush,
            ),
        )
    }

    /** Pins [slots]; the first tool ever pinned also turns the drawer's hint off for good. */
    suspend fun setSlots(slots: QuickSlots) {
        store.edit { p ->
            SLOT_KEYS.forEachIndexed { i, key -> slots[i]?.let { p[key] = it.name } ?: p.remove(key) }
            if (slots.anyFilled) p[PIN_HINT_DONE] = true
        }
    }

    suspend fun setTutorialSeen(seen: Boolean) {
        store.edit { it[TUTORIAL_SEEN] = seen }
    }

    suspend fun setStyle(style: ToolStyleSettings) {
        store.edit { p ->
            p[HIGHLIGHT_COLOR] = style.highlightColor
            p[LINE_COLOR] = style.lineColor
            p[PEN_COLOR] = style.penColor
            p[PEN_WIDTH] = style.penWidth
            p[MARKER_COLOR] = style.markerColor
            p[MARKER_WIDTH] = style.markerWidth
            p[PARTIAL_ERASER] = style.partialEraser
            p[ERASER_WIDTH] = style.eraserWidth
            p[LAST_MARKUP] = style.lastMarkup.name
            p[LAST_BRUSH] = style.lastBrush.name
        }
    }

    private fun tool(name: String?): DrawerTool? = DrawerTool.entries.firstOrNull { it.name == name }
}
