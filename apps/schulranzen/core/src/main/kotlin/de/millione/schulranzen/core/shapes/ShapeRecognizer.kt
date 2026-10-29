package de.millione.schulranzen.core.shapes

import de.millione.schulranzen.core.shapes.PathMath.dist
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind { LINE, ARROW, CIRCLE, ELLIPSE, RECTANGLE, SQUARE, TRIANGLE, POLYGON }

/**
 * An idealised shape that replaces a hand-drawn stroke.
 *
 * [paths] are the strokes to draw instead (x/y pairs, page units, densely
 * sampled so they render like ink); an arrow has two (shaft and head), every
 * other shape one. [corners] are the key points as x/y pairs: line ends;
 * arrow tail, tip and the two barb ends; polygon corners in drawing order;
 * for circles and ellipses the centre. [radiusX]/[radiusY]/[rotation]
 * (degrees) describe ellipses. [confidence] is 0.5 … 1 for accepted shapes.
 */
class RecognizedShape(
    val kind: ShapeKind,
    val paths: List<FloatArray>,
    val corners: FloatArray,
    val confidence: Float,
    val radiusX: Float = 0f,
    val radiusY: Float = 0f,
    val rotation: Float = 0f,
) {
    val bounds: Bounds get() = paths.mapNotNull { Bounds.ofStroke(it, 2) }.reduce { a, b -> a.union(b) }

    /** The paths as lists of [x, y] points, the form `PageInk.batchOf` takes. */
    fun pointLists(): List<List<FloatArray>> = paths.map { p -> List(p.size / 2) { floatArrayOf(p[it * 2], p[it * 2 + 1]) } }

    override fun toString() = "RecognizedShape($kind, conf=${"%.2f".format(confidence)}, corners=${corners.contentToString()})"
}

/**
 * Tuning for [ShapeRecognizer]. Sizes are page units (A4 = 1000 wide,
 * about 4.8 units per mm). The minimum sizes keep handwriting from turning
 * into shapes: a written "l", "1", "o" or "0" stays below them.
 */
data class ShapeOptions(
    val minLineLength: Float = 50f,
    val minClosedSize: Float = 45f,
    val minConfidence: Float = 0.55f,
    /** Lines and edges within this many degrees of a multiple of 45° (rectangles: 90°) are straightened. */
    val snapDegrees: Float = 5f,
)

/**
 * Turns one pen stroke into an ideal geometric shape, or says it is none.
 *
 * Works on arc-length resampled points, so pen speed does not matter, and
 * uses relative tolerances, so tremor scales with the drawing. Decisions are
 * deliberately strict; "no shape" is always the safe answer because the
 * stroke then simply stays as written. Callers should still only ask when the
 * user wants shapes (shape tool, or the pen held still at the end of a
 * stroke, see [heldAtEnd]).
 */
object ShapeRecognizer {

    /** Recognises a stroke given as x, y, t triples. Null when it is no clear shape. */
    fun recognize(points: FloatArray, options: ShapeOptions = ShapeOptions()): RecognizedShape? {
        val raw = PathMath.parse(points)
        if (raw.size < 4) return null
        val len = PathMath.length(raw)
        val box = PathMath.bounds(raw)
        val size = max(box.width, box.height)
        if (size < min(options.minLineLength, options.minClosedSize)) return null
        val p = PathMath.resample(raw, (len / 2.5f).roundToInt().coerceIn(48, 192))

        val shape = closeLoop(p, size, len, tolerance = 0.25f)?.let { loop ->
            // The whole stroke, overshoot included, may not wind much more than once (spirals).
            val wound = abs(pathTurn(p)) / (2 * PI) <= 1.45
            if (size >= options.minClosedSize && wound) closedShape(loop, size, options) else null
        } ?: line(p, options) ?: arrow(p, size, len, options)
        return shape?.takeIf { it.confidence >= options.minConfidence }
    }

