package de.millione.schulranzen.ink

import android.graphics.Canvas
import android.graphics.Paint
import de.millione.schulranzen.ink.PageGeometry.HEIGHT
import de.millione.schulranzen.ink.PageGeometry.UNITS_PER_MM
import de.millione.schulranzen.ink.PageGeometry.WIDTH

/** Paper ruling. Stored by name. */
enum class PaperStyle(val label: String) {
    LINED("Liniert"),
    SQUARED("Kariert"),
    DOTTED("Punkte"),
    BLANK("Blanko");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: LINED
    }
}

/** Draws the sheet and its ruling in page units. */
object PaperPainter {
    private const val SHEET = 0xFFFFFEFA.toInt()
    private const val RULE = 0xFFC7D2DF.toInt()       // soft blue-grey, like school paper
    private const val MARGIN = 0xFFE3A99C.toInt()     // faded red margin
    private const val GRID = 0xFFD5DCE5.toInt()

    private val sheet = Paint().apply { color = SHEET }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE; strokeWidth = 1.1f }
    private val margin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARGIN; strokeWidth = 1.2f }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID; strokeWidth = 0.9f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE }

    fun draw(canvas: Canvas, style: PaperStyle) {
        canvas.drawRect(0f, 0f, WIDTH, HEIGHT, sheet)
        when (style) {
            PaperStyle.LINED -> {
                // 8 mm lines (Lineatur 27/28), a margin at 25 mm on the left like a German school notebook.
                val step = 8f * UNITS_PER_MM
                var y = 22f * UNITS_PER_MM
                while (y < HEIGHT - 10f * UNITS_PER_MM) { canvas.drawLine(0f, y, WIDTH, y, rule); y += step }
                val x = 25f * UNITS_PER_MM
                canvas.drawLine(x, 0f, x, HEIGHT, margin)
            }
            PaperStyle.SQUARED -> {
                val step = 5f * UNITS_PER_MM
                var x = step; while (x < WIDTH) { canvas.drawLine(x, 0f, x, HEIGHT, grid); x += step }
                var y = step; while (y < HEIGHT) { canvas.drawLine(0f, y, WIDTH, y, grid); y += step }
            }
            PaperStyle.DOTTED -> {
                val step = 5f * UNITS_PER_MM
                var y = step
                while (y < HEIGHT) {
                    var x = step
                    while (x < WIDTH) { canvas.drawCircle(x, y, 1.6f, dot); x += step }
                    y += step
                }
            }
            PaperStyle.BLANK -> Unit
        }
    }
}
