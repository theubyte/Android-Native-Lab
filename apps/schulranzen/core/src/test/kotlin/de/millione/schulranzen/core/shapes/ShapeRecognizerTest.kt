package de.millione.schulranzen.core.shapes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds pen strokes like a hand would: sampled every few units, with tremor. */
internal class Pen(seed: Int = 7, private val jitter: Float = 1.2f) {
    private val rnd = Random(seed)
    private val out = ArrayList<Float>()
    private var t = 0f

    private fun add(x: Float, y: Float) {
        out += x + (rnd.nextFloat() - 0.5f) * 2 * jitter
        out += y + (rnd.nextFloat() - 0.5f) * 2 * jitter
        out += t
        t += 8f
    }

    fun polyline(vararg xy: Float): Pen {
        for (i in 0 until xy.size / 2 - 1) {
            val ax = xy[i * 2]; val ay = xy[i * 2 + 1]; val bx = xy[i * 2 + 2]; val by = xy[i * 2 + 3]
            val n = maxOf(2, (hypot(bx - ax, by - ay) / 4f).toInt())
            for (k in 0 until n) add(ax + (bx - ax) * k / n, ay + (by - ay) * k / n)
        }
        add(xy[xy.size - 2], xy[xy.size - 1])
        return this
    }

    /** Ellipse from angle [from] sweeping [sweep] radians (more than 2π overshoots). */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, from: Double = 0.3, sweep: Double = 2 * PI, rot: Double = 0.0): Pen {
        val n = ((rx + ry) * sweep / 6).toInt().coerceAtLeast(24)
        for (k in 0..n) {
            val a = from + sweep * k / n
            val u = rx * cos(a); val v = ry * sin(a)
            add((cx + u * cos(rot) - v * sin(rot)).toFloat(), (cy + u * sin(rot) + v * cos(rot)).toFloat())
        }
        return this
    }

    fun hold(ms: Float): Pen {
        val x = out[out.size - 3]; val y = out[out.size - 2]
        var waited = 0f
        while (waited < ms) { out += x; out += y; out += t; t += 16f; waited += 16f }
        return this
    }

    fun build() = out.toFloatArray()
}

class ShapeRecognizerTest {
    private fun rec(p: FloatArray) = ShapeRecognizer.recognize(p)

    @Test fun `shaky horizontal line becomes a level line`() {
        val s = assertNotNull(rec(Pen().polyline(100f, 300f, 200f, 304f, 400f, 307f).build()))
        assertEquals(ShapeKind.LINE, s.kind)
        assertEquals(s.corners[1], s.corners[3], 0.01f) // snapped level
        assertEquals(300f, abs(s.corners[2] - s.corners[0]), 8f)
    }

    @Test fun `slanted line keeps its angle`() {
        val a = 30.0 * PI / 180
        val s = assertNotNull(rec(Pen().polyline(100f, 100f, (100 + 250 * cos(a)).toFloat(), (100 + 250 * sin(a)).toFloat()).build()))
        assertEquals(ShapeKind.LINE, s.kind)
        val deg = Math.toDegrees(kotlin.math.atan2((s.corners[3] - s.corners[1]).toDouble(), (s.corners[2] - s.corners[0]).toDouble()))
        assertEquals(30.0, deg, 2.0)
    }

    @Test fun `hook at lift-off does not spoil a line`() {
        val s = assertNotNull(rec(Pen().polyline(100f, 200f, 380f, 200f, 384f, 208f).build()))
        assertEquals(ShapeKind.LINE, s.kind)
    }

    @Test fun `short dash stays handwriting`() {
        assertNull(rec(Pen().polyline(100f, 200f, 130f, 200f).build()))
    }

    @Test fun `circle with overshoot`() {
        val s = assertNotNull(rec(Pen().ellipse(300f, 300f, 80f, 76f, sweep = 2 * PI + 0.5).build()))
        assertEquals(ShapeKind.CIRCLE, s.kind)
        assertEquals(78f, s.radiusX, 5f)
        assertEquals(300f, s.corners[0], 5f); assertEquals(300f, s.corners[1], 5f)
    }

