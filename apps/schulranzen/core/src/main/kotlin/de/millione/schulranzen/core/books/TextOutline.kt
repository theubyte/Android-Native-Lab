package de.millione.schulranzen.core.books

import java.text.Normalizer
import kotlin.math.abs

/**
 * Builds a table of contents from the text layer when a PDF has no
 * bookmarks. Feed the pages in order with [addPage]; [build] then picks the
 * best of:
 *
 * 1. a printed table of contents ("Kapitel 3 ........ 47"), mapped to PDF
 *    pages through the printed page numbers found on the pages and, where
 *    possible, snapped to the page that actually carries that heading;
 * 2. chapter headings found at the top of pages.
 *
 * Running heads (the chapter title repeated on every page) are kept only
 * where they first appear, so the list stays a list of chapters.
 * Streaming keeps memory flat for 600-page books: only a few lines per page
 * are kept.
 */
class TextOutlineBuilder(private val pageCount: Int) {
    private class Heading(val title: String, val norm: String, val page: Int, val level: Int)
    private class TocLine(val title: String, val printed: Int, val level: Int)

    private val headings = ArrayList<Heading>()
    private val toc = ArrayList<TocLine>()
    private val offsetVotes = HashMap<Int, Int>()
    private var tocUntil = -1
    private var tocLinesOnLastTocPage = 0
    private var pagesWithText = 0

    /** Only the first pages can hold the printed contents. */
    private val tocSearchLimit = maxOf(12, minOf(60, pageCount / 5))

    /** Adds the extracted text of PDF page [index] (0-based). */
    fun addPage(index: Int, text: String?) {
        if (text.isNullOrBlank()) return
        val lines = lines(text)
        if (lines.isEmpty()) return
        pagesWithText++

        if (index < tocSearchLimit) {
            if (lines.any { looksLikeTocHeader(it) }) tocUntil = maxOf(tocUntil, index + 10)
            if (index <= tocUntil) {
                val found = lines.mapNotNull { parseToc(it) }
                // A page in the zone without any entries ends the contents.
                if (found.isEmpty() && tocLinesOnLastTocPage > 0 && toc.isNotEmpty()) tocUntil = index - 1
                else if (toc.size < 700) toc += found
                tocLinesOnLastTocPage = found.size
            }
        }

        printedPageNumber(lines)?.let { printed ->
            val off = index - (printed - 1)
            if (abs(off) <= 120) offsetVotes[off] = (offsetVotes[off] ?: 0) + 1
        }

        if (index > tocUntil) {
            detectHeading(lines)?.let { (title, level) ->
                if (headings.size < 1500) headings += Heading(title, normWords(title), index, level)
            }
        }
    }

    fun build(): Outline {
        val hasText = pagesWithText > 0
        val offset = bestOffset()
        if (toc.size >= 3) {
            val last = (pageCount - 1).coerceAtLeast(0)
            val mapped = toc.mapNotNull { line ->
                val predicted = (line.printed - 1 + offset).coerceIn(0, last)
                val title = cleanTitle(line.title)
                if (title.length < 3) return@mapNotNull null
                val page = bestHeading(normWords(title), predicted)?.page ?: predicted
                OutlineEntry(title, page, line.level)
            }
            val entries = dedupe(mapped)
            if (entries.size >= 3) return Outline(entries, OutlineSource.PRINTED_TOC, pageCount, hasText)
        }
        val firstSeen = HashSet<String>()
        val fromHeadings = headings.filter { firstSeen.add(it.norm) }.map { OutlineEntry(it.title, it.page, it.level) }
        val entries = dedupe(fromHeadings)
        return if (entries.size >= 2) Outline(entries, OutlineSource.HEADINGS, pageCount, hasText)
        else Outline(emptyList(), OutlineSource.NONE, pageCount, hasText)
    }

    private fun bestOffset(): Int {
        val best = offsetVotes.maxByOrNull { it.value } ?: return 0
        return if (best.value >= 2) best.key else 0
    }

    private fun bestHeading(title: String, predicted: Int): Heading? {
        if (title.length < 3) return null
        var best: Heading? = null
        var score = 0f
        for (h in headings) {
            val dist = abs(h.page - predicted)
            if (dist > 40) continue
            var s = similarity(title, h.norm)
            s += when { dist <= 3 -> 0.15f; dist <= 12 -> 0.08f; else -> 0f }
            if (s > score) { score = s; best = h }
        }
        return if (score >= 0.6f) best else null
    }

