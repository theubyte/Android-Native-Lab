package de.millione.schulranzen.core.books

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Page units of a book page: every page is [WIDTH] units wide before
 * rotation, its height follows the PDF's aspect. Ink, notes and marked
 * areas are stored in these unrotated units, so turning a page later turns
 * everything on it along.
 */
object BookUnits {
    const val WIDTH = 1000f
    /** Gap between two pages in the reading column (display units). */
    const val GAP = 36f

    /** Unrotated page height in units for a PDF page of [w] × [h] points. */
    fun height(w: Float, h: Float): Float = if (w <= 0f) WIDTH * 1.414f else WIDTH * h / w
}

/** Quarter turns of a page. Only 0, 90, 180 and 270 exist. */
object PageRotation {
    fun normalize(degrees: Int): Int = (Math.floorMod(degrees, 360) / 90) * 90

    fun next(degrees: Int): Int = normalize(degrees + 90)

    /** Size of the turned page ("box") for an unrotated page of [w] × [h]. */
    fun boxSize(w: Float, h: Float, degrees: Int): Pair<Float, Float> =
        if (normalize(degrees) % 180 == 0) w to h else h to w

    /** Unrotated page point -> point in the turned box (clockwise turn). */
    fun toBox(x: Float, y: Float, w: Float, h: Float, degrees: Int): Pair<Float, Float> = when (normalize(degrees)) {
        90 -> (h - y) to x
        180 -> (w - x) to (h - y)
        270 -> y to (w - x)
        else -> x to y
    }

    /** Point in the turned box -> unrotated page point. */
    fun fromBox(x: Float, y: Float, w: Float, h: Float, degrees: Int): Pair<Float, Float> = when (normalize(degrees)) {
        90 -> y to (h - x)
        180 -> (w - x) to (h - y)
        270 -> (w - y) to x
        else -> x to y
    }
}

/**
 * The reading column: pages one below the other, each shown [BookUnits.WIDTH]
 * wide whatever its rotation, so a turned landscape page does not shrink the
 * rest. [aspects] are display height / width per page.
 */
class ColumnLayout(private val aspects: FloatArray, private val gap: Float = BookUnits.GAP) {
    private val tops = FloatArray(aspects.size)
    val count get() = aspects.size
    val totalHeight: Float

    init {
        var y = 0f
        for (i in aspects.indices) { tops[i] = y; y += height(i) + gap }
        totalHeight = if (aspects.isEmpty()) 0f else y - gap
    }

    fun top(i: Int): Float = tops[i]
    fun height(i: Int): Float = BookUnits.WIDTH * aspects[i]
    fun bottom(i: Int): Float = tops[i] + height(i)

    /** Page under the column position [y], or -1 in a gap or outside. */
    fun pageAt(y: Float): Int {
        val i = nearest(y)
        return if (i >= 0 && y >= tops[i] && y <= bottom(i)) i else -1
    }

    /** Page whose span (including the gap below it) holds [y]; clamped to the ends. */
    fun nearest(y: Float): Int {
        if (aspects.isEmpty()) return -1
        var lo = 0; var hi = aspects.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Pages overlapping the column span [y0, y1]. Empty when there are no pages. */
    fun visible(y0: Float, y1: Float): IntRange {
        if (aspects.isEmpty()) return IntRange.EMPTY
        val first = nearest(y0).let { if (bottom(it) < y0 && it < count - 1) it + 1 else it }
        val last = nearest(y1)
        return first..max(first, last)
    }
}

/**
 * The page overview shows a book in blocks, so a 600-page book never
 * renders 600 thumbnails at once.
 */
object PageBlocks {
    const val SIZE = 24

    fun count(pageCount: Int, size: Int = SIZE): Int = if (pageCount <= 0) 0 else ceil(pageCount / size.toDouble()).toInt()

    fun block(index: Int, pageCount: Int, size: Int = SIZE): IntRange {
        val start = index * size
        if (start >= pageCount || index < 0) return IntRange.EMPTY
        return start until min(pageCount, start + size)
    }

    fun blockOf(page: Int, size: Int = SIZE): Int = max(0, page) / size

    /** "1–24" with 1-based page numbers, for the block selector. */
    fun label(index: Int, pageCount: Int, size: Int = SIZE): String {
        val r = block(index, pageCount, size)
        return if (r.isEmpty()) "" else "${r.first + 1}–${r.last + 1}"
    }
}

/** Typed page numbers ("12", "S. 12", "Seite 12") -> 0-based index. */
object PageJump {
    private val NUMBER = Regex("""^(?:s(?:eite)?\.?\s*)?(\d{1,5})$""", RegexOption.IGNORE_CASE)

    fun parse(input: String, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        val m = NUMBER.matchEntire(input.trim()) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n < 1) return null
        return min(n, pageCount) - 1
    }
}

/** A rectangle in page units, independent of android.graphics. */
data class UnitRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        /** Rectangle spanned by two drag points, clamped to a page of [w] × [h]. */
        fun spanning(ax: Float, ay: Float, bx: Float, by: Float, w: Float, h: Float) = UnitRect(
            min(ax, bx).coerceIn(0f, w), min(ay, by).coerceIn(0f, h),
            max(ax, bx).coerceIn(0f, w), max(ay, by).coerceIn(0f, h),
        )
    }
}

/** Sizes for rendering: sharp enough, never more pixels than needed. */
object RenderMath {
    /** One page width (1000 units) at print quality: A4 at 300 dpi is 2480 px. */
    const val CLIP_PX_PER_UNIT = 2.48f

    /**
     * Pixel size of a clipping of [w] × [h] units: print quality, the long
     * side at most [maxSide], tiny selections enlarged to at least [minSide].
     */
    fun clipSize(w: Float, h: Float, maxSide: Int = 3200, minSide: Int = 640): Pair<Int, Int> {
        if (w <= 0f || h <= 0f) return 0 to 0
        var f = CLIP_PX_PER_UNIT
        val long = max(w, h)
        if (long * f > maxSide) f = maxSide / long
        if (long * f < minSide) f = minSide / long
        return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
    }

    /**
     * Width of the cached whole-page bitmap: the page as wide as it shows at
     * fit zoom, within [minPx]..[maxPx] so memory stays bounded.
     */
    fun baseWidth(fitPageWidthPx: Float, minPx: Int = 600, maxPx: Int = 1600): Int =
        fitPageWidthPx.roundToInt().coerceIn(minPx, maxPx)

    /** Whether the cached page bitmap would look soft at the shown width. */
    fun needsDetail(shownWidthPx: Float, baseWidthPx: Int): Boolean = shownWidthPx > baseWidthPx * 1.15f
}

/** The title shown on the shelf: the PDF's own title when it is a real one, else the file name. */
object BookTitle {
    private val JUNK = Regex("""^(untitled|unbenannt|microsoft word|document|dokument|titel|title|\d+)\b.*""", RegexOption.IGNORE_CASE)

    fun choose(metaTitle: String?, fileName: String?): String {
        val meta = metaTitle?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        val usableMeta = meta.length in 3..120 && !JUNK.matches(meta) && meta.count { it.isLetter() } >= 3 &&
            !meta.endsWith(".pdf", ignoreCase = true) && !meta.endsWith(".doc", ignoreCase = true) && !meta.endsWith(".docx", ignoreCase = true)
        if (usableMeta) return meta
        val file = fileName?.trim().orEmpty()
            .replace(Regex("""\.pdf$""", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return file.ifEmpty { "Neues Buch" }
    }
}
