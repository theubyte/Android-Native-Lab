package de.millione.schulranzen.core.shapes

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in page units (y grows downwards). */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun union(o: Bounds) = Bounds(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    companion object {
        /** Bounds of a stroke given as x, y, t triples (or x, y pairs with [stride] 2); null when empty. */
        fun ofStroke(points: FloatArray, stride: Int = 3): Bounds? {
            if (points.size < 2) return null
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < points.size) {
                x0 = min(x0, points[i]); x1 = max(x1, points[i])
                y0 = min(y0, points[i + 1]); y1 = max(y1, points[i + 1])
                i += stride
            }
            return Bounds(x0, y0, x1, y1)
        }

        fun unionOf(list: Iterable<Bounds>): Bounds? = list.reduceOrNull { a, b -> a.union(b) }
    }
}

/** A point of a pen path; [t] in milliseconds (0 when unknown). */
internal data class Pt(val x: Float, val y: Float, val t: Float = 0f)

/** Small plane-geometry toolbox shared by the shape and enclosure detectors. */
internal object PathMath {
    fun parse(points: FloatArray, stride: Int = 3): List<Pt> {
        val out = ArrayList<Pt>(points.size / stride)
        var i = 0
        while (i + 1 < points.size) {
            val p = Pt(points[i], points[i + 1], if (stride >= 3 && i + 2 < points.size) points[i + 2] else 0f)
            // Digitisers repeat points while the pen rests; duplicates only skew averages.
            if (out.isEmpty() || out.last().x != p.x || out.last().y != p.y) out += p
            i += stride
        }
        return out
    }

    fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    fun length(p: List<Pt>): Float {
        var l = 0f
        for (i in 1 until p.size) l += dist(p[i - 1], p[i])
        return l
    }

    fun bounds(p: List<Pt>): Bounds {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (q in p) { x0 = min(x0, q.x); y0 = min(y0, q.y); x1 = max(x1, q.x); y1 = max(y1, q.y) }
        return Bounds(x0, y0, x1, y1)
    }

    /** [n] points evenly spaced along the path (arc length), so fits are not biased by pen speed. */
    fun resample(p: List<Pt>, n: Int): List<Pt> {
        if (p.size < 2 || n < 2) return p
        val total = length(p)
        if (total == 0f) return List(n) { p[0] }
        val step = total / (n - 1)
        val out = ArrayList<Pt>(n)
        out += p[0]
        var acc = 0f
        var i = 1
        var prev = p[0]
        while (i < p.size && out.size < n - 1) {
            val cur = p[i]
            val d = dist(prev, cur)
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                val q = Pt(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t, prev.t + (cur.t - prev.t) * t)
                out += q
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out += p.last()
        return out
    }

    fun pointSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Ramer–Douglas–Peucker: the corners of a polyline, dropping wobble below [eps]. */
    fun simplify(p: List<Pt>, eps: Float): List<Pt> {
        if (p.size < 3) return p
        val keep = BooleanArray(p.size)
        keep[0] = true; keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to p.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1; var bestD = eps
            for (i in a + 1 until b) {
                val d = pointSegment(p[i], p[a], p[b])
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) { keep[best] = true; stack.addLast(a to best); stack.addLast(best to b) }
        }
        return p.filterIndexed { i, _ -> keep[i] }
    }

    /** Even-odd point in polygon test. */
    fun inside(poly: List<Pt>, x: Float, y: Float): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    /** Non-zero winding test: a loop traced twice still encloses its inside. */
    fun encloses(poly: List<Pt>, x: Float, y: Float): Boolean {
        var w = 0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val cross = (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y)
            if (a.y <= y) { if (b.y > y && cross > 0) w++ }
            else if (b.y <= y && cross < 0) w--
        }
        return w != 0
    }

    fun area(poly: List<Pt>): Float {
        var s = 0f
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    /** Direction of a→b in radians. */
    fun angle(a: Pt, b: Pt) = atan2(b.y - a.y, b.x - a.x)

    /** Points along a polyline no further than [step] apart, as x/y pairs (for redrawing as ink). */
    fun densify(vertices: List<Pt>, step: Float = 3f): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]; val b = vertices[i + 1]
            val n = max(1, (dist(a, b) / step).toInt())
            for (k in 0 until n) {
                val t = k / n.toFloat()
                out += a.x + (b.x - a.x) * t; out += a.y + (b.y - a.y) * t
            }
        }
        vertices.lastOrNull()?.let { out += it.x; out += it.y }
        return out.toFloatArray()
    }
}