    companion object {
        private val NUMBERED = Regex("""^(\d{1,2}(?:\.\d{1,2}){0,4})[.)]?\s+(.{3,120})$""")
        private val ENDING_PAGE = Regex("""^(.{3,160}?)(?:\s*[.·…_]{2,}\s*|\s+)(\d{1,4})$""")
        private val PLAIN_PAGE = Regex("""^(?:seite\s*|s\s)?(\d{1,4})$""")
        private val SECTION = Regex("""^(\d+(?:\.\d+)*)""")
        private val KEYWORDS = listOf("kapitel ", "chapter ", "lektion ", "lernfeld ", "modul ", "teil ", "abschnitt ", "thema ", "unit ", "lernsituation ")
        private val STOP_WORDS = Regex("""\b(der|die|das|und|oder|von|vom|zur|zum|im|in|mit|the|and|of|a|an)\b""")

        /** Non-empty lines with single spaces, at most 120 per page. */
        internal fun lines(text: String): List<String> =
            text.replace('\r', '\n').split('\n').asSequence()
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .take(120).toList()

        internal fun looksLikeTocHeader(line: String): Boolean {
            val n = norm(line)
            if (n.length > 40) return false
            return n.contains("inhaltsverzeichnis") || n == "inhalt" || n == "contents" ||
                n.contains("table of contents") || n == "gliederung" || n == "inhaltsubersicht"
        }

        /** "3.2 Ohmsches Gesetz ....... 47" -> (title, printed page, level). */
        internal fun parseTocLine(raw: String): Pair<String, Int>? = parseToc(raw)?.let { it.title to it.printed }

        private fun parseToc(raw: String): TocLine? {
            val line = raw.trim()
            if (line.length < 4 || line.length > 180) return null
            val m = ENDING_PAGE.matchEntire(line) ?: return null
            val title = m.groupValues[1].replace(Regex("[.·…_ ]+$"), "").trim()
            if (title.length < 3 || title.all { it.isDigit() || it in " .:/-" }) return null
            if (title.count { it.isLetter() } < 3) return null
            val page = m.groupValues[2].toIntOrNull() ?: return null
            if (page <= 0 || page > 5000) return null
            return TocLine(title, page, sectionLevel(title))
        }

        /** Heading in the first lines of a page: keyword, numbered or short all-caps line. */
        internal fun detectHeading(lines: List<String>): Pair<String, Int>? {
            for ((i, s) in lines.take(6).withIndex()) {
                if (s.length < 3 || s.length > 130) continue
                val n = norm(s)
                if (PLAIN_PAGE.matches(n)) continue
                if (KEYWORDS.any { n.startsWith(it) } && letterCount(s) >= 6) return cleanTitle(s) to 0
                NUMBERED.matchEntire(s)?.let { m ->
                    val rest = m.groupValues[2]
                    // "3 Äpfel kosten 2,50 €" is a sentence, not a heading.
                    val sentence = rest.endsWith(".") || rest.contains(Regex("\\d+[,.]\\d")) || wordCount(rest) > 12
                    if (letterCount(rest) >= 4 && !sentence && rest.first().isLetter() && rest.first().isUpperCase()) {
                        return cleanTitle(s) to sectionLevel(m.groupValues[1])
                    }
                }
                if (i <= 2 && s.length <= 85 && letterCount(s) >= 5 && upperRatio(s) >= 0.8f && wordCount(s) <= 10) {
                    return cleanTitle(s) to 1
                }
            }
            return null
        }

        /** Page number printed at the very top or bottom of the page. */
        internal fun printedPageNumber(lines: List<String>): Int? {
            val n = lines.size
            for (i in listOf(n - 1, 0, n - 2, 1).distinct()) {
                if (i !in 0 until n) continue
                PLAIN_PAGE.matchEntire(norm(lines[i]))?.let { return it.groupValues[1].toIntOrNull() }
            }
            return null
        }

        internal fun sectionLevel(s: String): Int {
            val m = SECTION.find(s.trim()) ?: return 0
            return m.groupValues[1].count { it == '.' }.coerceAtMost(4)
        }

        internal fun similarity(a: String, b: String): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            if (a == b) return 1f
            if (a.contains(b) || b.contains(a)) return 0.86f
            val aa = a.split(' ').filter { it.length >= 3 }.toSet()
            val bb = b.split(' ').filter { it.length >= 3 }.toSet()
            if (aa.isEmpty() || bb.isEmpty()) return 0f
            return aa.count { it in bb } / maxOf(aa.size, bb.size).toFloat()
        }

        /** Sorted by page, same title on the same page once, capped. */
        internal fun dedupe(entries: List<OutlineEntry>): List<OutlineEntry> {
            val seen = HashSet<String>()
            return entries.filter { it.page >= 0 }
                .sortedWith(compareBy({ it.page }, { it.level }))
                .filter { seen.add("${it.page}|${norm(it.title)}") }
                .take(Outline.MAX_ENTRIES)
        }

        internal fun cleanTitle(s: String): String = s.replace(Regex("\\s+"), " ").replace(Regex("[.·•…_]+$"), "").trim()

        internal fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
                .replace(Regex("[^a-z0-9ß ]+"), " ").replace(Regex("\\s+"), " ").trim()

        private fun normWords(s: String) = norm(s).replace(Regex("^[0-9 ]+"), "").replace(STOP_WORDS, " ").replace(Regex("\\s+"), " ").trim()
        private fun letterCount(s: String) = s.count { it.isLetter() }
        private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        private fun upperRatio(s: String): Float {
            val letters = s.filter { it.isLetter() }
            return if (letters.isEmpty()) 0f else letters.count { it.isUpperCase() } / letters.length.toFloat()
        }
    }
}
