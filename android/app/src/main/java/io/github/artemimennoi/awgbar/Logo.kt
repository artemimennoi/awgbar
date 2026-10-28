package io.github.artemimennoi.awgbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The app icon's shield and gear, from Resources/AppIcon.icon/Assets (1024-point space). */
private object LogoPaths {
    val shield = PathParser().parsePathString("M 512.00,156.58 C 616.39,211.26 715.81,236.12 800.31,238.60 C 817.71,238.60 830.14,251.03 830.14,268.43 L 830.14,459.81 C 830.14,666.10 688.47,797.83 512.00,869.90 C 335.53,797.83 193.86,666.10 193.86,459.81 L 193.86,268.43 C 193.86,251.03 206.29,238.60 223.69,238.60 C 308.19,236.12 407.61,211.26 512.00,156.58 Z").toPath()
    val gear = PathParser().parsePathString("M 476.65,354.77 L 481.39,313.21 A 186.41 186.41 0 0 1 542.61,313.21 L 547.35,354.77 A 146.64 146.64 0 0 1 587.64,371.46 L 587.64,371.46 L 620.38,345.42 A 186.41 186.41 0 0 1 663.66,388.71 L 637.63,421.45 A 146.64 146.64 0 0 1 654.32,461.74 L 654.32,461.74 L 695.88,466.48 A 186.41 186.41 0 0 1 695.88,527.69 L 654.32,532.44 A 146.64 146.64 0 0 1 637.63,572.72 L 637.63,572.72 L 663.66,605.47 A 186.41 186.41 0 0 1 620.38,648.75 L 587.64,622.72 A 146.64 146.64 0 0 1 547.35,639.40 L 547.35,639.40 L 542.61,680.97 A 186.41 186.41 0 0 1 481.39,680.97 L 476.65,639.40 A 146.64 146.64 0 0 1 436.36,622.72 L 436.36,622.72 L 403.62,648.75 A 186.41 186.41 0 0 1 360.34,605.47 L 386.37,572.72 A 146.64 146.64 0 0 1 369.68,532.44 L 369.68,532.44 L 328.12,527.69 A 186.41 186.41 0 0 1 328.12,466.48 L 369.68,461.74 A 146.64 146.64 0 0 1 386.37,421.45 L 386.37,421.45 L 360.34,388.71 A 186.41 186.41 0 0 1 403.62,345.42 L 436.36,371.46 A 146.64 146.64 0 0 1 476.65,354.77 Z M 415.07,497.09 A 96.93 96.93 0 1 0 608.93,497.09 A 96.93 96.93 0 1 0 415.07,497.09 Z").toPath().apply { fillType = PathFillType.EvenOdd }
    val v = PathParser().parsePathString("M 459.81,456.08 L 512.00,548.04 L 564.19,456.08").toPath()
}

/** Outlined grey shield when off, a filled green one when connected, half-filled while busy. */
@Composable
fun StatusShield(connected: Boolean, busy: Boolean, ink: Color, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val green = Color(0xFF34C759)
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 720f
        withTransform({
            translate(this@Canvas.size.width / 2 - 512 * scale, this@Canvas.size.height / 2 - 513 * scale)
            scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero)
        }) {
            val shieldColor = if (connected) green else ink.copy(alpha = if (busy) 0.6f else 0.35f)
            if (connected) drawPath(LogoPaths.shield, shieldColor)
            else drawPath(LogoPaths.shield, shieldColor, style = Stroke(width = 40f, join = StrokeJoin.Round))
            val gearColor = if (connected) Color.White else ink.copy(alpha = if (busy) 0.6f else 0.8f)
            drawPath(LogoPaths.gear, gearColor)
            drawPath(LogoPaths.v, gearColor, style = Stroke(width = 37.28f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
