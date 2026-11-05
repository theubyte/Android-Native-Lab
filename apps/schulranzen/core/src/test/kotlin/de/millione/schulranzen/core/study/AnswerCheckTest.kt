package de.millione.schulranzen.core.study

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerCheckTest {
    private fun v(given: String, expected: String) = AnswerCheck.check(given, expected).verdict

    @Test fun `exact and case differences are right`() {
        assertEquals(Verdict.RIGHT, v("Kupfer", "Kupfer"))
        assertEquals(Verdict.RIGHT, v("kupfer ", "Kupfer"))
    }

    @Test fun `umlaut spellings and sharp s are the same`() {
        assertEquals(Verdict.RIGHT, v("Waermetauscher", "Wärmetauscher"))
        assertEquals(Verdict.RIGHT, v("Massstab", "Maßstab"))
        assertEquals(Verdict.RIGHT, v("Wärmetauscher", "Waermetauscher"))
    }

    @Test fun `articles and fillers do not matter`() {
        assertEquals(Verdict.RIGHT, v("der Mischer", "Mischer"))
        assertEquals(Verdict.RIGHT, v("Mischer", "ein Mischer"))
    }

    @Test fun `typing slips count as typo on long words only`() {
        assertEquals(Verdict.TYPO, v("Wärmetauschr", "Wärmetauscher"))
        assertEquals(Verdict.TYPO, v("Ausdehnugnsgefäß", "Ausdehnungsgefäß"))
        assertEquals(Verdict.WRONG, v("Ohr", "Rohr"))
        assertEquals(Verdict.WRONG, v("kalt", "warm"))
        assertEquals(Verdict.WRONG, v("Rücklauf", "Vorlauf"))
    }

    @Test fun `typo counts as right in the grade`() {
        assertEquals(SelfGrade.RIGHT, Verdict.TYPO.grade)
        assertEquals(SelfGrade.PARTLY, Verdict.PARTLY.grade)
    }

    @Test fun `numbers are never fuzzy`() {
        assertEquals(Verdict.WRONG, v("80 °C", "60 °C"))
        assertEquals(Verdict.WRONG, v("61", "60"))
        assertEquals(Verdict.WRONG, v("1,6 bar", "1,5 bar"))
    }

    @Test fun `comma and dot in numbers`() {
        assertEquals(Verdict.RIGHT, v("1.5 bar", "1,5 bar"))
        assertEquals(Verdict.RIGHT, v("1,5 bar", "1.5 bar"))
        assertEquals(Verdict.RIGHT, v("1500", "1.500"))
        assertEquals(1.5, AnswerCheck.parseNumber("1,5"))
        assertEquals(1500.0, AnswerCheck.parseNumber("1.500"))
        assertEquals(1500.5, AnswerCheck.parseNumber("1.500,5"))
        assertEquals(1.5, AnswerCheck.parseNumber("1,500"))
        assertEquals(1.5, AnswerCheck.parseNumber("1.5"))
        assertNull(AnswerCheck.parseNumber("abc"))
    }

    @Test fun `units convert and are required`() {
        assertEquals(Verdict.RIGHT, v("150 kPa", "1,5 bar"))
        assertEquals(Verdict.RIGHT, v("60°C", "60 °C"))
        assertEquals(Verdict.RIGHT, v("60 Grad", "60 °C"))
        assertEquals(Verdict.RIGHT, v("0,5 m", "50 cm"))
        assertEquals(Verdict.RIGHT, v("2 h", "120 min"))
        val missing = AnswerCheck.check("60", "60 °C")
        assertEquals(Verdict.PARTLY, missing.verdict)
        assertEquals(AnswerHint.UNIT_MISSING, missing.hint)
        val wrongUnit = AnswerCheck.check("60 bar", "60 °C")
        assertEquals(Verdict.WRONG, wrongUnit.verdict)
        assertEquals(AnswerHint.UNIT_WRONG, wrongUnit.hint)
        assertEquals(Verdict.WRONG, v("1,5 kPa", "1,5 bar"))
    }

    @Test fun `compound units are compared as written`() {
        assertEquals(Verdict.RIGHT, v("2,5 m³/h", "2,5 m³/h"))
        assertEquals(Verdict.RIGHT, v("0,5 L/min", "0,5 l/min"))
        assertEquals(Verdict.WRONG, v("0,5 l/s", "0,5 l/min"))
    }

    @Test fun `hedging with two values is not an answer`() {
        val r = AnswerCheck.check("60 oder 70 °C", "60 °C")
        assertEquals(Verdict.WRONG, r.verdict)
        assertEquals(AnswerHint.TOO_MANY_VALUES, r.hint)
    }

    @Test fun `bracket remarks on the card are optional`() {
        assertEquals(Verdict.RIGHT, v("60 °C", "60 °C (Legionellenschutz)"))
    }

    @Test fun `value with word around it`() {
        assertEquals(Verdict.RIGHT, v("60 °C", "mindestens 60 °C"))
        assertEquals(Verdict.RIGHT, v("DN 20", "DN 20"))
        assertEquals(Verdict.WRONG, v("DN 25", "DN 20"))
    }

    @Test fun `negation changes the meaning`() {
        val r = AnswerCheck.check("nicht zulässig", "zulässig")
        assertEquals(Verdict.WRONG, r.verdict)
        assertEquals(AnswerHint.NEGATION, r.hint)
        assertEquals(Verdict.WRONG, v("zulässig", "nicht zulässig"))
        assertEquals(Verdict.RIGHT, v("nicht zulässig", "nicht zulässig"))
    }

    @Test fun `alternatives on the card each suffice`() {
        assertEquals(Verdict.RIGHT, v("Stahl", "Kupfer oder Stahl"))
        assertEquals(Verdict.RIGHT, v("Kupfer", "Kupfer / Stahl"))
        assertEquals(Verdict.WRONG, v("Messing", "Kupfer oder Stahl"))
    }

    @Test fun `lists need every item in any order`() {
        val back = "• Vorlauf\n• Rücklauf\n• Pumpe"
        assertEquals(Verdict.RIGHT, v("Pumpe, Vorlauf und Rücklauf", back))
        val part = AnswerCheck.check("Pumpe, Vorlauf", back)
        assertEquals(Verdict.PARTLY, part.verdict)
        assertEquals(2, part.matched)
        assertEquals(3, part.total)
        assertEquals(Verdict.WRONG, v("Kessel", back))
    }

    @Test fun `phrases can be partly right and compounds joined`() {
        assertEquals(Verdict.RIGHT, v("Membran Ausdehnungsgefäß", "Membranausdehnungsgefäß"))
        assertEquals(Verdict.PARTLY, v("Vorlauf Rücklauf", "Vorlauf Rücklauf Pumpe"))
        assertEquals(Verdict.WRONG, v("Vorlauf", "Vorlauf Rücklauf Pumpe"))
    }

    @Test fun `long explanations cannot be checked by machine`() {
        val long = "Das Ausdehnungsgefäß nimmt die Volumenänderung des Heizwassers beim Erwärmen auf und hält den Druck in der Anlage in dem zulässigen Bereich"
        assertFalse(AnswerCheck.canCheck(long))
        assertEquals(Verdict.UNCHECKABLE, v("irgendwas", long))
        assertFalse(AnswerCheck.canCheck("  "))
    }

    @Test fun `empty answer is wrong`() {
        assertEquals(Verdict.WRONG, v("", "Kupfer"))
    }
}

