package de.millione.schulranzen.app.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AboutTextsTest {
    @Test fun `inline bold and placeholder`() {
        val s = MiniMarkdown.inline("Vor **fett** dann `[PLATZHALTER: Name]`.")
        assertEquals(listOf(Span("Vor "), Span("fett", bold = true), Span(" dann "), Span("[PLATZHALTER: Name]", code = true), Span(".")), s)
    }

    @Test fun `blocks`() {
        val b = MiniMarkdown.parse("## Titel\n\n### Eins\nText\n- Punkt")
        assertEquals(Block.Title("Titel"), b[0])
        assertEquals(Block.Heading("Eins"), b[1])
        assertEquals(Block.Paragraph(listOf(Span("Text"))), b[2])
        assertEquals(Block.Bullet(listOf(Span("Punkt"))), b[3])
    }

    @Test fun `licence names map to the shipped texts`() {
        assertEquals("apache-2.0.txt", licenseFile("The Apache Software License, Version 2.0"))
        assertEquals("apache-2.0.txt", licenseFile("Apache-2.0"))
        assertEquals("ofl-1.1.txt", licenseFile("SIL Open Font License 1.1"))
        assertEquals("bouncycastle.txt", licenseFile("Bouncy Castle Licence"))
        assertNull(licenseFile("Android Software Development Kit License"))
    }
}