    /**
     * An arrow drawn in two or three strokes: a straight shaft plus a "V"
     * head, or plus two separate barbs. Null when the strokes do not form one.
     */
    fun recognizeArrow(strokes: List<FloatArray>, options: ShapeOptions = ShapeOptions()): RecognizedShape? {
        if (strokes.size !in 2..3) return null
        val parsed = strokes.map { PathMath.parse(it) }.filter { it.size >= 2 }
        if (parsed.size != strokes.size) return null
        // The shaft is the longest stroke and must be straight on its own.
        val shaftIdx = parsed.indices.maxBy { PathMath.length(parsed[it]) }
        val shaftPts = PathMath.resample(parsed[shaftIdx], 64)
        val shaft = line(shaftPts, options, snap = false) ?: return null
        val a = Pt(shaft.corners[0], shaft.corners[1]); val b = Pt(shaft.corners[2], shaft.corners[3])
        val shaftLen = dist(a, b)
        val head = parsed.filterIndexed { i, _ -> i != shaftIdx }.flatMap { s ->
            val sb = PathMath.bounds(s)
            PathMath.simplify(s, max(1.5f, 0.06f * max(sb.width, sb.height)))
        }
        val nearA = head.minOf { dist(it, a) }; val nearB = head.minOf { dist(it, b) }
        val (tail, tip) = if (nearB <= nearA) a to b else b to a
        if (min(nearA, nearB) > 0.2f * shaftLen) return null
        return arrowFrom(tail, tip, head, shaft.confidence, options)
    }

    /**
     * True when the pen rested at the end of the stroke for at least
     * [holdMs] within [radius] units: the "hold to straighten" gesture.
     * Needs real timestamps (t) in the points.
     */
    fun heldAtEnd(points: FloatArray, holdMs: Float = 450f, radius: Float = 5f): Boolean {
        val n = points.size / 3
        if (n < 2) return false
        val lx = points[(n - 1) * 3]; val ly = points[(n - 1) * 3 + 1]; val lt = points[(n - 1) * 3 + 2]
        var i = n - 1
        while (i > 0 && hypot(points[(i - 1) * 3] - lx, points[(i - 1) * 3 + 1] - ly) <= radius) i--
        return lt - points[i * 3 + 2] >= holdMs
    }

    /**
     * Indices of [others] (bounds of other strokes on the page) that the
     * stroke [points] (x, y, t) encircles: a single closed loop around them,
     * gaps up to a third of its size allowed. Used to compute circled
     * calculations automatically. The loop's own bounds must not be in [others].
     */
    fun enclosedBy(points: FloatArray, others: List<Bounds>, minSize: Float = 30f): List<Int> {
        val raw = PathMath.parse(points)
        if (raw.size < 8 || others.isEmpty()) return emptyList()
        val len = PathMath.length(raw)
        val box = PathMath.bounds(raw)
        val size = max(box.width, box.height)
        if (size < minSize || min(box.width, box.height) < minSize / 3) return emptyList()
        val p = PathMath.resample(raw, 128)
        val loop = closeLoop(p, size, len, tolerance = 0.33f) ?: return emptyList()
        // One turn around, or two when traced twice: rules out spirals, scribbles and figure eights.
        val turns = abs(ringTurn(loop)) / (2 * PI)
        if (!(turns in 0.8..1.35 || turns in 1.7..2.35)) return emptyList()
        if (PathMath.area(loop) < 0.3f * box.width * box.height) return emptyList()
        return others.indices.filter { i ->
            val o = others[i]
            if (o.width >= box.width || o.height >= box.height) return@filter false
            if (!PathMath.encloses(loop, o.centerX, o.centerY)) return@filter false
            // A loop that runs through the strokes (a spiral, a crossed-out word) does not circle them.
            val ix = o.width * 0.2f; val iy = o.height * 0.2f
            if (raw.any { it.x > o.left + ix && it.x < o.right - ix && it.y > o.top + iy && it.y < o.bottom - iy }) return@filter false
            val dx = o.width * 0.15f; val dy = o.height * 0.15f
            val corners = listOf(o.left + dx to o.top + dy, o.right - dx to o.top + dy, o.left + dx to o.bottom - dy, o.right - dx to o.bottom - dy)
            corners.count { (x, y) -> PathMath.encloses(loop, x, y) } >= 3
        }
    }

