package com.marcogn.pdftoolkit.ui.signatures

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.data.signatures.SignatureRendering
import com.marcogn.pdftoolkit.domain.signature.InkColor
import com.marcogn.pdftoolkit.domain.signature.InkPoint
import com.marcogn.pdftoolkit.domain.signature.InkStroke
import kotlinx.coroutines.launch

/** Base line width of the drawing, in dp. */
private val BASE_WIDTH = 3.5.dp

/** The strokes drawn so far and the ink colour; survives rotation (the dialog turns the screen to landscape). */
@Stable
class InkCanvasState(strokes: List<InkStroke> = emptyList(), color: InkColor = InkColor.BLACK) {
    val strokes = mutableStateListOf<InkStroke>().apply { addAll(strokes) }
    var color by mutableStateOf(color)

    /** The stroke the finger is drawing now. */
    val current = mutableStateListOf<InkPoint>()

    val isEmpty: Boolean get() = strokes.isEmpty() && current.isEmpty()

    fun finishStroke() {
        if (current.isNotEmpty()) strokes.add(InkStroke(color, current.toList()))
        current.clear()
    }

    fun undoStroke() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
    }

    fun clear() {
        strokes.clear()
        current.clear()
    }

    /** What is on the canvas, the stroke in progress included. */
    fun visibleStrokes(): List<InkStroke> =
        if (current.isEmpty()) strokes.toList() else strokes + InkStroke(color, current.toList())

    companion object {
        /** Flat list: colour ordinal, number of points, then x, y, time of each point. */
        val Saver = listSaver<InkCanvasState, Any>(
            save = { state ->
                buildList {
                    add(state.color.ordinal)
                    for (stroke in state.strokes) {
                        add(stroke.color.ordinal)
                        add(stroke.points.size)
                        stroke.points.forEach { add(it.x); add(it.y); add(it.timeMs) }
                    }
                }
            },
            restore = { saved ->
                val strokes = ArrayList<InkStroke>()
                var i = 1
                while (i < saved.size) {
                    val color = InkColor.entries[saved[i] as Int]
                    val count = saved[i + 1] as Int
                    i += 2
                    strokes.add(InkStroke(color, List(count) { n -> InkPoint(saved[i + 3 * n] as Float, saved[i + 3 * n + 1] as Float, saved[i + 3 * n + 2] as Long) }))
                    i += 3 * count
                }
                InkCanvasState(strokes, InkColor.entries[saved[0] as Int])
            },
        )
    }
}

/**
 * The drawing screen (spec §6.5): a full-screen canvas, turned to landscape while it is open, black
 * and blue ink, Clear / Undo stroke / Save. [onSave] gets the strokes and the base line width in pixels.
 */
@Composable
fun SignatureDrawDialog(onSave: suspend (strokes: List<InkStroke>, basePx: Float) -> Unit, onDismiss: () -> Unit) {
    val state = rememberSaveable(saver = InkCanvasState.Saver) { InkCanvasState() }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val basePx = with(LocalDensity.current) { BASE_WIDTH.toPx() }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    val close = { if (state.isEmpty) onDismiss() else confirmDiscard = true }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.save_cancel)) }
                    InkColor.entries.forEach { color ->
                        InkSwatch(color, selected = state.color == color, label = stringResource(color.labelRes())) { state.color = color }
                    }
                    Box(Modifier.weight(1f))
                    IconButton(onClick = { state.undoStroke() }, enabled = state.strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.signature_undo_stroke))
                    }
                    IconButton(onClick = { state.clear() }, enabled = !state.isEmpty) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.signature_clear))
                    }
                    Button(
                        enabled = state.strokes.isNotEmpty() && !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                onSave(state.strokes.toList(), basePx)
                                saving = false
                            }
                        },
                    ) { Text(stringResource(R.string.signature_save)) }
                }
                DrawingSurface(state, basePx, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp))
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.signature_discard_title)) },
            text = { Text(stringResource(R.string.signature_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text(stringResource(R.string.signature_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.signature_keep_drawing)) } },
        )
    }
}

@Composable
private fun DrawingSurface(state: InkCanvasState, basePx: Float, modifier: Modifier) {
    val guide = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.current.clear()
                    state.current.add(InkPoint(down.position.x, down.position.y, down.uptimeMillis))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.historical.forEach { state.current.add(InkPoint(it.position.x, it.position.y, it.uptimeMillis)) }
                        state.current.add(InkPoint(change.position.x, change.position.y, change.uptimeMillis))
                        change.consume()
                    }
                    state.finishStroke()
                }
            },
    ) {
        // Paper white, like the signature will be on the page, with a guide line to sign along.
        drawRect(Color.White)
        val lineY = size.height * GUIDE_LINE_FRACTION
        drawLine(guide, Offset(size.width * 0.05f, lineY), Offset(size.width * 0.95f, lineY), strokeWidth = 1.dp.toPx())
        drawIntoCanvas { SignatureRendering.draw(it.nativeCanvas, state.visibleStrokes(), basePx) }
    }
}

private const val GUIDE_LINE_FRACTION = 0.75f

@Composable
private fun InkSwatch(color: InkColor, selected: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { this.selected = selected; contentDescription = label }) {
        Box(
            Modifier
                .size(if (selected) 28.dp else 22.dp)
                .clip(CircleShape)
                .background(Color(color.argb))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

private fun InkColor.labelRes() = when (this) {
    InkColor.BLACK -> R.string.signature_ink_black
    InkColor.BLUE -> R.string.signature_ink_blue
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
