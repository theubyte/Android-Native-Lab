package de.millione.schulranzen.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes

/** What the pen does. Stored as a byte in page files, so never reorder. */
enum class ToolKind(val code: Byte, val writes: Boolean) {
    PEN(1, true),
    MARKER(2, true),
    ERASER(3, false),
    STROKE_ERASER(4, false);

    val erases get() = this == ERASER || this == STROKE_ERASER

    companion object {
        fun of(code: Byte) = entries.firstOrNull { it.code == code } ?: PEN
    }
}

/** Page geometry in page units: an A4 sheet is 1000 units wide (4.76 units per mm). */
object PageGeometry {
    const val WIDTH = 1000f
    const val HEIGHT = 1414f
    /** Desk gap between two sheets in a notebook. */
    const val GAP = 48f
    const val UNITS_PER_MM = WIDTH / 210f
}

/** Builds the Jetpack Ink brush for a tool, colour (ARGB) and size (page units). */
object Brushes {
    private const val EPSILON = 0.08f
    private val pen by lazy { StockBrushes.pressurePen() }
    private val marker by lazy { StockBrushes.highlighter() }

    /** Pen sizes offered in menus, in page units (0.4 … 2.1 mm). */
    val penSizes = listOf(2f, 3f, 4.2f, 6f, 10f)
    /** Marker sizes in page units (2.5 … 6 mm). */
    val markerSizes = listOf(12f, 18f, 26f)

    fun brush(tool: ToolKind, color: Int, size: Float): Brush = when (tool) {
        ToolKind.MARKER -> Brush.createWithColorIntArgb(marker, markerColor(color), size, EPSILON)
        else -> Brush.createWithColorIntArgb(pen, color, size, EPSILON)
    }

    /** Markers are translucent like real highlighters; ink below stays readable. */
    fun markerColor(color: Int): Int = (color and 0x00FFFFFF) or (0x72 shl 24)
}
