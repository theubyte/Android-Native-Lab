package de.millione.schulranzen.core.books

/**
 * Headings from the look of a page, for books whose text comes from text
 * recognition: no bookmarks, often no clean printed contents. A heading
 * is a short line clearly taller than the body text of the book, near the
 * top of the page, or a numbered line ("3.2 Wasserdruck") a little taller
 * than the body text.
 */
object HeadingDetector {
    /** Taller than this × body height counts as a heading. */
    const val TALL = 1.32f
    /** This tall or more is a chapter (level 0). */
    const val CHAPTER = 1.75f
    /** Numbered lines need a little extra size only. */
    const val NUMBERED_TALL = 1.12f
    /** Unnumbered headings only in the upper part of a page. */
    const val TOP_SHARE = 0.45f

    private val NUMBER = Regex("""^(\d{1,2}(?:\.\d{1,2}){0,4})[.)]?\s+(\S.*)$""")
    private val KEYWORD = Regex("""^(kapitel|lektion|lernfeld|modul|teil|abschnitt|thema|lernsituation|chapter|unit)\s+\S+""", RegexOption.IGNORE_CASE)

    /** Body text height: the median line height over the given lines (0 when none). */
    fun bodyHeight(lines: List<TextLine>): Float {
        val hs = lines.filter { it.text.count(Char::isLetter) >= 8 }.map { it.height }.filter { it > 0f }.sorted()
        if (hs.isEmpty()) return lines.map { it.height }.filter { it > 0f }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        return hs[hs.size / 2]
    }

    /**
     * Headings on one page as (title, level). [pageHeight] in the same units
     * as the line boxes, [body] from [bodyHeight] over the whole book.
     */
    fun detect(lines: List<TextLine>, pageHeight: Float, body: Float): List<Pair<String, Int>> {
        if (body <= 0f || lines.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < lines.size && out.size < 4) {
            val line = lines[i]
            val text = TextOutlineBuilder.cleanTitle(line.text)
            val ratio = line.height / body
            val top = line.box.top < pageHeight * TOP_SHARE
            val numbered = NUMBER.matchEntire(text)
            val ok = plausible(line.text) && when {
                numbered != null -> ratio >= NUMBERED_TALL && numbered.groupValues[2].first().isUpperCase()
                KEYWORD.containsMatchIn(text) -> ratio >= NUMBERED_TALL
                else -> ratio >= TALL && top
            }
            if (ok) {
                // A heading set over two lines: the next line of the same size belongs to it.
                var title = text
                val next = lines.getOrNull(i + 1)
                if (next != null && numbered == null && next.height / body >= TALL && kotlin.math.abs(next.height - line.height) < line.height * 0.15f &&
                    plausible(next.text) && (title.length + next.text.length) <= 90) {
                    title = "$title ${TextOutlineBuilder.cleanTitle(next.text)}"
                    i++
                }
                val level = when {
                    numbered != null -> TextOutlineBuilder.sectionLevel(numbered.groupValues[1])
                    ratio >= CHAPTER || KEYWORD.containsMatchIn(text) -> 0
                    else -> 1
                }
                out += title to level
            }
            i++
        }
        return out
    }

    private fun plausible(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3 || t.length > 90) return false
        if (t.count(Char::isLetter) < 4) return false
        if (t.endsWith(".") && !t.endsWith("..")) return false
        if (t.endsWith(",") || t.endsWith(";")) return false
        val words = t.split(Regex("\\s+")).size
        if (words > 11) return false
        // Page numbers and running numbers alone are no headings.
        if (t.all { it.isDigit() || it.isWhitespace() }) return false
        return true
    }
}

/**
 * Table of contents from the book's text index: a printed contents list
 * when one is found, otherwise headings. For recognised (scanned) pages the
 * headings come from line size ([HeadingDetector]); for a PDF text layer
 * from [TextOutlineBuilder] as before.
 */
object IndexOutline {
    fun build(index: BookTextIndex, pageHeight: (Int) -> Float): Outline {
        val pages = index.ordered()
        val textBuilder = TextOutlineBuilder(index.pageCount)
        val linesByPage = HashMap<Int, List<TextLine>>()
        for (p in pages) {
            if (!p.hasText) continue
            val lines = p.lines()
            linesByPage[p.page] = lines
            textBuilder.addPage(p.page, lines.joinToString("\n") { it.text })
        }
        val hasText = linesByPage.isNotEmpty()
        val fromText = textBuilder.build()
        val recognized = index.hasOcr
        if (fromText.source == OutlineSource.PRINTED_TOC) return fromText.copy(recognized = recognized)

        val ocrPages = pages.filter { it.source == TextSource.OCR && it.hasText }
        if (ocrPages.isNotEmpty() || fromText.source == OutlineSource.NONE) {
            val candidates = if (ocrPages.isNotEmpty()) ocrPages else pages.filter { it.hasText }
            val body = HeadingDetector.bodyHeight(candidates.flatMap { linesByPage[it.page].orEmpty() })
            val found = ArrayList<OutlineEntry>()
            val seen = HashSet<String>()
            for (p in candidates) {
                for ((title, level) in HeadingDetector.detect(linesByPage[p.page].orEmpty(), pageHeight(p.page), body)) {
                    // Running heads: only where a title first appears.
                    if (seen.add(TextOutlineBuilder.norm(title))) found += OutlineEntry(title, p.page, level)
                }
            }
            val entries = TextOutlineBuilder.dedupe(found)
            if (entries.size >= 2) return Outline(entries, OutlineSource.HEADINGS, index.pageCount, hasText, recognized = recognized)
        }
        return fromText.copy(hasText = hasText, recognized = recognized)
    }
}
