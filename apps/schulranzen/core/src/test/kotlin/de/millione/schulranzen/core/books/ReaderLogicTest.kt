package de.millione.schulranzen.core.books

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Found while auditing the reader for release: most tests hold one fixed fault. */
class ReaderLogicTest {
    private fun page(i: Int, source: TextSource = TextSource.PDF, build: PageTextBuilder.() -> Unit) =
        PageTextBuilder(i, source).apply(build).build()

    private fun words(vararg lines: List<String>) = page(0) {
        lines.forEachIndexed { l, ws ->
            ws.forEachIndexed { k, w -> word(w, k * 100f, l * 30f, k * 100f + 90f, l * 30f + 20f) }
            newLine()
        }
    }

    // ------------------------------------------------------------- search

    @Test fun decomposedUmlautIsFound() {
        val hits = TextSearch.find(listOf(words(listOf("Die", "Übung", "beginnt"))), "Übung")
        assertEquals(1, hits.size)
        assertEquals("Übung", hits[0].snippet.substring(hits[0].matchStart, hits[0].matchEnd))
    }

    @Test fun softHyphenInsideAWordIsIgnored() {
        assertEquals(1, TextSearch.find(listOf(words(listOf("Wärme­tauscher"))), "Wärmetauscher").size)
    }

    @Test fun softAndUnicodeHyphensAtLineEndJoinTheWord() {
        for (h in listOf("­", "‐", "-")) {
            val p = words(listOf("Der", "Wider$h"), listOf("stand", "steigt"))
            val hit = TextSearch.find(listOf(p), "Widerstand").singleOrNull()
            assertEquals(2, hit?.rects?.size, "Trennstrich U+%04X".format(h[0].code))
        }
    }

    @Test fun compoundWithCapitalAfterLineEndHyphenKeepsItsHyphen() {
        val p = words(listOf("Das", "Heizungs-"), listOf("Wasser", "fließt"))
        assertEquals(1, TextSearch.find(listOf(p), "Heizungs-Wasser").size)
        assertEquals(1, TextSearch.find(listOf(p), "Wasser fließt").size)
    }

    @Test fun nonBreakingHyphenMatchesATypedHyphen() {
        assertEquals(1, TextSearch.find(listOf(words(listOf("Wand‑Heizung"))), "wand-heizung").size)
    }

