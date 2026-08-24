package com.lunov.flyshare.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The icons this app uses, drawn rather than imported.
 *
 * `material-icons-extended` is several megabytes for a handful of glyphs, and
 * the shrunk APK is 3.2 MB in total. These are stroke drawings on a 24-unit
 * grid, which is also what keeps them consistent with an interface built from
 * hairlines and small capitals — a filled Material glyph would sit in it like
 * a sticker.
 */
enum class Glyph { Settings, Back, History, Folder, Device, Open, Check, Close, Chevron, Pause, Play }

/**
 * [description] is not optional decoration. A button whose whole label is a
 * drawing is unusable with a screen reader without one — and, as it happens,
 * invisible to the tooling that drives the app in testing too.
 */
@Composable
fun Icon(
    glyph: Glyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    description: String? = null,
) {
    Canvas(
        modifier
            .size(size)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                }
            ),
    ) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(
            width = 1.9f * u,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (glyph) {
            Glyph.Settings -> drawSettings(tint, u, stroke)
            Glyph.Back -> drawPolyline(tint, u, stroke, 15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.Chevron -> drawPolyline(tint, u, stroke, 9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.History -> drawHistory(tint, u, stroke)
            Glyph.Folder -> drawFolder(tint, u, stroke)
            Glyph.Device -> drawDevice(tint, u, stroke)
            Glyph.Open -> drawOpen(tint, u, stroke)
            Glyph.Check -> drawPolyline(tint, u, stroke, 5f, 13f, 10f, 18f, 19f, 6f)
            Glyph.Close -> {
                drawPolyline(tint, u, stroke, 6f, 6f, 18f, 18f)
                drawPolyline(tint, u, stroke, 18f, 6f, 6f, 18f)
            }
            Glyph.Pause -> {
                drawPolyline(tint, u, stroke, 9f, 5f, 9f, 19f)
                drawPolyline(tint, u, stroke, 15f, 5f, 15f, 19f)
            }
            Glyph.Play -> drawPlay(tint, u)
        }
    }
}

/** Points in the 24-unit grid, as x, y pairs. */
private fun DrawScope.drawPolyline(tint: Color, u: Float, stroke: Stroke, vararg points: Float) {
    val path = Path()
    path.moveTo(points[0] * u, points[1] * u)
    for (i in 2 until points.size step 2) path.lineTo(points[i] * u, points[i + 1] * u)
    drawPath(path, tint, style = stroke)
}

/** Sliders rather than a gear: fewer strokes, and it reads at 20dp. */
private fun DrawScope.drawSettings(tint: Color, u: Float, stroke: Stroke) {
    drawPolyline(tint, u, stroke, 4f, 8f, 20f, 8f)
    drawPolyline(tint, u, stroke, 4f, 16f, 20f, 16f)
    drawCircle(tint, radius = 2.6f * u, center = center.copy(x = 9f * u, y = 8f * u), style = stroke)
    drawCircle(tint, radius = 2.6f * u, center = center.copy(x = 15f * u, y = 16f * u), style = stroke)
}

private fun DrawScope.drawHistory(tint: Color, u: Float, stroke: Stroke) {
    drawCircle(tint, radius = 8f * u, center = center.copy(x = 12f * u, y = 12f * u), style = stroke)
    drawPolyline(tint, u, stroke, 12f, 7f, 12f, 12f, 15.5f, 14f)
}

private fun DrawScope.drawFolder(tint: Color, u: Float, stroke: Stroke) {
    val path = Path().apply {
        moveTo(3.5f * u, 18.5f * u)
        lineTo(3.5f * u, 6f * u)
        lineTo(9.5f * u, 6f * u)
        lineTo(11.5f * u, 8.5f * u)
        lineTo(20.5f * u, 8.5f * u)
        lineTo(20.5f * u, 18.5f * u)
        close()
    }
    drawPath(path, tint, style = stroke)
}

/** A phone beside a screen: the two things this app talks between. */
private fun DrawScope.drawDevice(tint: Color, u: Float, stroke: Stroke) {
    val phone = Path().apply {
        moveTo(4f * u, 5f * u); lineTo(10f * u, 5f * u)
        lineTo(10f * u, 19f * u); lineTo(4f * u, 19f * u); close()
    }
    drawPath(phone, tint, style = stroke)
    val screen = Path().apply {
        moveTo(13f * u, 7f * u); lineTo(21f * u, 7f * u)
        lineTo(21f * u, 15f * u); lineTo(13f * u, 15f * u); close()
    }
    drawPath(screen, tint, style = stroke)
    drawPolyline(tint, u, stroke, 15f, 18f, 19f, 18f)
}

/** An arrow leaving a box: open this somewhere else. */
private fun DrawScope.drawOpen(tint: Color, u: Float, stroke: Stroke) {
    drawPolyline(tint, u, stroke, 13f, 5f, 19f, 5f, 19f, 11f)
    drawPolyline(tint, u, stroke, 19f, 5f, 11f, 13f)
    drawPolyline(
        tint, u, stroke,
        16f, 14f, 16f, 19f, 5f, 19f, 5f, 8f, 10f, 8f,
    )
}

private fun DrawScope.drawPlay(tint: Color, u: Float) {
    val path = Path().apply {
        moveTo(8f * u, 5.5f * u)
        lineTo(19f * u, 12f * u)
        lineTo(8f * u, 18.5f * u)
        close()
    }
    drawPath(path, tint)
}
