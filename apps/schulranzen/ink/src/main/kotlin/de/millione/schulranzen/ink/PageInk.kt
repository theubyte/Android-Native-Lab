package de.millione.schulranzen.ink

import android.graphics.Matrix
import android.graphics.RectF
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

    val bounds get() = RectF(minX, minY, maxX, maxY)

    /**
     * The stroke moved/scaled by [m] (uniform scale + translation). The
     * brush width scales with it, so a shrunk drawing keeps its look.
     */
    fun transformed(m: Matrix, newId: Long): PageStroke {
        val scale = abs(m.mapRadius(1f))
        val pts = FloatArray(2)
        val inputs = stroke.inputs
        val out = MutableStrokeInputBatch()
        for (i in 0 until inputs.size) {
            val s = inputs.get(i)
            pts[0] = s.x; pts[1] = s.y
            m.mapPoints(pts)
            out.add(StrokeInput.create(
                pts[0], pts[1], s.elapsedTimeMillis, s.toolType, s.strokeUnitLengthCm,
                if (s.hasPressure) s.pressure else StrokeInput.NO_PRESSURE,
                if (s.hasTilt) s.tiltRadians else StrokeInput.NO_TILT,
                if (s.hasOrientation) s.orientationRadians else StrokeInput.NO_ORIENTATION,
            ))
        }
        val newSize = size * scale
        return PageStroke(newId, tool, color, newSize, Stroke(Brushes.brush(tool, color, newSize), out))
    }

    fun recolored(newColor: Int, newId: Long) =
        PageStroke(newId, tool, newColor, size, Stroke(Brushes.brush(tool, newColor, size), stroke.inputs))

    private fun abs(f: Float) = if (f < 0) -f else f
}

/**
 * A change to one page (by page id): strokes and objects taken away (each
 * with the index it had, ascending) and added. Page ids, not positions, so
 * undo still finds the page after pages were moved.
 */
class PageEdit(
    val page: String,
    val removed: List<Pair<Int, PageStroke>>,
    val added: List<PageStroke>,
    val removedItems: List<Pair<Int, PageItem>> = emptyList(),
    val addedItems: List<PageItem> = emptyList(),
) {
    val isEmpty get() = removed.isEmpty() && added.isEmpty() && removedItems.isEmpty() && addedItems.isEmpty()
}

/**
 * The content of one page: strokes plus objects (text, images). All
 * coordinates are page units; the eraser works on the true path geometry
 * (capsules with the brush width), so fast strokes leave no remnants.
 */