    /** True when the stroke is a closed loop around at least one of [otherStrokeBounds]. */
    fun isEnclosingLoop(points: FloatArray, otherStrokeBounds: List<Bounds>): Boolean =
        enclosedBy(points, otherStrokeBounds).isNotEmpty()

    // ---------------------------------------------------------------- closed

    /**
     * The path as a closed loop with any overshoot cut off, or null when the
     * ends do not meet within [tolerance] of the size.
     */
    private fun closeLoop(p: List<Pt>, size: Float, len: Float, tolerance: Float): List<Pt>? {
        if (len < 1.6f * size || p.size < 12) return null
        // Overshoot at the end: the point in the last 40 % nearest to the start.
        var end = p.size - 1; var best = dist(p.last(), p[0])
        for (k in (p.size * 0.6f).toInt() until p.size) {
            val d = dist(p[k], p[0]); if (d < best) { best = d; end = k }
        }
        // Overshoot at the start: the point in the first 25 % nearest to that end.
        var start = 0
        for (k in 1 until (p.size * 0.25f).toInt()) {
            val d = dist(p[k], p[end]); if (d < best) { best = d; start = k }
        }
        if (best > tolerance * size) return null
        val loop = p.subList(start, end + 1)
        return if (PathMath.length(loop) >= 1.6f * size) loop else null
    }

    private class Fit(val err: Float, val shape: RecognizedShape)

    private fun closedShape(loop: List<Pt>, size: Float, o: ShapeOptions): RecognizedShape? {
        // A shape goes round once; spirals and figure eights do not.
        val turns = abs(ringTurn(loop)) / (2 * PI)
        if (turns !in 0.8..1.3) return null
        val ell = fitEllipse(loop, size, o)
        val poly = fitPolygon(loop, size, o)
        val ellOk = ell != null && ell.err < ELLIPSE_LIMIT
        val polyOk = poly != null && poly.err < POLYGON_LIMIT
        return when {
            polyOk && (!ellOk || poly!!.err < 0.6f * ell!!.err) -> poly!!.shape
            ellOk -> ell!!.shape
            else -> null
        }
    }

    private const val ELLIPSE_LIMIT = 0.045f
    private const val POLYGON_LIMIT = 0.03f

