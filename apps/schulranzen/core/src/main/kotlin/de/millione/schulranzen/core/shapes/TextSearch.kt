package de.millione.schulranzen.core.shapes

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Forgiving search over recognised handwriting.
 *
 * Handwriting recognition makes mistakes and pupils type quickly, so a query
 * word matches a written word exactly, as a prefix ("Wärm" → "Wärmepumpe"),
 * inside a compound ("pumpe" → "Wärmepumpe") or with a typo or two
 * (Damerau-Levenshtein, scaled with the word length). Umlauts and ß match
 * both spellings: "Muell", "Mull" and "Müll" all find "Müll".
 */
object TextSearch {

    /** Lower case, ä→ae, ö→oe, ü→ue, ß→ss, accents removed, punctuation as spaces. */
    fun normalize(s: String): String = clean(
        s.lowercase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"),
    )

    /** Like [normalize] but folds umlauts to the bare vowel (ä→a), for people who type without them. */
    fun fold(s: String): String = clean(
        s.lowercase(Locale.GERMAN).replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss"),
    )

    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    private fun clean(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").replace(separators, " ").trim()

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * How well [query] (one word) matches [word] (one written word, any
     * spelling): 1 exact, 0.9 prefix, 0.8/0.7 one/two typos, 0.75 inside a
     * compound, 0.65 typo in a prefix, 0 no match.
     */
    fun wordScore(query: String, word: String): Float {
        var best = 0f
        for ((q, w) in listOf(normalize(query) to normalize(word), fold(query) to fold(word))) {
            val qs = q.replace(" ", ""); if (qs.isEmpty()) continue
            for (cand in candidates(w)) best = max(best, score(qs, cand))
        }
        return best
    }

    /** A written word as search keys: its parts plus the parts joined ("z.B." → "z", "b", "zb"). */
    private fun candidates(w: String): List<String> {
        val parts = w.split(' ').filter { it.isNotEmpty() }
        return if (parts.size > 1) parts + parts.joinToString("") else parts
    }

    private fun score(q: String, w: String): Float {
        if (q == w) return 1f
        if (q.length >= 2 && w.startsWith(q)) return 0.9f
        val allowed = when { q.length <= 3 -> 0; q.length <= 6 -> 1; else -> 2 }
        if (allowed > 0) {
            if (kotlin.math.abs(q.length - w.length) <= allowed) {
                val d = distance(q, w)
                if (d <= allowed) return if (d <= 1) 0.8f else 0.7f
            }
        }
        if (q.length >= 4 && w.contains(q)) return 0.75f
        if (allowed > 0 && w.length > q.length) {
            for (len in max(1, q.length - allowed)..min(w.length, q.length + allowed)) {
                if (distance(q, w.substring(0, len)) <= allowed) return 0.65f
            }
        }
        return 0f
    }

    /** Optimal string alignment distance: edits plus swaps of neighbouring letters. */
    fun distance(a: String, b: String): Int {
        val n = a.length; val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[n][m]
    }

    /** Result of matching a query against a line: [words] are the indices of the matched words. */
    data class Match(val score: Float, val words: List<Int>)

    /**
     * Matches every word of [query] against the written [words] of one line.
     * All query words must be found; words found next to each other in the
     * same order score a little higher. Null when something is missing.
     */
    fun match(query: String, words: List<String>): Match? {
        val q = tokens(query)
        if (q.isEmpty() || words.isEmpty()) return null
        var total = 0f
        val hits = ArrayList<Int>()
        for (t in q) {
            var best = 0f; var at = -1
            for ((i, w) in words.withIndex()) {
                val s = wordScore(t, w)
                if (s > best) { best = s; at = i }
            }
            if (at < 0) return null
            total += best; hits += at
        }
        var score = total / q.size
        if (q.size > 1) score = score * 0.95f + if (hits.zipWithNext().all { (a, b) -> b == a + 1 }) 0.05f else 0f
        return Match(score, hits)
    }

    /**
     * A short excerpt of a line around the matched words, with the range of
     * the first match inside it for highlighting.
     */
    fun snippet(words: List<String>, matched: List<Int>, maxChars: Int = 70): Pair<String, IntRange?> {
        val full = words.joinToString(" ")
        val first = matched.minOrNull()
        val range = first?.let { i ->
            val start = words.take(i).sumOf { it.length + 1 }
            start until start + words[i].length
        }
        if (full.length <= maxChars) return full to range
        val center = range?.first ?: 0
        var from = (center - maxChars / 3).coerceAtLeast(0)
        val to = (from + maxChars).coerceAtMost(full.length)
        from = (to - maxChars).coerceAtLeast(0)
        // Cut at word boundaries where possible.
        val s = full.lastIndexOf(' ', from).let { if (it < 0 || from == 0) from else it + 1 }
        val e = full.indexOf(' ', to).let { if (it < 0 || to == full.length) to else it }
        val prefix = if (s > 0) "… " else ""
        val suffix = if (e < full.length) " …" else ""
        val text = prefix + full.substring(s, e) + suffix
        val shifted = range?.let { (it.first - s + prefix.length)..(it.last - s + prefix.length) }
        return text to shifted
    }
}
