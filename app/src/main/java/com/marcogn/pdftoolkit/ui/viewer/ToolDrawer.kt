package com.marcogn.pdftoolkit.ui.viewer

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.tools.DrawerGroup
import com.marcogn.pdftoolkit.domain.tools.DrawerTool
import com.marcogn.pdftoolkit.ui.annotate.AnnotatePaneState
import com.marcogn.pdftoolkit.ui.annotate.ToolBubble
import com.marcogn.pdftoolkit.ui.annotate.icon
import com.marcogn.pdftoolkit.ui.annotate.labelRes
import kotlin.math.roundToInt

/** The entries of the fan, in order: the tools by group, then "Leggi". */
private val FAN_TOOLS: List<DrawerTool> = DrawerGroup.entries.flatMap { group -> DrawerTool.entries.filter { it.group == group } }

/**
 * The tool drawer (plan T-b): a scrim and a fan of tools that unfolds from [origin], upward in the bottom bar and
 * leftward in the rail ([side]). Under the bar, so the bar stays usable. Items: tap arms ([onArm], and the drawer
 * closes), hold still then let go opens the bubble, hold and move drags the tool toward the quick slots
 * ([callbacks]). "Leggi" puts the tool down ([onRead]). While no slot was ever filled a hint says how to pin;
 * the first time a tutorial of three steps shows ([showTutorial], [onTutorialDone]).
 */
