package com.marcogn.pdftoolkit.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.marcogn.pdftoolkit.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ThumbHeight = 56.dp
private val ThumbWidth = 28.dp
private const val HIDE_DELAY_MS = 1_500L

/**
 * Draggable bar on the right edge to jump between pages (spec §4.2), with a bubble showing the
 * page number while dragging. The thumb appears when the reader moves and fades out after a
 * moment; only the thumb takes touches, so the rest of the edge keeps panning the page.
 *
 * Drag position maps linearly to pages: the middle of the bar is the middle of the document.
 */
@Composable
fun PageScrubber(
    pageCount: Int,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount < 2) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val thumbHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { ThumbHeight.roundToPx() }
    val trackPx = (heightPx - thumbHeightPx).coerceAtLeast(1)

    LaunchedEffect(currentPage, dragging) {
        visible = true
        if (!dragging) {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }

    val shownFraction = if (dragging) dragFraction else fractionForPage(currentPage, pageCount)
    val label = stringResource(R.string.cd_scrubber)

    Box(modifier.fillMaxHeight().width(ThumbWidth).onSizeChanged { heightPx = it.height }) {
        AnimatedVisibility(visible = visible || dragging, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxHeight().width(ThumbWidth)) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (shownFraction * trackPx).roundToInt()) }
                        .height(ThumbHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dragging) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.viewer_scrubber_bubble, currentPage + 1, pageCount),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .width(ThumbWidth)
                            .height(ThumbHeight)
                            .semantics {
                                contentDescription = label
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    current = currentPage.toFloat(),
                                    range = 0f..(pageCount - 1).toFloat(),
                                    steps = pageCount - 2,
                                )
                                setProgress { value ->
                                    onPageSelected(value.roundToInt().coerceIn(0, pageCount - 1))
                                    true
                                }
                            }
                            .pointerInput(pageCount, trackPx) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragFraction = fractionForPage(currentPage, pageCount)
                                        dragging = true
                                    },
                                    onDragEnd = { dragging = false },
                                    onDragCancel = { dragging = false },
                                ) { change, dy ->
                                    change.consume()
                                    dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                                    onPageSelected(pageForFraction(dragFraction, pageCount))
                                }
                            },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            Modifier
                                .size(width = 8.dp, height = ThumbHeight - 8.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** Position of the thumb along the bar, 0 (first page) to 1 (last page). */
internal fun fractionForPage(page: Int, pageCount: Int): Float =
    if (pageCount < 2) 0f else (page.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)

/** Page the thumb at [fraction] stands for: the inverse of [fractionForPage]. */
internal fun pageForFraction(fraction: Float, pageCount: Int): Int =
    (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()
