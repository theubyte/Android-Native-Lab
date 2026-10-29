package de.millione.schulranzen.core.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexOutlineTest {
    private class L(val text: String, val height: Float)

    /** A recognised page: lines top to bottom with the given heights. */
    private fun page(n: Int, vararg lines: L, source: TextSource = TextSource.OCR): PageText {
        val b = PageTextBuilder(n, source)
        var y = 80f
        for (l in lines) {
            var x = 60f
            for (w in l.text.split(' ')) {
                b.word(w, x, y, x + w.length * l.height * 0.5f, y + l.height)
                x += w.length * l.height * 0.5f + l.height * 0.3f
            }
            b.newLine()
            y += l.height * 1.4f
        }
        return b.build()
    }

    private val body = Array(14) { L("Hier steht ganz normaler Text auf der Seite", 14f) }

    private fun line(text: String, h: Float) = TextLine(text, UnitRect(60f, 100f, 600f, 100f + h), 0, 1)

    @Test fun `tall short line at the top is a heading`() {
        val found = HeadingDetector.detect(listOf(line("Wärmepumpen", 30f), line("Normaler Text der Seite hier", 14f)), 1414f, 14f)
        assertEquals(listOf("Wärmepumpen" to 0), found)
    }

    @Test fun `numbered line needs little extra size and gets its level`() {
        val found = HeadingDetector.detect(listOf(line("3.2 Wasserdruck", 17f)), 1414f, 14f)
        assertEquals(listOf("3.2 Wasserdruck" to 1), found)
    }

    @Test fun `body text, sentences and page numbers are no headings`() {
        assertTrue(HeadingDetector.detect(listOf(line("Ganz normaler Text", 14f)), 1414f, 14f).isEmpty())
        assertTrue(HeadingDetector.detect(listOf(line("Das ist ein großer Satz.", 30f)), 1414f, 14f).isEmpty())
        assertTrue(HeadingDetector.detect(listOf(line("123", 30f)), 1414f, 14f).isEmpty())
    }

    @Test fun `unnumbered tall line low on the page is not a heading`() {
        val low = TextLine("Merksatz", UnitRect(60f, 1100f, 400f, 1130f), 0, 1)
        assertTrue(HeadingDetector.detect(listOf(low), 1414f, 14f).isEmpty())
    }

    @Test fun `heading over two lines is joined`() {
        val found = HeadingDetector.detect(listOf(line("Heizungsanlagen", 30f), line("im Altbau", 30f)), 1414f, 14f)
        assertEquals(listOf("Heizungsanlagen im Altbau" to 0), found)
    }

    @Test fun `recognised book gets chapters from line size`() {
        val pages = mapOf(
            0 to page(0, L("Grundlagen", 32f), *body),
            1 to page(1, *body),
            2 to page(2, L("Rohrleitungen", 32f), *body),
            3 to page(3, L("3.1 Kupferrohr", 17f), *body),
            4 to page(4, L("Grundlagen", 32f), *body), // running head
        )
        val outline = IndexOutline.build(BookTextIndex(5, pages, textDone = true)) { 1414f }
        assertEquals(OutlineSource.HEADINGS, outline.source)
        assertTrue(outline.recognized)
        assertEquals(listOf("Grundlagen" to 0, "Rohrleitungen" to 2, "3.1 Kupferrohr" to 3), outline.entries.map { it.title to it.page })
        assertEquals(1, outline.entries[2].level)
        assertTrue(outline.summary().contains("Texterkennung"))
    }

    @Test fun `printed contents win over headings`() {
        val toc = page(1, L("Inhaltsverzeichnis", 20f), L("Grundlagen ....... 1", 14f), L("Rohre ....... 3", 14f), L("Pumpen ....... 5", 14f))
        val pages = HashMap<Int, PageText>()
        pages[0] = page(0, *body)
        pages[1] = toc
        for (i in 2..9) pages[i] = page(i, *body, L("${i - 1}", 14f))
        val outline = IndexOutline.build(BookTextIndex(10, pages, textDone = true)) { 1414f }
        assertEquals(OutlineSource.PRINTED_TOC, outline.source)
        assertEquals(listOf("Grundlagen", "Rohre", "Pumpen"), outline.entries.map { it.title })
    }

    @Test fun `book without any text says so`() {
        val outline = IndexOutline.build(BookTextIndex(2, mapOf(0 to PageText.empty(0), 1 to PageText.empty(1)), true)) { 1414f }
        assertEquals(OutlineSource.NONE, outline.source)
        assertEquals(false, outline.hasText)
    }
}
