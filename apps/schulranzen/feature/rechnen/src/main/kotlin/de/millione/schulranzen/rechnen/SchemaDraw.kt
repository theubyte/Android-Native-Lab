package de.millione.schulranzen.rechnen

import kotlin.math.abs
import androidx.compose.ui.geometry.Rect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import de.millione.schulranzen.core.shk.PipeCatalog
import de.millione.schulranzen.core.shk.PipeKind
import de.millione.schulranzen.core.shk.Prim
import de.millione.schulranzen.core.shk.Pt
import de.millione.schulranzen.core.shk.Schema
import de.millione.schulranzen.core.shk.SchemaGeometry
import de.millione.schulranzen.core.shk.SchemaPipe
import de.millione.schulranzen.core.shk.SchemaSymbol
import de.millione.schulranzen.core.shk.SymbolLibrary
import kotlin.math.ceil
import kotlin.math.floor

/** Colours of a schema drawing; the screen follows the theme, exports are always light. */
data class SchemaStyle(
    val ink: Color,
    val paper: Color,
    val grid: Color,
    val accent: Color,
    val dark: Boolean,
) {
    fun pipe(kind: PipeKind): Color {
        val base = Color(kind.argb.toInt())
        // On slate the deep colours sink in; lift them toward chalk.
        return if (dark) lerp(base, Color.White, 0.35f) else base
    }

    companion object {
        val Print = SchemaStyle(Color(0xFF1C1B19), Color.White, Color(0xFFE4DED2), Color(0xFFD9542F), dark = false)
    }
}

/** What the editor highlights on top of the drawing. */
data class SchemaOverlay(
    val selectedId: String? = null,
    val showPorts: Boolean = false,
    val snapAt: Pt? = null,
    val draft: List<Pt>? = null,
    val draftKind: PipeKind = PipeKind.FLOW,
)

private const val LINE = 0.09f
private const val PIPE = 0.14f

private fun Pt.o() = Offset(x.toFloat(), y.toFloat())

/**
 * Draws [schema] in schema units: callers set up the transform (screen pan
 * and zoom or the export page) so that one unit is one grid cell.
 */