class QuestionMakerTest {
    private fun card(id: Long, front: String, back: String, image: Boolean = false) = QuizCard(id, front, back, image)
    private val pool = listOf(
        card(1, "Temperatur A?", "60 °C"), card(2, "Temperatur B?", "55 °C"), card(3, "Temperatur C?", "70 °C"),
        card(4, "Temperatur D?", "20 °C"), card(5, "Werkstoff?", "Kupfer"), card(6, "Bauteil?", "Mischer"), card(7, "Bauteil 2?", "Pumpe"),
    )

    @Test fun `choice holds the right answer once and alike distractors`() {
        repeat(20) { seed ->
            val q = assertNotNull(QuestionMaker.choice(pool[0], pool, Random(seed)))
            assertEquals(4, q.options.size)
            assertEquals("60 °C", q.options[q.correct])
            assertEquals(4, q.options.distinct().size)
            assertTrue(q.options.all { it.contains("°C") }, "numbers compete with numbers: ${q.options}")
        }
    }

    @Test fun `choice skips answers that would also be right`() {
        val cards = listOf(card(1, "a", "Wärmetauscher"), card(2, "b", "Waermetauscher"), card(3, "c", "Waermetauschr"), card(4, "d", "Pumpe"), card(5, "e", "Mischer"))
        val q = assertNotNull(QuestionMaker.choice(cards[0], cards, Random(1)))
        assertEquals(3, q.options.size)
        assertTrue("Waermetauscher" !in q.options)
    }

