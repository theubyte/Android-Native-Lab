package de.millione.schulranzen.core.shapes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextSearchTest {
    @Test fun `normalises umlauts sharp s and punctuation`() {
        assertEquals("waermepumpe strasse z b", TextSearch.normalize("Wärmepumpe, Straße – z.B."))
        assertEquals("warmepumpe", TextSearch.fold("Wärmepumpe"))
        assertEquals("cafe", TextSearch.normalize("Café"))
    }

    @Test fun `all umlaut spellings find each other`() {
        for (q in listOf("Müll", "Muell", "Mull", "müll")) assertTrue(TextSearch.wordScore(q, "Müll") >= 0.8f, q)
        assertTrue(TextSearch.wordScore("Strasse", "Straße") == 1f)
    }

    @Test fun `prefix compound and typos`() {
        assertEquals(0.9f, TextSearch.wordScore("Wärm", "Wärmepumpe"))
        assertEquals(0.75f, TextSearch.wordScore("pumpe", "Wärmepumpe"))
        assertTrue(TextSearch.wordScore("Heizkesel", "Heizkessel") >= 0.7f)
        assertTrue(TextSearch.wordScore("Ventli", "Ventil") >= 0.7f) // swapped letters
        assertTrue(TextSearch.wordScore("Ausdehnungsgefas", "Ausdehnungsgefäß") > 0f)
        assertTrue(TextSearch.wordScore("Druck", "Druckverlust") >= 0.9f)
    }

    @Test fun `short words need to match exactly or as prefix`() {
        assertEquals(0f, TextSearch.wordScore("rot", "rat"))
        assertEquals(0f, TextSearch.wordScore("a", "Haus"))
        assertEquals(0f, TextSearch.wordScore("Pumpe", "Lampe"))
    }

    @Test fun `distance counts swaps as one edit`() {
        assertEquals(1, TextSearch.distance("ab", "ba"))
        assertEquals(3, TextSearch.distance("kitten", "sitting"))
    }

    @Test fun `multi word query must find every word`() {
        val line = listOf("Die", "Wärmepumpe", "braucht", "Strom")
        val m = assertNotNull(TextSearch.match("wärmepumpe strom", line))
        assertEquals(listOf(1, 3), m.words)
        assertNull(TextSearch.match("wärmepumpe gas", line))
        val near = assertNotNull(TextSearch.match("braucht strom", line))
        assertTrue(near.score > m.score, "neighbouring words rank higher")
    }

    @Test fun `snippet keeps the match visible`() {
        val words = "Heute haben wir in der Berufsschule sehr lange über die hydraulische Weiche und den Pufferspeicher gesprochen".split(" ")
        val i = words.indexOf("Pufferspeicher")
        val (text, range) = TextSearch.snippet(words, listOf(i), maxChars = 40)
        assertNotNull(range)
        assertEquals("Pufferspeicher", text.substring(range.first, range.last + 1))
        assertTrue(text.length <= 50)
        assertTrue(text.startsWith("…"))
    }
}