    @Test fun hitAfterFoldedLettersKeepsItsPosition() {
        // ß folds to two letters and a soft hyphen to none: neither may shift what comes after.
        val p = words(listOf("Straße", "Wär­me", "Rohr"))
        val hit = TextSearch.find(listOf(p), "Rohr").single()
        assertEquals("Rohr", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertEquals(200f, hit.rects.single().left)
    }

    // -------------------------------------------------------------- index

    @Test fun blankPagesAreNotRecognisedAgainAndAgain() {
        val scanned = BookTextIndex(3, mapOf(
            0 to PageText.empty(0),
            1 to PageText.empty(1, TextSource.OCR),
            2 to page(2, TextSource.OCR) { word("Text", 0f, 0f, 10f, 10f) },
        ), textDone = true)
        assertEquals(listOf(0), scanned.pagesWithoutText())
    }

    @Test fun boxesOfVeryLongPagesSurviveTheFile() {
        val p = page(0) { word("oben", 10f, 20f, 50f, 40f); newLine(); word("unten", 10f, 30_000f, 50f, 30_020f) }
        val out = ByteArrayOutputStream()
        TextIndexCodec.write(out, BookTextIndex(1, mapOf(0 to p), true))
        val back = TextIndexCodec.read(ByteArrayInputStream(out.toByteArray())).page(0)!!
        assertEquals(30_000f, back.box(1).top, 2f)
        assertEquals(20f, back.box(0).top, 2f)
    }

    @Test fun normalPagesKeepQuarterUnitsInTheFile() {
        val p = page(0) { word("Wort", 10.25f, 20.5f, 50.75f, 40f) }
        val out = ByteArrayOutputStream()
        TextIndexCodec.write(out, BookTextIndex(1, mapOf(0 to p), true))
        assertEquals(10.25f, TextIndexCodec.read(ByteArrayInputStream(out.toByteArray())).page(0)!!.box(0).left)
    }

    // ------------------------------------------------------------- layout

    @Test fun invalidPageSizesStillGiveAPage() {
        assertEquals(1414f, BookUnits.height(0f, 100f))
        assertEquals(1414f, BookUnits.height(100f, 0f))
    }

    @Test fun jumpedToShortPageStaysCurrent() {
        // Slides on a phone: several pages fit the screen. The page at the top must be the current one.
        val layout = ColumnLayout(FloatArray(10) { 0.56f })
        val top = layout.top(3)
        assertEquals(3, layout.dominant(top - 20f, top - 20f + 2200f))
    }

    @Test fun mostVisiblePageIsCurrent() {
        val layout = ColumnLayout(FloatArray(5) { 1.414f })
        assertEquals(1, layout.dominant(layout.top(1) + 200f, layout.top(1) + 1600f))
        assertEquals(2, layout.dominant(layout.top(1) + 900f, layout.top(1) + 2300f))
    }

    @Test fun noPagesNoCurrentPage() { assertEquals(-1, ColumnLayout(FloatArray(0)).dominant(0f, 100f)) }

    @Test fun normalPagesRenderAtFullWidth() { assertEquals(1600 to 2262, RenderMath.baseSize(1600, 1.414f)) }

    @Test fun stripPagesGetASmallerBitmap() {
        val (w, h) = RenderMath.baseSize(1600, 40f)
        assertTrue(w.toLong() * h <= RenderMath.MAX_BASE_PIXELS * 1.01, "$w x $h")
        assertEquals(40f, h / w.toFloat(), 0.5f)
    }

    @Test fun brokenAspectStillGivesASize() { assertEquals(1000 to 1414, RenderMath.baseSize(1000, Float.NaN)) }

    // ----------------------------------------------------------- position

    @Test fun positionStaysInTheBook() {
        assertEquals(ReadingPosition(9, 1f, 5), ReadingPosition(40, 3f, 5).within(10))
        assertEquals(ReadingPosition(0, 0f), ReadingPosition(-1, Float.NaN).within(10))
        assertEquals(0, ReadingPosition(3).within(0).page)
    }

    @Test fun progressRunsFromFirstToLastPage() {
        assertEquals(0, ReadingProgress.percent(0, 200))
        assertEquals(50, ReadingProgress.percent(100, 201))
        assertEquals(100, ReadingProgress.percent(199, 200))
        assertEquals(100, ReadingProgress.percent(0, 1))
        assertEquals(0, ReadingProgress.percent(0, 0))
    }

    // -------------------------------------------------------------- night

    private fun channels(c: Int) = listOf(c shr 16 and 255, c shr 8 and 255, c and 255)

    @Test fun whitePaperTurnsDarkButNotBlack() {
        val (r, g, b) = channels(NightColors.apply(0xFFFFFFFF.toInt()))
        assertTrue(r in 15..40 && g in 15..40 && b in 15..40, "$r $g $b")
    }

    @Test fun blackInkTurnsLight() {
        channels(NightColors.apply(0xFF000000.toInt())).forEach { assertTrue(it >= 200, "$it") }
    }

    @Test fun redStaysRed() {
        val (r, g, b) = channels(NightColors.apply(0xFFC03020.toInt()))
        assertTrue(r > g && r > b, "$r $g $b")
    }

    @Test fun alphaIsKept() { assertEquals(0x40, NightColors.apply(0x40FF0000) ushr 24) }

    // --------------------------------------------------------- area text

    private val all = UnitRect(0f, 0f, 1000f, 1000f)

    @Test fun onlyWordsInTheAreaCount() {
        val p = words(listOf("eins", "zwei", "drei"), listOf("vier", "fünf", "sechs"))
        assertEquals("zwei drei", AreaText.extract(p, UnitRect(100f, 0f, 400f, 25f)))
        assertEquals("", AreaText.extract(p, UnitRect(500f, 0f, 900f, 100f)))
    }

    @Test fun wrappedLinesFlowAndHyphensCloseAgain() {
        val p = words(listOf("Das", "Ausdehnungs-"), listOf("gefäß", "nimmt"), listOf("wenig", "auf."))
        assertEquals("Das Ausdehnungsgefäß nimmt wenig auf.", AreaText.extract(p, all))
    }

    @Test fun sentencesAndListsKeepTheirLineEnds() {
        assertEquals("Erster Satz.\nZweiter Satz.", AreaText.join(listOf("Erster Satz.", "Zweiter Satz.")))
        assertEquals("Pumpe\nVentil\nKessel", AreaText.join(listOf("Pumpe", "Ventil", "Kessel")))
    }

    @Test fun longLineGoesOnEvenBeforeANoun() {
        val long = "Das Ventil regelt den Durchfluss durch die"
        assertEquals("$long Pumpe im Kreislauf.", AreaText.join(listOf(long, "Pumpe im Kreislauf.")))
    }

    @Test fun quoteIsCutAtAWord() {
        assertEquals("kurz", AreaText.quote(" kurz\n"))
        assertEquals(
            "Ein Membran-Ausdehnungsgefäß nimmt die …",
            AreaText.quote("Ein Membran-Ausdehnungsgefäß nimmt die Volumenänderung des Heizungswassers auf", 40),
        )
    }
}
