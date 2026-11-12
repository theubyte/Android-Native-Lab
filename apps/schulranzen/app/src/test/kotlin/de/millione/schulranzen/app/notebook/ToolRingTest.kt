package de.millione.schulranzen.app.notebook

import de.millione.schulranzen.ink.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRingTest {
    private val density = 2f
    private val main = RingMenus.main(listOf(1, 2, 3, 4, 5, 6))

    @Test fun mainLevelHasToolsMoreAndClose() {
        assertEquals(8, main.inner.size)
        assertEquals(RingItem.More, main.inner.last())
        assertEquals(RingItem.Close, main.centre)
    }

    @Test fun hitFindsCentreToolsColoursAndNothingOutside() {
        val g = ToolRingGeometry
        assertEquals(RingItem.Close, g.hit(main, 500f, 500f, 505f, 505f, density))
        // Straight up, in the middle of the inner band: the first tool.
        assertEquals(RingItem.Tool(ToolKind.PEN), g.hit(main, 500f, 500f, 501f, 500f - 55 * density, density))
        // Straight up in the outer band: the first colour.
        assertEquals(RingItem.Swatch(1), g.hit(main, 500f, 500f, 501f, 500f - 102 * density, density))
        assertNull(g.hit(main, 500f, 500f, 500f, 500f - 200 * density, density))
    }

    @Test fun moreWithoutFavouritesHasAnEmptyOuterRing() {
        val more = RingMenus.more(emptyList(), canPaste = false)
        assertNull(ToolRingGeometry.hit(more, 0f, 0f, 1f, -102 * density, density))
        assertFalse(RingItem.Action(RingAction.PASTE) in more.inner)
        assertTrue(RingItem.Action(RingAction.PASTE) in RingMenus.more(emptyList(), canPaste = true).inner)
        assertEquals(RingItem.Back, more.centre)
    }

    @Test fun ringMovesInFromTheEdges() {
        val (x, y) = ToolRingGeometry.centre(5f, 5f, 2000f, 1200f, density)
        val r = (ToolRingGeometry.OUTER + 22) * density
        assertEquals(r, x, 0.01f)
        assertEquals(r, y, 0.01f)
        val (_, y2) = ToolRingGeometry.centre(1000f, 1199f, 2000f, 1200f, density)
        assertEquals(1200f - r - ToolRingGeometry.LABEL * density, y2, 0.01f)
        val (x3, y3) = ToolRingGeometry.centre(1000f, 600f, 2000f, 1200f, density)
        assertEquals(1000f, x3, 0.01f); assertEquals(600f, y3, 0.01f)
    }

    @Test fun thicknessReadsInMillimetres() {
        assertEquals("0,15 mm", millimetres(0.7f))
        assertEquals("2,1 mm", millimetres(10f))
    }
}
