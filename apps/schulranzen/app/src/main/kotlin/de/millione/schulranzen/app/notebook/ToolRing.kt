package de.millione.schulranzen.app.notebook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.notebook.PenPreset
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.ink.PageGeometry
import de.millione.schulranzen.ink.ToolKind
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The tool ring has three levels:
 * - [MAIN]: tools inside, colours of the current pen outside. A writing tool
 *   leads on to its style, the others are picked and the ring closes.
 * - [STYLE]: thicknesses inside, all colours outside, for pen or marker.
 *   Picks apply at once and the ring stays, so thickness and colour go together.
 * - [MORE]: actions inside (undo, redo, new page, bookmark, save favourite,
 *   paste), favourite pens outside.
 * The centre closes the ring on [MAIN] and goes back on the other levels.
 */
enum class RingLevel { MAIN, STYLE, MORE }

enum class RingAction(val label: String) {
    UNDO("Rückgängig"), REDO("Wiederholen"), NEW_PAGE("Neue Seite"),
    BOOKMARK("Lesezeichen"), SAVE_FAVOURITE("Stift als Favorit"), PASTE("Einfügen"),
}

sealed interface RingItem {
    data class Tool(val kind: ToolKind) : RingItem
    data class Swatch(val color: Int) : RingItem
    data class Size(val size: Float) : RingItem
    data class Favourite(val preset: PenPreset) : RingItem
    data class Action(val action: RingAction) : RingItem
    data object More : RingItem
    data object Back : RingItem
    data object Close : RingItem
}

/** One level as it is drawn and hit: inner ring, outer ring, centre, and what is current. */
data class RingLayout(
    val level: RingLevel,
    val inner: List<RingItem>,
    val outer: List<RingItem>,
    val centre: RingItem,
    val selected: Set<RingItem> = emptySet(),
    /** Pen or marker whose style the [RingLevel.STYLE] level shows (colour of the size dots). */
    val inkColor: Int = 0,
    val marker: Boolean = false,
)

object RingMenus {
    val tools = listOf(ToolKind.PEN, ToolKind.MARKER, ToolKind.ERASER, ToolKind.STROKE_ERASER, ToolKind.LASSO, ToolKind.RULER, ToolKind.TEXT)

    fun main(swatches: List<Int>) = RingLayout(RingLevel.MAIN, tools.map { RingItem.Tool(it) } + RingItem.More, swatches.map { RingItem.Swatch(it) }, RingItem.Close)

    fun style(sizes: List<Float>, colors: List<Int>, inkColor: Int, marker: Boolean) =
        RingLayout(RingLevel.STYLE, sizes.map { RingItem.Size(it) }, colors.map { RingItem.Swatch(it) }, RingItem.Back, inkColor = inkColor, marker = marker)

    fun more(favourites: List<PenPreset>, canPaste: Boolean) = RingLayout(
        RingLevel.MORE,
        listOf(RingAction.UNDO, RingAction.REDO, RingAction.NEW_PAGE, RingAction.BOOKMARK, RingAction.SAVE_FAVOURITE)
            .plus(if (canPaste) listOf(RingAction.PASTE) else emptyList())
            .map { RingItem.Action(it) },
        favourites.map { RingItem.Favourite(it) },
        RingItem.Back,
    )
}

/** Ring sizes in dp around its centre. Shared by the drawing and the hit test so both always agree. */
object ToolRingGeometry {
    const val HOLE = 30f
    const val INNER = 80f
    const val OUTER = 124f
    /** Room below the ring for the name of what the pen points at. */
    const val LABEL = 52f

    fun hit(layout: RingLayout, cx: Float, cy: Float, x: Float, y: Float, density: Float): RingItem? {
        val d = hypot(x - cx, y - cy) / density
        val a = angle(x - cx, y - cy)
        fun List<RingItem>.at() = if (isEmpty()) null else this[(a / (2 * PI / size)).toInt().coerceIn(0, size - 1)]
        return when {
            d < HOLE -> layout.centre
            d < INNER + 4 -> layout.inner.at()
            d < OUTER + 18 -> layout.outer.at()
            else -> null
        }
    }

