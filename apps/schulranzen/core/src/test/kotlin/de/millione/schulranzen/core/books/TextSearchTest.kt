package de.millione.schulranzen.core.books

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextSearchTest {
    /** Lines of words; each word 10 units per character wide, lines 30 units apart. */
    private fun page(n: Int, vararg lines: String, source: TextSource = TextSource.PDF): PageText {
        val b = PageTextBuilder(n, source)
        lines.forEachIndexed { li, line ->
            var x = 50f
            for (w in line.split(' ')) {
                b.word(w, x, 100f + li * 30, x + w.length * 10f, 120f + li * 30)
                x += w.length * 10f + 10f
            }
            b.newLine()
        }
        return b.build()
    }

    @Test fun `builder joins words and lines`() {
        val p = page(0, "Das Ohmsche Gesetz", "gilt immer")
        assertEquals("Das Ohmsche Gesetz\ngilt immer", p.text)
        assertEquals(5, p.wordCount)
        val lines = p.lines()
        assertEquals(listOf("Das Ohmsche Gesetz", "gilt immer"), lines.map { it.text })
        assertEquals(UnitRect(50f, 100f, 230f, 120f), lines[0].box)
    }

    @Test fun `search ignores case umlauts and sharp s`() {
        val pages = listOf(page(0, "Übung zur Straße"), page(3, "keine Treffer hier"))
        assertEquals(1, TextSearch.find(pages, "ubung").size)
        assertEquals(1, TextSearch.find(pages, "STRASSE").size)
        assertEquals(1, TextSearch.find(pages, "übung zur").size)
        assertTrue(TextSearch.find(pages, "x").isEmpty(), "one letter is too short")
    }

    @Test fun `hits are in page order with page and snippet`() {
        val pages = listOf(page(7, "Druck im Rohr"), page(2, "Der Druck steigt"))
        val hits = TextSearch.find(pages, "druck")
        assertEquals(listOf(2, 7), hits.map { it.page })
        val h = hits[0]
        assertEquals("Druck", h.snippet.substring(h.matchStart, h.matchEnd))
    }

    @Test fun `hyphen at line end is joined`() {
        val p = page(0, "Der Wider-", "stand ist hoch")
        val hits = TextSearch.find(listOf(p), "widerstand")
        assertEquals(1, hits.size)
        // Two lines -> two highlight rectangles.
        assertEquals(2, hits[0].rects.size)
    }

    @Test fun `phrase across line break and spaces`() {
        val p = page(0, "Ohmsche", "Gesetz")
        assertEquals(1, TextSearch.find(listOf(p), "ohmsche   gesetz").size)
    }

    @Test fun `highlight covers only the matched part of a word`() {
        val p = page(0, "Wasserdruck")
        val r = TextSearch.find(listOf(p), "druck").single().rects.single()
        // "Wasser" is 6 of 11 characters, word box 50..160.
        assertEquals(110f, r.left, 0.01f)
        assertEquals(160f, r.right, 0.01f)
    }

    @Test fun `limit stops the search`() {
        val p = page(0, List(50) { "Ton" }.joinToString(" "))
        assertEquals(10, TextSearch.find(listOf(p), "ton", limit = 10).size)
    }

    @Test fun `long snippet is cut at word edges with ellipsis`() {
        val long = List(40) { "wort$it" }.joinToString(" ")
        val p = page(0, "$long Zielwort $long")
        val h = TextSearch.find(listOf(p), "zielwort").single()
        assertTrue(h.snippet.startsWith("… "))
        assertTrue(h.snippet.endsWith(" …"))
        assertEquals("Zielwort", h.snippet.substring(h.matchStart, h.matchEnd))
        assertTrue(h.snippet.length < 160)
    }

    @Test fun `index survives the file round trip`() {
        val index = BookTextIndex(10, mapOf(0 to page(0, "Übung macht", "den Meister"), 1 to PageText.empty(1), 4 to page(4, "Scan", source = TextSource.OCR)), textDone = true)
        val bytes = ByteArrayOutputStream().also { TextIndexCodec.write(it, index) }.toByteArray()
        val back = TextIndexCodec.read(ByteArrayInputStream(bytes))
        assertEquals(10, back.pageCount)
        assertTrue(back.textDone)
        assertEquals(index.page(0)!!.text, back.page(0)!!.text)
        assertEquals(TextSource.OCR, back.page(4)!!.source)
        assertEquals(index.page(0)!!.box(2), back.page(0)!!.box(2))
        assertFalse(back.page(1)!!.hasText)
        assertTrue(back.hasOcr)
    }

    @Test fun `scanned book and pages without text`() {
        val index = BookTextIndex(3, mapOf(0 to PageText.empty(0), 1 to PageText.empty(1), 2 to PageText.empty(2)), textDone = true)
        assertTrue(index.looksScanned)
        assertEquals(listOf(0, 1, 2), index.pagesWithoutText())
        val after = index.with(page(1, "Erkannt", source = TextSource.OCR))
        assertEquals(listOf(0, 2), after.pagesWithoutText())
    }

    @Test fun `undo stack moves steps between both sides`() {
        val s = UndoStack<Int>(limit = 3)
        s.push(1); s.push(2); s.push(3); s.push(4)
        assertEquals(4, s.undo()); assertEquals(3, s.undo()); assertEquals(2, s.undo())
        assertEquals(null, s.undo(), "oldest step fell off")
        assertEquals(2, s.redo())
        s.push(9)
        assertFalse(s.canRedo, "a new step clears redo")
        assertEquals(9, s.undo())
    }
}
