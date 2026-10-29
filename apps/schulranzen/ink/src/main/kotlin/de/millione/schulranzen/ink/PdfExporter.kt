package de.millione.schulranzen.ink

import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Notebook as PDF: one A4 page (595 × 842 pt) per sheet, ink as vectors,
 * so it prints sharp. [ruling] false leaves the paper plain (for handing in).
 */
object PdfExporter {
    private const val A4_W = 595
    private const val A4_H = 842

    fun export(pages: List<CanvasPage>, out: OutputStream, ruling: Boolean = true) {
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { i, page ->
                val p = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, i + 1).create())
                val c = p.canvas
                c.scale(A4_W / PageGeometry.WIDTH, A4_H / PageGeometry.HEIGHT)
                PagePainter.paint(c, page, ruling)
                doc.finishPage(p)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