    @Test fun `circle with a small gap`() {
        val s = assertNotNull(rec(Pen().ellipse(300f, 300f, 60f, 60f, sweep = 2 * PI - 0.5).build()))
        assertEquals(ShapeKind.CIRCLE, s.kind)
    }

    @Test fun `flat ellipse`() {
        val s = assertNotNull(rec(Pen().ellipse(300f, 300f, 150f, 60f).build()))
        assertEquals(ShapeKind.ELLIPSE, s.kind)
        assertEquals(150f, s.radiusX, 10f); assertEquals(60f, s.radiusY, 8f)
        assertEquals(0f, s.rotation % 180f, 0.01f)
    }

    @Test fun `square drawn by hand`() {
        val s = assertNotNull(rec(Pen().polyline(100f, 100f, 202f, 98f, 204f, 199f, 101f, 203f, 99f, 104f).build()))
        assertEquals(ShapeKind.SQUARE, s.kind)
        assertEquals(8, s.corners.size)
        // Snapped upright: top edge level.
        assertEquals(s.corners[1], s.corners[3], 0.5f)
    }

    @Test fun `rotated rectangle`() {
        val a = 20.0 * PI / 180
        fun p(u: Float, v: Float) = floatArrayOf((300 + u * cos(a) - v * sin(a)).toFloat(), (300 + u * sin(a) + v * cos(a)).toFloat())
        val c = listOf(p(-120f, -50f), p(120f, -50f), p(120f, 50f), p(-120f, 50f), p(-120f, -50f))
        val s = assertNotNull(rec(Pen().polyline(*c.flatMap { it.toList() }.toFloatArray()).build()))
        assertEquals(ShapeKind.RECTANGLE, s.kind)
        assertEquals(20f, s.rotation, 3f)
    }

    @Test fun `triangle`() {
        val s = assertNotNull(rec(Pen().polyline(100f, 300f, 200f, 120f, 300f, 300f, 102f, 298f).build()))
        assertEquals(ShapeKind.TRIANGLE, s.kind)
        assertEquals(6, s.corners.size)
    }

    @Test fun `pentagon`() {
        val pts = (0..5).flatMap { k -> val a = -PI / 2 + 2 * PI * k / 5; listOf((300 + 120 * cos(a)).toFloat(), (300 + 120 * sin(a)).toFloat()) }
        val s = assertNotNull(rec(Pen().polyline(*pts.toFloatArray()).build()))
        assertEquals(ShapeKind.POLYGON, s.kind)
        assertEquals(10, s.corners.size)
    }

    @Test fun `one-stroke arrow with head drawn there and back`() {
        val s = assertNotNull(rec(Pen().polyline(100f, 300f, 400f, 300f, 370f, 280f, 400f, 300f, 370f, 320f).build()))
        assertEquals(ShapeKind.ARROW, s.kind)
        assertEquals(400f, s.corners[2], 6f); assertEquals(300f, s.corners[3], 6f)
        assertEquals(2, s.paths.size)
    }

    @Test fun `one-stroke arrow with closed head`() {
        val s = assertNotNull(rec(Pen().polyline(100f, 100f, 100f, 400f, 80f, 370f, 120f, 370f, 100f, 400f).build()))
        assertEquals(ShapeKind.ARROW, s.kind)
        assertEquals(400f, s.corners[3], 6f)
    }

    @Test fun `arrow head drawn first`() {
        val s = assertNotNull(rec(Pen().polyline(370f, 280f, 400f, 300f, 370f, 320f, 400f, 300f, 100f, 300f).build()))
        assertEquals(ShapeKind.ARROW, s.kind)
        assertEquals(400f, s.corners[2], 6f)
    }

    @Test fun `two-stroke arrow`() {
        val shaft = Pen().polyline(100f, 300f, 400f, 302f).build()
        val head = Pen(3).polyline(372f, 282f, 401f, 301f, 371f, 322f).build()
        val s = assertNotNull(ShapeRecognizer.recognizeArrow(listOf(shaft, head)))
        assertEquals(ShapeKind.ARROW, s.kind)
        assertEquals(400f, s.corners[2], 6f)
        // Two lines without a head are no arrow.
        assertNull(ShapeRecognizer.recognizeArrow(listOf(shaft, Pen(4).polyline(100f, 350f, 400f, 350f).build())))
    }

