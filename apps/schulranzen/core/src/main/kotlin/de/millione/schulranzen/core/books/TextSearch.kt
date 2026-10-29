package de.millione.schulranzen.core.books

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * One place where the query was found. [snippet] is a short piece of the
 * page around it with the match at `[matchStart, matchEnd)`; [rects] are
 * the found words' boxes (one per line), in unrotated page units.
 */
data class SearchHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
    val rects: List<UnitRect>,
)

/**
 * Full-text search over a [BookTextIndex]. Matching ignores case, accents
 * and umlauts ("Ubung" finds "Übung", "strasse" finds "Straße"), runs of
 * white space, and words split by a hyphen at the end of a line
 * ("Wider-\nstand" is found as "Widerstand").
 */
object TextSearch {
    const val MAX_HITS = 300
    private const val BEFORE = 46
    private const val AFTER = 70

    fun find(pages: Iterable<PageText>, query: String, limit: Int = MAX_HITS): List<SearchHit> {
        val q = Folded.of(query).text.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (p in pages.sortedBy { it.page }) {
            if (!p.hasText) continue
            val folded = Folded.of(p.text)
            var from = 0
            while (hits.size < limit) {
                val at = folded.text.indexOf(q, from)
                if (at < 0) break
                val s = folded.map[at]
                val e = folded.map[at + q.length - 1] + 1
                hits += hit(p, s, e)
                from = at + q.length
            }
            if (hits.size >= limit) break
        }
        return hits
    }

    private fun hit(p: PageText, s: Int, e: Int): SearchHit {
        val text = p.text
        var a = max(0, s - BEFORE)
        var b = min(text.length, e + AFTER)
        // Whole words at the edges.
        if (a > 0) { val sp = text.indexOfAny(charArrayOf(' ', '\n'), a); if (sp in a until s) a = sp + 1 }
        if (b < text.length) { val sp = text.lastIndexOfAny(charArrayOf(' ', '\n'), b); if (sp > e) b = sp }
        val prefix = if (a > 0) "… " else ""
        val suffix = if (b < text.length) " …" else ""
        val body = text.substring(a, b).replace('\n', ' ')
        return SearchHit(p.page, s, e, prefix + body + suffix, prefix.length + (s - a), prefix.length + (e - a), rects(p, s, e))
    }

    /** Boxes of the words covering text `[s, e)`, merged per line; partial words cut by character share. */
    fun rects(p: PageText, s: Int, e: Int): List<UnitRect> {
        val out = ArrayList<UnitRect>()
        var cur: UnitRect? = null
        var lastWord = -1
        for (i in 0 until p.wordCount) {
            val ws = p.starts[i]; val we = p.ends[i]
            if (we <= s || ws >= e) continue
            val b = p.box(i)
            val len = (we - ws).coerceAtLeast(1)
            val l = if (s > ws) b.left + b.width * (s - ws) / len else b.left
            val r = if (e < we) b.left + b.width * (e - ws) / len else b.right
            val piece = UnitRect(l, b.top, r, b.bottom)
            val sameLine = cur != null && lastWord >= 0 && !p.text.substring(p.ends[lastWord], ws).contains('\n')
            cur = if (sameLine) union(cur!!, piece) else { cur?.let { out += it }; piece }
            lastWord = i
        }
        cur?.let { out += it }
        return out
    }

    private fun union(a: UnitRect, b: UnitRect) =
        UnitRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}

/**
 * Text folded for matching, with [map] from each folded character back to
 * its index in the original text.
 */
class Folded private constructor(val text: String, val map: IntArray) {
    companion object {
        fun of(original: String): Folded {
            val sb = StringBuilder(original.length)
            val map = IntArray(original.length * 2 + 1)
            var lastSpace = true
            var i = 0
            val n = original.length
            fun put(c: Char, from: Int) { map[sb.length] = from; sb.append(c) }
            while (i < n) {
                val c = original[i]
                // Hyphen at a line end between letters joins the word.
                if (c == '-' && i + 1 < n && original[i + 1] == '\n' && i > 0 && original[i - 1].isLetter() &&
                    i + 2 < n && original[i + 2].isLowerCase()) { i += 2; continue }
                if (c.isWhitespace()) {
                    if (!lastSpace) put(' ', i)
                    lastSpace = true
                    i++; continue
                }
                lastSpace = false
                when (c) {
                    'ß' -> { put('s', i); put('s', i) }
                    'ẞ' -> { put('s', i); put('s', i) }
                    else -> {
                        val lower = c.lowercaseChar()
                        if (lower.code < 128) put(lower, i)
                        else {
                            val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: lower
                            put(base, i)
                        }
                    }
                }
                i++
            }
            return Folded(sb.toString(), map.copyOf(sb.length))
        }
    }
}
