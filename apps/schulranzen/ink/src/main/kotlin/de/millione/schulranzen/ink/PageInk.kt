package de.millione.schulranzen.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import de.millione.schulranzen.core.Geometry.pointSegmentDistance
import de.millione.schulranzen.core.Geometry.segmentDistance
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One finished stroke on a page, in page units. */
class PageStroke(
    val id: Long,
    val tool: ToolKind,
    val color: Int,
    val size: Float,
    val stroke: Stroke,
) {
    // Bounding box of the input path, widened by half the brush size.
    val minX: Float; val minY: Float; val maxX: Float; val maxY: Float
    init {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        val inputs = stroke.inputs
        val scratch = StrokeInput()
        for (i in 0 until inputs.size) {
            inputs.populate(i, scratch)
            x0 = min(x0, scratch.x); y0 = min(y0, scratch.y); x1 = max(x1, scratch.x); y1 = max(y1, scratch.y)
        }
        val half = size / 2
        minX = x0 - half; minY = y0 - half; maxX = x1 + half; maxY = y1 + half
    }
}

/** A change to one page, as the eraser or a new stroke produced it. */
class PageEdit(
    val page: Int,
    /** Strokes taken away, each with the index it had, ascending. */
    val removed: List<Pair<Int, PageStroke>>,
    val added: List<PageStroke>,
)

/**
 * The strokes of one page and the operations on them. All coordinates are
 * page units; the eraser works on the true path geometry (capsules with the
 * brush width), so fast strokes leave no remnants.
 */
class PageInk(strokes: List<PageStroke> = emptyList()) {
    private val list = ArrayList(strokes)
    val strokes: List<PageStroke> get() = list
    /** Bumped on every change; renderers re-record when it differs. */
    var revision = 0L
        private set

    fun add(s: PageStroke) { list += s; revision++ }

    fun apply(edit: PageEdit) {
        if (edit.removed.isNotEmpty()) {
            val ids = edit.removed.mapTo(HashSet()) { it.second.id }
            list.removeAll { it.id in ids }
        }
        list += edit.added
        revision++
    }

    fun revert(edit: PageEdit) {
        if (edit.added.isNotEmpty()) {
            val ids = edit.added.mapTo(HashSet()) { it.id }
            list.removeAll { it.id in ids }
        }
        for ((index, s) in edit.removed) list.add(index.coerceIn(0, list.size), s)
        revision++
    }

    /**
     * Erases along the segment (ax, ay)–(bx, by) with [radius]. A stroke
     * eraser removes whole strokes it touches; the partial eraser cuts the
     * touched parts out and keeps the rest as new strokes. Returns the edit,
     * or null when nothing was hit. [nextId] provides ids for new pieces.
     */
    fun erase(page: Int, ax: Float, ay: Float, bx: Float, by: Float, radius: Float, whole: Boolean, nextId: () -> Long): PageEdit? {
        val removed = ArrayList<Pair<Int, PageStroke>>()
        val added = ArrayList<PageStroke>()
        val sx0 = min(ax, bx) - radius; val sx1 = max(ax, bx) + radius
        val sy0 = min(ay, by) - radius; val sy1 = max(ay, by) + radius
        val scratch = StrokeInput()
        list.forEachIndexed { index, s ->
            if (s.maxX < sx0 || s.minX > sx1 || s.maxY < sy0 || s.minY > sy1) return@forEachIndexed
            val reach = radius + s.size / 2
            val inputs = s.stroke.inputs
            if (whole) {
                if (pathTouches(inputs, scratch, ax, ay, bx, by, reach)) removed += index to s
            } else {
                val pieces = cut(inputs, ax, ay, bx, by, reach, step = max(radius / 3f, 0.75f)) ?: return@forEachIndexed
                removed += index to s
                for (p in pieces) added += PageStroke(nextId(), s.tool, s.color, s.size, Stroke(s.stroke.brush, p))
            }
        }
        if (removed.isEmpty()) return null
        val edit = PageEdit(page, removed, added)
        apply(edit)
        return edit
    }

    companion object {
        private fun pathTouches(inputs: StrokeInputBatch, s: StrokeInput, ax: Float, ay: Float, bx: Float, by: Float, reach: Float): Boolean {
            if (inputs.size == 0) return false
            inputs.populate(0, s)
            var px = s.x; var py = s.y
            if (inputs.size == 1) return segmentDistance(px, py, px, py, ax, ay, bx, by) <= reach
            for (i in 1 until inputs.size) {
                inputs.populate(i, s)
                if (segmentDistance(px, py, s.x, s.y, ax, ay, bx, by) <= reach) return true
                px = s.x; py = s.y
            }
            return false
        }

        /**
         * Splits [inputs] where the eraser capsule covers them. The path is
         * resampled to [step] first so a long straight piece between two
         * sparse points is cut exactly at the eraser edge. Returns null when
         * the eraser does not touch the stroke, otherwise the remaining runs
         * (possibly none).
         */
        internal fun cut(inputs: StrokeInputBatch, ax: Float, ay: Float, bx: Float, by: Float, reach: Float, step: Float): List<StrokeInputBatch>? {
            val points = resample(inputs, step)
            val hit = BooleanArray(points.size) { pointSegmentDistance(points[it].x, points[it].y, ax, ay, bx, by) <= reach }
            if (hit.none { it }) return null
            val runs = ArrayList<StrokeInputBatch>()
            var start = -1
            for (i in 0..points.size) {
                val keep = i < points.size && !hit[i]
                if (keep && start < 0) start = i
                if (!keep && start >= 0) {
                    if (i - start >= 2) {
                        val batch = MutableStrokeInputBatch()
                        for (k in start until i) batch.add(points[k])
                        runs += batch
                    }
                    start = -1
                }
            }
            return runs
        }

        /** The inputs with extra points interpolated so no gap is longer than [step]. */
        internal fun resample(inputs: StrokeInputBatch, step: Float): List<StrokeInput> {
            val out = ArrayList<StrokeInput>(inputs.size * 2)
            if (inputs.size == 0) return out
            var prev = inputs.get(0)
            out += prev
            for (i in 1 until inputs.size) {
                val cur = inputs.get(i)
                val len = hypot(cur.x - prev.x, cur.y - prev.y)
                val n = (len / step).toInt()
                for (k in 1..n) {
                    val t = k / (n + 1f)
                    out += lerp(prev, cur, t)
                }
                out += cur
                prev = cur
            }
            return out
        }

        private fun lerp(a: StrokeInput, b: StrokeInput, t: Float): StrokeInput {
            fun mix(u: Float, v: Float) = u + (v - u) * t
            return StrokeInput.create(
                mix(a.x, b.x), mix(a.y, b.y),
                a.elapsedTimeMillis + ((b.elapsedTimeMillis - a.elapsedTimeMillis) * t).toLong(),
                a.toolType,
                a.strokeUnitLengthCm,
                if (a.hasPressure && b.hasPressure) mix(a.pressure, b.pressure) else StrokeInput.NO_PRESSURE,
                if (a.hasTilt && b.hasTilt) mix(a.tiltRadians, b.tiltRadians) else StrokeInput.NO_TILT,
                if (a.hasOrientation && b.hasOrientation) a.orientationRadians else StrokeInput.NO_ORIENTATION,
            )
        }

        /** Builds a batch from plain points (tests, imports). */
        fun batchOf(points: List<FloatArray>, tool: InputToolType = InputToolType.STYLUS): StrokeInputBatch {
            val b = MutableStrokeInputBatch()
            points.forEachIndexed { i, p -> b.add(tool, p[0], p[1], i * 8L) }
            return b
        }
    }
}