    @Test fun `cursive m-chain is not a line`() {
        val xy = ArrayList<Float>()
        for (k in 0..10) { xy += 100f + k * 18f; xy += 220f; xy += 109f + k * 18f; xy += 205f }
        assertNull(rec(Pen(jitter = 0.5f).polyline(*xy.toFloatArray()).build()))
    }

    @Test fun `written letter o is not a circle`() {
        assertNull(rec(Pen(jitter = 0.6f).ellipse(300f, 300f, 9f, 12f).build()))
    }

    @Test fun `open curves and scribbles are no shape`() {
        // "S"-curve
        val s = Pen().ellipse(300f, 250f, 50f, 50f, from = 0.0, sweep = -1.5 * PI).build()
        assertNull(rec(s + Pen(9).ellipse(300f, 350f, 50f, 50f, from = -PI / 2, sweep = 1.5 * PI).build().shifted(0f)))
        // "U"
        assertNull(rec(Pen().polyline(100f, 100f, 100f, 300f, 300f, 300f, 300f, 100f).build()))
        // zig-zag
        assertNull(rec(Pen().polyline(100f, 100f, 200f, 200f, 300f, 100f, 400f, 200f, 500f, 100f).build()))
        // spiral
        val sp = ArrayList<Float>(); var t = 0f
        for (k in 0..300) { val a = k * 0.05; val r = 20 + k * 0.4; sp += (300 + r * cos(a)).toFloat(); sp += (300 + r * sin(a)).toFloat(); sp += t; t += 8f }
        assertNull(rec(sp.toFloatArray()))
    }

    @Test fun `hold at the end is detected from timestamps`() {
        val held = Pen().polyline(100f, 100f, 300f, 100f).hold(500f).build()
        assertTrue(ShapeRecognizer.heldAtEnd(held))
        assertFalse(ShapeRecognizer.heldAtEnd(Pen().polyline(100f, 100f, 300f, 100f).build()))
        // The resting points do not disturb recognition.
        assertEquals(ShapeKind.LINE, rec(held)?.kind)
    }

    @Test fun `loop around a calculation encloses it`() {
        val calc = listOf(Bounds(220f, 280f, 250f, 320f), Bounds(260f, 290f, 280f, 310f), Bounds(290f, 280f, 320f, 320f))
        val outside = Bounds(500f, 280f, 530f, 320f)
        val loop = Pen().ellipse(270f, 300f, 90f, 50f, sweep = 2 * PI - 0.6).build()
        assertEquals(listOf(0, 1, 2), ShapeRecognizer.enclosedBy(loop, calc + outside))
        assertTrue(ShapeRecognizer.isEnclosingLoop(loop, calc))
        // A loop beside the strokes encloses nothing; half a loop neither.
        assertFalse(ShapeRecognizer.isEnclosingLoop(Pen().ellipse(700f, 300f, 90f, 50f).build(), calc))
        assertFalse(ShapeRecognizer.isEnclosingLoop(Pen().ellipse(270f, 300f, 90f, 50f, sweep = PI * 1.1).build(), calc))
    }

    @Test fun `circling twice counts, a scribble through it does not`() {
        val calc = listOf(Bounds(250f, 290f, 290f, 310f))
        val loop = Pen().ellipse(270f, 300f, 90f, 50f, sweep = 4 * PI).build()
        // Two full turns: the overshoot cut keeps one turn, so it still counts.
        assertTrue(ShapeRecognizer.isEnclosingLoop(loop, calc))
        val spiral = ArrayList<Float>(); var t = 0f
        // A scribble that starts in the middle runs through the strokes.
        for (k in 0..400) { val a = k * 0.05; val r = 2 + k * 0.3; spiral += (270 + r * cos(a)).toFloat(); spiral += (300 + r * sin(a)).toFloat(); spiral += t; t += 8f }
        assertFalse(ShapeRecognizer.isEnclosingLoop(spiral.toFloatArray(), calc))
    }

    private fun FloatArray.shifted(dt: Float): FloatArray {
        val c = copyOf(); var i = 2
        while (i < c.size) { c[i] += 10_000f + dt; i += 3 }
        return c
    }
}
