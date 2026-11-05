package de.millione.schulranzen.core.shk

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pipe media with their schematic colours (ARGB, light theme). */
@Serializable
enum class PipeKind(val title: String, val short: String, val argb: Long, val dashed: Boolean = false) {
    FLOW("Heizung Vorlauf", "VL", 0xFFC2362B),
    RETURN("Heizung Rücklauf", "RL", 0xFF2448A8),
    COLD("Trinkwasser kalt", "PWC", 0xFF2E7D4F),
    HOT("Trinkwasser warm", "PWH", 0xFFD9542F, dashed = true),
    CIRCULATION("Zirkulation", "PWH-C", 0xFF7D5A7A, dashed = true),
}

/** A symbol placed on the sheet. [rotation] counts quarter turns clockwise; mirroring happens before rotating. */
@Serializable
data class SchemaSymbol(
    val id: String,
    val type: SymbolType,
    val at: Pt,
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val label: String = "",
    /** Calculator inputs as typed (German text, may be an expression). */
    val props: Map<String, String> = emptyMap(),
)

/** Connection of a pipe end to a symbol port. */
@Serializable
data class PortRef(val symbolId: String, val port: Int)

/**
 * A pipe as an orthogonal polyline. [lengthM] overrides the drawn length
 * (drawn length × [Schema.metersPerUnit]) for the material list.
 */
@Serializable
data class SchemaPipe(
    val id: String,
    val kind: PipeKind,
    val points: List<Pt>,
    val start: PortRef? = null,
    val end: PortRef? = null,
    val pipeId: String? = null,
    val lengthM: Double? = null,
    val label: String = "",
    val props: Map<String, String> = emptyMap(),
)

@Serializable
data class SchemaLabel(val id: String, val at: Pt, val text: String)

@Serializable
data class Schema(
    val id: String,
    val name: String,
    val updatedAt: Long = 0,
    /** Scale for the material list: metres per grid cell. */
    val metersPerUnit: Double = 0.5,
    val symbols: List<SchemaSymbol> = emptyList(),
    val pipes: List<SchemaPipe> = emptyList(),
    val labels: List<SchemaLabel> = emptyList(),
)

/** Result of a hit test: what lies under the finger or pen. */
sealed interface SchemaHit {
    data class Symbol(val id: String) : SchemaHit
    data class Pipe(val id: String, val segment: Int) : SchemaHit
    data class Label(val id: String) : SchemaHit
}

/** Where a point snaps to: a free grid point or a symbol port. */
data class SnapResult(val at: Pt, val port: PortRef?)