    private fun fitEllipse(loop: List<Pt>, size: Float, o: ShapeOptions): Fit? {
        val n = loop.size
        val cx = loop.sumOf { it.x.toDouble() } / n; val cy = loop.sumOf { it.y.toDouble() } / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in loop) { val dx = q.x - cx; val dy = q.y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        var theta = 0.5 * atan2(2 * sxy, sxx - syy)
        // Least squares for u²·A + v²·B = 1 in the principal frame.
        fun fit(th: Double): Pair<Double, Double>? {
            val c = cos(th); val s = sin(th)
            var uu = 0.0; var vv = 0.0; var uv = 0.0; var u2 = 0.0; var v2 = 0.0
            for (q in loop) {
                val dx = q.x - cx; val dy = q.y - cy
                val u = dx * c + dy * s; val v = -dx * s + dy * c
                val a = u * u; val b = v * v
                uu += a * a; vv += b * b; uv += a * b; u2 += a; v2 += b
            }
            val det = uu * vv - uv * uv
            if (abs(det) < 1e-12) return null
            val aa = (u2 * vv - v2 * uv) / det; val bb = (uu * v2 - uv * u2) / det
            return if (aa > 0 && bb > 0) aa to bb else null
        }
        val (A, B) = fit(theta) ?: return null
        var rx = (1 / sqrt(A)).toFloat(); var ry = (1 / sqrt(B)).toFloat()
        // Mean distance along the ray from the centre, relative to the size.
        var err = 0.0
        val c = cos(theta); val s = sin(theta)
        for (q in loop) {
            val dx = q.x - cx; val dy = q.y - cy
            val u = dx * c + dy * s; val v = -dx * s + dy * c
            val r = sqrt(u * u * A + v * v * B)
            if (r > 1e-9) err += abs(r - 1) / r * hypot(dx, dy)
        }
        val e = (err / n / size).toFloat()

        val circle = min(rx, ry) / max(rx, ry) >= 0.86f
        if (circle) { val r = (rx + ry) / 2; rx = r; ry = r; theta = 0.0 }
        else {
            // Ellipses are nearly always meant upright or level.
            val deg = Math.toDegrees(theta)
            val near = (deg / 90).roundToInt() * 90.0
            if (abs(deg - near) <= o.snapDegrees * 2) theta = Math.toRadians(near)
        }
        val ct = cos(theta).toFloat(); val st = sin(theta).toFloat()
        val steps = max(48, (PI * (rx + ry) / 3).toInt())
        val pts = (0..steps).map { k ->
            val a = 2 * PI * k / steps
            val u = rx * cos(a).toFloat(); val v = ry * sin(a).toFloat()
            Pt(cx.toFloat() + u * ct - v * st, cy.toFloat() + u * st + v * ct)
        }
        val path = FloatArray(pts.size * 2).also { arr -> pts.forEachIndexed { i, q -> arr[i * 2] = q.x; arr[i * 2 + 1] = q.y } }
        val conf = 0.5f + 0.5f * (1 - e / ELLIPSE_LIMIT).coerceIn(0f, 1f)
        return Fit(e, RecognizedShape(
            if (circle) ShapeKind.CIRCLE else ShapeKind.ELLIPSE, listOf(path),
            floatArrayOf(cx.toFloat(), cy.toFloat()), conf, rx, ry, Math.toDegrees(theta).toFloat(),
        ))
    }

    private fun fitPolygon(loop: List<Pt>, size: Float, o: ShapeOptions): Fit? {
        val n = loop.size
        val cx = loop.sumOf { it.x.toDouble() }.toFloat() / n; val cy = loop.sumOf { it.y.toDouble() }.toFloat() / n
        // Start at the point farthest from the centre: almost surely a corner.
        val start = loop.indices.maxBy { hypot(loop[it].x - cx, loop[it].y - cy) }
        val ring = loop.subList(0, n - 1)
        val rotated = ring.drop(start) + ring.take(start) + ring[start % ring.size]
        var v = PathMath.simplify(rotated, 0.04f * size).dropLast(1).toMutableList()
        // Tidy: merge corners that sit together, drop corners that are barely a bend.
        var changed = true
        while (changed && v.size >= 3) {
            changed = false
            for (i in v.indices) {
                val next = v[(i + 1) % v.size]
                if (dist(v[i], next) < 0.1f * size) { v.removeAt((i + 1) % v.size); changed = true; break }
            }
            if (changed) continue
            for (i in v.indices) {
                if (turnAt(v[(i - 1 + v.size) % v.size], v[i], v[(i + 1) % v.size]).let { abs(it) } < Math.toRadians(25.0)) {
                    v.removeAt(i); changed = true; break
                }
            }
        }
        if (v.size !in 3..8) return null
        var err = 0.0
        for (q in loop) {
            var d = Float.MAX_VALUE
            for (i in v.indices) d = min(d, PathMath.pointSegment(q, v[i], v[(i + 1) % v.size]))
            err += d
        }
        val e = (err / n / size).toFloat()
        val conf = 0.5f + 0.5f * (1 - e / POLYGON_LIMIT).coerceIn(0f, 1f)

        if (v.size == 4) {
            val rightAngles = v.indices.all { i ->
                val turn = abs(turnAt(v[(i + 3) % 4], v[i], v[(i + 1) % 4]))
                abs(Math.toDegrees(turn.toDouble()) - 90) <= 20
            }
            if (rightAngles) return Fit(e, rectangle(v, conf, o))
        }
        val corners = snapEdges(v, o.snapDegrees)
        val path = PathMath.densify(corners + corners[0])
        val kind = if (v.size == 3) ShapeKind.TRIANGLE else ShapeKind.POLYGON
        return Fit(e, RecognizedShape(kind, listOf(path), flat(corners), conf))
    }