@Composable
fun ToolDrawerHost(
    open: Boolean,
    onClose: () -> Unit,
    origin: Offset?,
    side: Boolean,
    state: AnnotatePaneState,
    armed: DrawerTool?,
    editable: Boolean,
    showPinHint: Boolean,
    showTutorial: Boolean,
    onTutorialDone: () -> Unit,
    callbacks: ToolBarCallbacks,
    onArm: (DrawerTool) -> Unit,
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = open) { onClose() }
    var hostRoot by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val animated = !animationsRemoved()
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(if (animated) FAN_MS else FADE_MS, easing = FastOutSlowInEasing),
        label = "fan",
    )
    var bubble by remember { mutableStateOf<DrawerTool?>(null) }
    LaunchedEffect(open) { if (!open) bubble = null }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                hostRoot = it.positionInRoot()
            },
    ) {
        if (progress <= 0f && !open) return@Box
        // Dimming that closes on a tap; its alpha follows the fan.
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA * progress))
                .pointerInput(open) { detectTapGestures { if (open) onClose() } },
        )
        if (origin == null) return@Box
        val local = origin - hostRoot
        val itemPx = with(density) { ITEM_SIZE.toPx() }
        val entries = FAN_TOOLS.size + 1
        val positions = remember(entries, side) {
            FanLayout.positions(
                count = entries,
                startDeg = if (side) 180f else 90f,
                endDeg = if (side) 270f else 0f,
                itemSpacingDp = ITEM_SPACING.value,
            )
        }
        val dragging = callbacks.drag.active
        val reading = stringResource(R.string.tools_read)
        val readingCd = stringResource(R.string.tools_read_cd)
        positions.forEachIndexed { index, position ->
            val tool = FAN_TOOLS.getOrNull(index)
            // Items unfold one after the other; with the system's animations removed they only fade.
            val shown = (progress * (1f + STAGGER * entries) - STAGGER * index).coerceIn(0f, 1f)
            val spread = if (animated) shown else 1f
            val at = Offset(
                local.x + with(density) { position.x.dp.toPx() } * spread - itemPx / 2,
                local.y + with(density) { position.y.dp.toPx() } * spread - itemPx / 2,
            )
            Box(
                Modifier
                    .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .alpha(if (dragging && callbacks.drag.tool != tool) shown * DRAG_DIM else shown),
            ) {
                if (tool != null) {
                    FanItem(
                        icon = { Icon(tool.icon(), contentDescription = null) },
                        label = stringResource(tool.labelRes()),
                        active = armed == tool,
                        dimmed = !editable,
                        gestures = { base ->
                            base.toolGestures(
                                onTap = { if (open) onArm(tool) },
                                onHold = { if (open) bubble = tool },
                                onDragStart = {
                                    bubble = null
                                    if (open) callbacks.drag.start(tool, null, it)
                                },
                                onDrag = callbacks.drag::move,
                                onDragEnd = {
                                    val dropped = callbacks.drag.tool
                                    callbacks.drag.end()
                                    if (dropped != null) {
                                        callbacks.onDrop(dropped, null, callbacks.geometry.slotAt(it), callbacks.geometry.overBar(it))
                                        onClose()
                                    }
                                },
                                onDragCancel = callbacks.drag::end,
                            )
                        },
                        description = stringResource(tool.labelRes()),
                        onTap = { onArm(tool) },
                        onHold = { bubble = tool },
                    )
                    ToolBubble(tool, state, expanded = bubble == tool, onDismiss = { if (bubble == tool) bubble = null })
                } else {
                    FanItem(
                        icon = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                        label = reading,
                        active = armed == null,
                        dimmed = false,
                        gestures = { it.toolGestures(holdAndDrag = false, onTap = { if (open) onRead() }) },
                        description = readingCd,
                        onTap = onRead,
                        onHold = null,
                    )
                }
            }
        }
        if (open && !dragging) {
            val tutorial = showTutorial
            if (tutorial) {
                ToolTutorial(onDone = onTutorialDone, modifier = Modifier.align(Alignment.TopCenter))
            } else if (showPinHint) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = FAN_TOP).widthIn(max = 420.dp),
                ) {
                    Text(
                        stringResource(R.string.tools_pin_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FanItem(
    icon: @Composable () -> Unit,
    label: String,
    active: Boolean,
    dimmed: Boolean,
    gestures: @Composable (Modifier) -> Modifier,
    description: String,
    onTap: () -> Unit,
    onHold: (() -> Unit)?,
) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
        modifier = Modifier.alpha(if (dimmed) DIMMED else 1f),
    ) {
        Column(
            gestures(Modifier)
                .size(ITEM_SIZE)
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    selected = active
                    contentDescription = description
                    onClick { onTap(); true }
                    if (onHold != null) onLongClick { onHold(); true }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides color) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** The tool under the finger while it is dragged to a quick slot (plan T-b); above everything. */
@Composable
fun ToolDragGhost(drag: ToolDragState, modifier: Modifier = Modifier) {
    var root by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { root = it.positionInRoot() }) {
        val tool = drag.tool ?: return@Box
        val half = with(LocalDensity.current) { (GHOST_SIZE / 2).toPx() }
        val at = drag.position - root
        Box(
            Modifier
                .offset { IntOffset((at.x - half).roundToInt(), (at.y - half - half).roundToInt()) }
                .size(GHOST_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(tool.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

/** The three steps shown the first time the drawer opens (plan T-b); replayed from Settings and About. */
@Composable
fun ToolTutorial(onDone: () -> Unit, modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = listOf(R.string.tools_tutorial_step1, R.string.tools_tutorial_step2, R.string.tools_tutorial_step3)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = FAN_TOP).widthIn(max = 420.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tools_tutorial_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.tools_tutorial_step_of, step + 1, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(steps[step]), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.align(Alignment.End)) {
                if (step < steps.lastIndex) TextButton(onClick = onDone) { Text(stringResource(R.string.tools_tutorial_skip)) }
                TextButton(onClick = { if (step < steps.lastIndex) step++ else onDone() }) {
                    Text(stringResource(if (step < steps.lastIndex) R.string.tools_tutorial_next else R.string.tools_tutorial_done))
                }
            }
        }
    }
}

/** The system's "remove animations": the fan then only fades (plan T-b). */
@Composable
private fun animationsRemoved(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

private const val FAN_MS = 240
private const val FADE_MS = 120
private const val SCRIM_ALPHA = 0.32f
private const val DRAG_DIM = 0.45f
private const val DIMMED = 0.38f
private const val STAGGER = 0.06f
private val ITEM_SIZE = 64.dp
private val ITEM_SPACING = 72.dp
private val GHOST_SIZE = 56.dp
private val FAN_TOP = 64.dp
