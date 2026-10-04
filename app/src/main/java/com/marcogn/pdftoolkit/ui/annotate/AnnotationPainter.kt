package com.marcogn.pdftoolkit.ui.annotate

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.marcogn.pdftoolkit.domain.annotate.AnnotationShape
import com.marcogn.pdftoolkit.domain.annotate.MarkupKind
import com.marcogn.pdftoolkit.domain.annotate.UserPoint
import com.marcogn.pdftoolkit.pdf.annotations.AnnotationGeometry
import com.marcogn.pdftoolkit.pdf.render.Affine
import kotlin.math.max

/**
 * Draws annotations on screen from [AnnotationGeometry], the same paths their PDF appearance is
 * written with. [userToScreen] maps the page's user space to the screen (see
 * [com.marcogn.pdftoolkit.pdf.render.PageCoordinateMapper.userToScreen]); [pxPerPoint] scales
 * stroke widths. A highlight multiplies with the page, as its appearance does, where the
 * platform can (API 29+); before that it is drawn translucent on top.
 */
fun DrawScope.drawAnnotations(annotations: List<DrawnAnnotation>, userToScreen: Affine, pxPerPoint: Float) {
    for (annotation in annotations) {
        val style = annotation.style
        val color = Color(style.color.red, style.color.green, style.color.blue, style.opacity)
        val isHighlight = (annotation.shape as? AnnotationShape.TextMarkup)?.kind == MarkupKind.HIGHLIGHT
        val blend = if (isHighlight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) BlendMode.Multiply else BlendMode.SrcOver
        for (paths in AnnotationGeometry.paths(annotation.shape)) {
            for (polygon in paths.fills) {
                drawPath(path(polygon, userToScreen).apply { close() }, color, blendMode = blend)
            }
            if (paths.strokes.isEmpty()) continue
            // At least one pixel, as a PDF line of width 0 is.
            val stroke = Stroke(width = max(paths.strokeWidth * pxPerPoint, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (polyline in paths.strokes) {
                if (polyline.size == 1) {
                    val p = userToScreen.map(Offset(polyline[0].x, polyline[0].y))
                    drawCircle(color, stroke.width / 2, p, blendMode = blend)
                } else {
                    drawPath(path(polyline, userToScreen), color, style = stroke, blendMode = blend)
                }
            }
        }
    }
}

private fun path(points: List<UserPoint>, transform: Affine): Path = Path().apply {
    points.forEachIndexed { i, point ->
        val p = transform.map(Offset(point.x, point.y))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
}