class PageInk(strokes: List<PageStroke> = emptyList(), items: List<PageItem> = emptyList()) {
    private val list = ArrayList(strokes)
    private val objects = ArrayList(items)
    val strokes: List<PageStroke> get() = list
    val items: List<PageItem> get() = objects
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
        if (edit.removedItems.isNotEmpty()) {
            val ids = edit.removedItems.mapTo(HashSet()) { it.second.id }
            objects.removeAll { it.id in ids }
        }
        objects += edit.addedItems
        revision++
    }

    fun revert(edit: PageEdit) {
        if (edit.added.isNotEmpty()) {
            val ids = edit.added.mapTo(HashSet()) { it.id }
            list.removeAll { it.id in ids }
        }
        for ((index, s) in edit.removed) list.add(index.coerceIn(0, list.size), s)
        if (edit.addedItems.isNotEmpty()) {
            val ids = edit.addedItems.mapTo(HashSet()) { it.id }
            objects.removeAll { it.id in ids }
        }
        for ((index, s) in edit.removedItems) objects.add(index.coerceIn(0, objects.size), s)
        revision++
    }

    /** Edit that replaces the given strokes/items by their changed versions. */
    fun replaceEdit(page: String, strokes: Map<Long, PageStroke>, items: Map<Long, PageItem>): PageEdit {
        val removed = list.withIndex().filter { it.value.id in strokes }.map { it.index to it.value }
        val removedItems = objects.withIndex().filter { it.value.id in items }.map { it.index to it.value }
        return PageEdit(page, removed, strokes.values.toList(), removedItems, items.values.toList())
    }

    /** Edit that removes the given strokes and items. */
    fun removeEdit(page: String, strokeIds: Set<Long>, itemIds: Set<Long>): PageEdit = PageEdit(
        page,
        list.withIndex().filter { it.value.id in strokeIds }.map { it.index to it.value }, emptyList(),
        objects.withIndex().filter { it.value.id in itemIds }.map { it.index to it.value }, emptyList(),
    )

    /**
     * Erases along the segment (ax, ay)–(bx, by) with [radius]. A stroke
     * eraser removes whole strokes it touches; the partial eraser cuts the
     * touched parts out and keeps the rest as new strokes. Returns the edit,
     * or null when nothing was hit. [nextId] provides ids for new pieces.
     */
    fun erase(page: String, ax: Float, ay: Float, bx: Float, by: Float, radius: Float, whole: Boolean, nextId: () -> Long): PageEdit? {
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

    /**
     * What a lasso loop ([polygon], page units, x/y pairs) encloses: strokes
     * with most of their path inside, objects whose centre is inside.
     */
    fun select(polygon: FloatArray): Pair<Set<Long>, Set<Long>> {
        if (polygon.size < 6) return emptySet<Long>() to emptySet()
        val strokes = HashSet<Long>()
        val scratch = StrokeInput()
        for (s in list) {
            val inputs = s.stroke.inputs
            val n = inputs.size
            if (n == 0) continue
            val step = max(1, n / 24)
            var inside = 0; var total = 0
            var i = 0
            while (i < n) {
                inputs.populate(i, scratch)
                if (contains(polygon, scratch.x, scratch.y)) inside++
                total++
                i += step
            }
            if (inside * 2 > total) strokes += s.id
        }
        val items = objects.filter { val b = it.bounds(); contains(polygon, b.centerX(), b.centerY()) }.mapTo(HashSet()) { it.id }
        return strokes to items
    }

    /** Union of the bounds of the given strokes and items, or null. */
    fun boundsOf(strokeIds: Set<Long>, itemIds: Set<Long>): RectF? {
        var r: RectF? = null
        for (s in list) if (s.id in strokeIds) r = (r ?: RectF(s.bounds)).also { it.union(s.bounds) }
        for (o in objects) if (o.id in itemIds) { val b = o.bounds(); r = (r ?: RectF(b)).also { it.union(b) } }
        return r
    }

    /** Text item at a page point, for editing. */
    fun textAt(x: Float, y: Float): TextItem? =
        objects.asReversed().firstOrNull { it is TextItem && it.bounds().contains(x, y) } as? TextItem

    companion object {
        /** Even-odd point in polygon test. */
        fun contains(poly: FloatArray, x: Float, y: Float): Boolean {
            var c = false
            var j = poly.size - 2
            var i = 0
            while (i < poly.size) {
                val xi = poly[i]; val yi = poly[i + 1]; val xj = poly[j]; val yj = poly[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i; i += 2
            }
            return c
        }

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

        /** A straight stroke from a to b (ruler), evenly sampled. */
        fun line(ax: Float, ay: Float, bx: Float, by: Float, tool: InputToolType = InputToolType.STYLUS): StrokeInputBatch {
            val b = MutableStrokeInputBatch()
            val n = max(2, (hypot(bx - ax, by - ay) / 3f).toInt())
            for (i in 0..n) {
                val t = i / n.toFloat()
                b.add(tool, ax + (bx - ax) * t, ay + (by - ay) * t, i * 4L)
            }
            return b
        }

        /** Builds a batch from plain points (tests, imports). */
        fun batchOf(points: List<FloatArray>, tool: InputToolType = InputToolType.STYLUS): StrokeInputBatch {
            val b = MutableStrokeInputBatch()
            points.forEachIndexed { i, p -> b.add(tool, p[0], p[1], i * 8L) }
            return b
        }
    }
}
