package de.millione.schulranzen.app.notebook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ColorFilter
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.ink.ToolKind
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

sealed interface RingItem {
    data class Tool(val kind: ToolKind) : RingItem
    data class Swatch(val color: Int) : RingItem
}

/**
 * Ring layout in dp around the pen tip: an inner ring with the tools and an
 * outer ring with the colours of the current writing tool. Shared by the
 * drawing and the hit test so both always agree.
 */
object ToolRingGeometry {
    const val HOLE = 30f
    const val INNER = 80f
    const val OUTER = 124f
    val tools = listOf(ToolKind.PEN, ToolKind.MARKER, ToolKind.ERASER, ToolKind.STROKE_ERASER)
    /** Swatches follow the tool; set by the session before the ring opens. */
    var swatches: List<Int> = InkPalette.pen

    fun hit(cx: Float, cy: Float, x: Float, y: Float, density: Float): RingItem? {
        val d = hypot(x - cx, y - cy) / density
        val a = angle(x - cx, y - cy)
        return when {
            d < HOLE -> null
            d < INNER + 4 -> tools[(a / (2 * PI / tools.size)).toInt().coerceIn(0, tools.size - 1)].let { RingItem.Tool(it) }
            d < OUTER + 18 -> swatches[(a / (2 * PI / swatches.size)).toInt().coerceIn(0, swatches.size - 1)].let { RingItem.Swatch(it) }
            else -> null
        }
    }

    /** Angle from 12 o'clock, clockwise, 0 … 2π; segments start at the top. */
    fun angle(dx: Float, dy: Float): Double {
        val a = atan2(dx.toDouble(), -dy.toDouble())
        return (a + 2 * PI) % (2 * PI)
    }
}

/**
 * The ring around the pen tip. It unfolds with a spring; the segment under
 * the hovering pen lifts; colours sit in the outer ring as small lamps.
 */
