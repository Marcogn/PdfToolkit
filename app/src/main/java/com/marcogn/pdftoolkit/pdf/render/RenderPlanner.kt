package com.marcogn.pdftoolkit.pdf.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One bitmap to render: page [pageIndex] drawn at [scale] pixels per point and shifted by
 * (-[left], -[top]) pixels, into a [width] x [height] bitmap. It is also the cache key, so two
 * requests for the same pixels are equal.
 */
sealed interface RenderKey {
    val pageIndex: Int
    val width: Int
    val height: Int
    val scale: Float
    val left: Int
    val top: Int

    /** ARGB_8888, the only format `PdfRenderer` accepts. */
    val byteCount: Long get() = width.toLong() * height * 4
}

/** A whole page, at fit-width resolution unless that would exceed the bitmap limit. */
data class PageKey(
    override val pageIndex: Int,
    override val width: Int,
    override val height: Int,
    override val scale: Float,
) : RenderKey {
    override val left: Int get() = 0
    override val top: Int get() = 0
}

/**
 * A high-resolution square of a page: column [col], row [row] of a grid of `tileSize` pixels laid
 * over the page rendered at [scale]. [level] is the quantised zoom ([RenderPlanner.tileLevelFor]).
 * Edge tiles are smaller than `tileSize`.
 */
data class TileKey(
    override val pageIndex: Int,
    val level: Int,
    val col: Int,
    val row: Int,
    val tileSize: Int,
    override val width: Int,
    override val height: Int,
    override val scale: Float,
) : RenderKey {
    override val left: Int get() = col * tileSize
    override val top: Int get() = row * tileSize
}

/**
 * Decides what to render for a viewport (spec §5), as pure functions of the layout.
 *
 * Two levels: every page has a [PageKey] at fit-width resolution, scaled while zooming. When the
 * zoom settles above what that bitmap can show sharply, the visible part of the page is covered
 * by [TileKey]s rendered at the zoom level. Zoom levels are quantised to quarter octaves
 * (2^(1/4) ≈ 1.19), rounded up, so small zoom changes and pans reuse the same tiles and the tiles
 * are never upscaled.
 */
