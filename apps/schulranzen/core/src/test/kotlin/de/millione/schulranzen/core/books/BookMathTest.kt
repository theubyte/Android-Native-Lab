package de.millione.schulranzen.core.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookMathTest {
    @Test fun `rotation normalises to quarter turns`() {
        assertEquals(0, PageRotation.normalize(360))
        assertEquals(270, PageRotation.normalize(-90))
        assertEquals(90, PageRotation.normalize(135))
        assertEquals(0, PageRotation.next(270))
        assertEquals(180, PageRotation.next(90))
    }

    @Test fun `box size swaps for quarter turns`() {
        assertEquals(1414f to 1000f, PageRotation.boxSize(1000f, 1414f, 90))
        assertEquals(1000f to 1414f, PageRotation.boxSize(1000f, 1414f, 180))
    }

    @Test fun `clockwise turn moves the top left corner to the top right`() {
        val w = 1000f; val h = 1400f
        assertEquals(h to 0f, PageRotation.toBox(0f, 0f, w, h, 90))
        assertEquals(0f to w, PageRotation.toBox(0f, 0f, w, h, 270))
        assertEquals(w to h, PageRotation.toBox(0f, 0f, w, h, 180))
    }

    @Test fun `box mapping round trips for every rotation`() {
        val w = 1000f; val h = 1300f
        for (deg in listOf(0, 90, 180, 270)) {
            val (bx, by) = PageRotation.toBox(123f, 456f, w, h, deg)
            val (x, y) = PageRotation.fromBox(bx, by, w, h, deg)
            assertEquals(123f, x, 1e-3f, "x at $deg")
            assertEquals(456f, y, 1e-3f, "y at $deg")
            val (bw, bh) = PageRotation.boxSize(w, h, deg)
            assertTrue(bx in 0f..bw && by in 0f..bh, "inside box at $deg")
        }
    }

    @Test fun `column layout stacks pages with gaps`() {
        val layout = ColumnLayout(floatArrayOf(1.4f, 0.7f, 1.4f), gap = 20f)
        assertEquals(0f, layout.top(0))
        assertEquals(1420f, layout.top(1))
        assertEquals(1420f + 700f + 20f, layout.top(2))
        assertEquals(layout.bottom(2), layout.totalHeight)
        assertEquals(0, layout.pageAt(10f))
        assertEquals(-1, layout.pageAt(1410f))
        assertEquals(1, layout.pageAt(1500f))
        assertEquals(2, layout.pageAt(layout.totalHeight - 1f))
        assertEquals(-1, layout.pageAt(-5f))
    }

    @Test fun `visible pages cover the window`() {
        val layout = ColumnLayout(FloatArray(10) { 1f }, gap = 0f)
        assertEquals(0..0, layout.visible(0f, 500f))
        assertEquals(2..4, layout.visible(2500f, 4200f))
        assertEquals(9..9, layout.visible(9800f, 20000f))
        // Window starting in a gap begins with the next page.
        val gapped = ColumnLayout(FloatArray(3) { 1f }, gap = 100f)
        assertEquals(1..1, gapped.visible(1050f, 1900f))
        assertTrue(ColumnLayout(FloatArray(0)).visible(0f, 100f).isEmpty())
    }

    @Test fun `page blocks split a book`() {
        assertEquals(0, PageBlocks.count(0))
        assertEquals(1, PageBlocks.count(24))
        assertEquals(2, PageBlocks.count(25))
        assertEquals(24 until 25, PageBlocks.block(1, 25))
        assertTrue(PageBlocks.block(2, 25).isEmpty())
        assertEquals(2, PageBlocks.blockOf(48))
        assertEquals("25–48", PageBlocks.label(1, 100))
        assertEquals("97–100", PageBlocks.label(4, 100))
    }

    @Test fun `page jump reads numbers and clamps`() {
        assertEquals(11, PageJump.parse("12", 300))
        assertEquals(11, PageJump.parse(" S. 12 ", 300))
        assertEquals(11, PageJump.parse("Seite 12", 300))
        assertEquals(299, PageJump.parse("999", 300))
        assertNull(PageJump.parse("0", 300))
        assertNull(PageJump.parse("abc", 300))
        assertNull(PageJump.parse("5", 0))
    }

    @Test fun `spanning rectangle is ordered and clamped`() {
        val r = UnitRect.spanning(900f, 50f, -20f, 1600f, 1000f, 1414f)
        assertEquals(UnitRect(0f, 50f, 900f, 1414f), r)
        assertEquals(900f, r.width)
    }

    @Test fun `clip size is print quality but bounded`() {
        assertEquals(1240 to 620, RenderMath.clipSize(500f, 250f))
        val (w, h) = RenderMath.clipSize(1000f, 1414f)
        assertEquals(3200, h); assertTrue(w < 3200)
        val (sw, sh) = RenderMath.clipSize(40f, 20f)
        assertEquals(640, sw); assertEquals(320, sh)
        assertEquals(0 to 0, RenderMath.clipSize(0f, 10f))
    }

    @Test fun `base width and detail threshold`() {
        assertEquals(600, RenderMath.baseWidth(300f))
        assertEquals(1200, RenderMath.baseWidth(1200f))
        assertEquals(1600, RenderMath.baseWidth(5000f))
        assertTrue(RenderMath.needsDetail(1500f, 1200))
        assertTrue(!RenderMath.needsDetail(1300f, 1200))
    }

    @Test fun `book title prefers a real pdf title`() {
        assertEquals("Fachkunde Sanitär", BookTitle.choose("Fachkunde  Sanitär", "fk.pdf"))
        assertEquals("Mathe Band 2", BookTitle.choose("Microsoft Word - mathe.docx", "Mathe_Band_2.pdf"))
        assertEquals("Mathe Band 2", BookTitle.choose("", "Mathe_Band_2.PDF"))
        assertEquals("Mathe Band 2", BookTitle.choose("untitled", "Mathe_Band_2.pdf"))
        assertEquals("Neues Buch", BookTitle.choose(null, null))
    }
}
