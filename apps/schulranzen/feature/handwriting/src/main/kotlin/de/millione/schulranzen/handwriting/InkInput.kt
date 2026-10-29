package de.millione.schulranzen.handwriting

import android.graphics.RectF
import androidx.ink.strokes.Stroke
import de.millione.schulranzen.core.shapes.Bounds
import de.millione.schulranzen.core.shapes.RecognizedShape
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.TextItem
import de.millione.schulranzen.ink.ToolKind

/** Bridges between the ink engine's strokes and the recognisers' plain point arrays. */
object InkInput {

    /** A stroke as x, y, t triples (t in ms from the stroke's first point). */
    fun points(s: PageStroke): FloatArray {
        val inputs = s.stroke.inputs
        val out = FloatArray(inputs.size * 3)
        if (inputs.size == 0) return out
        val t0 = inputs.get(0).elapsedTimeMillis
        for (i in 0 until inputs.size) {
            val p = inputs.get(i)
            out[i * 3] = p.x; out[i * 3 + 1] = p.y; out[i * 3 + 2] = (p.elapsedTimeMillis - t0).toFloat()
        }
        return out
    }

    /** The written strokes of a page for the index: pen only, no highlighter. */
    fun handwriting(strokes: List<PageStroke>): List<Pair<Long, FloatArray>> =
        strokes.filter { it.tool == ToolKind.PEN && it.stroke.inputs.size > 0 }.map { it.id to points(it) }

    fun handwriting(page: PageInk): List<Pair<Long, FloatArray>> = handwriting(page.strokes)

    fun bounds(s: PageStroke) = Bounds(s.minX, s.minY, s.maxX, s.maxY)

    fun RectF.toBounds() = Bounds(left, top, right, bottom)
    fun Bounds.toRectF() = RectF(left, top, right, bottom)

    /**
     * The replacement strokes for a recognised shape, drawn with the tool,
     * colour and width of the hand-drawn [original].
     */
    fun shapeStrokes(shape: RecognizedShape, original: PageStroke, nextId: () -> Long): List<PageStroke> =
        shape.pointLists().filter { it.size >= 2 }.map { pts ->
            PageStroke(nextId(), original.tool, original.color, original.size, Stroke(original.stroke.brush, PageInk.batchOf(pts)))
        }

    /**
     * A text object in place of handwriting with [bounds]: same left/top,
     * font size picked from the line height (one of [TextItem.sizes]).
     */
    fun textItem(id: Long, text: String, bounds: RectF, lines: Int, color: Int): TextItem {
        val lineHeight = bounds.height() / lines.coerceAtLeast(1)
        val size = TextItem.sizes.minBy { kotlin.math.abs(it * 1.6f - lineHeight) }
        val width = maxOf(bounds.width() + 40f, 200f).coerceAtMost(1000f - bounds.left - 20f).coerceAtLeast(120f)
        return TextItem(id, bounds.left, bounds.top, width, text, size, color)
    }
}