    /** An exact (possibly rotated) rectangle through four rough corners. */
    private fun rectangle(v: List<Pt>, conf: Float, o: ShapeOptions): RecognizedShape {
        // Mean edge direction modulo 90°, averaged on the 4θ circle.
        var sx = 0.0; var sy = 0.0
        for (i in 0 until 4) {
            val a = v[i]; val b = v[(i + 1) % 4]
            val w = dist(a, b).toDouble(); val ang = PathMath.angle(a, b).toDouble()
            sx += w * cos(4 * ang); sy += w * sin(4 * ang)
        }
        var theta = atan2(sy, sx) / 4
        if (abs(Math.toDegrees(theta)) <= o.snapDegrees + 1) theta = 0.0
        val c = cos(theta).toFloat(); val s = sin(theta).toFloat()
        val cx = v.map { it.x }.average().toFloat(); val cy = v.map { it.y }.average().toFloat()
        val us = v.map { (it.x - cx) * c + (it.y - cy) * s }.sorted()
        val vs = v.map { -(it.x - cx) * s + (it.y - cy) * c }.sorted()
        var l = (us[0] + us[1]) / 2; var r = (us[2] + us[3]) / 2
        var t = (vs[0] + vs[1]) / 2; var b = (vs[2] + vs[3]) / 2
        val w = r - l; val h = b - t
        val square = min(w, h) / max(w, h) >= 0.9f
        if (square) {
            val m = (w + h) / 2; val mu = (l + r) / 2; val mv = (t + b) / 2
            l = mu - m / 2; r = mu + m / 2; t = mv - m / 2; b = mv + m / 2
        }
        fun back(u: Float, vv: Float) = Pt(cx + u * c - vv * s, cy + u * s + vv * c)
        val corners = listOf(back(l, t), back(r, t), back(r, b), back(l, b))
        return RecognizedShape(
            if (square) ShapeKind.SQUARE else ShapeKind.RECTANGLE,
            listOf(PathMath.densify(corners + corners[0])), flat(corners), conf,
            rotation = Math.toDegrees(theta).toFloat(),
        )
    }

    /** Makes nearly level or upright edges exactly so. */
    private fun snapEdges(v: List<Pt>, snapDeg: Float): List<Pt> {
        val out = v.toMutableList()
        for (i in out.indices) {
            val j = (i + 1) % out.size
            val a = out[i]; val b = out[j]
            val deg = Math.toDegrees(PathMath.angle(a, b).toDouble())
            val near = (deg / 90).roundToInt() * 90.0
            if (abs(deg - near) > snapDeg) continue
            if (abs(near) % 180.0 == 0.0) { val y = (a.y + b.y) / 2; out[i] = a.copy(y = y); out[j] = b.copy(y = y) }
            else { val x = (a.x + b.x) / 2; out[i] = a.copy(x = x); out[j] = b.copy(x = x) }
        }
        return out
    }

    // ------------------------------------------------------------------ open

