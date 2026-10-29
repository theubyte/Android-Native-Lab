package de.millione.schulranzen.core.shapes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineGrouperTest {
    /** Fake handwriting: each word is a few letter boxes 20 high, 12 wide, 3 apart. */
    private fun word(x: Float, baseline: Float, letters: Int, slope: Float = 0f): List<Bounds> =
        (0 until letters).map { i ->
            val lx = x + i * 15f
            val y = baseline + slope * (lx - x)
            Bounds(lx, y - 20f, lx + 12f, y)
        }

    @Test fun `two lines with words and i-dots`() {
        val strokes = ArrayList<Bounds>()
        strokes += word(100f, 200f, 4)      // 0..3
        strokes += word(190f, 202f, 3)      // 4..6, next word a bit lower
        strokes += Bounds(105f, 170f, 108f, 173f) // 7: i-dot above line 1
        strokes += word(100f, 250f, 5)      // 8..12 second line
        val lines = LineGrouper.group(strokes)
        assertEquals(2, lines.size)
        assertEquals(2, lines[0].words.size)
        assertTrue(7 in lines[0].strokes, "dot belongs to the first line")
        // The dot was written last but sits in the first word: writing order inside the word.
        assertEquals(listOf(0, 1, 2, 3, 7), lines[0].words[0])
        assertEquals((8..12).toList(), lines[1].strokes)
        assertEquals(1, lines[1].words.size)
    }

    @Test fun `sloping line stays one line`() {
        // Drifts 40 units up over 600 units: more than a line height overall.
        val strokes = (0 until 8).flatMap { w -> word(100f + w * 80f, 300f - w * 5.5f, 4) }
        val lines = LineGrouper.group(strokes)
        assertEquals(1, lines.size)
        assertEquals(8, lines[0].words.size)
    }

    @Test fun `lines come in reading order even when written out of order`() {
        val second = word(100f, 260f, 3)
        val first = word(100f, 210f, 3)
        val lines = LineGrouper.group(second + first)
        assertEquals(listOf(3, 4, 5), lines[0].strokes)
        assertEquals(listOf(0, 1, 2), lines[1].strokes)
        assertEquals(lines[0].block, lines[1].block)
    }

    @Test fun `two columns become two blocks`() {
        val left1 = word(100f, 200f, 4); val right1 = word(600f, 200f, 4)
        val left2 = word(100f, 250f, 4); val right2 = word(600f, 250f, 4)
        val lines = LineGrouper.group(left1 + right1 + left2 + right2)
        assertEquals(4, lines.size)
        assertEquals(2, lines.map { it.block }.distinct().size)
        // Left column is read first, both of its lines.
        assertEquals(lines[0].block, lines[1].block)
        assertTrue(lines[0].bounds.left < 200f && lines[1].bounds.left < 200f)
    }

    @Test fun `far apart paragraphs are separate blocks`() {
        val lines = LineGrouper.group(word(100f, 200f, 4) + word(100f, 600f, 4))
        assertEquals(listOf(0, 1), lines.map { it.block })
    }
}