@Composable
fun ToolRingOverlay(
    state: RingState,
    current: ToolKind,
    currentColor: Int,
    onPick: (RingItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ranzen.colors
    val density = LocalDensity.current.density
    val open = remember { Animatable(0f) }
    LaunchedEffect(Unit) { open.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) }
    val icons = mapOf(
        ToolKind.PEN to RanzenIcons.Pen, ToolKind.MARKER to RanzenIcons.Marker,
        ToolKind.ERASER to RanzenIcons.Eraser, ToolKind.STROKE_ERASER to RanzenIcons.Trash,
    )
    val painters = icons.mapValues { rememberVectorPainter(it.value) }
    val hovered = ToolRingGeometry.hit(state.x, state.y, state.hoverX, state.hoverY, density)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(state.x, state.y) {
                detectTapGestures { p ->
                    val item = ToolRingGeometry.hit(state.x, state.y, p.x, p.y, density)
                    if (item != null) onPick(item) else onDismiss()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = open.value
            val centre = Offset(state.x, state.y)
            scale(0.55f + 0.45f * t, centre) {
                val alpha = t.coerceIn(0f, 1f)
                val hole = ToolRingGeometry.HOLE.dp.toPx()
                val inner = ToolRingGeometry.INNER.dp.toPx()
                val outer = ToolRingGeometry.OUTER.dp.toPx()
                // Soft shadow and paper discs
                val shadowR = outer + 22.dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        0f to c.bevelShadow.copy(alpha = 0.55f * alpha),
                        (outer / shadowR) to c.bevelShadow.copy(alpha = 0.35f * alpha),
                        1f to Color.Transparent,
                        center = centre + Offset(0f, 6.dp.toPx()), radius = shadowR,
                    ),
                    shadowR, centre + Offset(0f, 6.dp.toPx()),
                )
                drawCircle(c.paper.copy(alpha = 0.97f * alpha), outer, centre)
                drawCircle(c.raised.copy(alpha = alpha), inner, centre)
                drawCircle(c.line.copy(alpha = alpha), inner, centre, style = Stroke(1.dp.toPx()))
                drawCircle(c.line.copy(alpha = alpha), outer, centre, style = Stroke(1.dp.toPx()))

                // Tools
                val tools = ToolRingGeometry.tools
                val seg = 360f / tools.size
                tools.forEachIndexed { i, kind ->
                    val mid = Math.toRadians((i + 0.5) * seg)
                    val r = (hole + inner) / 2
                    val pos = centre + Offset((sin(mid) * r).toFloat(), (-cos(mid) * r).toFloat())
                    val selected = kind == current
                    val hot = hovered == RingItem.Tool(kind)
                    if (selected || hot) {
                        drawArcSegment(centre, hole + 3.dp.toPx(), inner - 3.dp.toPx(), i * seg, seg,
                            (if (hot) c.signal.copy(alpha = 0.22f) else c.signal.copy(alpha = 0.12f)).copy(alpha = if (hot) 0.22f * alpha else 0.12f * alpha))
                    }
                    val iconSize = (if (hot) 30 else 26).dp.toPx()
                    translate(pos.x - iconSize / 2, pos.y - iconSize / 2) {
                        with(painters.getValue(kind)) {
                            draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(if (selected) c.signal else c.ink))
                        }
                    }
                }
                // Dividers between tools
                for (i in tools.indices) {
                    rotate(i * seg, centre) {
                        drawLine(c.line.copy(alpha = alpha), centre + Offset(0f, -hole), centre + Offset(0f, -inner), 1.dp.toPx())
                    }
                }

                // Colour lamps
                val swatches = ToolRingGeometry.swatches
                val sseg = 360f / swatches.size
                swatches.forEachIndexed { i, argb ->
                    val mid = Math.toRadians((i + 0.5) * sseg)
                    val r = (inner + outer) / 2
                    val pos = centre + Offset((sin(mid) * r).toFloat(), (-cos(mid) * r).toFloat())
                    val hot = hovered == RingItem.Swatch(argb)
                    val color = Color(argb)
                    val rad = (if (hot) 15 else 11).dp.toPx()
                    drawCircle(color.copy(alpha = 0.3f * alpha), rad + 5.dp.toPx(), pos)
                    drawCircle(color.copy(alpha = alpha), rad, pos)
                    if (argb == currentColor) drawCircle(c.ink.copy(alpha = alpha), rad + 3.dp.toPx(), pos, style = Stroke(1.5.dp.toPx()))
                }
                // Centre: the pen tip marker
                drawCircle(c.signal.copy(alpha = alpha), 3.dp.toPx(), centre)
            }
        }
        // Name of what the pen points at, under the ring
        val label = when (hovered) {
            is RingItem.Tool -> toolName(hovered.kind)
            is RingItem.Swatch -> colorName(hovered.color)
            null -> toolName(current)
        }
        val below = (ToolRingGeometry.OUTER + 18) * density
        Box(
            Modifier
                .offset { IntOffset((state.x - 70 * density).toInt(), (state.y + below).toInt()) }
                .width(140.dp)
                .graphicsLayer { alpha = open.value.coerceIn(0f, 1f) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, style = Ranzen.type.label, color = c.paper,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink.copy(alpha = 0.86f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

fun toolName(kind: ToolKind) = when (kind) {
    ToolKind.PEN -> "Stift"
    ToolKind.MARKER -> "Marker"
    ToolKind.ERASER -> "Radierer"
    ToolKind.STROKE_ERASER -> "Strichradierer"
}

private fun DrawScope.drawArcSegment(centre: Offset, r0: Float, r1: Float, startDeg: Float, sweepDeg: Float, color: Color) {
    val path = Path()
    val start = startDeg - 90f
    path.arcTo(androidx.compose.ui.geometry.Rect(centre, r1), start, sweepDeg, true)
    path.arcTo(androidx.compose.ui.geometry.Rect(centre, r0), start + sweepDeg, -sweepDeg, false)
    path.close()
    drawPath(path, color)
}

private fun colorName(argb: Int): String = when (argb) {
    InkPalette.pen[0] -> "Tinte"
    InkPalette.pen[1] -> "Königsblau"
    InkPalette.pen[2] -> "Korrekturrot"
    InkPalette.pen[3] -> "Tannengrün"
    InkPalette.pen[4] -> "Pflaume"
    InkPalette.pen[5] -> "Ocker"
    InkPalette.marker[0] -> "Gelb"
    InkPalette.marker[1] -> "Grün"
    InkPalette.marker[2] -> "Rosa"
    InkPalette.marker[3] -> "Blau"
    InkPalette.marker[4] -> "Orange"
    else -> "Farbe"
}
