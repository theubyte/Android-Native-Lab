package de.millione.schulranzen.core.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextOutlineTest {
    private fun body(n: Int) = "Hier steht ganz normaler Fließtext der Seite.\nNoch eine Zeile Text ohne Überschrift.\n$n"

    @Test fun `toc lines are parsed with dots and plain spacing`() {
        assertEquals("3.2 Ohmsches Gesetz" to 47, TextOutlineBuilder.parseTocLine("3.2 Ohmsches Gesetz ........ 47"))
        assertEquals("Kapitel 1 Grundlagen" to 9, TextOutlineBuilder.parseTocLine("Kapitel 1 Grundlagen 9"))
        assertNull(TextOutlineBuilder.parseTocLine("12 34"))
        assertNull(TextOutlineBuilder.parseTocLine("Text ohne Zahl am Ende"))
    }

    @Test fun `section level follows the numbering depth`() {
        assertEquals(0, TextOutlineBuilder.sectionLevel("Kapitel"))
        assertEquals(0, TextOutlineBuilder.sectionLevel("3 Strom"))
        assertEquals(2, TextOutlineBuilder.sectionLevel("3.2.1 Strom"))
    }

    @Test fun `headings are found at the top of a page`() {
        assertEquals("Kapitel 4 Rohrleitungen" to 0, TextOutlineBuilder.detectHeading(listOf("Kapitel 4 Rohrleitungen", "Text")))
        assertEquals("2.1 Wasserdruck" to 1, TextOutlineBuilder.detectHeading(listOf("17", "2.1 Wasserdruck", "Text")))
        assertEquals("WÄRMEPUMPEN" to 1, TextOutlineBuilder.detectHeading(listOf("WÄRMEPUMPEN", "Text")))
        // A sentence that starts with a number is not a heading.
        assertNull(TextOutlineBuilder.detectHeading(listOf("3 Äpfel kosten 2,50 Euro.")))
    }

    @Test fun `printed page number at top or bottom`() {
        assertEquals(12, TextOutlineBuilder.printedPageNumber(listOf("Titel", "Text", "12")))
        assertEquals(7, TextOutlineBuilder.printedPageNumber(listOf("Seite 7", "Text", "mehr Text")))
        assertNull(TextOutlineBuilder.printedPageNumber(listOf("Titel", "Text")))
    }

    @Test fun `printed contents map to pdf pages through the page offset`() {
        // Four front pages without numbers, then the book starts with printed page 1 at pdf index 4.
        val b = TextOutlineBuilder(30)
        b.addPage(0, "Elektrotechnik\nfür Anlagenmechaniker")
        b.addPage(1, "Inhaltsverzeichnis\n1 Grundlagen ........ 1\n2 Stromkreis ........ 6\n3 Schutzmaßnahmen ....... 12\n4 Prüfen ....... 20")
        b.addPage(2, "Vorwort\nDieses Buch ist für die Ausbildung gedacht und ganz normal geschrieben.")
        b.addPage(3, "Noch mehr Vorwort und Hinweise zur Benutzung des Buches im Unterricht.")
        for (p in 4 until 30) {
            val printed = p - 3
            val text = when (printed) {
                1 -> "1 Grundlagen\n" + body(printed)
                7 -> "2 Stromkreis\n" + body(printed) // one page later than printed in the contents
                12 -> "3 Schutzmaßnahmen\n" + body(printed)
                20 -> "4 Prüfen\n" + body(printed)
                else -> body(printed)
            }
            b.addPage(p, text)
        }
        val outline = b.build()
        assertEquals(OutlineSource.PRINTED_TOC, outline.source)
        assertEquals(listOf("1 Grundlagen", "2 Stromkreis", "3 Schutzmaßnahmen", "4 Prüfen"), outline.entries.map { it.title })
        // Printed page n is pdf index n + 3; "Stromkreis" snaps to the page that really carries the heading.
        assertEquals(listOf(4, 10, 15, 23), outline.entries.map { it.page })
        assertTrue(outline.hasText)
    }

    @Test fun `without contents headings are used and running heads kept once`() {
        val b = TextOutlineBuilder(12)
        for (p in 0 until 12) {
            val text = when {
                p < 4 -> "KAPITEL EINS DAS WASSER\n" + body(p + 1)
                p < 8 -> "KAPITEL ZWEI DIE WÄRME\n" + body(p + 1)
                else -> "Lernfeld 3 Abwasser\n" + body(p + 1)
            }
            b.addPage(p, text)
        }
        val outline = b.build()
        assertEquals(OutlineSource.HEADINGS, outline.source)
        assertEquals(listOf(0, 4, 8), outline.entries.map { it.page })
        assertEquals("Lernfeld 3 Abwasser", outline.entries.last().title)
    }

    @Test fun `scanned book has no text`() {
        val b = TextOutlineBuilder(5)
        for (p in 0 until 5) b.addPage(p, "   ")
        val outline = b.build()
        assertEquals(OutlineSource.NONE, outline.source)
        assertTrue(!outline.hasText)
        assertTrue(outline.summary().contains("keine Textschicht"))
    }

    @Test fun `dedupe sorts by page and drops repeats`() {
        val out = TextOutlineBuilder.dedupe(listOf(
            OutlineEntry("B", 5), OutlineEntry("A", 1), OutlineEntry("a", 1), OutlineEntry("X", -1),
        ))
        assertEquals(listOf(1, 5), out.map { it.page })
    }

    @Test fun `normalisation removes accents and punctuation`() {
        assertEquals("warme und kalte", TextOutlineBuilder.norm("Wärme- und Kälte!"))
    }
}
