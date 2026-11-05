package de.millione.schulranzen.core.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The small decisions of the scan flow, free of Android so they can be tested. */
object ScanPlan {
    /** A scan never holds more sheets than this (a whole notebook, not a whole book). */
    const val MAX_SHEETS = 100
    /** Long side of a stored sheet, same as a picture in a notebook. */
    const val MAX_LONG_SIDE = 2400
    /** A4 at 200 dpi: PDF pages are rendered to fit inside this. */
    const val PDF_WIDTH = 1654
    const val PDF_HEIGHT = 2339

    /**
     * Database position for pages inserted after the first [after] pages,
     * [positions] being the current positions in order. Positions need not be
     * gapless (a merge may leave holes), so the index alone is not enough.
     * Null or too large means the end.
     */
    fun insertPosition(positions: List<Int>, after: Int?): Int {
        val a = after?.coerceAtLeast(0) ?: positions.size
        return if (a >= positions.size) (positions.lastOrNull() ?: -1) + 1 else positions[a]
    }

    /** Index of the first inserted page, once they are in. */
    fun firstIndex(pageCount: Int, after: Int?): Int = (after ?: pageCount).coerceIn(0, pageCount)

    /** [list] with the element at [from] moved to [to]; out-of-range moves change nothing. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Next quarter turn clockwise. */
    fun turned(rotation: Int): Int = (rotation + 90).mod(360)

    /** Target size so the long side is at most [maxLong]; never scales up, never reaches zero. */
    fun fit(width: Int, height: Int, maxLong: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxLong || long == 0) return width to height
        val f = maxLong.toFloat() / long
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    /** Power of two for decoding, so the result is at least [maxLong] on the long side where possible. */
    fun sampleSize(width: Int, height: Int, maxLong: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxLong) sample *= 2
        return sample
    }

    /** Scale of a PDF page (points) inside A4 at 200 dpi; a poster-sized page must not eat the memory. */
    fun pdfScale(pageWidth: Int, pageHeight: Int): Float =
        min(PDF_WIDTH.toFloat() / max(pageWidth, 1), PDF_HEIGHT * 1.5f / max(pageHeight, 1))

    /**
     * What another app may hand us: only a content Uri of someone else, as
     * picture or PDF. A file Uri or one of our own provider could be used to
     * make the app read its private files.
     */
    fun acceptsShare(scheme: String?, authority: String?, ownAuthority: String, mime: String?): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        if (authority == null || authority == ownAuthority) return false
        return mime == null || mime.startsWith("image/") || mime == "application/pdf"
    }

    /** "Seite" or "Seiten", for the short messages. */
    fun pages(n: Int): String = if (n == 1) "1 Seite" else "$n Seiten"
}
