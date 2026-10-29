package de.millione.schulranzen.backup

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import de.millione.schulranzen.core.backup.ImportedPage
import de.millione.schulranzen.core.backup.ImportedStroke
import de.millione.schulranzen.core.backup.ImportedTool
import de.millione.schulranzen.ink.Brushes
import de.millione.schulranzen.ink.ImageItem
import de.millione.schulranzen.ink.PageItem
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.TextItem
import de.millione.schulranzen.ink.ToolKind

/** Content of one imported page, ready for InkCodec.write / ItemCodec.write. */
class ImportedPageContent(val paper: PaperStyle, val strokes: List<PageStroke>, val items: List<PageItem>)

/**
 * Turns imported V15 pages into the new app's ink objects. Coordinates are
 * already page units (see V15Model). V15 stored no timestamps, so points
 * get 8 ms steps; pressure and tilt are kept.
 */
object V15Ink {
    fun toPageContent(page: ImportedPage, images: Map<String, String>, nextId: () -> Long): ImportedPageContent {
        val strokes = page.strokes.mapNotNull { toStroke(it, nextId()) }
        val items = ArrayList<PageItem>()
        // Pictures first (backgrounds at the very bottom), text on top.
        for (im in page.images.sortedByDescending { it.background }) {
            val file = images[im.entry] ?: continue
            items += ImageItem(nextId(), im.x, im.y, im.w, im.h, file)
        }
        for (t in page.texts) items += TextItem(nextId(), t.x, t.y, t.width, t.text, t.size, t.color)
        return ImportedPageContent(PaperStyle.of(page.paper), strokes, items)
    }

    fun toStroke(s: ImportedStroke, id: Long): PageStroke? {
        if (s.pointCount < 1) return null
        val tool = if (s.tool == ImportedTool.MARKER) ToolKind.MARKER else ToolKind.PEN
        val batch = MutableStrokeInputBatch()
        var lastX = Float.NaN; var lastY = Float.NaN
        var t = 0L
        for (i in 0 until s.pointCount) {
            val x = s.x(i); val y = s.y(i)
            if (!x.isFinite() || !y.isFinite()) continue
            // Ink rejects consecutive duplicates; V15 recorded them when the pen rested.
            if (x == lastX && y == lastY) continue
            runCatching {
                batch.add(StrokeInput.create(
                    x, y, t, InputToolType.STYLUS, StrokeInput.NO_STROKE_UNIT_LENGTH,
                    s.pressure(i).coerceIn(0f, 1f), s.tilt(i).coerceIn(0f, HALF_PI), StrokeInput.NO_ORIENTATION,
                ))
                lastX = x; lastY = y; t += 8
            }
        }
        if (batch.size == 0) return null
        val size = s.width.coerceIn(0.5f, 80f)
        return PageStroke(id, tool, s.color, size, Stroke(Brushes.brush(tool, s.color, size), batch))
    }

    private const val HALF_PI = (Math.PI / 2).toFloat()
}