    private fun line(p: List<Pt>, o: ShapeOptions, snap: Boolean = true): RecognizedShape? {
        val first = p.first(); val last = p.last()
        if (dist(first, last) < o.minLineLength) return null
        // Ignore the little hooks pens leave at touch-down and lift-off.
        val cut = (p.size * 0.05f).toInt()
        val core = p.subList(cut, p.size - cut)
        val n = core.size
        val cx = core.sumOf { it.x.toDouble() } / n; val cy = core.sumOf { it.y.toDouble() } / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in core) { val dx = q.x - cx; val dy = q.y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val th = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(th); val uy = sin(th)
        var sq = 0.0; var mx = 0.0; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (q in core) {
            val dx = q.x - cx; val dy = q.y - cy
            val d = abs(-dx * uy + dy * ux)
            sq += d * d; mx = max(mx, d)
            val t = dx * ux + dy * uy
            lo = min(lo, t); hi = max(hi, t)
        }
        val extent = hi - lo
        val chord = dist(first, last)
        // A line goes one way: no doubling back along the axis ...
        var backward = 0.0; var prevT = (core[0].x - cx) * ux + (core[0].y - cy) * uy
        val sign = if (((last.x - first.x) * ux + (last.y - first.y) * uy) >= 0) 1 else -1
        for (k in 1 until n) {
            val t = (core[k].x - cx) * ux + (core[k].y - cy) * uy
            val step = (t - prevT) * sign
            if (step < 0) backward -= step
            prevT = t
        }
        if (backward > 0.08 * extent) return null
        // ... and no zig-zag across it (cursive "mmm" or "www"); pen jitter below 1 unit is ignored.
        if (PathMath.length(PathMath.simplify(core, 1f)) > 1.1 * extent) return null
        val rms = sqrt(sq / n)
        val limit = 0.015 * chord + 0.8
        if (rms > limit || mx > 0.06 * chord + 1.5) return null
        fun proj(q: Pt): Pt { val t = (q.x - cx) * ux + (q.y - cy) * uy; return Pt((cx + t * ux).toFloat(), (cy + t * uy).toFloat()) }
        var a = proj(first); var b = proj(last)
        if (snap) snapSegment(a, b, o.snapDegrees)?.let { (sa, sb) -> a = sa; b = sb }
        val conf = (0.5 + 0.5 * (1 - rms / limit)).toFloat()
        return RecognizedShape(ShapeKind.LINE, listOf(PathMath.densify(listOf(a, b))), floatArrayOf(a.x, a.y, b.x, b.y), conf)
    }

    /** The segment turned about its midpoint onto the nearest multiple of 45°, if within [snapDeg]. */
    private fun snapSegment(a: Pt, b: Pt, snapDeg: Float, pivotAtB: Boolean = false): Pair<Pt, Pt>? {
        val deg = Math.toDegrees(PathMath.angle(a, b).toDouble())
        val near = (deg / 45).roundToInt() * 45.0
        if (abs(deg - near) > snapDeg || abs(deg - near) < 1e-6) return null
        val r = Math.toRadians(near)
        val len = dist(a, b)
        val dx = (cos(r) * len).toFloat(); val dy = (sin(r) * len).toFloat()
        if (pivotAtB) return Pt(b.x - dx, b.y - dy) to b
        val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
        return Pt(mx - dx / 2, my - dy / 2) to Pt(mx + dx / 2, my + dy / 2)
    }

    /** One-stroke arrow: a long straight shaft with a short head at one end. */
    private fun arrow(p: List<Pt>, size: Float, len: Float, o: ShapeOptions): RecognizedShape? {
        val v = PathMath.simplify(p, max(2f, 0.04f * size))
        if (v.size < 4) return null
        val i = (0 until v.size - 1).maxBy { dist(v[it], v[it + 1]) }
        val (tail, tip, head) = when (i) {
            0 -> Triple(v[0], v[1], v.drop(2))
            v.size - 2 -> Triple(v.last(), v[v.size - 2], v.take(v.size - 2))
            else -> return null
        }
        val shaft = dist(tail, tip)
        if (shaft < o.minLineLength || shaft < 0.5f * len) return null
        // The shaft itself must be straight, not just its end points.
        val from = p.indexOf(v[i])
        val to = p.indexOf(v[i + 1])
        if (from >= 0 && to > from) {
            val seg = p.subList(from, to + 1)
            if (seg.any { PathMath.pointSegment(it, v[i], v[i + 1]) > 0.04f * shaft + 1.5f }) return null
        }
        return arrowFrom(tail, tip, head, 0.9f, o)
    }

