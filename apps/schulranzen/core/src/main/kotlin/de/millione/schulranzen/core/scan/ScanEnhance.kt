package de.millione.schulranzen.core.scan

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** What is done to a scanned sheet before it goes into the notebook. */
@Serializable
enum class ScanMode(val label: String) {
    ORIGINAL("Original"),
    BRIGHT("Aufhellen"),
    BLACK_WHITE("Schwarzweiß"),
}

/**
 * Makes a photographed sheet look like a copy. The lighting of the paper is
 * estimated in a coarse grid (a bright percentile per cell, so writing does
 * not count as paper) and divided out; shadows of the hand and the falloff of
 * the room light disappear, while ink stays dark and colours stay colours.
 */
object ScanEnhance {
    private const val GRID = 48
    /** Below this the area is not paper; dividing by it would only blow up noise. */
    private const val MIN_PAPER = 48f
    private const val BLACK = 28f
    private const val WHITE = 238f

    /** Works on ARGB pixels in place; alpha is kept. */
    fun apply(argb: IntArray, width: Int, height: Int, mode: ScanMode) {
        if (mode == ScanMode.ORIGINAL || width <= 0 || height <= 0) return
        require(argb.size >= width * height)
        val cell = max(8, max(width, height) / GRID)
        val gw = (width + cell - 1) / cell
        val gh = (height + cell - 1) / cell
        val paper = smooth(smooth(paperGrid(argb, width, height, cell, gw, gh), gw, gh), gw, gh)
        for (y in 0 until height) {
            val gy = ((y.toFloat() / cell) - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = gy.toInt(); val y1 = min(y0 + 1, gh - 1); val fy = gy - y0
            val row = y * width
            for (x in 0 until width) {
                val gx = ((x.toFloat() / cell) - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = gx.toInt(); val x1 = min(x0 + 1, gw - 1); val fx = gx - x0
                val top = paper[y0 * gw + x0] * (1 - fx) + paper[y0 * gw + x1] * fx
                val bottom = paper[y1 * gw + x0] * (1 - fx) + paper[y1 * gw + x1] * fx
                val gain = 255f / (top * (1 - fy) + bottom * fy)
                val p = argb[row + x]
                val r = (p shr 16 and 255) * gain
                val g = (p shr 8 and 255) * gain
                val b = (p and 255) * gain
                argb[row + x] = (p and 0xFF000000.toInt()) or when (mode) {
                    ScanMode.BLACK_WHITE -> {
                        val v = hardContrast((0.299f * r + 0.587f * g + 0.114f * b) / 255f)
                        (v shl 16) or (v shl 8) or v
                    }
                    else -> (levels(r) shl 16) or (levels(g) shl 8) or levels(b)
                }
            }
        }
    }

    /** Stretches between black point and white point, so paper reaches white and ink reaches black. */
    private fun levels(v: Float): Int = (((v - BLACK) / (WHITE - BLACK)).coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Soft threshold: writing black, paper white, without jagged edges. */
    private fun hardContrast(v: Float): Int {
        val t = ((v - 0.55f) / 0.30f).coerceIn(0f, 1f)
        return (t * t * (3 - 2 * t) * 255f + 0.5f).toInt()
    }

    /** 90th percentile of the brightness per cell, from a thin sample of its pixels. */
    private fun paperGrid(argb: IntArray, width: Int, height: Int, cell: Int, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw * gh)
        val hist = IntArray(64)
        val step = max(1, cell / 16)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            hist.fill(0)
            var n = 0
            var y = gy * cell
            val yEnd = min(height, y + cell)
            while (y < yEnd) {
                var x = gx * cell
                val xEnd = min(width, x + cell)
                while (x < xEnd) {
                    val p = argb[y * width + x]
                    val lum = (77 * (p shr 16 and 255) + 150 * (p shr 8 and 255) + 29 * (p and 255)) shr 8
                    hist[lum shr 2]++
                    n++
                    x += step
                }
                y += step
            }
            var seen = 0
            var bin = 63
            val wanted = n / 10
            while (bin > 0) {
                seen += hist[bin]
                if (seen > wanted) break
                bin--
            }
            out[gy * gw + gx] = max(MIN_PAPER, bin * 4f + 2f)
        }
        return out
    }

    /** One pass of a 3 × 3 box, so cell borders do not show as steps. */
    private fun smooth(src: FloatArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var sum = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until gw && ny in 0 until gh) { sum += src[ny * gw + nx]; n++ }
            }
            out[y * gw + x] = sum / n
        }
        return out
    }
}
