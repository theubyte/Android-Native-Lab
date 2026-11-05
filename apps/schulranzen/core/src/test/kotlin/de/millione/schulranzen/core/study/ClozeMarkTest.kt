package de.millione.schulranzen.core.study

import org.junit.Assert.assertEquals
import org.junit.Test

class ClozeMarkTest {
    @Test fun `gap marks are not shown as text`() {
        assertEquals("Mit doppelten Klammern um das Wort.", QuestionMaker.plain("Mit {{c1::doppelten Klammern}} um das Wort."))
        assertEquals("Wasser", QuestionMaker.plain("{{c2::Wasser::Hinweis}}"))
        assertEquals("ohne Lücke", QuestionMaker.plain("ohne Lücke"))
    }

    @Test fun `a typed answer is checked against the plain text`() {
        assertEquals(Verdict.RIGHT, AnswerCheck.check("Wasserhahn", "{{c1::Wasserhahn}}").verdict)
    }
}