    @Test fun `no choice without enough distractors or for pictures`() {
        assertNull(QuestionMaker.choice(pool[0], pool.take(2), Random(1)))
        assertNull(QuestionMaker.choice(card(9, "x", "y", image = true), pool, Random(1)))
        assertNull(QuestionMaker.choice(card(9, "x", "z".repeat(200)), pool, Random(1)))
    }

    @Test fun `cloze cuts a value first`() {
        val c = assertNotNull(QuestionMaker.cloze("Der Speicher muss mindestens 60 °C halten."))
        assertEquals("60 °C", c.answer)
        assertEquals("Der Speicher muss mindestens _____ halten.", c.text)
        val d = assertNotNull(QuestionMaker.cloze("Im Heizkreis liegt der Druck bei 1,5 bar im Betrieb."))
        assertEquals("1,5 bar", d.answer)
    }

    @Test fun `cloze takes a noun and avoids the question words`() {
        val c = assertNotNull(QuestionMaker.cloze("Das Ausdehnungsgefäß nimmt die Volumenänderung des Heizwassers auf.", avoid = "Was macht das Ausdehnungsgefäß?"))
        assertFalse(c.answer.contains("Ausdehnungsgefäß"))
        assertTrue(c.text.contains(QuestionMaker.BLANK))
        assertFalse(c.text.contains(c.answer))
    }

    @Test fun `marked cloze wins and other marks are filled in`() {
        val c = assertNotNull(QuestionMaker.cloze("{{c1::Kupfer}} und {{c2::Stahl}} sind Werkstoffe."))
        assertEquals("Kupfer", c.answer)
        assertEquals("_____ und Stahl sind Werkstoffe.", c.text)
    }

    @Test fun `too short text has no cloze`() {
        assertNull(QuestionMaker.cloze("Kupfer"))
        assertNull(QuestionMaker.clozeQuestion(card(1, "?", "Kupfer")))
    }

    @Test fun `exam mix asks each card once and mixes kinds`() {
        val cards = (1L..16L).map { card(it, "Frage $it?", "Antwort Nummer $it") }
        val long = cards + card(99, "Erkläre", "Das ist eine sehr lange Erklärung mit vielen Worten, die keine Maschine fair vergleichen kann, weil sie frei formuliert ist und viel erklärt.")
        val qs = QuestionMaker.examMix(long, long, Random(3))
        val asked = qs.flatMap { q -> q.cards.map { it.id } }
        assertEquals(long.size, asked.size)
        assertEquals(long.size, asked.distinct().size)
        assertTrue(qs.map { it.type }.distinct().size >= 3, "kinds: ${qs.map { it.type }}")
        assertEquals(2, qs.count { it is Question.Match })
    }

    @Test fun `pictures stay flip questions`() {
        val img = card(1, "Was ist das?", "Mischer", image = true)
        assertTrue(QuestionMaker.examMix(listOf(img), pool, Random(1)).single() is Question.Flip)
        assertTrue(QuestionMaker.question(QuestionType.TYPE, img, pool, Random(1)) is Question.Flip)
    }

    @Test fun `falling back from choice keeps the card`() {
        val q = QuestionMaker.question(QuestionType.CHOICE, card(1, "a", "b"), emptyList(), Random(1))
        assertTrue(q is Question.TypeIn)
    }

    @Test fun `match round tracks first tries and shared answers`() {
        val cs = listOf(card(1, "A", "x"), card(2, "B", "y"), card(3, "C", "y"))
        val q = assertNotNull(QuestionMaker.match(cs, Random(1)))
        assertEquals(setOf(0, 1, 2), q.rightOrder.toSet())
        val r = MatchRound(cs)
        assertFalse(r.pick(1, 2))
        assertTrue(r.pick(1, 1))
        assertTrue(r.pick(2, 3), "two cards with the same answer accept either")
        assertTrue(r.pick(3, 2))
        assertTrue(r.done)
        assertFalse(r.firstTry(1))
        assertTrue(r.firstTry(2))
        assertEquals(setOf(1L), r.missed)
    }

    @Test fun `match needs three usable cards`() {
        assertNull(QuestionMaker.match(pool.take(2), Random(1)))
        assertNull(QuestionMaker.match(listOf(card(1, "a", "x"), card(2, "b", "y"), card(3, "c", "z".repeat(100))), Random(1)))
    }
}