    /** Validates head points against the shaft and builds a clean arrow. */
    private fun arrowFrom(tail: Pt, tip: Pt, head: List<Pt>, baseConf: Float, o: ShapeOptions): RecognizedShape? {
        val shaft = dist(tail, tip)
        if (shaft < o.minLineLength) return null
        val dx = (tip.x - tail.x) / shaft; val dy = (tip.y - tail.y) / shaft
        if (head.any { dist(it, tip) > 0.6f * shaft }) return null
        val barbs = head.filter { dist(it, tip) > 0.06f * shaft }
        fun side(q: Pt) = (q.x - tip.x) * -dy + (q.y - tip.y) * dx
        fun back(q: Pt) = (tip.x - q.x) * dx + (tip.y - q.y) * dy
        val left = barbs.filter { side(it) > 0.03f * shaft && back(it) > 0 }.maxByOrNull { dist(it, tip) } ?: return null
        val right = barbs.filter { side(it) < -0.03f * shaft && back(it) > 0 }.maxByOrNull { dist(it, tip) } ?: return null
        var score = 0f
        for (q in listOf(left, right)) {
            val l = dist(q, tip)
            val ang = Math.toDegrees(kotlin.math.acos((back(q) / l).toDouble().coerceIn(-1.0, 1.0)))
            if (ang !in 12.0..75.0) return null
            score += (1 - abs(ang - 35) / 40).toFloat().coerceIn(0f, 1f)
        }
        val headLen = ((dist(left, tip) + dist(right, tip)) / 2).coerceIn(0.08f * shaft, 0.35f * shaft)
        var t0 = tail
        snapSegment(tail, tip, o.snapDegrees, pivotAtB = true)?.let { t0 = it.first }
        val ux = (tip.x - t0.x) / shaft; val uy = (tip.y - t0.y) / shaft
        val half = Math.toRadians(28.0)
        fun barb(sign: Int): Pt {
            val c = cos(half).toFloat(); val s = sin(half).toFloat() * sign
            val bx = -ux * c + uy * s; val by = -uy * c - ux * s
            return Pt(tip.x + bx * headLen, tip.y + by * headLen)
        }
        val b1 = barb(1); val b2 = barb(-1)
        val conf = (baseConf * (0.75f + 0.125f * score)).coerceIn(0f, 1f)
        return RecognizedShape(
            ShapeKind.ARROW,
            listOf(PathMath.densify(listOf(t0, tip)), PathMath.densify(listOf(b1, tip, b2))),
            floatArrayOf(t0.x, t0.y, tip.x, tip.y, b1.x, b1.y, b2.x, b2.y), max(conf, 0.5f),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Signed turn at [b] going a → b → c, radians in (-π, π]. */
    private fun turnAt(a: Pt, b: Pt, c: Pt): Float {
        val t = PathMath.angle(b, c) - PathMath.angle(a, b)
        var r = t.toDouble()
        while (r > PI) r -= 2 * PI
        while (r <= -PI) r += 2 * PI
        return r.toFloat()
    }

    /** Total signed turning along an open path. */
    private fun pathTurn(p: List<Pt>): Float {
        var s = 0f
        var prev: Pt? = null; var cur: Pt? = null
        for (q in p) {
            if (cur != null && dist(cur, q) <= 1e-3f) continue
            if (prev != null && cur != null) s += turnAt(prev, cur, q)
            prev = cur; cur = q
        }
        return s
    }

    /** Total signed turning of a closed loop (±2π for one simple turn around). */
    private fun ringTurn(p: List<Pt>): Float {
        val ring = ArrayList<Pt>(p.size)
        for (q in p) if (ring.isEmpty() || dist(ring.last(), q) > 1e-3f) ring += q
        while (ring.size > 2 && dist(ring.first(), ring.last()) <= 1e-3f) ring.removeAt(ring.size - 1)
        if (ring.size < 3) return 0f
        var s = 0f
        for (i in ring.indices) s += turnAt(ring[(i - 1 + ring.size) % ring.size], ring[i], ring[(i + 1) % ring.size])
        return s
    }

    private fun flat(v: List<Pt>) = FloatArray(v.size * 2).also { a -> v.forEachIndexed { i, q -> a[i * 2] = q.x; a[i * 2 + 1] = q.y } }
}
