package io.github.artemimennoi.awgbar

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The power button's halo, breathing at the Mac's pace: a glow with a soft edge swells out from
 * behind the button and brightens on the in-breath, then sinks back; slowly while connected, quicker
 * while connecting. A wide faint glow around it breathes along.
 */
@Composable
fun Halo(color: Color, fast: Boolean, modifier: Modifier = Modifier) {
    // Not rememberInfiniteTransition: it keeps the spec it started with, so a halo that appears
    // while connecting would go on at the connecting pace once connected. This picks up the new
    // pace from wherever the breath is.
    val breath = remember { Animatable(0f) }
    LaunchedEffect(fast) {
        val half = tween<Float>(if (fast) 700 else 2200, easing = EaseInOutSine)
        while (true) {
            breath.animateTo(1f, half)
            breath.animateTo(0f, half)
        }
    }
    Box(
        modifier.drawBehind {
            val b = breath.value
            val r = size.minDimension / 2
            val glow = r * 2.4f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.10f + 0.08f * b), Color.Transparent), center, glow), glow)
            val ring = r * (0.96f + 0.2f * b)
            val alpha = 0.16f + 0.12f * b
            drawCircle(Brush.radialGradient(0.78f to color.copy(alpha = alpha), 1f to color.copy(alpha = 0f), center = center, radius = ring), ring)
        },
    )
}

/** Room around a menu's panel inside its pop-up window, so the window's edge doesn't cut the shadow off. */
private val MenuMargin = 24.dp

/**
 * A Material menu that fades in under its anchor, or above it when there's no room below. Drawn by
 * hand: DropdownMenu's elevation shadow showed as a pale box around the menu while it animated.
 */
@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    if (!visible.currentState && !visible.targetState) return
    val density = LocalDensity.current
    val position = remember(density) { with(density) { MenuPosition(MenuMargin.roundToPx(), 4.dp.roundToPx()) } }
    val shape = MaterialTheme.shapes.small
    val shadow = Color.Black.copy(alpha = if (isSystemInDarkTheme()) 0.45f else 0.16f)
    val panel = MaterialTheme.colorScheme.surfaceContainerHigh
    Popup(position, onDismiss, PopupProperties(focusable = true, clippingEnabled = false)) {
        AnimatedVisibility(visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(90))) {
            Column(
                Modifier.padding(MenuMargin)
                    .outerShadow(shape, shadow)
                    .background(panel, shape)
                    .clip(shape)
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}

@Composable
fun AppMenuItem(text: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = color) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/**
 * Lines a menu up with its anchor: the start edge for an anchor on the left half of the window, the
 * end edge for one on the right. [margin] is the shadow room around the panel, which may hang off
 * the window's edges.
 */
private class MenuPosition(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (anchorBounds.center.x <= windowSize.width / 2) anchorBounds.left - margin
        else anchorBounds.right - popupContentSize.width + margin
        val below = anchorBounds.bottom + gap - margin
        val y = if (below + popupContentSize.height - margin <= windowSize.height) below
        else anchorBounds.top - gap - popupContentSize.height + margin
        return IntOffset(x, y)
    }
}

/** A blurred shadow clipped to outside [shape], soft like an elevation shadow but drawn in the popup itself. */
private fun Modifier.outerShadow(shape: Shape, color: Color) = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val paint = Paint().apply {
        this.color = color
        asFrameworkPaint().maskFilter = BlurMaskFilter(12.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
    }
    val dy = 3.dp.toPx()
    onDrawBehind {
        clipPath(path, ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(0f, dy)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
    }
}