class CardCsvTest {
    @Test fun `plain tab separated file`() {
        val p = CardCsv.parse("Frage 1\tAntwort 1\nFrage 2\tAntwort 2\n")
        assertEquals('\t', p.delimiter)
        assertEquals(listOf(CsvCard("Frage 1", "Antwort 1"), CsvCard("Frage 2", "Antwort 2")), p.cards)
        assertEquals(0, p.skipped)
    }

    @Test fun `anki headers quotes and html`() {
        val text = "#separator:tab\n#html:true\n#tags column:3\n\"Zeile <b>eins</b>\"\t\"A<br>B &amp; C\"\tlf3\n\"mehr\nzeilig\"\t\"mit \"\"Zitat\"\"\"\t\n"
        val p = CardCsv.parse(text)
        assertEquals("Zeile eins", p.cards[0].front)
        assertEquals("A\nB & C", p.cards[0].back)
        assertEquals("lf3", p.cards[0].tags)
        assertEquals("mehr\nzeilig", p.cards[1].front)
        assertEquals("mit \"Zitat\"", p.cards[1].back)
    }

    @Test fun `columns can be swapped by header`() {
        val p = CardCsv.parse("#separator:semicolon\n#columns:Back;Front\nAntwort;Frage\n")
        assertEquals(CsvCard("Frage", "Antwort"), p.cards.single())
    }

    @Test fun `deck column is read`() {
        val p = CardCsv.parse("#separator:tab\n#deck column:3\nF\tA\tLF 3\n")
        assertEquals("LF 3", p.cards.single().deck)
    }

    @Test fun `semicolon and comma are detected and bom and crlf removed`() {
        assertEquals(';', CardCsv.parse("﻿a;b\r\nc;d\r\n").delimiter)
        assertEquals("a", CardCsv.parse("﻿a;b\r\nc;d\r\n").cards[0].front)
        assertEquals(',', CardCsv.parse("a,b\nc,d\n").delimiter)
    }

    @Test fun `broken lines are counted not fatal`() {
        val p = CardCsv.parse("nur eine Spalte\nF\tA\n\t\nkein\tProblem\n")
        assertEquals(2, p.cards.size)
        assertEquals(2, p.skipped)
    }

    @Test fun `media references are dropped and reported`() {
        val p = CardCsv.parse("Bild <img src=\"a.png\">\tTon [sound:b.mp3] ok\n")
        assertEquals("Bild", p.cards[0].front)
        assertEquals("Ton ok", p.cards[0].back)
        assertEquals(1, p.mediaDropped)
    }

    @Test fun `title row is recognised`() {
        val cards = CardCsv.dropTitleRow(CardCsv.parse("Frage;Antwort\na;b\n").cards)
        assertEquals(listOf(CsvCard("a", "b")), cards)
    }

    @Test fun `what is written reads back unchanged`() {
        val cards = listOf("Zeile\nzwei" to "mit \"Zitat\"\tund Tab", "60 °C" to "• A\n• B", "a;b" to "c,d")
        fun same(text: String) {
            val p = CardCsv.dropTitleRow(CardCsv.parse(text).cards)
            assertEquals(cards, p.map { it.front to it.back })
        }
        same(CardCsv.write(cards))
        same(CardCsv.writeCsv(cards))
    }

    @Test fun `empty text is empty`() {
        assertTrue(CardCsv.parse("").cards.isEmpty())
        assertTrue(CardCsv.parse("#separator:tab\n").cards.isEmpty())
    }
}

class TextQuestionsTest {
    @Test fun `questions come from the text only`() {
        val text = "Ein MAG ist ein Membran-Ausdehnungsgefäß im Heizkreis.\nDas Warmwasser im Speicher muss mindestens 60 °C heiß sein.\nDas war gestern schön."
        val qs = TextQuestions.fromText(text)
        assertTrue(qs.any { it.kind == SuggestionKind.DEFINITION && it.front.contains("MAG") })
        val gap = qs.first { it.kind == SuggestionKind.GAP && it.back == "60 °C" }
        assertTrue(gap.front.startsWith("Ergänze:"))
        assertFalse(gap.front.contains("60 °C"))
        assertTrue(qs.none { it.front.contains("gestern") })
    }

    @Test fun `nothing from nothing`() = assertTrue(TextQuestions.fromText(" \n ").isEmpty())
}