/** Axis-aligned box in schema units. */
data class SchemaBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(p: Pt, pad: Double = 0.0) = p.x >= left - pad && p.x <= right + pad && p.y >= top - pad && p.y <= bottom + pad
    fun union(o: SchemaBox) = SchemaBox(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

/** Geometry of the schema: transforms, ports, snapping, hit tests and routing. Pure and tested. */
object SchemaGeometry {
    /** Local symbol coordinates to sheet coordinates (mirror, then rotate clockwise, then move). */
    fun toWorld(s: SchemaSymbol, local: Pt): Pt {
        var x = if (s.mirrored) -local.x else local.x
        var y = local.y
        repeat(Math.floorMod(s.rotation, 4)) { val nx = -y; y = x; x = nx }
        return Pt(s.at.x + x + 0.0, s.at.y + y + 0.0)
    }

    fun ports(s: SchemaSymbol): List<Pt> = SymbolLibrary.def(s.type).ports.map { toWorld(s, it.at) }

    fun port(schema: Schema, ref: PortRef): Pt? =
        schema.symbols.firstOrNull { it.id == ref.symbolId }?.let { ports(it).getOrNull(ref.port) }

    fun bounds(s: SchemaSymbol): SchemaBox {
        val d = SymbolLibrary.def(s.type)
        val corners = listOf(Pt(-d.halfW, -d.halfH), Pt(d.halfW, -d.halfH), Pt(d.halfW, d.halfH), Pt(-d.halfW, d.halfH))
            .map { toWorld(s, it) } + ports(s)
        return SchemaBox(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    /** Bounds of everything on the sheet, or null when it is empty. */
    fun bounds(schema: Schema): SchemaBox? {
        val boxes = schema.symbols.map { bounds(it) } +
            schema.pipes.filter { it.points.isNotEmpty() }.map { p -> SchemaBox(p.points.minOf { it.x }, p.points.minOf { it.y }, p.points.maxOf { it.x }, p.points.maxOf { it.y }) } +
            schema.labels.map { SchemaBox(it.at.x - it.text.length * 0.2, it.at.y - 0.4, it.at.x + it.text.length * 0.2, it.at.y + 0.4) }
        return boxes.reduceOrNull { a, b -> a.union(b) }
    }

    fun grid(p: Pt): Pt = Pt(p.x.roundToInt().toDouble(), p.y.roundToInt().toDouble())

    /**
     * Snaps [p] to the nearest free port within [radius] units, else to the
     * grid. Ports of [ignoreSymbol] are skipped (a symbol never snaps to itself).
     */
    fun snap(schema: Schema, p: Pt, radius: Double = 0.8, ignoreSymbol: String? = null): SnapResult {
        var best: SnapResult? = null
        var bestD = radius
        schema.symbols.forEach { s ->
            if (s.id == ignoreSymbol) return@forEach
            ports(s).forEachIndexed { i, at ->
                val d = at.dist(p)
                if (d <= bestD) { bestD = d; best = SnapResult(at, PortRef(s.id, i)) }
            }
        }
        return best ?: SnapResult(grid(p), null)
    }

    /** Distance from [p] to the segment a–b. */
    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return p.dist(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return p.dist(Pt(a.x + t * dx, a.y + t * dy))
    }

    /** Topmost element under [p]: labels, then symbols, then pipes (last drawn wins). */
    fun hit(schema: Schema, p: Pt, tolerance: Double = 0.4): SchemaHit? {
        schema.labels.asReversed().firstOrNull { abs(it.at.x - p.x) <= max(0.6, it.text.length * 0.2) && abs(it.at.y - p.y) <= 0.5 }
            ?.let { return SchemaHit.Label(it.id) }
        schema.symbols.asReversed().firstOrNull { bounds(it).contains(p, tolerance * 0.5) }?.let { return SchemaHit.Symbol(it.id) }
        var best: SchemaHit.Pipe? = null
        var bestD = tolerance
        schema.pipes.forEach { pipe ->
            pipe.points.zipWithNext().forEachIndexed { i, (a, b) ->
                val d = distanceToSegment(p, a, b)
                if (d <= bestD) { bestD = d; best = SchemaHit.Pipe(pipe.id, i) }
            }
        }
        return best
    }

    /**
     * Orthogonal route from [a] to [b]: straight when aligned, else one bend.
     * [horizontalFirst] picks which leg comes first.
     */
    fun route(a: Pt, b: Pt, horizontalFirst: Boolean = true): List<Pt> = when {
        a == b -> listOf(a)
        a.x == b.x || a.y == b.y -> listOf(a, b)
        horizontalFirst -> listOf(a, Pt(b.x, a.y), b)
        else -> listOf(a, Pt(a.x, b.y), b)
    }

    /** Drawn length in units. */
    fun length(points: List<Pt>): Double = points.zipWithNext().sumOf { (a, b) -> a.dist(b) }

    /** Length in metres used for the material list. */
    fun lengthM(schema: Schema, pipe: SchemaPipe): Double = pipe.lengthM ?: (length(pipe.points) * schema.metersPerUnit)

    /** Removes repeated points and middle points on straight lines. */
    fun simplify(points: List<Pt>): List<Pt> {
        val dedup = points.fold(ArrayList<Pt>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
        if (dedup.size < 3) return dedup
        val out = ArrayList<Pt>()
        out += dedup.first()
        for (i in 1 until dedup.size - 1) {
            val a = out.last(); val b = dedup[i]; val c = dedup[i + 1]
            val collinear = (a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)
            if (!collinear) out += b
        }
        out += dedup.last()
        return out
    }

    /** Index of the interior corner of [pipe] nearest to [p] within [tolerance]; the ends belong to their ports. */
    fun cornerAt(pipe: SchemaPipe, p: Pt, tolerance: Double): Int? =
        (1 until pipe.points.size - 1).filter { pipe.points[it].dist(p) <= tolerance }.minByOrNull { pipe.points[it].dist(p) }

    /**
     * Moves corner [index] to [to]. Its neighbours slide along so every stretch
     * stays straight; a pipe end stays on its port and gets a new corner instead.
     */
    fun moveCorner(points: List<Pt>, index: Int, to: Pt): List<Pt> {
        if (index <= 0 || index >= points.size - 1) return points
        val old = points[index]
        fun neighbour(i: Int): Pt {
            val n = points[i]
            return if (n.y == old.y) Pt(n.x, to.y) else Pt(to.x, n.y)
        }
        val out = ArrayList<Pt>()
        out += points.subList(0, index - 1)
        if (index - 1 == 0) {
            val first = points[0]
            out += first
            if (first.x != to.x && first.y != to.y) out += if (first.y == old.y) Pt(to.x, first.y) else Pt(first.x, to.y)
        } else out += neighbour(index - 1)
        out += to
        if (index + 1 == points.size - 1) {
            val last = points.last()
            if (last.x != to.x && last.y != to.y) out += if (last.y == old.y) Pt(to.x, last.y) else Pt(last.x, to.y)
            out += last
        } else {
            out += neighbour(index + 1)
            out += points.subList(index + 2, points.size)
        }
        return simplify(out)
    }

    /**
     * Shifts straight stretch [segment] sideways so it runs through [to]. At a
     * pipe end the end stays on its port and a short leg connects to the stretch.
     */
    fun moveSegment(points: List<Pt>, segment: Int, to: Pt): List<Pt> {
        if (segment < 0 || segment >= points.size - 1) return points
        val a = points[segment]; val b = points[segment + 1]
        val horizontal = a.y == b.y
        fun shifted(q: Pt) = if (horizontal) Pt(q.x, to.y) else Pt(to.x, q.y)
        val out = ArrayList<Pt>()
        out += points.subList(0, segment)
        if (segment == 0) out += a
        out += shifted(a)
        out += shifted(b)
        if (segment + 1 == points.size - 1) out += b
        out += points.subList(segment + 2, points.size)
        return simplify(out)
    }

    /** Index of the segment that [p] lies on, or null. */
    fun segmentAt(points: List<Pt>, p: Pt): Int? =
        points.zipWithNext().indexOfFirst { (a, b) -> distanceToSegment(p, a, b) < 1e-6 }.takeIf { it >= 0 }

    /**
     * Moves the end of an orthogonal polyline to [to] and keeps it
     * orthogonal by sliding the neighbouring point along with it.
     */
    fun moveEnd(points: List<Pt>, atStart: Boolean, to: Pt): List<Pt> {
        if (points.size < 2) return listOf(to)
        val pts = if (atStart) points.reversed().toMutableList() else points.toMutableList()
        val last = pts.size - 1
        val old = pts[last]
        if (pts.size == 2) {
            val routed = route(pts[0], to, horizontalFirst = pts[0].y == old.y)
            return simplify(if (atStart) routed.reversed() else routed)
        }
        val prev = pts[last - 1]
        pts[last - 1] = if (prev.y == old.y) Pt(prev.x, to.y) else Pt(to.x, prev.y)
        pts[last] = to
        // The point before may now be off-axis; re-route that stretch if needed.
        val fixed = ArrayList<Pt>()
        fixed += pts[0]
        for (i in 1 until pts.size) {
            val a = fixed.last(); val b = pts[i]
            if (a.x != b.x && a.y != b.y) fixed += Pt(b.x, a.y)
            fixed += b
        }
        val result = simplify(fixed)
        return if (atStart) result.reversed() else result
    }
}

/** Editing steps on an immutable [Schema]; the UI keeps snapshots for undo. */
object SchemaOps {
    fun addSymbol(schema: Schema, symbol: SchemaSymbol): Schema = schema.copy(symbols = schema.symbols + symbol)

    fun updateSymbol(schema: Schema, id: String, change: (SchemaSymbol) -> SchemaSymbol): Schema =
        reconnect(schema.copy(symbols = schema.symbols.map { if (it.id == id) change(it) else it }), id)

    fun moveSymbol(schema: Schema, id: String, to: Pt): Schema = updateSymbol(schema, id) { it.copy(at = SchemaGeometry.grid(to)) }

    fun rotate(schema: Schema, id: String): Schema = updateSymbol(schema, id) { it.copy(rotation = Math.floorMod(it.rotation + 1, 4)) }

    fun mirror(schema: Schema, id: String): Schema = updateSymbol(schema, id) { it.copy(mirrored = !it.mirrored) }

    /** Deletes a symbol; pipes attached to it stay but lose their connection. */
    fun deleteSymbol(schema: Schema, id: String): Schema = schema.copy(
        symbols = schema.symbols.filter { it.id != id },
        pipes = schema.pipes.map { p ->
            p.copy(start = p.start?.takeIf { it.symbolId != id }, end = p.end?.takeIf { it.symbolId != id })
        },
    )

    fun deletePipe(schema: Schema, id: String): Schema = schema.copy(pipes = schema.pipes.filter { it.id != id })
    fun deleteLabel(schema: Schema, id: String): Schema = schema.copy(labels = schema.labels.filter { it.id != id })

    fun updatePipe(schema: Schema, id: String, change: (SchemaPipe) -> SchemaPipe): Schema =
        schema.copy(pipes = schema.pipes.map { if (it.id == id) change(it) else it })

    fun updateLabel(schema: Schema, id: String, change: (SchemaLabel) -> SchemaLabel): Schema =
        schema.copy(labels = schema.labels.map { if (it.id == id) change(it) else it })

    /**
     * Draws a pipe from [from] to [to], both snapped to ports or the grid.
     * Returns the schema unchanged when start and end fall on the same point.
     */
    fun addPipe(schema: Schema, id: String, kind: PipeKind, from: Pt, to: Pt, pipeId: String? = null): Schema {
        val a = SchemaGeometry.snap(schema, from)
        val b = SchemaGeometry.snap(schema, to)
        if (a.at == b.at) return schema
        // Leave a port in its own direction: horizontal ports first go sideways.
        val horizontalFirst = abs(to.x - from.x) >= abs(to.y - from.y)
        val pts = SchemaGeometry.route(a.at, b.at, horizontalFirst)
        return schema.copy(pipes = schema.pipes + SchemaPipe(id, kind, pts, a.port, b.port, pipeId))
    }

    /** Drags a corner of a pipe; everything snaps to the grid so pipes stay on the sheet's raster. */
    fun moveCorner(schema: Schema, pipeId: String, index: Int, to: Pt): Schema =
        updatePipe(schema, pipeId) { it.copy(points = SchemaGeometry.moveCorner(it.points, index, SchemaGeometry.grid(to))) }

    fun moveSegment(schema: Schema, pipeId: String, segment: Int, to: Pt): Schema =
        updatePipe(schema, pipeId) { it.copy(points = SchemaGeometry.moveSegment(it.points, segment, SchemaGeometry.grid(to))) }

    /**
     * Cuts pipe [pipeId] at [at] and joins both parts with a tee there. A typed
     * length is shared out by drawn length, so the sum stays what was measured.
     * Returns the schema unchanged when [at] is not inside the pipe.
     */
    fun splitWithTee(schema: Schema, pipeId: String, at: Pt, teeId: String, secondPipeId: String): Schema {
        val pipe = schema.pipes.firstOrNull { it.id == pipeId } ?: return schema
        val seg = SchemaGeometry.segmentAt(pipe.points, at) ?: return schema
        if (at == pipe.points.first() || at == pipe.points.last()) return schema
        val first = SchemaGeometry.simplify(pipe.points.subList(0, seg + 1) + at)
        val second = SchemaGeometry.simplify(listOf(at) + pipe.points.subList(seg + 1, pipe.points.size))
        val total = SchemaGeometry.length(pipe.points)
        fun share(part: List<Pt>) = pipe.lengthM?.let { it * SchemaGeometry.length(part) / total }
        val a = pipe.copy(points = first, end = PortRef(teeId, 0), lengthM = share(first))
        val b = pipe.copy(id = secondPipeId, points = second, start = PortRef(teeId, 1), lengthM = share(second))
        return schema.copy(
            symbols = schema.symbols + SchemaSymbol(teeId, SymbolType.TEE, at),
            pipes = schema.pipes.flatMap { if (it.id == pipeId) listOf(a, b) else listOf(it) },
        )
    }

    /** A pipe of the same medium whose run passes through [at] (not one that merely ends there). */
    private fun pipeThrough(schema: Schema, kind: PipeKind, at: Pt): SchemaPipe? = schema.pipes.firstOrNull { p ->
        p.kind == kind && at != p.points.first() && at != p.points.last() && SchemaGeometry.segmentAt(p.points, at) != null
    }

    /**
     * Like [addPipe], but an end dropped onto a running pipe of the same medium
     * makes a real junction: a tee goes in and counts in the material list.
     */
    fun addPipeWithTees(schema: Schema, id: String, kind: PipeKind, from: Pt, to: Pt, pipeId: String?, newId: () -> String): Schema {
        val a = SchemaGeometry.snap(schema, from)
        val b = SchemaGeometry.snap(schema, to)
        if (a.at == b.at) return schema
        var s = schema
        fun join(end: SnapResult): PortRef? {
            end.port?.let { return it }
            val through = pipeThrough(s, kind, end.at) ?: return null
            val tee = newId()
            s = splitWithTee(s, through.id, end.at, tee, newId())
            return PortRef(tee, 2)
        }
        val startRef = join(a)
        val endRef = join(b)
        val horizontalFirst = abs(to.x - from.x) >= abs(to.y - from.y)
        return s.copy(pipes = s.pipes + SchemaPipe(id, kind, SchemaGeometry.route(a.at, b.at, horizontalFirst), startRef, endRef, pipeId))
    }

    /** Re-attaches all pipe ends connected to symbol [symbolId] after it moved, turned or flipped. */
    fun reconnect(schema: Schema, symbolId: String): Schema {
        val symbol = schema.symbols.firstOrNull { it.id == symbolId } ?: return schema
        val ports = SchemaGeometry.ports(symbol)
        return schema.copy(
            pipes = schema.pipes.map { p ->
                var pts = p.points
                p.start?.takeIf { it.symbolId == symbolId }?.let { ref -> ports.getOrNull(ref.port)?.let { pts = SchemaGeometry.moveEnd(pts, true, it) } }
                p.end?.takeIf { it.symbolId == symbolId }?.let { ref -> ports.getOrNull(ref.port)?.let { pts = SchemaGeometry.moveEnd(pts, false, it) } }
                if (pts === p.points) p else p.copy(points = pts)
            },
        )
    }
}

/** Simple bounded undo/redo over immutable snapshots. */
class UndoStack<T>(initial: T, private val limit: Int = 100) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    /** Records [next] as a new step; equal snapshots are ignored. */
    fun push(next: T) {
        if (next == current) return
        past.addLast(current)
        if (past.size > limit) past.removeFirst()
        future.clear()
        current = next
    }

    /** Replaces the current state without a new step (e.g. while dragging). */
    fun replace(next: T) { current = next }

    fun undo(): T { if (past.isNotEmpty()) { future.addLast(current); current = past.removeLast() }; return current }
    fun redo(): T { if (future.isNotEmpty()) { past.addLast(current); current = future.removeLast() }; return current }

    fun reset(state: T) { past.clear(); future.clear(); current = state }
}

/** What a tapped element opens: a quick calculator with pre-filled inputs (German text). */
data class CalcLink(val calculatorId: String, val inputs: Map<String, String>)

/** Links schema elements to calculations. */
object SchemaLinks {
    const val PIPE_CALC = "pipe_sizing"

    fun forSymbol(s: SchemaSymbol): CalcLink? =
        SymbolLibrary.def(s.type).calculatorId?.let { CalcLink(it, s.props.filterValues { v -> v.isNotBlank() }) }

    fun forPipe(p: SchemaPipe): CalcLink = CalcLink(PIPE_CALC, p.props.filterValues { it.isNotBlank() })

    /** Property fields of a symbol = the inputs of its calculator. */
    fun propertiesOf(type: SymbolType): List<ShkInput> =
        SymbolLibrary.def(type).calculatorId?.let { ShkCalculators.byId(it)?.inputs }.orEmpty()

    fun pipeProperties(): List<ShkInput> = ShkCalculators.byId(PIPE_CALC)?.inputs.orEmpty().filter { it.key == "p" || it.key == "dt" }
}
