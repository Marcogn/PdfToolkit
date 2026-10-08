package com.marcogn.pdftoolkit.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import kotlinx.coroutines.delay

/** Undo and redo of the edit session, as the tool strip shows them (plan U21). */
class UndoRedo(val canUndo: Boolean, val canRedo: Boolean, val onUndo: () -> Unit, val onRedo: () -> Unit)

/** Landscape: the tools go to a rail at the side so the page keeps its height (plan U18). */
@Composable
fun isLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp > configuration.screenHeightDp
}

private val RailWidth = 88.dp

/**
 * The controls of an edit pane: a bar at the bottom of the screen, or with [side] a rail at the end
 * of the content (plan U18). The same [tools] are laid out in a row or in a column; [trailing] (a style
 * button) and undo and redo stay at the end where the thumb is (plan U21) and don't scroll with the tools.
 */
@Composable
fun ToolStrip(
    side: Boolean,
    undoRedo: UndoRedo?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    tools: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        if (side) {
            Column(Modifier.fillMaxHeight().widthIn(max = RailWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { tools() }
                trailing()
                if (undoRedo != null) Column(horizontalAlignment = Alignment.CenterHorizontally) { UndoRedoButtons(undoRedo) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 72.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Spread out when the tools fit, scrolling when they don't (six tools on a narrow phone).
                BoxWithConstraints(Modifier.weight(1f)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) { tools() }
                }
                trailing()
                if (undoRedo != null) UndoRedoButtons(undoRedo)
            }
        }
    }
}

@Composable
private fun UndoRedoButtons(undoRedo: UndoRedo) {
    IconButton(onClick = undoRedo.onUndo, enabled = undoRedo.canUndo) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.edit_undo))
    }
    IconButton(onClick = undoRedo.onRedo, enabled = undoRedo.canRedo) {
        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.edit_redo))
    }
}

/**
 * A hint over the top of the page that goes away by itself (plan U7): it takes no room from the page
 * and shows again when [key] changes. [text] null shows nothing. With [action] it carries one button.
 */
@Composable
fun BoxScope.TransientHint(text: String?, key: Any?, action: Pair<String, () -> Unit>? = null) {
    var visible by remember(key, text) { mutableStateOf(text != null) }
    LaunchedEffect(key, text) {
        if (text != null) {
            visible = true
            delay(HINT_MS)
            visible = false
        } else {
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
            shadowElevation = 2.dp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(start = 16.dp, end = if (action != null) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 10.dp).weight(1f, fill = false),
                )
                if (action != null) TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}

private const val HINT_MS = 4500L
