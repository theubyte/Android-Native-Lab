package de.millione.schulranzen.rechnen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import de.millione.schulranzen.core.shk.Schema
import de.millione.schulranzen.core.shk.SchemaGeometry
import de.millione.schulranzen.core.shk.ShkDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One table in a PDF report. [weights] share out the page width between the columns. */
data class PdfSection(
    val heading: String,
    val columns: List<String>,
    val weights: List<Float>,
    val rows: List<List<String>>,
    /** Columns printed right-aligned (numbers). */
    val rightAligned: Set<Int> = emptySet(),
)

/**
 * Exports of the SHK tools: table reports and schemata as PDF (Android
 * PdfDocument, A4) and schemata as PNG. All writers block; call them from IO.
 */
object ShkExport {
    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN = 40f

    /** Writes through the content resolver on IO. Returns false and keeps quiet on failure. */
    suspend fun save(context: Context, uri: Uri, write: (OutputStream) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { write(it) } ?: error("no stream")
        }.isSuccess
    }

    fun stamp(): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())

    /** Safe file name with date: "Aufmaß EFH Müller 2026-10-29.pdf". */
    fun fileName(title: String, ext: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Export" }
        return "$clean ${SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())}.$ext"
    }

    fun tablePdf(out: OutputStream, title: String, subtitle: String, sections: List<PdfSection>) {
        val doc = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = 0xFF6E6A62.toInt() }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val colPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF6E6A62.toInt() }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f }
        val rule = Paint().apply { color = 0xFFDDD6C8.toInt(); strokeWidth = 0.6f }
        val usable = A4_W - 2 * MARGIN
        val rowH = 15f

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finish() {
            page?.let { p ->
                subPaint.textAlign = Paint.Align.LEFT
                p.canvas.drawText("Schulranzen · ${ShkDesign.SOURCE}", MARGIN, A4_H - 22f, subPaint.apply { textSize = 7.5f })
                subPaint.textAlign = Paint.Align.RIGHT
                p.canvas.drawText("Seite $pageNo", A4_W - MARGIN, A4_H - 22f, subPaint)
                subPaint.textAlign = Paint.Align.LEFT; subPaint.textSize = 10f
                doc.finishPage(p)
            }
        }
        fun newPage() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNo).create())
            y = MARGIN + 6f
            if (pageNo == 1) {
                page!!.canvas.drawText(title, MARGIN, y + 12f, titlePaint)
                page!!.canvas.drawText(subtitle, MARGIN, y + 28f, subPaint)
                y += 48f
            }
        }
        fun columnsX(weights: List<Float>): List<Float> {
            val total = weights.sum()
            var x = MARGIN
            return weights.map { w -> x.also { x += usable * w / total } }
        }
        fun cell(canvas: android.graphics.Canvas, text: String, x: Float, width: Float, yy: Float, paint: Paint, right: Boolean) {
            var t = text
            while (t.isNotEmpty() && paint.measureText(t) > width - 6f) t = t.dropLast(1)
            if (t.length < text.length) t = t.dropLast(1) + "…"
            paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(t, if (right) x + width - 4f else x, yy, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        newPage()
        sections.forEach { s ->
            val xs = columnsX(s.weights)
            val widths = xs.mapIndexed { i, x -> (xs.getOrNull(i + 1) ?: (A4_W - MARGIN)) - x }
            fun header() {
                val c = page!!.canvas
                s.columns.forEachIndexed { i, h -> cell(c, h.uppercase(Locale.GERMANY), xs[i], widths[i], y + 10f, colPaint, i in s.rightAligned) }
                y += 14f
                c.drawLine(MARGIN, y, A4_W - MARGIN, y, rule)
                y += 4f
            }
            if (y + 60f > A4_H - MARGIN) newPage()
            page!!.canvas.drawText(s.heading, MARGIN, y + 14f, headPaint)
            y += 24f
            header()
            s.rows.forEach { row ->
                if (y + rowH > A4_H - MARGIN - 10f) { newPage(); header() }
                val c = page!!.canvas
                row.forEachIndexed { i, text -> if (i < xs.size) cell(c, text, xs[i], widths[i], y + 10.5f, cellPaint, i in s.rightAligned) }
                y += rowH
                c.drawLine(MARGIN, y - 2f, A4_W - MARGIN, y - 2f, rule.apply { alpha = 110 })
                rule.alpha = 255
            }
            y += 14f
        }
        finish()
        doc.writeTo(out)
        doc.close()
    }

    private fun DrawScopeOn(canvas: android.graphics.Canvas, w: Float, h: Float, block: DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(w, h), block)
    }

    /** The schema fitted onto one A4 landscape page with title and date. */
    fun schemaPdf(out: OutputStream, schema: Schema) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_H, A4_W, 1).create())
        val c = page.canvas
        c.drawText(schema.name, MARGIN, MARGIN, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) })
        c.drawText("Stand ${stamp()} · 1 Kästchen = ${schema.metersPerUnit.toString().replace('.', ',')} m", MARGIN, MARGIN + 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF6E6A62.toInt() })
        c.save()
        c.translate(0f, MARGIN + 20f)
        DrawScopeOn(c, A4_H.toFloat(), A4_W - MARGIN - 20f) { drawSchemaFitted(schema, SchemaStyle.Print, A4_H.toFloat(), A4_W - MARGIN - 40f, MARGIN) }
        c.restore()
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    /** PNG at [pxPerUnit] pixels per grid cell, capped so huge schemata stay below ~16 MP. */
    fun schemaPng(out: OutputStream, schema: Schema, pxPerUnit: Float = 48f) {
        val b = SchemaGeometry.bounds(schema)
        val wu = ((b?.width ?: 10.0) + 3.0).toFloat(); val hu = ((b?.height ?: 6.0) + 3.0).toFloat()
        val scale = minOf(pxPerUnit, 4000f / wu, 4000f / hu)
        val w = (wu * scale).roundToInt().coerceAtLeast(64); val h = (hu * scale).roundToInt().coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        DrawScopeOn(canvas, w.toFloat(), h.toFloat()) { drawSchemaFitted(schema, SchemaStyle.Print, w.toFloat(), h.toFloat(), 0f) }
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
    }
}
