package de.millione.schulranzen.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import de.millione.schulranzen.ink.PageGeometry.HEIGHT
import de.millione.schulranzen.ink.PageGeometry.WIDTH

/**
 * Paints a page in page units: sheet, ruling, pictures, markers below ink,
 * ink, text. One painter for the screen, thumbnails and PDF export, so they
 * always look alike.
 */
object PagePainter {
    // One per thread: the renderer keeps scratch arrays and mesh caches without any lock,
    // and thumbnails and the PDF export paint beside the main thread.
    private val renderers = object : ThreadLocal<CanvasStrokeRenderer>() {
        override fun initialValue(): CanvasStrokeRenderer = CanvasStrokeRenderer.create()
    }
    private val renderer: CanvasStrokeRenderer get() = renderers.get()!!
    private val identity = Matrix()

    /**
     * Paints the page. With [asyncImages] pictures not yet decoded are left
     * out and false is returned (paint again when they are ready).
     */
    fun paint(
        canvas: Canvas,
        page: CanvasPage,
        ruling: Boolean = true,
        hideStrokes: Set<Long> = emptySet(),
        hideItems: Set<Long> = emptySet(),
        asyncImages: Boolean = false,
    ): Boolean {
        var complete = true
        val sheet = page.background
        if (sheet != null) {
            PaperPainter.draw(canvas, PaperStyle.BLANK)
            complete = ItemPainter.drawSheet(canvas, sheet, asyncImages)
        } else {
            PaperPainter.draw(canvas, if (ruling) page.paper else PaperStyle.BLANK)
        }
        val items = page.ink.items
        for (it in items) if (it is ImageItem && it.id !in hideItems && !ItemPainter.draw(canvas, it, asyncImages)) complete = false
        val strokes = page.ink.strokes
        for (s in strokes) if (s.tool == ToolKind.MARKER && s.id !in hideStrokes) renderer.draw(canvas, s.stroke, identity)
        for (s in strokes) if (s.tool != ToolKind.MARKER && s.id !in hideStrokes) renderer.draw(canvas, s.stroke, identity)
        for (it in items) if (it is TextItem && it.id !in hideItems) ItemPainter.draw(canvas, it)
        return complete
    }

    /** Strokes [from] until [to] of the page, in their order: what was written since the last full paint. */
    fun paintStrokes(canvas: Canvas, page: CanvasPage, from: Int, to: Int) {
        val strokes = page.ink.strokes
        for (i in from until to) renderer.draw(canvas, strokes[i].stroke, identity)
    }

    /** Just the given strokes and items (a moving selection). */
    fun paintSome(canvas: Canvas, page: CanvasPage, strokes: Set<Long>, items: Set<Long>) {
        for (it in page.ink.items) if (it is ImageItem && it.id in items) ItemPainter.draw(canvas, it)
        for (s in page.ink.strokes) if (s.tool == ToolKind.MARKER && s.id in strokes) renderer.draw(canvas, s.stroke, identity)
        for (s in page.ink.strokes) if (s.tool != ToolKind.MARKER && s.id in strokes) renderer.draw(canvas, s.stroke, identity)
        for (it in page.ink.items) if (it is TextItem && it.id in items) ItemPainter.draw(canvas, it)
    }

    /** Small picture of a page for the page overview. */
    fun thumbnail(page: CanvasPage, widthPx: Int): Bitmap {
        val h = (widthPx * HEIGHT / WIDTH).toInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(widthPx / WIDTH, widthPx / WIDTH)
        paint(c, page)
        return bmp
    }
}
