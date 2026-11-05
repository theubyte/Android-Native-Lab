package de.millione.schulranzen.core.shapes

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The thresholds at their edges, over several pens: shapes of the smallest
 * accepted size still count, written letters do not.
 */
class ShapeThresholdTest {
    private val seeds = 1..40

    private fun kindOf(seed: Int, draw: Pen.() -> Pen) = ShapeRecognizer.recognize(Pen(seed).draw().build())?.kind

    private fun assertAll(expected: Set<ShapeKind?>, draw: Pen.() -> Pen) {
        for (s in seeds) {
            val k = kindOf(s, draw)
            assertTrue(k in expected, "Stift $s: $k statt $expected")
        }
    }

    @Test fun smallestLineCounts() = assertAll(setOf(ShapeKind.LINE)) { polyline(100f, 100f, 170f, 100f) }

    @Test fun smallestSquareCounts() = assertAll(setOf(ShapeKind.SQUARE, ShapeKind.RECTANGLE)) {
        polyline(100f, 100f, 170f, 100f, 170f, 170f, 100f, 170f, 100f, 100f)
    }

    @Test fun smallestCircleCounts() = assertAll(setOf(ShapeKind.CIRCLE)) { ellipse(200f, 200f, 35f, 35f) }

    /** A fourth of these came back as "no shape": the closing step decided how often the loop had turned. */
    @Test fun cleanSquareIsAlwaysASquare() = assertAll(setOf(ShapeKind.SQUARE)) {
        polyline(100f, 100f, 400f, 100f, 400f, 400f, 100f, 400f, 100f, 100f)
    }

    @Test fun bigCircleIsAlwaysACircle() = assertAll(setOf(ShapeKind.CIRCLE)) { ellipse(500f, 500f, 220f, 220f) }

    @Test fun triangleIsAlwaysATriangle() = assertAll(setOf(ShapeKind.TRIANGLE)) {
        polyline(100f, 400f, 300f, 100f, 500f, 400f, 100f, 400f)
    }

    @Test fun thinRectangleIsARectangle() = assertAll(setOf(ShapeKind.RECTANGLE)) {
        polyline(100f, 100f, 500f, 100f, 500f, 170f, 100f, 170f, 100f, 100f)
    }

    @Test fun circleWithASmallGapStillCounts() = assertAll(setOf(ShapeKind.CIRCLE)) {
        ellipse(300f, 300f, 90f, 90f, sweep = 2 * PI * 0.94)
    }

    @Test fun halfCircleIsNoCircle() = assertAll(setOf(null)) { ellipse(300f, 300f, 90f, 90f, sweep = PI) }

    @Test fun writtenLoopStaysWriting() = assertAll(setOf(null)) { ellipse(200f, 200f, 12f, 16f) }

    @Test fun writtenOneStaysWriting() = assertAll(setOf(null)) { polyline(100f, 100f, 100f, 135f) }

    @Test fun bigZIsNoShape() = assertAll(setOf(null)) { polyline(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f) }

    @Test fun bigCheckMarkIsNoShape() = assertAll(setOf(null)) { polyline(100f, 200f, 150f, 280f, 320f, 90f) }

    @Test fun waveIsNoLine() = assertAll(setOf(null)) {
        polyline(100f, 200f, 160f, 170f, 220f, 230f, 280f, 170f, 340f, 230f, 400f, 200f)
    }

    @Test fun figureEightIsNoShape() = assertAll(setOf(null)) {
        ellipse(300f, 200f, 60f, 60f, from = PI / 2, sweep = 2 * PI).ellipse(300f, 320f, 60f, 60f, from = -PI / 2, sweep = -2 * PI)
    }

    @Test fun lineGoesThroughTheHeldEndWithoutMovingIt() {
        val s = ShapeRecognizer.recognize(Pen(3).polyline(100f, 300f, 500f, 303f).hold(600f).build())
        assertEquals(ShapeKind.LINE, s?.kind)
        assertTrue(s!!.corners[1] == s.corners[3], "Fast waagerecht wird waagerecht")
    }

    @Test fun nothingAtAll() {
        assertNull(ShapeRecognizer.recognize(FloatArray(0)))
        assertNull(ShapeRecognizer.recognize(floatArrayOf(1f, 1f, 0f, 1f, 1f, 8f, 1f, 1f, 16f, 1f, 1f, 24f)))
    }
}