    /** Angle from 12 o'clock, clockwise, 0 … 2π; segments start at the top. */
    fun angle(dx: Float, dy: Float): Double {
        val a = atan2(dx.toDouble(), -dy.toDouble())
        return (a + 2 * PI) % (2 * PI)
    }

    /** Ring centre for a pen at [x], [y]: moved in from the edges so the whole ring and its label show. */
    fun centre(x: Float, y: Float, width: Float, height: Float, density: Float): Pair<Float, Float> {
        val r = (OUTER + 22) * density
        fun fit(v: Float, lo: Float, hi: Float) = if (hi > lo) v.coerceIn(lo, hi) else (lo + hi) / 2
        return fit(x, r, width - r) to fit(y, r, height - r - LABEL * density)
    }
}

/**
 * The ring around the pen tip. It unfolds with a spring on every level; the
 * entry under the hovering pen lifts. Only the ring itself takes touches:
 * pen or finger on the paper beside it reaches the page, which closes the ring.
 */
@Composable
fun ToolRingOverlay(
    state: RingState,
    layout: RingLayout,
    onPick: (RingItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ranzen.colors
    val density = LocalDensity.current.density
    val open = remember { Animatable(0f) }
    LaunchedEffect(layout.level) {
        if (open.value > 0f) open.snapTo(0.7f)
        open.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }
    BackHandler(onBack = onDismiss)
    val icons: Map<RingItem, ImageVector> = mapOf(
        RingItem.Tool(ToolKind.PEN) to RanzenIcons.Pen, RingItem.Tool(ToolKind.MARKER) to RanzenIcons.Marker,
        RingItem.Tool(ToolKind.ERASER) to RanzenIcons.Eraser, RingItem.Tool(ToolKind.STROKE_ERASER) to RanzenIcons.Trash,
        RingItem.Tool(ToolKind.LASSO) to RanzenIcons.Lasso, RingItem.Tool(ToolKind.RULER) to RanzenIcons.Ruler,
        RingItem.Tool(ToolKind.TEXT) to RanzenIcons.Text, RingItem.More to RanzenIcons.More,
        RingItem.Back to RanzenIcons.Back, RingItem.Close to RanzenIcons.Close,
        RingItem.Action(RingAction.UNDO) to RanzenIcons.Undo, RingItem.Action(RingAction.REDO) to RanzenIcons.Redo,
        RingItem.Action(RingAction.NEW_PAGE) to RanzenIcons.Plus, RingItem.Action(RingAction.BOOKMARK) to RanzenIcons.Bookmark,
        RingItem.Action(RingAction.SAVE_FAVOURITE) to RanzenIcons.Check, RingItem.Action(RingAction.PASTE) to RanzenIcons.Copy,
    )
    val painters: Map<RingItem, Painter> = icons.mapValues { rememberVectorPainter(it.value) }
    // Until the pen moves, it rests on the centre only because the ring opened there: nothing is pointed at yet.
    val moved = hypot(state.hoverX - state.penX, state.hoverY - state.penY) > 12 * density
    val hovered = if (moved) ToolRingGeometry.hit(layout, state.x, state.y, state.hoverX, state.hoverY, density) else null
    val reach = (ToolRingGeometry.OUTER + 22) * density

    // Only the ring's square takes touches; its corners close it.
    Box(
        Modifier
            .offset { IntOffset((state.x - reach).toInt(), (state.y - reach).toInt()) }
            .size((2 * reach / density).dp)
            .pointerInput(state.x, state.y, layout) {
                detectTapGestures { p ->
                    val item = ToolRingGeometry.hit(layout, reach, reach, p.x, p.y, density)
                    if (item != null) onPick(item) else onDismiss()
                }
            },
    ) {
        Canvas(Modifier.size((2 * reach / density).dp)) {
            val t = open.value
            val centre = Offset(reach, reach)
            scale(0.55f + 0.45f * t, centre) {
                val alpha = t.coerceIn(0f, 1f)
                val hole = ToolRingGeometry.HOLE.dp.toPx()
                val inner = ToolRingGeometry.INNER.dp.toPx()
                val outer = ToolRingGeometry.OUTER.dp.toPx()
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

                fun place(i: Int, n: Int, r: Float): Offset {
                    val mid = Math.toRadians((i + 0.5) * 360.0 / n)
                    return centre + Offset((sin(mid) * r).toFloat(), (-cos(mid) * r).toFloat())
                }

                // Inner ring
                val seg = 360f / layout.inner.size.coerceAtLeast(1)
                layout.inner.forEachIndexed { i, item ->
                    val selected = item in layout.selected
                    val hot = hovered == item
                    if (selected || hot) {
                        drawArcSegment(centre, hole + 3.dp.toPx(), inner - 3.dp.toPx(), i * seg, seg,
                            c.signal.copy(alpha = (if (hot) 0.22f else 0.12f) * alpha))
                    }
                    val pos = place(i, layout.inner.size, (hole + inner) / 2)
                    when (item) {
                        is RingItem.Size -> drawSize(pos, item.size, layout, hot, alpha)
                        else -> painters[item]?.let { p ->
                            val iconSize = (if (hot) 28 else 23).dp.toPx()
                            translate(pos.x - iconSize / 2, pos.y - iconSize / 2) {
                                with(p) { draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(if (selected) c.signal else c.ink)) }
                            }
                        }
                    }
                }
                if (layout.inner.size > 1) for (i in layout.inner.indices) {
                    rotate(i * seg, centre) { drawLine(c.line.copy(alpha = alpha), centre + Offset(0f, -hole), centre + Offset(0f, -inner), 1.dp.toPx()) }
                }

                // Outer ring: colour lamps and favourite pens
                layout.outer.forEachIndexed { i, item ->
                    val pos = place(i, layout.outer.size, (inner + outer) / 2)
                    val hot = hovered == item
                    val argb = when (item) { is RingItem.Swatch -> item.color; is RingItem.Favourite -> item.preset.color; else -> return@forEachIndexed }
                    val color = Color(argb)
                    val rad = (if (hot) 15 else 11).dp.toPx()
                    drawCircle(color.copy(alpha = 0.3f * alpha), rad + 5.dp.toPx(), pos)
                    if (item is RingItem.Favourite && ToolKind.of(item.preset.tool) == ToolKind.MARKER) {
                        drawRoundRect(color.copy(alpha = 0.75f * alpha), pos - Offset(rad, rad * 0.6f), Size(2 * rad, 1.2f * rad), CornerRadius(3.dp.toPx()))
                    } else {
                        drawCircle(color.copy(alpha = alpha), rad, pos)
                        // Thin edge so dark ink shows on the night ring.
                        drawCircle(c.ink.copy(alpha = 0.3f * alpha), rad, pos, style = Stroke(1.dp.toPx()))
                    }
                    if (item is RingItem.Favourite) {
                        // Thickness as a light dot in the lamp.
                        val dot = (1.5f + item.preset.size / PageGeometry.UNITS_PER_MM * 3f).coerceAtMost(7f).dp.toPx()
                        drawCircle(Color.White.copy(alpha = 0.85f * alpha), dot, pos)
                    }
                    if (item in layout.selected) drawCircle(c.ink.copy(alpha = alpha), rad + 3.dp.toPx(), pos, style = Stroke(1.5.dp.toPx()))
                }

                // Centre: close or back
                val hotCentre = hovered == layout.centre
                drawCircle(c.raised.copy(alpha = alpha), hole - 4.dp.toPx(), centre)
                if (hotCentre) drawCircle(c.signal.copy(alpha = 0.22f * alpha), hole - 4.dp.toPx(), centre)
                painters[layout.centre]?.let { p ->
                    val iconSize = 18.dp.toPx()
                    translate(centre.x - iconSize / 2, centre.y - iconSize / 2) {
                        with(p) { draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(c.ink)) }
                    }
                }
            }
        }
    }
    // Name of what the pen points at, under the ring
    val label = ringLabel(hovered, layout)
    val below = (ToolRingGeometry.OUTER + 14) * density
    Box(
        Modifier
            .offset { IntOffset((state.x - 130 * density).toInt(), (state.y + below).toInt()) }
            .width(260.dp)
            .graphicsLayer { alpha = open.value.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = Ranzen.type.label, color = c.paper,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink.copy(alpha = 0.86f)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val PAPER = Color(0xFFFBF8F1)

/** Thickness entry: a dot (pen) or a bar (marker) as wide as the line it draws, in the current colour. */
private fun DrawScope.drawSize(pos: Offset, size: Float, layout: RingLayout, hot: Boolean, alpha: Float) {
    val color = Color(layout.inkColor).copy(alpha = alpha * (if (layout.marker) 0.7f else 1f))
    val grow = if (hot) 1.25f else 1f
    // On a small piece of paper, so dark ink shows in the night theme too.
    drawCircle(PAPER.copy(alpha = alpha), 17.dp.toPx() * grow, pos)
    if (layout.marker) {
        val h = (4f + size / 26f * 12f).dp.toPx() * grow
        val w = 22.dp.toPx() * grow
        drawRoundRect(color, pos - Offset(w / 2, h / 2), Size(w, h), CornerRadius(h / 3))
    } else {
        val r = (1.5f + size / 10f * 7f).dp.toPx() * grow
        drawCircle(color, r, pos)
    }
}

fun millimetres(size: Float): String = String.format(Locale.GERMANY, "%.2f", size / PageGeometry.UNITS_PER_MM).trimEnd('0').trimEnd(',') + " mm"

private fun ringLabel(item: RingItem?, layout: RingLayout): String = when (item) {
    is RingItem.Tool -> toolName(item.kind) + if (item.kind.writes) " · Dicke und Farbe" else ""
    is RingItem.Swatch -> colorName(item.color)
    is RingItem.Size -> "Dicke " + millimetres(item.size)
    is RingItem.Favourite -> "Favorit: " + toolName(ToolKind.of(item.preset.tool)) + ", " + colorName(item.preset.color) + ", " + millimetres(item.preset.size)
    is RingItem.Action -> item.action.label
    RingItem.More -> "Mehr: Rückgängig, Seite, Favoriten"
    RingItem.Back -> "Zurück"
    RingItem.Close -> "Schließen"
    null -> when (layout.level) {
        RingLevel.MAIN -> "Tippen zum Wählen · aufs Blatt schreiben schließt"
        RingLevel.STYLE -> (if (layout.marker) "Marker" else "Stift") + ": Dicke innen, Farbe außen"
        RingLevel.MORE -> if (layout.outer.isEmpty()) "Favoriten: „Stift als Favorit“ tippen" else "Aktionen innen, Favoriten außen"
    }
}

fun toolName(kind: ToolKind) = when (kind) {
    ToolKind.PEN -> "Stift"
    ToolKind.MARKER -> "Marker"
    ToolKind.ERASER -> "Radierer"
    ToolKind.STROKE_ERASER -> "Strichradierer"
    ToolKind.LASSO -> "Lasso"
    ToolKind.RULER -> "Lineal"
    ToolKind.TEXT -> "Text"
}

private fun DrawScope.drawArcSegment(centre: Offset, r0: Float, r1: Float, startDeg: Float, sweepDeg: Float, color: Color) {
    val path = Path()
    val start = startDeg - 90f
    path.arcTo(androidx.compose.ui.geometry.Rect(centre, r1), start, sweepDeg, true)
    path.arcTo(androidx.compose.ui.geometry.Rect(centre, r0), start + sweepDeg, -sweepDeg, false)
    path.close()
    drawPath(path, color)
}

internal fun colorName(argb: Int): String = when (argb) {
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
    else -> ExtraColors.names[argb] ?: "Eigene Farbe"
}

/** More colours for the style panel and the ring's style level, beside the ones in the main ring. */
internal object ExtraColors {
    val pen = listOf(
        0xFF6E6A62.toInt(), 0xFF2E86C1.toInt(), 0xFF1F8A8C.toInt(),
        0xFFD9762B.toInt(), 0xFFB0307A.toInt(), 0xFF6B4A2F.toInt(),
    )
    val marker = listOf(0xFFC9A6F0.toInt())
    val names = mapOf(
        pen[0] to "Grau", pen[1] to "Himmelblau", pen[2] to "Türkis",
        pen[3] to "Orange", pen[4] to "Magenta", pen[5] to "Braun", marker[0] to "Lila",
    )
}