fun DrawScope.drawSchemaUnits(schema: Schema, style: SchemaStyle, overlay: SchemaOverlay = SchemaOverlay()) {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = style.ink.toArgb()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    schema.pipes.forEach { p ->
        if (p.id == overlay.selectedId) drawPolyline(p.points, style.accent.copy(alpha = 0.35f), PIPE * 3.2f)
        drawPipe(p, style)
    }
    overlay.draft?.let { drawPolyline(it, style.pipe(overlay.draftKind).copy(alpha = 0.7f), PIPE, dashed = true) }

    schema.symbols.forEach { s ->
        if (s.id == overlay.selectedId) {
            val b = SchemaGeometry.bounds(s)
            drawRoundRect(
                style.accent, Offset(b.left.toFloat() - 0.3f, b.top.toFloat() - 0.3f), Size(b.width.toFloat() + 0.6f, b.height.toFloat() + 0.6f),
                CornerRadius(0.3f), style = Stroke(LINE, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.3f, 0.2f))),
            )
        }
        drawSymbol(s, style, textPaint)
        if (s.label.isNotBlank()) {
            val b = SchemaGeometry.bounds(s)
            drawText(s.label, Pt((b.left + b.right) / 2, b.bottom + 0.65), 0.5f, textPaint, style.ink)
        }
        if (overlay.showPorts) SchemaGeometry.ports(s).forEach { port ->
            drawCircle(style.paper, 0.18f, port.o())
            drawCircle(style.accent, 0.18f, port.o(), style = Stroke(LINE * 0.8f))
        }
    }

    // Pipe labels after symbols, so they stay readable. Each label goes on the
    // longest segment it fits on, beside the pipe, and never on top of another
    // label; a pipe too short for its label (or with no free spot) stays unlabelled.
    val placed = ArrayList<Rect>()
    // Symbols (with their name below), all pipe runs and free texts are in the way as well.
    val blocked = ArrayList<Rect>()
    schema.symbols.forEach { sym ->
        val b = SchemaGeometry.bounds(sym)
        blocked += Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.bottom + if (sym.label.isNotBlank()) 0.9 else 0.0).toFloat())
    }
    schema.pipes.forEach { p ->
        p.points.zipWithNext().forEach { (a, b) ->
            blocked += Rect(minOf(a.x, b.x).toFloat() - 0.08f, minOf(a.y, b.y).toFloat() - 0.08f, maxOf(a.x, b.x).toFloat() + 0.08f, maxOf(a.y, b.y).toFloat() + 0.08f)
        }
    }
    schema.labels.forEach { l -> blocked += Rect(l.at.x.toFloat() - l.text.length * 0.18f, l.at.y.toFloat() - 0.5f, l.at.x.toFloat() + l.text.length * 0.18f, l.at.y.toFloat() + 0.4f) }
    schema.pipes.forEach { p ->
        val text = listOfNotNull(PipeCatalog.byId(p.pipeId)?.fullLabel, p.label.ifBlank { null }).joinToString(" · ")
        if (text.isEmpty() || p.points.size < 2) return@forEach
        val size = 0.42f
        textPaint.textSize = size * TEXT_K
        val w = textPaint.measureText(text) / TEXT_K + 0.2f
        val h = size * 1.25f
        val spot = p.points.zipWithNext().sortedByDescending { (u, v) -> u.dist(v) }.asSequence().flatMap { (a, b) ->
            // Middle first, then towards the ends of the run.
            sequenceOf(0.5, 0.3, 0.7, 0.15, 0.85).flatMap { f ->
                val m = Pt(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                if (a.y == b.y) {
                    if (abs(b.x - a.x) < w + 0.4) emptySequence()
                    else sequenceOf(Pt(m.x, m.y - 0.35), Pt(m.x, m.y + 0.35 + h * 0.75))
                } else {
                    if (abs(b.y - a.y) < h + 0.6) emptySequence()
                    else sequenceOf(Pt(m.x + 0.25 + w / 2, m.y + 0.15), Pt(m.x - 0.25 - w / 2, m.y + 0.15))
                }
            }
        }.map { at -> at to Rect(at.x.toFloat() - w / 2, at.y.toFloat() - size * 0.95f, at.x.toFloat() + w / 2, at.y.toFloat() + size * 0.3f) }
            .firstOrNull { (_, r) -> placed.none { it.overlaps(r) } && blocked.none { it.overlaps(r) } } ?: return@forEach
        placed += spot.second
        drawText(text, spot.first, size, textPaint, style.pipe(p.kind), background = style.paper)
    }

    schema.labels.forEach { l ->
        if (l.id == overlay.selectedId) {
            val w = l.text.length * 0.32f + 0.6f
            drawRoundRect(style.accent, Offset(l.at.x.toFloat() - w / 2, l.at.y.toFloat() - 0.55f), Size(w, 1.1f), CornerRadius(0.25f), style = Stroke(LINE))
        }
        drawText(l.text, Pt(l.at.x, l.at.y + 0.22), 0.6f, textPaint, style.ink)
    }

    overlay.snapAt?.let { drawCircle(style.accent, 0.32f, it.o(), style = Stroke(LINE * 1.4f)) }
}

/** Background grid with a dot every unit and a faint line every five. */
fun DrawScope.drawSchemaGrid(style: SchemaStyle, left: Double, top: Double, right: Double, bottom: Double, unitPx: Float) {
    val x0 = floor(left).toInt(); val x1 = ceil(right).toInt()
    val y0 = floor(top).toInt(); val y1 = ceil(bottom).toInt()
    val lineW = 1f / unitPx
    for (x in x0..x1) if (Math.floorMod(x, 5) == 0) drawLine(style.grid, Offset(x.toFloat(), y0.toFloat()), Offset(x.toFloat(), y1.toFloat()), lineW)
    for (y in y0..y1) if (Math.floorMod(y, 5) == 0) drawLine(style.grid, Offset(x0.toFloat(), y.toFloat()), Offset(x1.toFloat(), y.toFloat()), lineW)
    if (unitPx >= 10f) {
        val r = 1.6f / unitPx
        for (x in x0..x1) for (y in y0..y1) drawCircle(style.grid, r, Offset(x.toFloat(), y.toFloat()))
    }
}

private fun DrawScope.drawPolyline(points: List<Pt>, color: Color, width: Float, dashed: Boolean = false) {
    if (points.size < 2) return
    val path = Path().apply { moveTo(points[0].x.toFloat(), points[0].y.toFloat()); points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
    drawPath(
        path, color,
        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(0.5f, 0.3f)) else null),
    )
}

