package de.millione.schulranzen.core.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookLogicTest {
    private val w = 1000f
    private val h = 1414f

    @Test fun centreOnPage_leavesContentOnSheetAlone() {
        assertEquals(0f to 0f, PagePlacement.centreOnPage(100f, 100f, 200f, 200f, w, h))
    }

    @Test fun centreOnPage_bringsBackWhatWasDroppedOutside() {
        val (dx, dy) = PagePlacement.centreOnPage(-3000f, 2000f, -2900f, 2100f, w, h)
        val cx = -2950f + dx; val cy = 2050f + dy
        assertTrue(cx in 0f..w)
        assertTrue(cy in 0f..h)
    }

    @Test fun imageBox_tallPhotoFitsThePage() {
        val b = PagePlacement.imageBox(aspect = 3f, cx = 500f, cy = 700f, pageW = w, pageH = h)
        assertTrue(b[3] <= h * 0.8f + 0.01f)
        assertEquals(3f, b[3] / b[2], 0.001f)
        assertTrue(b[0] >= 0f && b[1] >= 0f && b[0] + b[2] <= w && b[1] + b[3] <= h)
    }

    @Test fun imageBox_nearTheEdgeStaysOnSheet() {
        val b = PagePlacement.imageBox(aspect = 0.5f, cx = 990f, cy = 5f, pageW = w, pageH = h)
        assertEquals(600f, b[2], 0.01f)
        assertTrue(b[0] + b[2] <= w && b[1] >= 0f)
    }

    @Test fun imageBox_nonsenseAspect() {
        val b = PagePlacement.imageBox(aspect = Float.NaN, cx = 500f, cy = 700f, pageW = w, pageH = h)
        assertTrue(b[3] > 0f)
    }

    @Test fun textBox_movesInwardAtEdges() {
        val b = PagePlacement.textBox(x = 990f, y = 1410f, fontSize = 26f, pageW = w, pageH = h)
        assertTrue(b[0] + b[2] <= w)
        assertTrue(b[2] >= 160f)
        assertTrue(b[1] + 26f <= h)
        val free = PagePlacement.textBox(x = 100f, y = 100f, fontSize = 26f, pageW = w, pageH = h)
        assertEquals(100f, free[0], 0f)
        assertEquals(860f, free[2], 0f)
    }

    @Test fun recentColors_frontNoDoublesLimited() {
        var r = emptyList<Int>()
        for (c in 1..8) r = PenPresets.pushColor(r, c)
        assertEquals(listOf(8, 7, 6, 5, 4, 3), r)
        assertEquals(listOf(5, 8, 7, 6, 4, 3), PenPresets.pushColor(r, 5))
    }

    @Test fun fill_topsUpFromPalette() {
        assertEquals(listOf(9, 1, 2, 3), PenPresets.fill(listOf(9, 1), listOf(1, 2, 3, 4), 4))
    }

    @Test fun colors_roundTripIncludingAlpha() {
        val colors = listOf(0xFF1C1B19.toInt(), 0x7F00FF00, 0)
        assertEquals(colors, PenPresets.decodeColors(PenPresets.encodeColors(colors)))
        assertEquals(emptyList<Int>(), PenPresets.decodeColors(null))
        assertEquals(listOf(0xFF), PenPresets.decodeColors("ff,zz"))
    }

    @Test fun presets_roundTripAndSurviveDamage() {
        val p = listOf(PenPreset(1, 0xFF2448A8.toInt(), 4.2f), PenPreset(2, 0xFFF2D04B.toInt(), 18f))
        assertEquals(p, PenPresets.decode(PenPresets.encode(p)))
        assertEquals(p.take(1), PenPresets.decode(PenPresets.encode(p.take(1)) + ";kaputt;1:zz:3;1:ff:-2"))
        assertEquals(1, PenPresets.add(PenPresets.add(emptyList(), p[0]), p[0]).size)
        var many = emptyList<PenPreset>()
        for (i in 1..12) many = PenPresets.add(many, PenPreset(1, i, 3f))
        assertEquals(PenPresets.MAX_PRESETS, many.size)
        assertEquals(12, many[0].color)
    }

    @Test fun titleOrder_germanAndNumeric() {
        val sorted = listOf("Heft 10", "Zeichnen", "Ärger", "heft 2", "Apfel", "Öl").sortedWith(NotebookNames.titleOrder)
        assertEquals(listOf("Apfel", "Ärger", "heft 2", "Heft 10", "Öl", "Zeichnen"), sorted)
    }

    @Test fun sort_cyclesAndSurvivesUnknownNames() {
        assertEquals(NotebookSort.RECENT, NotebookSort.of("gibtsnicht"))
        assertEquals(NotebookSort.RECENT, NotebookSort.CREATED.next())
    }

    @Test fun copyTitle_findsFreeName() {
        assertEquals("Mathe (Kopie)", NotebookNames.copyTitle("Mathe", listOf("Mathe")))
        assertEquals("Mathe (Kopie 2)", NotebookNames.copyTitle("Mathe", listOf("Mathe", "Mathe (Kopie)")))
        assertEquals("Mathe (Kopie 3)", NotebookNames.copyTitle("Mathe (Kopie 2)", listOf("Mathe (Kopie)", "Mathe (Kopie 2)")))
    }

    @Test fun fileName_isSafe() {
        assertEquals("Mathe 1 2.pdf", NotebookNames.fileName("Mathe: 1/2", "pdf"))
        assertEquals("Heft.pdf", NotebookNames.fileName("  ???  ", "pdf"))
        assertTrue(NotebookNames.fileName("x".repeat(300), "pdf").length <= 84)
    }
}
