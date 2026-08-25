package de.lautstark.vorlaut.app.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * vorlaut's mark.
 *
 * The bubble is the family's, path for path — design.md §4.1 fixes that
 * geometry and says it must not drift. The face is vorlaut's own and is the
 * only such licence in the family: bildhaft smiles, vorlaut winks and talks
 * over you, which is what the name means.
 *
 * The features are plain white rather than a surface token, so the mark
 * survives being dropped on any ground in either scheme.
 */
@Composable
fun VorlautMark(
    modifier: Modifier = Modifier,
    fill: Color = Vorlaut.colors.accent,
) {
    Canvas(modifier) {
        val s = size.minDimension / 512f

        fun x(v: Float) = v * s
        val white = Color.White

        // The bubble, exactly as in icon.svg.
        val bubble =
            Path().apply {
                moveTo(x(128f), x(76f))
                lineTo(x(384f), x(76f))
                quadraticBezierTo(x(436f), x(76f), x(436f), x(128f))
                lineTo(x(436f), x(272f))
                quadraticBezierTo(x(436f), x(324f), x(384f), x(324f))
                lineTo(x(314f), x(324f))
                lineTo(x(256f), x(408f))
                lineTo(x(198f), x(324f))
                lineTo(x(128f), x(324f))
                quadraticBezierTo(x(76f), x(324f), x(76f), x(272f))
                lineTo(x(76f), x(128f))
                quadraticBezierTo(x(76f), x(76f), x(128f), x(76f))
                close()
            }
        drawPath(bubble, fill)

        // The open eye.
        drawCircle(white, radius = x(22f), center = Offset(x(200f), x(174f)))

        // The wink: a half-circle arc, stroked at bildhaft's weight so the
        // family carries one stroke width rather than two.
        drawArc(
            color = white,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(x(290f), x(152f)),
            size = Size(x(44f), x(44f)),
            style = Stroke(width = x(26f), cap = StrokeCap.Round),
        )

        // The open mouth. An ellipse and it stays one — this is the talking.
        drawOval(
            color = white,
            topLeft = Offset(x(256f - 46f), x(256f - 38f)),
            size = Size(x(92f), x(76f)),
        )
    }
}