class RenderPlanner(
    val layout: DocumentLayout,
    maxPageBitmapBytes: Long,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    /**
     * Added to the page index of every key. A layout of one page (single-page mode) is page 0 of
     * its own layout but page [pageIndexOffset] of the document, which is what the renderer needs.
     * Everything else here still takes layout indices.
     */
    val pageIndexOffset: Int = 0,
) {
    private val pageKeys: List<PageKey> = layout.pageRects.mapIndexed { index, rect ->
        pageKeyFor(index + pageIndexOffset, rect, layout.pageSizes[index], maxPageBitmapBytes)
    }

    fun pageKey(pageIndex: Int): PageKey = pageKeys[pageIndex]

    /** Smallest quarter-octave level whose resolution is at least [zoom]. Can be negative. */
    fun tileLevelFor(zoom: Float): Int = ceil(LEVELS_PER_OCTAVE * log2(zoom) - LEVEL_EPSILON).toInt()

    /** Pixels per point of the tiles of [level]. */
    fun levelScale(level: Int): Float = layout.pxPerPoint * 2f.pow(level.toFloat() / LEVELS_PER_OCTAVE)

    /** True when the page bitmap is visibly too coarse at [level] (more than 10% upscaling). */
    fun needsTiles(pageIndex: Int, level: Int): Boolean =
        levelScale(level) > pageKeys[pageIndex].scale * TILE_THRESHOLD

    fun visiblePages(viewport: Viewport, viewportSize: Size): IntRange = layout.pagesBetween(
        viewport.offset.y / viewport.zoom,
        (viewport.offset.y + viewportSize.height) / viewport.zoom,
    )

    /** Tiles of [level] that cover the visible part of page [pageIndex]; empty if not needed. */
    fun visibleTiles(pageIndex: Int, level: Int, viewport: Viewport, viewportSize: Size): List<TileKey> {
        if (!needsTiles(pageIndex, level)) return emptyList()
        val mapper = PageCoordinateMapper(layout, viewport)
        val onScreen = mapper.pageBoundsOnScreen(pageIndex)
        val visible = onScreen.intersect(Rect(Offset.Zero, viewportSize))
        if (visible.width <= 0f || visible.height <= 0f) return emptyList()

        val scale = levelScale(level)
        val toTilePx = scale / mapper.screenPxPerPoint
        val page = layout.pageSizes[pageIndex]
        val pageWidthPx = ceil(page.width * scale).toInt()
        val pageHeightPx = ceil(page.height * scale).toInt()
        val lastCol = (pageWidthPx - 1) / tileSize
        val lastRow = (pageHeightPx - 1) / tileSize

        val firstCol = floor((visible.left - onScreen.left) * toTilePx / tileSize).toInt().coerceIn(0, lastCol)
        val endCol = floor(((visible.right - onScreen.left) * toTilePx - EDGE_EPSILON) / tileSize).toInt().coerceIn(0, lastCol)
        val firstRow = floor((visible.top - onScreen.top) * toTilePx / tileSize).toInt().coerceIn(0, lastRow)
        val endRow = floor(((visible.bottom - onScreen.top) * toTilePx - EDGE_EPSILON) / tileSize).toInt().coerceIn(0, lastRow)

        val tiles = ArrayList<TileKey>((endCol - firstCol + 1) * (endRow - firstRow + 1))
        for (row in firstRow..endRow) {
            for (col in firstCol..endCol) {
                tiles += TileKey(
                    pageIndex = pageIndex + pageIndexOffset,
                    level = level,
                    col = col,
                    row = row,
                    tileSize = tileSize,
                    width = minOf(tileSize, pageWidthPx - col * tileSize),
                    height = minOf(tileSize, pageHeightPx - row * tileSize),
                    scale = scale,
                )
            }
        }
        return tiles
    }

    /** Area of [tile] in page points, to draw it through [PageCoordinateMapper.pageRectToScreen]. */
    fun tileRectInPoints(tile: TileKey): Rect = Rect(
        tile.left / tile.scale,
        tile.top / tile.scale,
        (tile.left + tile.width) / tile.scale,
        (tile.top + tile.height) / tile.scale,
    )

    /**
     * Everything to render for [viewport], most urgent first:
     * 1. the page bitmaps of the visible pages, closest to the centre of the screen first;
     * 2. if [tileLevel] is given (the zoom has settled), their tiles at that level;
     * 3. the page bitmaps of the [prefetch] pages after and before the visible ones, nearest first.
     */
    fun plan(viewport: Viewport, viewportSize: Size, tileLevel: Int?, prefetch: Int = PREFETCH_PAGES): List<RenderKey> {
        val visible = visiblePages(viewport, viewportSize)
        if (visible.isEmpty()) return emptyList()
        val centreY = (viewport.offset.y + viewportSize.height / 2f) / viewport.zoom
        val byDistance = visible.sortedBy { abs(layout.pageRects[it].center.y - centreY) }

        val keys = ArrayList<RenderKey>()
        byDistance.mapTo(keys) { pageKeys[it] }
        if (tileLevel != null) {
            byDistance.forEach { keys += visibleTiles(it, tileLevel, viewport, viewportSize) }
        }
        for (distance in 1..prefetch) {
            val after = visible.last + distance
            val before = visible.first - distance
            if (after < layout.pageCount) keys += pageKeys[after]
            if (before >= 0) keys += pageKeys[before]
        }
        return keys
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 512
        const val PREFETCH_PAGES = 2
        const val LEVELS_PER_OCTAVE = 4
        private const val TILE_THRESHOLD = 1.1f
        private const val LEVEL_EPSILON = 1e-3f
        private const val EDGE_EPSILON = 1e-3f

        /**
         * Fit-width size of the page; if that is above [maxBytes], scaled down uniformly to fit.
         * The scale is recomputed from the rounded width so that the bitmap holds exactly the page.
         */
        internal fun pageKeyFor(index: Int, rect: Rect, size: PageSize, maxBytes: Long): PageKey {
            var width = max(1, rect.width.roundToInt())
            var height = max(1, rect.height.roundToInt())
            val bytes = width.toLong() * height * 4
            if (bytes > maxBytes) {
                val factor = sqrt(maxBytes.toDouble() / bytes)
                width = max(1, floor(width * factor).toInt())
                height = max(1, floor(height * factor).toInt())
            }
            return PageKey(index, width, height, width / size.width)
        }
    }
}
