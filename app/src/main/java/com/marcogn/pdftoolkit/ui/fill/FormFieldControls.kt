package com.marcogn.pdftoolkit.ui.fill

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.domain.fill.FieldValue
import com.marcogn.pdftoolkit.domain.fill.FormField
import com.marcogn.pdftoolkit.domain.fill.MarkKind
import com.marcogn.pdftoolkit.domain.fill.MarkShape
import kotlin.math.min
import kotlin.math.roundToInt

/** Font size for fields whose appearance says "auto" (0): fit a single line, never above 12 pt. */
private const val AUTO_LINE_FRACTION = 0.65f
private const val AUTO_MAX_PT = 12f
private const val AUTO_MULTILINE_PT = 10f
private const val FIELD_FILL_ALPHA = 0.12f
private const val FIELD_BORDER_ALPHA = 0.5f
private const val RADIO_DOT_FRACTION = 0.3f
private const val HALF_TURN = 180

/**
 * A Compose control laid over one widget of an AcroForm field (spec §6.5), at [rect] in screen
 * pixels; [pxPerPoint] scales the text with the zoom. [rotation] (clockwise, quarter turns) is
 * the direction the field's text runs on screen: on a turned page the control turns with it, so
 * the text runs along the field as in the saved PDF. [value] is the current value (the user's,
 * or the file's). [onChange] receives the new value; `typing` is true for keystrokes, so the edit
 * session makes one undo step of them. The control hides what the page shows under it.
 */
@Composable
internal fun FieldControl(
    field: FormField,
    widgetIndex: Int,
    rect: Rect,
    pxPerPoint: Float,
    value: FieldValue,
    onChange: (FieldValue, typing: Boolean) -> Unit,
    rotation: Int = 0,
) {
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    // Laid out unturned (width along the text), centred on the widget, then turned about its centre.
    val sideways = rotation % HALF_TURN != 0
    val width = if (sideways) rect.height else rect.width
    val height = if (sideways) rect.width else rect.height
    val unturned = Rect(rect.center.x - width / 2f, rect.center.y - height / 2f, rect.center.x + width / 2f, rect.center.y + height / 2f)
    val base = Modifier
        .offset { IntOffset(unturned.left.roundToInt(), unturned.top.roundToInt()) }
        .size(with(density) { width.toDp() }, with(density) { height.toDp() })
        .graphicsLayer { rotationZ = rotation.toFloat() }
        // Opaque: the page bitmap already holds the widget's appearance stream (PdfRenderer draws
        // annotations, and a saved form has the value in it), so letting it show through would
        // draw the value twice. The control is the only picture of the field while filling.
        .background(Color.White)
        .background(primary.copy(alpha = FIELD_FILL_ALPHA))
        .border(1.dp, primary.copy(alpha = FIELD_BORDER_ALPHA))
        .semantics { contentDescription = field.label ?: field.name }
    when (field) {
        is FormField.Text -> TextFieldControl(field, base, unturned, pxPerPoint, (value as? FieldValue.Text)?.value.orEmpty(), onChange)
        is FormField.CheckBox -> {
            val on = (value as? FieldValue.Toggle)?.on == true
            Box(base.toggleable(value = on, enabled = !field.readOnly, role = Role.Checkbox) { onChange(FieldValue.Toggle(it), false) }) {
                if (on) Mark(MarkKind.CHECK, Modifier.fillMaxSize())
            }
        }
        is FormField.Radio -> {
            val mine = field.widgetValues.getOrNull(widgetIndex) ?: return
            val selected = (value as? FieldValue.Choice)?.value == mine
            Box(base.selectable(selected = selected, enabled = !field.readOnly, role = Role.RadioButton) { onChange(FieldValue.Choice(mine), false) }) {
                if (selected) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.Black, radius = min(size.width, size.height) * RADIO_DOT_FRACTION)
                    }
                }
            }
        }
        is FormField.Choice -> {
            var expanded by remember { mutableStateOf(false) }
            val current = (value as? FieldValue.Choice)?.value
            val label = field.options.firstOrNull { it.value == current }?.label ?: current.orEmpty()
            Box(base.clickable(enabled = !field.readOnly, role = Role.DropdownList) { expanded = true }, contentAlignment = Alignment.CenterStart) {
                Text(
                    label,
                    style = TextStyle(color = Color.Black, fontSize = with(density) { autoFontPx(unturned, pxPerPoint, false).toSp() }),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                expanded = false
                                onChange(FieldValue.Choice(option.value), false)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextFieldControl(
    field: FormField.Text,
    modifier: Modifier,
    rect: Rect,
    pxPerPoint: Float,
    text: String,
    onChange: (FieldValue, typing: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    // Local state keeps the cursor; it follows the session when the value changes elsewhere (undo).
    var local by remember(field.name) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (local.text != text) local = TextFieldValue(text, TextRange(text.length))
    }
    val fontPx = if (field.fontSize > 0f) field.fontSize * pxPerPoint else autoFontPx(rect, pxPerPoint, field.multiline)
    BasicTextField(
        value = local,
        onValueChange = { changed ->
            val limited = field.maxLength?.let { max -> if (changed.text.length > max) return@BasicTextField else changed } ?: changed
            local = limited
            if (limited.text != text) onChange(FieldValue.Text(limited.text), true)
        },
        readOnly = field.readOnly,
        singleLine = !field.multiline,
        textStyle = TextStyle(color = Color.Black, fontSize = with(density) { fontPx.toSp() }),
        cursorBrush = SolidColor(Color.Black),
        // Spec §6.5: the keyboard's "Next" moves to the next field.
        keyboardOptions = KeyboardOptions(imeAction = if (field.multiline) ImeAction.Default else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
        modifier = modifier.padding(horizontal = 1.dp),
    )
}

/** Size of the text, in screen pixels, for a field whose appearance doesn't fix one. */
private fun autoFontPx(rect: Rect, pxPerPoint: Float, multiline: Boolean): Float {
    val points = if (multiline) AUTO_MULTILINE_PT else min(rect.height / pxPerPoint * AUTO_LINE_FRACTION, AUTO_MAX_PT)
    return points * pxPerPoint
}

/** A tick or cross with the strokes written in the PDF. */
@Composable
internal fun Mark(kind: MarkKind, modifier: Modifier = Modifier, color: Color = Color.Black) {
    Canvas(modifier) {
        val path = Path()
        MarkShape.strokes(kind).forEach { points ->
            points.forEachIndexed { i, (x, y) ->
                val p = Offset(x * size.width, y * size.height)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
        }
        drawPath(path, color, style = Stroke(width = MarkShape.STROKE * min(size.width, size.height), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