private fun DrawScope.drawPipe(p: SchemaPipe, style: SchemaStyle) {
    val color = style.pipe(p.kind)
    drawPolyline(p.points, color, PIPE, dashed = p.kind.dashed)
    // A dot where the pipe joins a port; an open end gets a short cap.
    listOf(p.start to p.points.firstOrNull(), p.end to p.points.lastOrNull()).forEach { (ref, at) ->
        if (at == null) return@forEach
        if (ref != null) drawCircle(color, PIPE * 1.1f, at.o()) else drawCircle(color, PIPE * 0.9f, at.o(), style = Stroke(LINE))
    }
}

private fun DrawScope.drawSymbol(s: SchemaSymbol, style: SchemaStyle, paint: Paint) {
    val def = SymbolLibrary.def(s.type)
    // Paper behind closed bodies hides grid and pipes that run underneath.
    def.prims.forEach { prim ->
        when (prim) {
            is Prim.Circle -> if (!prim.filled) drawCircle(style.paper, prim.r.toFloat(), SchemaGeometry.toWorld(s, prim.c).o())
            is Prim.Poly -> if (prim.closed && !prim.filled) drawPath(polyPath(s, prim.points, true), style.paper, style = Fill)
            else -> Unit
        }
    }
    def.prims.forEach { prim ->
        when (prim) {
            is Prim.Line -> drawLine(
                style.ink, SchemaGeometry.toWorld(s, prim.a).o(), SchemaGeometry.toWorld(s, prim.b).o(), LINE,
                cap = StrokeCap.Round, pathEffect = if (prim.dashed) PathEffect.dashPathEffect(floatArrayOf(0.15f, 0.12f)) else null,
            )
            is Prim.Circle -> {
                val c = SchemaGeometry.toWorld(s, prim.c).o()
                if (prim.filled) drawCircle(style.ink, prim.r.toFloat(), c) else drawCircle(style.ink, prim.r.toFloat(), c, style = Stroke(LINE))
            }
            is Prim.Poly -> drawPath(
                polyPath(s, prim.points, prim.closed), style.ink,
                style = if (prim.filled) Fill else Stroke(LINE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            is Prim.Label -> drawText(prim.text, SchemaGeometry.toWorld(s, prim.at).let { Pt(it.x, it.y + prim.size * 0.36) }, prim.size.toFloat(), paint, style.ink, bold = true)
        }
    }
}

private fun polyPath(s: SchemaSymbol, pts: List<Pt>, closed: Boolean) = Path().apply {
    pts.forEachIndexed { i, p -> val w = SchemaGeometry.toWorld(s, p); if (i == 0) moveTo(w.x.toFloat(), w.y.toFloat()) else lineTo(w.x.toFloat(), w.y.toFloat()) }
    if (closed) close()
}

/** Text in schema units via the platform canvas (works on screen and in PDF/PNG). */
private fun DrawScope.drawText(text: String, at: Pt, size: Float, paint: Paint, color: Color, bold: Boolean = false, background: Color? = null) {
    // Text sizes below one unit lay out badly (hinted advances); draw at TEXT_K times and scale down.
    paint.textSize = size * TEXT_K
    paint.color = color.toArgb()
    paint.isFakeBoldText = bold
    if (background != null) {
        val w = paint.measureText(text) / TEXT_K
        drawRoundRect(background.copy(alpha = 0.85f), Offset(at.x.toFloat() - w / 2 - 0.1f, at.y.toFloat() - size * 0.95f), Size(w + 0.2f, size * 1.25f), CornerRadius(0.12f))
    }
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    canvas.translate(at.x.toFloat(), at.y.toFloat())
    canvas.scale(1f / TEXT_K, 1f / TEXT_K)
    canvas.drawText(text, 0f, 0f, paint)
    canvas.restore()
}

private const val TEXT_K = 64f

/** Renders the whole schema fitted into [width] × [height] px (export). */
fun DrawScope.drawSchemaFitted(schema: Schema, style: SchemaStyle, width: Float, height: Float, margin: Float) {
    val b = SchemaGeometry.bounds(schema) ?: return
    val pad = 1.5
    val bw = (b.width + 2 * pad).toFloat(); val bh = (b.height + 2 * pad).toFloat()
    val scale = minOf((width - 2 * margin) / bw, (height - 2 * margin) / bh)
    val ox = margin + ((width - 2 * margin) - bw * scale) / 2 - (b.left - pad).toFloat() * scale
    val oy = margin + ((height - 2 * margin) - bh * scale) / 2 - (b.top - pad).toFloat() * scale
    withTransform({ translate(ox, oy); scale(scale, scale, Offset.Zero) }) {
        drawSchemaUnits(schema, style)
    }
}
