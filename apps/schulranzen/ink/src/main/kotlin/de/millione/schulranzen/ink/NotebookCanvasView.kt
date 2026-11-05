package de.millione.schulranzen.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import de.millione.schulranzen.ink.PageGeometry.GAP
import de.millione.schulranzen.ink.PageGeometry.HEIGHT
import de.millione.schulranzen.ink.PageGeometry.UNITS_PER_MM
import de.millione.schulranzen.ink.PageGeometry.WIDTH
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One sheet as the canvas sees it. */
class CanvasPage(
    val id: String,
    var paper: PaperStyle,
    val ink: PageInk,
    var bookmarked: Boolean = false,
    var locked: Boolean = false,
    /** Worksheet page: picture file (app image folder) printed on the sheet instead of ruling. */
    var background: String? = null,
)

/** What the side button did while the pen hovered (no contact). */
sealed interface RingEvent {
    /** Held long enough: open the tool ring around the pen tip (view pixels). */
    data class Open(val x: Float, val y: Float) : RingEvent
    /** Pen moved while the ring is open. */
    data class Move(val x: Float, val y: Float) : RingEvent
    /** Button let go: pick what is under the pen, or keep the ring for a tap. */
    data class Release(val x: Float, val y: Float) : RingEvent
    /** Pen left the screen. */
    data object Cancel : RingEvent
}

/** Copied content, positions relative to the page it came from. */
class Clip(val strokes: List<PageStroke>, val items: List<PageItem>, val bounds: RectF)

/**
 * The writing surface: an endless column of A4 sheets.
 *
 * - Stylus writes with Jetpack Ink's front-buffered low-latency renderer and
 *   motion prediction; finished strokes are handed off to the page layer
 *   without a flicker.
 * - Fingers scroll and zoom; while the pen is near the screen, other touches
 *   are treated as the resting hand and ignored.
 * - Pages are recorded as display lists in page units, so writing stays
 *   sharp at every zoom (no bitmap cache).
 * - Lasso selects; the selection moves, scales, copies. The ruler draws
 *   straight lines with angle snap and a length readout. Text is placed
 *   by tapping.
 * - Side button held while touching = eraser (if enabled); held while
 *   hovering = tool ring ([RingEvent]).
 */
@SuppressLint("ViewConstructor")
class NotebookCanvasView(context: Context) : FrameLayout(context) {

    interface Listener {
        /** Content of a page changed (save it). */
        fun onPageChanged(pageId: String) {}
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {}
        fun onVisiblePageChanged(page: Int, count: Int) {}
        fun onRing(event: RingEvent) {}
        /** Short press of the side button while hovering. */
        fun onSideButtonClick() {}
        /** Selection frame in view pixels, or null when nothing is selected. */
        fun onSelectionChanged(frame: RectF?) {}
        /** Text tool tapped a page: new text at (x, y) or [existing] to edit. */
        fun onTextRequest(page: Int, x: Float, y: Float, existing: TextItem?) {}
        fun onLockedPageTouched() {}
        /**
         * A finished pen stroke before it lands on [page]: return replacement
         * strokes (e.g. a straightened shape) or null to keep it. Same undo step.
         */
        fun transformStroke(page: Int, stroke: PageStroke): List<PageStroke>? = null
        /** A pen stroke was added to [page] (after [transformStroke]). */
        fun onStrokeAdded(page: Int, stroke: PageStroke) {}
    }

    var listener: Listener? = null

    // ---- tool state (set by the toolbar / ring) ----
    var tool: ToolKind = ToolKind.PEN
        set(value) {
            field = value
            if (value != ToolKind.LASSO) clearSelection()
            overlay.invalidate()
        }
    var penColor: Int = 0xFF1C1B19.toInt()
    var penSize: Float = 3f
    var markerColor: Int = 0xFFF2D04B.toInt()
    var markerSize: Float = 18f
    /** Eraser radius in screen dp, independent of zoom. */
    var eraserRadiusDp: Float = 14f
    /** Side button while writing switches to the eraser. */
    var buttonErases: Boolean = true
    /** Fingers draw too (no stylus at hand). */
    var fingerDraws: Boolean = false
    /** Partial (false) or whole-stroke (true) for the button eraser. */
    var buttonEraserWhole: Boolean = false
    /** Typeface for typed text and labels. */
    var typeface: Typeface
        get() = ItemPainter.typeface
        set(v) { ItemPainter.typeface = v; label.typeface = v }

    private val pages = ArrayList<CanvasPage>()
    val pageCount get() = pages.size
    val history = InkHistory()
    // Wall clock: uptime starts over after a reboot and could meet ids already stored on a page.
    private var nextStrokeId = System.currentTimeMillis() shl 20
    fun newId() = nextStrokeId++

    // ---- view transform: page units -> view pixels ----
    private val view = Matrix()
    private val inverse = Matrix()
    private var scale = 1f
    private var fitScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var laidOut = false

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Reading only (phones): nothing can change the ink. Finger and pen alike
     * scroll and zoom; hover, side button, lasso and ruler are dead. The page
     * fills the width and a swipe keeps gliding, as a reader is expected to.
     */
    var readOnly: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) { clearSelection(); overlay.hide() } else stopFling()
        }

    /** Free space above the first page, e.g. for a bar lying over the canvas. */
    var topGapPx: Float = 24f * density
        set(value) {
            if (field == value) return
            if (ty == field) ty = value
            field = value
            clampAndApply()
        }

    // ---- children ----
    private val layer = PageLayer()
    private val inProgress = InProgressStrokesView(context)
    private val overlay = Overlay()

    private val predictor = MotionEventPredictor.newInstance(this)

    private class Pending(val page: String, val tool: ToolKind, val color: Int, val size: Float)
    private val pending = HashMap<InProgressStrokeId, Pending>()

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * resources.displayMetrics.density }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
        })
        // The resting hand must never scroll the page while the pen writes.
        isMotionEventSplittingEnabled = false
        contentDescription = "Schreibfläche"
        ItemPainter.onImageReady = { post { layer.imageReady() } }
    }

    // ------------------------------------------------------------------ pages

    fun setPages(list: List<CanvasPage>) {
        clearSelection()
        pages.clear(); pages += list
        history.clear()
        // A gesture cut off by leaving the last notebook must not swallow the next one.
        if (mode == Mode.DRAW) { inProgress.cancelUnfinishedStrokes(); pending.clear() }
        endPen(); endPan()
        stopFling()
        eraseStep.clear()
        layer.dropCaches()
        clampAndApply()
        listener?.onHistoryChanged(false, false)
        lastReported = -1
        reportVisiblePage()
    }

    fun page(index: Int): CanvasPage? = pages.getOrNull(index)
    fun pageById(id: String): CanvasPage? = pages.firstOrNull { it.id == id }
    fun indexOf(id: String) = pages.indexOfFirst { it.id == id }
    val currentPage get() = lastReported.coerceAtLeast(0)
    val allPages: List<CanvasPage> get() = pages

    fun addPage(page: CanvasPage, at: Int = pages.size) {
        pages.add(at.coerceIn(0, pages.size), page)
        structureChanged()
    }

    fun removePage(index: Int): CanvasPage? {
        if (pages.size <= 1 || index !in pages.indices) return null
        val p = pages.removeAt(index)
        history.forget(p.id)
        listener?.onHistoryChanged(history.canUndo, history.canRedo)
        structureChanged()
        return p
    }

    fun movePage(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        pages.add(to, pages.removeAt(from))
        structureChanged()
    }

    private fun structureChanged() {
        clearSelection()
        layer.dropCaches()
        clampAndApply()
        lastReported = -1
        reportVisiblePage()
    }

    fun refreshPage(index: Int) { layer.dirty(index); layer.invalidate() }

    fun scrollToPage(index: Int) {
        // Not laid out yet (just made visible): the first layout would undo the jump.
        if (width == 0 || !laidOut) { pendingPage = index; return }
        stopFling()
        ty = -(pageTop(index.coerceIn(0, max(0, pages.size - 1))) * scale) + topGapPx
        clampAndApply()
    }
    private var pendingPage: Int? = null

    fun undo() {
        if (anyLocked(history.undoPages())) { listener?.onLockedPageTouched(); return }
        touched(history.undo { id -> pageById(id)?.ink })
    }

    fun redo() {
        if (anyLocked(history.redoPages())) { listener?.onLockedPageTouched(); return }
        touched(history.redo { id -> pageById(id)?.ink })
    }

    private fun anyLocked(ids: Set<String>) = ids.any { pageById(it)?.locked == true }

    /** Writes the strokes just lifted off into the pages, so they are saved with everything else. */
    fun settleStrokes() {
        if (pending.isEmpty() || mode == Mode.DRAW) return
        runCatching { inProgress.flush(150, java.util.concurrent.TimeUnit.MILLISECONDS, false) }
    }

    private fun touched(ids: Set<String>) {
        clearSelection()
        for (id in ids) { layer.dirty(indexOf(id)); listener?.onPageChanged(id) }
        layer.invalidate()
        listener?.onHistoryChanged(history.canUndo, history.canRedo)
    }

    /** Applies an edit made outside a gesture (text, paste, page actions) as one undo step. */
    fun commit(edits: List<PageEdit>) {
        if (readOnly) return
        val real = edits.filterNot { it.isEmpty }
        if (real.isEmpty()) return
        if (anyLocked(real.mapTo(HashSet()) { it.page })) { listener?.onLockedPageTouched(); return }
        for (e in real) pageById(e.page)?.ink?.apply(e)
        history.push(real)
        for (e in real) { layer.dirty(indexOf(e.page)); listener?.onPageChanged(e.page) }
        layer.invalidate()
        listener?.onHistoryChanged(history.canUndo, history.canRedo)
    }

    private fun pageTop(i: Int) = i * (HEIGHT + GAP)
    private fun pageAt(worldY: Float): Int {
        if (pages.isEmpty()) return -1
        val i = (worldY / (HEIGHT + GAP)).toInt()
        if (i < 0 || i >= pages.size || worldY < 0) return -1
        val local = worldY - pageTop(i)
        return if (local in 0f..HEIGHT) i else -1
    }

    // -------------------------------------------------------------- transform

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val margin = (if (readOnly) 6f else 28f) * density
        val wasFit = abs(scale - fitScale) < 0.001f
        val keepPage = lastReported
        // Landscape: the sheet as tall as fits nicely; portrait: full width. A reader always fills the width.
        fitScale = if (readOnly) (w - 2 * margin) / WIDTH else min((w - 2 * margin) / WIDTH, (h * 1.18f) / HEIGHT)
        if (!laidOut) {
            scale = fitScale
            ty = topGapPx
            laidOut = true
        } else if (readOnly && wasFit) {
            scale = fitScale
        } else {
            scale = scale.coerceIn(fitScale * 0.5f, fitScale * 6f)
        }
        // After a turn or a narrower pane the ruler must stay within reach.
        if (rulerCx >= 0f) { rulerCx = rulerCx.coerceIn(0f, w.toFloat()); rulerCy = rulerCy.coerceIn(0f, h.toFloat()) }
        clampAndApply()
        pendingPage?.let { pendingPage = null; scrollToPage(it) }
            // Turning the phone keeps the reader on the same page.
            ?: run { if (readOnly && oldw != 0 && oldw != w && keepPage >= 0) scrollToPage(keepPage) }
    }

    private fun clampAndApply() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val contentW = WIDTH * scale
        tx = if (contentW <= w) (w - contentW) / 2 else tx.coerceIn(w - contentW - 24f * density, 24f * density)
        val contentH = (pages.size * (HEIGHT + GAP) - GAP) * scale
        val top = topGapPx
        val minTy = min(top, h - contentH - 96f * density)
        ty = ty.coerceIn(minTy, top)
        view.setScale(scale, scale)
        view.postTranslate(tx, ty)
        view.invert(inverse)
        layer.invalidate()
        overlay.invalidate()
        reportSelection()
        reportVisiblePage()
    }

    private var lastReported = -1
    private fun reportVisiblePage() {
        if (pages.isEmpty() || height == 0) return
        val centre = floatArrayOf(width / 2f, height / 3f).also { inverse.mapPoints(it) }
        val i = (centre[1] / (HEIGHT + GAP)).toInt().coerceIn(0, pages.size - 1)
        if (i != lastReported) { lastReported = i; listener?.onVisiblePageChanged(i, pages.size) }
    }

    private fun toWorld(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { inverse.mapPoints(it) }

    /** Page-unit rectangle on page [page] in view pixels. */
    private fun toView(page: Int, r: RectF): RectF = RectF(r).also {
        it.offset(0f, pageTop(page))
        view.mapRect(it)
    }

    /** Centre of what is visible of [page], page units. */
    private fun visibleCentre(page: Int): FloatArray {
        val c = toWorld(width / 2f, height / 2f)
        c[1] = (c[1] - pageTop(page)).coerceIn(80f, HEIGHT - 80f)
        c[0] = c[0].coerceIn(80f, WIDTH - 80f)
        return c
    }

    // -------------------------------------------------------------- selection

    private class Selection(val page: Int, val strokes: Set<Long>, val items: Set<Long>, val bounds: RectF)
    private var selection: Selection? = null
    /** Live move/scale of the selection, page units. */
    private val drag = Matrix()

    val hasSelection get() = selection != null

    fun clearSelection() {
        if (selection == null) return
        val p = selection!!.page
        selection = null
        drag.reset()
        layer.dirty(p); layer.invalidate(); overlay.invalidate()
        listener?.onSelectionChanged(null)
    }

    private fun select(page: Int, strokes: Set<Long>, items: Set<Long>) {
        if (readOnly) return
        val b = pages[page].ink.boundsOf(strokes, items)
        if (b == null) { clearSelection(); return }
        selection = Selection(page, strokes, items, b)
        drag.reset()
        layer.dirty(page); layer.invalidate(); overlay.invalidate()
        reportSelection()
    }

    private fun reportSelection() {
        val s = selection ?: return
        val r = RectF(s.bounds).also { drag.mapRect(it) }
        listener?.onSelectionChanged(toView(s.page, r))
    }

    fun deleteSelection() {
        val s = selection ?: return
        val page = pages[s.page]
        if (page.locked) { listener?.onLockedPageTouched(); return }
        clearSelection()
        commit(listOf(page.ink.removeEdit(page.id, s.strokes, s.items)))
    }

    fun copySelection(): Clip? {
        val s = selection ?: return null
        val ink = pages[s.page].ink
        return Clip(ink.strokes.filter { it.id in s.strokes }, ink.items.filter { it.id in s.items }, RectF(s.bounds))
    }

    fun cutSelection(): Clip? = copySelection()?.also { deleteSelection() }

    /**
     * The selected strokes as recognisers want them: one FloatArray per
     * stroke with x, y, t (ms, increasing across strokes from 0).
     */
    fun selectedStrokePoints(): List<FloatArray> {
        val s = selection ?: return emptyList()
        return strokePoints(s.page, s.strokes)
    }

    /** Page and bounds (page units) of the selection, or null. */
    fun selectionInfo(): Pair<Int, RectF>? = selection?.let { it.page to RectF(it.bounds) }

    /** Page-unit rectangle on [page] in view pixels (for bubbles next to ink). */
    fun viewRectOf(page: Int, r: RectF): RectF = toView(page, r)

    /** Replaces the selection by [items] (e.g. typed text for handwriting) in one undo step. */
    fun replaceSelection(items: List<PageItem>) {
        val s = selection ?: return
        val page = pages[s.page]
        if (page.locked) { listener?.onLockedPageTouched(); return }
        clearSelection()
        commit(listOf(page.ink.removeEdit(page.id, s.strokes, s.items), PageEdit(page.id, emptyList(), emptyList(), emptyList(), items)))
        select(s.page, emptySet(), items.mapTo(HashSet()) { it.id })
    }

    /** The selection as a picture (white paper), at most [maxPx] wide or tall. */
    fun renderSelection(maxPx: Int = 1600): android.graphics.Bitmap? {
        val s = selection ?: return null
        val page = pages[s.page]
        val b = RectF(s.bounds).apply { inset(-12f, -12f) }
        val f = maxPx / max(b.width(), b.height()).coerceAtLeast(1f)
        val bmp = android.graphics.Bitmap.createBitmap((b.width() * f).toInt().coerceAtLeast(1), (b.height() * f).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(f, f); c.translate(-b.left, -b.top)
        PagePainter.paintSome(c, page, s.strokes, s.items)
        return bmp
    }

    /** Briefly marks [rect] (page units) on [page], e.g. a search hit or a marked passage. */
    fun flash(page: Int, rect: RectF) {
        flashPage = page; flashRect = RectF(rect); flashUntil = SystemClock.uptimeMillis() + 4000
        overlay.invalidate()
        postDelayed({ overlay.invalidate() }, 4100)
    }
    private var flashPage = -1
    private var flashRect: RectF? = null
    private var flashUntil = 0L

    /** Points of strokes [ids] on [page] for recognisers (x, y, t). */
    fun strokePoints(page: Int, ids: Set<Long>): List<FloatArray> {
        var offset = 0L
        val out = ArrayList<FloatArray>()
        for (st in pages[page].ink.strokes) {
            if (st.id !in ids || st.tool == ToolKind.MARKER) continue
            val inputs = st.stroke.inputs
            if (inputs.size == 0) continue
            val arr = FloatArray(inputs.size * 3)
            var last = 0L
            for (i in 0 until inputs.size) {
                val p = inputs.get(i)
                arr[i * 3] = p.x; arr[i * 3 + 1] = p.y
                last = p.elapsedTimeMillis
                arr[i * 3 + 2] = (offset + last).toFloat()
            }
            offset += last + 120
            out += arr
        }
        return out
    }

    /** Puts [clip] on the visible page (centred), or [offset] next to where it was. */
    fun paste(clip: Clip, offset: Float? = null) {
        val target = if (offset != null) selection?.page ?: currentPage else currentPage
        val page = pages.getOrNull(target) ?: return
        if (page.locked) { listener?.onLockedPageTouched(); return }
        val m = Matrix()
        if (offset != null) m.setTranslate(offset, offset)
        else {
            val c = visibleCentre(target)
            m.setTranslate(c[0] - clip.bounds.centerX(), c[1] - clip.bounds.centerY())
        }
        val strokes = clip.strokes.map { it.transformed(m, newId()) }
        val items = clip.items.map { it.transformed(m, newId()) }
        clearSelection()
        commit(listOf(PageEdit(page.id, emptyList(), strokes, emptyList(), items)))
        select(target, strokes.mapTo(HashSet()) { it.id }, items.mapTo(HashSet()) { it.id })
    }

    fun duplicateSelection() { copySelection()?.let { paste(it, offset = 36f) } }

    fun recolorSelection(color: Int) {
        val s = selection ?: return
        val page = pages[s.page]
        if (page.locked) { listener?.onLockedPageTouched(); return }
        val strokes = page.ink.strokes.filter { it.id in s.strokes }.associate { it.id to it.recolored(if (it.tool == ToolKind.MARKER) color else color, newId()) }
        val items = page.ink.items.filter { it.id in s.items && it is TextItem }.associate { it.id to (it as TextItem).copy(id = newId(), color = color) }
        commit(listOf(page.ink.replaceEdit(page.id, strokes, items)))
        select(s.page, strokes.values.mapTo(HashSet()) { it.id } + (s.strokes - strokes.keys), items.values.mapTo(HashSet()) { it.id } + (s.items - items.keys))
    }

    private fun commitDrag() {
        val s = selection ?: return
        if (drag.isIdentity) return
        val page = pages[s.page]
        val m = Matrix(drag)
        // Dropped with its centre on another (unlocked) page: it moves over to that page.
        val moved = RectF(s.bounds).also { m.mapRect(it) }
        val target = pageAt(moved.centerY() + pageTop(s.page)).takeIf { it >= 0 && it != s.page && !pages[it].locked }
        if (target != null) m.postTranslate(0f, pageTop(s.page) - pageTop(target))
        // Dropped beside the sheet (or onto a locked one): it stays reachable on its page.
        RectF(s.bounds).also { m.mapRect(it) }.let { r ->
            val (dx, dy) = de.millione.schulranzen.core.notebook.PagePlacement.centreOnPage(r.left, r.top, r.right, r.bottom, WIDTH, HEIGHT)
            m.postTranslate(dx, dy)
        }
        val strokes = page.ink.strokes.filter { it.id in s.strokes }.associate { it.id to it.transformed(m, newId()) }
        val items = page.ink.items.filter { it.id in s.items }.associate { it.id to it.transformed(m, newId()) }
        drag.reset()
        if (target == null) {
            commit(listOf(page.ink.replaceEdit(page.id, strokes, items)))
        } else {
            commit(listOf(
                page.ink.removeEdit(page.id, s.strokes, s.items),
                PageEdit(pages[target].id, emptyList(), strokes.values.toList(), emptyList(), items.values.toList()),
            ))
        }
        select(target ?: s.page, strokes.values.mapTo(HashSet()) { it.id }, items.values.mapTo(HashSet()) { it.id })
    }

    // ---------------------------------------------------------------- text

    /** Adds [item] on [page], or replaces [old] by it; empty text deletes. */
    fun putText(page: Int, item: TextItem?, old: TextItem?) {
        val p = pages.getOrNull(page) ?: return
        val removed = old?.let { o -> p.ink.items.indexOfFirst { it.id == o.id }.takeIf { it >= 0 }?.let { listOf(it to p.ink.items[it]) } } ?: emptyList()
        val added = if (item == null || item.text.isBlank()) emptyList() else listOf(item)
        commit(listOf(PageEdit(p.id, emptyList(), emptyList(), removed, added)))
    }

    fun addItem(page: Int, item: PageItem) {
        val p = pages.getOrNull(page) ?: return
        commit(listOf(PageEdit(p.id, emptyList(), emptyList(), emptyList(), listOf(item))))
        select(page, emptySet(), setOf(item.id))
    }

    /** Centre of the visible part of the current page, for new objects. */
    fun visibleCentreOfCurrent(): FloatArray = visibleCentre(currentPage)

    /** Centre of what is visible of [page], for new objects there. */
    fun visibleCentreOf(page: Int): FloatArray = visibleCentre(page)

    // ------------------------------------------------------------------ input

    private enum class Mode { NONE, DRAW, ERASE, PAN, LASSO, MOVE_SEL, SCALE_SEL, RULER, TEXT, IGNORE }
    private var mode = Mode.NONE
    private var strokeId: InProgressStrokeId? = null
    private var stylusPointer = -1
    private var eraseWhole = false
    private var eraseLast: FloatArray? = null
    private val eraseStep = ArrayList<PageEdit>()
    private var lastPenNear = 0L
    /** The current gesture was started by a finger ("Mit dem Finger schreiben"). */
    private var fingerGesture = false
    private var gestureStart = 0L

    // lasso / ruler / text / selection drag, all in page units of [gesturePage]
    private var gesturePage = -1
    private val lasso = ArrayList<Float>()
    private var rulerA = FloatArray(2)
    private var rulerB = FloatArray(2)
    private var downX = 0f; private var downY = 0f
    private var selStart = FloatArray(2)

    // The ruler lies on the screen (not on the page) like a real one on the
    // desk: fingers move and turn it, the pen draws along its edges. Centre of
    // the top edge in view pixels and angle in radians.
    private var rulerCx = -1f; private var rulerCy = 0f
    private var rulerAngle = 0f
    private var rulerRawAngle = 0f
    private val rulerW get() = 76f * density
    private val rulerL get() = max(width, 1) * 0.8f
    /** Offset of the edge the current pen line follows (0 or [rulerW]); NaN = free line. */
    private var rulerEdge = Float.NaN
    private var rulerFinger = false
    private var rfLastX = 0f; private var rfLastY = 0f; private var rfLastA = 0f; private var rfId = -1

    // finger pan / pinch
    private var panId = -1
    private var lastX = 0f; private var lastY = 0f
    private var startX = 0f; private var startY = 0f
    private var panning = false
    private var pinchDist = 0f
    private var pinchMidX = 0f; private var pinchMidY = 0f

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (readOnly) { readerTouch(e); return true }
        val action = e.actionMasked
        val index = e.actionIndex
        val down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        // The resting hand neither moves nor drops a selection.
        val handNearPen = !isPen(e, index) && SystemClock.uptimeMillis() - lastPenNear < 400
        if (down && mode != Mode.NONE && mode != Mode.PAN && fingerGesture && takeOverFingerGesture(e, index)) return true
        // Pen or finger on the selection: move or scale it.
        if (down && mode == Mode.NONE && selection != null && !handNearPen && beginSelectionDrag(e, index)) return true
        // A new pen contact takes over from any finger gesture.
        if (down && isPen(e, index) && (mode == Mode.NONE || mode == Mode.PAN)) {
            if (mode == Mode.PAN) endPan()
            beginPen(e, index)
            return true
        }
        if (mode != Mode.NONE && mode != Mode.PAN) { penEvent(e); return true }
        if (fingerDraws && action == MotionEvent.ACTION_DOWN && !isPen(e, index) && !handNearPen) {
            beginPen(e, index)
            fingerGesture = true
            gestureStart = SystemClock.uptimeMillis()
            return true
        }
        // Fingers: ignored while the pen is near (resting hand).
        if (SystemClock.uptimeMillis() - lastPenNear < 400) {
            if (mode == Mode.PAN) endPan()
            return true
        }
        if (tool == ToolKind.RULER && mode != Mode.PAN && rulerFingerEvent(e)) return true
        fingerEvent(e)
        return true
    }

    /**
     * While fingers write, a pen always takes over, and a second finger right
     * after the first means zoom or scroll: without this the page could not be
     * moved at all with "Mit dem Finger schreiben" on.
     */
    private fun takeOverFingerGesture(e: MotionEvent, index: Int): Boolean {
        val pen = isPen(e, index)
        if (!pen && (e.pointerCount != 2 || SystemClock.uptimeMillis() - gestureStart > 400)) return false
        if (mode == Mode.DRAW) strokeId?.let { inProgress.cancelStroke(it, e); pending.remove(it) }
        if (mode == Mode.ERASE) finishErase()
        endPen()
        if (pen) { beginPen(e, index); return true }
        panId = e.getPointerId(0)
        mode = Mode.PAN
        startPinch(e)
        return true
    }

    private fun beginSelectionDrag(e: MotionEvent, index: Int): Boolean {
        val s = selection ?: return false
        val frame = toView(s.page, s.bounds)
        val x = e.getX(index); val y = e.getY(index)
        val handle = 30f * density
        val onHandle = hypot(x - frame.right, y - frame.bottom) < handle
        val pad = 12f * density
        val inside = x in frame.left - pad..frame.right + pad && y in frame.top - pad..frame.bottom + pad
        if (!onHandle && !inside) {
            clearSelection()
            return false
        }
        stylusPointer = e.getPointerId(index)
        // A locked page can be selected and copied, not changed.
        if (pages[s.page].locked) {
            mode = Mode.IGNORE
            listener?.onLockedPageTouched()
            return true
        }
        gesturePage = s.page
        val w = toWorld(x, y)
        selStart = floatArrayOf(w[0], w[1] - pageTop(s.page))
        mode = if (onHandle) Mode.SCALE_SEL else Mode.MOVE_SEL
        if (isPen(e, index)) lastPenNear = SystemClock.uptimeMillis()
        return true
    }

    private fun beginPen(e: MotionEvent, index: Int) {
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
        stylusPointer = e.getPointerId(index)
        lastPenNear = SystemClock.uptimeMillis()
        val x = e.getX(index); val y = e.getY(index)
        downX = x; downY = y
        val buttonHeld = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val eraserEnd = e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val erase = tool.erases || eraserEnd || (buttonHeld && buttonErases)
        if (erase) {
            pageAt(toWorld(x, y)[1]).takeIf { it >= 0 && pages[it].locked }?.let { listener?.onLockedPageTouched() }
            mode = Mode.ERASE
            eraseWhole = if (tool.erases) tool == ToolKind.STROKE_ERASER else buttonEraserWhole
            eraseLast = toWorld(x, y)
            eraseStep.clear()
            eraseTo(x, y)
            overlay.eraserAt(x, y)
            return
        }
        val w = toWorld(x, y)
        val page = pageAt(w[1])
        if (page < 0) { mode = Mode.IGNORE; return }
        val local = floatArrayOf(w[0], w[1] - pageTop(page))
        if (pages[page].locked && tool != ToolKind.LASSO) {
            mode = Mode.IGNORE
            listener?.onLockedPageTouched()
            return
        }
        gesturePage = page
        when (tool) {
            ToolKind.LASSO -> {
                mode = Mode.LASSO
                lasso.clear(); lasso += local[0]; lasso += local[1]
            }
            ToolKind.RULER -> {
                mode = Mode.RULER
                ensureRuler()
                val (t, d) = rulerCoords(x, y)
                val grab = 30f * density
                rulerEdge = if (abs(t) <= rulerL / 2 + grab && d in -grab..rulerW + grab) (if (d < rulerW / 2) 0f else rulerW) else Float.NaN
                if (!rulerEdge.isNaN()) {
                    val p = rulerEdgePoint(t, rulerEdge)
                    rulerA = localOf(page, p[0], p[1]); rulerB = rulerA.copyOf()
                } else {
                    rulerA = local; rulerB = local.copyOf()
                }
            }
            ToolKind.TEXT -> mode = Mode.TEXT
            else -> {
                val (color, size) = if (tool == ToolKind.MARKER) markerColor to markerSize else penColor to penSize
                val brushTool = if (tool == ToolKind.MARKER) ToolKind.MARKER else ToolKind.PEN
                val brush = Brushes.brush(brushTool, color, size)
                val strokeToWorld = Matrix().apply { setTranslate(0f, pageTop(page)) }
                predictor.record(e)
                val id = inProgress.startStroke(e, stylusPointer, brush, Matrix(inverse), strokeToWorld)
                pending[id] = Pending(pages[page].id, brushTool, color, size)
                strokeId = id
                mode = Mode.DRAW
                overlay.hide()
            }
        }
    }

    private fun localOf(page: Int, x: Float, y: Float): FloatArray {
        val w = toWorld(x, y)
        return floatArrayOf(w[0], w[1] - pageTop(page))
    }

    private fun penEvent(e: MotionEvent) {
        val i = e.findPointerIndex(stylusPointer)
        if (i >= 0 && isPen(e, i)) lastPenNear = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (i < 0) return
                val x = e.getX(i); val y = e.getY(i)
                when (mode) {
                    Mode.DRAW -> {
                        val id = strokeId ?: return
                        predictor.record(e)
                        val predicted = predictor.predict()
                        try { inProgress.addToStroke(e, stylusPointer, id, predicted) } finally { predicted?.recycle() }
                    }
                    Mode.ERASE -> {
                        for (h in 0 until e.historySize) eraseTo(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        eraseTo(x, y)
                        overlay.eraserAt(x, y)
                    }
                    Mode.LASSO -> {
                        for (h in 0 until e.historySize) addLasso(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        addLasso(x, y)
                        overlay.invalidate()
                    }
                    Mode.RULER -> {
                        rulerB = if (rulerEdge.isNaN()) snap(rulerA, localOf(gesturePage, x, y)) else {
                            val p = rulerEdgePoint(rulerCoords(x, y).first, rulerEdge)
                            localOf(gesturePage, p[0], p[1])
                        }
                        overlay.invalidate()
                    }
                    Mode.MOVE_SEL -> {
                        val p = localOf(gesturePage, x, y)
                        drag.setTranslate(p[0] - selStart[0], p[1] - selStart[1])
                        layer.invalidate(); overlay.invalidate(); reportSelection()
                    }
                    Mode.SCALE_SEL -> {
                        val s = selection ?: return
                        val p = localOf(gesturePage, x, y)
                        val ax = s.bounds.left; val ay = s.bounds.top
                        val d0 = hypot(selStart[0] - ax, selStart[1] - ay).coerceAtLeast(1f)
                        val f = (hypot(p[0] - ax, p[1] - ay) / d0).coerceIn(0.15f, 8f)
                        drag.setScale(f, f, ax, ay)
                        layer.invalidate(); overlay.invalidate(); reportSelection()
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) != stylusPointer) return
                val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
                when (mode) {
                    Mode.DRAW -> strokeId?.let { inProgress.finishStroke(e, stylusPointer, it) }
                    Mode.ERASE -> finishErase()
                    Mode.LASSO -> finishLasso()
                    Mode.RULER -> finishRuler()
                    Mode.TEXT -> if (hypot(x - downX, y - downY) < touchSlop * 2) {
                        val p = localOf(gesturePage, x, y)
                        listener?.onTextRequest(gesturePage, p[0], p[1], pages[gesturePage].ink.textAt(p[0], p[1]))
                    }
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> commitDrag()
                    else -> Unit
                }
                endPen()
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.DRAW -> strokeId?.let { inProgress.cancelStroke(it, e); pending.remove(it) }
                    Mode.ERASE -> finishErase()
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> { drag.reset(); layer.invalidate(); reportSelection() }
                    else -> Unit
                }
                endPen()
            }
        }
    }

    private fun endPen() {
        mode = Mode.NONE; strokeId = null; stylusPointer = -1; eraseLast = null
        fingerGesture = false
        lasso.clear()
        overlay.hide()
    }

    private fun addLasso(x: Float, y: Float) {
        val p = localOf(gesturePage, x, y)
        val n = lasso.size
        if (n >= 2 && hypot(p[0] - lasso[n - 2], p[1] - lasso[n - 1]) < 2f) return
        lasso += p[0]; lasso += p[1]
    }

    private fun finishLasso() {
        if (lasso.size < 6) { clearSelection(); return }
        val (strokes, items) = pages[gesturePage].ink.select(lasso.toFloatArray())
        if (strokes.isEmpty() && items.isEmpty()) clearSelection() else {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            select(gesturePage, strokes, items)
        }
    }

    /** Angle snap: within 4° of a multiple of 15° the line locks to it. */
    private fun snap(a: FloatArray, b: FloatArray): FloatArray {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val len = hypot(dx, dy)
        if (len < 1f) return b
        val deg = Math.toDegrees(atan2(dy, dx).toDouble())
        val nearest = (deg / 15.0).roundToInt() * 15.0
        if (abs(deg - nearest) > 4.0) return b
        val r = Math.toRadians(nearest)
        return floatArrayOf(a[0] + (cos(r) * len).toFloat(), a[1] + (sin(r) * len).toFloat())
    }

    private fun ensureRuler() {
        if (rulerCx < 0f && width > 0) { rulerCx = width / 2f; rulerCy = height * 0.42f }
    }

    /** View point → (along the ruler from its centre, across from the top edge). */
    private fun rulerCoords(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - rulerCx; val dy = y - rulerCy
        val c = cos(rulerAngle); val s = sin(rulerAngle)
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    private fun rulerEdgePoint(t: Float, edge: Float): FloatArray {
        val c = cos(rulerAngle); val s = sin(rulerAngle)
        return floatArrayOf(rulerCx + c * t - s * edge, rulerCy + s * t + c * edge)
    }

    private fun onRuler(x: Float, y: Float): Boolean {
        val (t, d) = rulerCoords(x, y)
        val pad = 6f * density
        return abs(t) <= rulerL / 2 + pad && d in -pad..rulerW + pad
    }

    /** One finger moves the ruler, two turn it; it snaps to 15° steps. */
    private fun rulerFingerEvent(e: MotionEvent): Boolean {
        ensureRuler()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!onRuler(e.x, e.y)) { rulerFinger = false; return false }
                rulerFinger = true
                rfId = e.getPointerId(0); rfLastX = e.x; rfLastY = e.y
                rulerRawAngle = rulerAngle
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (rulerFinger && e.pointerCount == 2) {
                rfLastA = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                rfLastX = (e.getX(0) + e.getX(1)) / 2; rfLastY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> if (rulerFinger) {
                if (e.pointerCount >= 2) {
                    val a = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    var da = a - rfLastA
                    if (da > Math.PI) da -= (2 * Math.PI).toFloat() else if (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    rulerRawAngle += da
                    val step = Math.toRadians(15.0).toFloat()
                    val nearest = (rulerRawAngle / step).roundToInt() * step
                    val newAngle = if (abs(rulerRawAngle - nearest) < Math.toRadians(2.0)) nearest else rulerRawAngle
                    // Turn about the fingers' midpoint, then follow it.
                    val turn = newAngle - rulerAngle
                    val c = cos(turn); val s = sin(turn)
                    val rx = rulerCx - rfLastX; val ry = rulerCy - rfLastY
                    rulerCx = rfLastX + rx * c - ry * s + (mx - rfLastX)
                    rulerCy = rfLastY + rx * s + ry * c + (my - rfLastY)
                    rulerAngle = newAngle
                    rfLastA = a; rfLastX = mx; rfLastY = my
                } else {
                    val i = e.findPointerIndex(rfId).takeIf { it >= 0 } ?: 0
                    rulerCx += e.getX(i) - rfLastX; rulerCy += e.getY(i) - rfLastY
                    rfLastX = e.getX(i); rfLastY = e.getY(i)
                }
                rulerCx = rulerCx.coerceIn(0f, width.toFloat()); rulerCy = rulerCy.coerceIn(0f, height.toFloat())
                overlay.invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> if (rulerFinger) {
                val keep = if (e.actionIndex == 0) 1 else 0
                rfId = e.getPointerId(keep); rfLastX = e.getX(keep); rfLastY = e.getY(keep)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (rulerFinger) { rulerFinger = false; return true }
        }
        return rulerFinger
    }

    private fun finishRuler() {
        val a = rulerA; val b = rulerB
        if (hypot(b[0] - a[0], b[1] - a[1]) < 3f) return
        val page = pages[gesturePage]
        val stroke = PageStroke(newId(), ToolKind.PEN, penColor, penSize,
            Stroke(Brushes.brush(ToolKind.PEN, penColor, penSize), PageInk.line(a[0], a[1], b[0], b[1])))
        commit(listOf(PageEdit(page.id, emptyList(), listOf(stroke))))
    }

    private fun eraseTo(x: Float, y: Float) {
        val w = toWorld(x, y)
        val last = eraseLast ?: w
        eraseLast = w
        val radius = eraserRadiusDp * density / scale
        val candidates = listOf(pageAt(last[1]), pageAt(w[1])).filter { it >= 0 }.toSet()
        for (p in candidates) {
            if (pages[p].locked) continue
            val top = pageTop(p)
            val edit = pages[p].ink.erase(pages[p].id, last[0], last[1] - top, w[0], w[1] - top, radius, eraseWhole, ::newId) ?: continue
            eraseStep += edit
            layer.dirty(p)
            layer.invalidate()
        }
    }

    private fun finishErase() {
        if (eraseStep.isEmpty()) return
        history.push(eraseStep.toList())
        eraseStep.mapTo(HashSet()) { it.page }.forEach { listener?.onPageChanged(it) }
        eraseStep.clear()
        listener?.onHistoryChanged(history.canUndo, history.canRedo)
    }

    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        var added = false
        for ((id, stroke) in strokes) {
            val p = pending.remove(id) ?: continue
            val page = pageById(p.page) ?: continue
            val s = PageStroke(newId(), p.tool, p.color, p.size, stroke)
            val index = indexOf(p.page)
            val final = (if (p.tool == ToolKind.PEN) runCatching { listener?.transformStroke(index, s) }.getOrNull() else null) ?: listOf(s)
            final.forEach { page.ink.add(it) }
            // Each stroke its own undo step, even when two finish within one frame.
            history.push(listOf(PageEdit(p.page, emptyList(), final)))
            listener?.onPageChanged(p.page)
            added = true
            if (p.tool == ToolKind.PEN) final.forEach { listener?.onStrokeAdded(index, it) }
        }
        layer.invalidate()
        // Same frame as the page layer draws them: no flicker at the handoff.
        inProgress.removeFinishedStrokes(strokes.keys)
        if (added) listener?.onHistoryChanged(history.canUndo, history.canRedo)
    }

    private fun fingerEvent(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panId = e.getPointerId(0)
                lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                panning = false
                mode = Mode.PAN
                pinchDist = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) startPinch(e)
            MotionEvent.ACTION_MOVE -> {
                if (mode != Mode.PAN) return
                if (e.pointerCount >= 2 && pinchDist > 0f) {
                    val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val newScale = (scale * d / pinchDist).coerceIn(fitScale * 0.5f, fitScale * 6f)
                    val f = newScale / scale
                    // Zoom about the fingers' midpoint and follow its movement.
                    tx = mx - (pinchMidX - tx) * f
                    ty = my - (pinchMidY - ty) * f
                    scale = newScale
                    pinchDist = d; pinchMidX = mx; pinchMidY = my
                    clampAndApply()
                } else {
                    val i = e.findPointerIndex(panId).takeIf { it >= 0 } ?: return
                    val dx = e.getX(i) - lastX; val dy = e.getY(i) - lastY
                    if (!panning && hypot(e.getX(i) - startX, e.getY(i) - startY) > touchSlop) panning = true
                    if (panning) { tx += dx; ty += dy; clampAndApply() }
                    lastX = e.getX(i); lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val gone = e.actionIndex
                val keep = if (gone == 0) 1 else 0
                panId = e.getPointerId(keep)
                lastX = e.getX(keep); lastY = e.getY(keep)
                pinchDist = 0f
                if (e.pointerCount - 1 >= 2) startPinch(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A finger tap on empty paper drops the selection or places text. Only a
                // gesture that began as a tap: a hand lifted after the pen wrote is none.
                if (e.actionMasked == MotionEvent.ACTION_UP && mode == Mode.PAN && !panning) {
                    if (selection != null) clearSelection()
                    else if (tool == ToolKind.TEXT && !readOnly) fingerText(e.x, e.y)
                }
                endPan()
            }
        }
    }

    private fun fingerText(x: Float, y: Float) {
        val w = toWorld(x, y)
        val page = pageAt(w[1])
        if (page < 0) return
        if (pages[page].locked) { listener?.onLockedPageTouched(); return }
        val local = floatArrayOf(w[0], w[1] - pageTop(page))
        listener?.onTextRequest(page, local[0], local[1], pages[page].ink.textAt(local[0], local[1]))
    }

    private fun startPinch(e: MotionEvent) {
        pinchDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
        pinchMidX = (e.getX(0) + e.getX(1)) / 2; pinchMidY = (e.getY(0) + e.getY(1)) / 2
        panning = true
    }

    private fun endPan() { mode = Mode.NONE; panId = -1; panning = false; pinchDist = 0f }

    // ---- reading only: glide after a swipe, double tap zooms ----

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var pinched = false
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val glide = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            tx = scroller.currX.toFloat(); ty = scroller.currY.toFloat()
            clampAndApply()
            postOnAnimation(this)
        }
    }

    private fun stopFling() { scroller.forceFinished(true); removeCallbacks(glide) }

    private fun readerTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling(); pinched = false
                velocity?.recycle(); velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_POINTER_DOWN -> pinched = true
        }
        velocity?.addMovement(e)
        val swiped = panning
        fingerEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (swiped) { if (!pinched) startGlide() } else readerTap(e.x, e.y)
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null }
        }
    }

    private fun startGlide() {
        val v = velocity ?: return
        v.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
        val contentW = WIDTH * scale
        val contentH = (pages.size * (HEIGHT + GAP) - GAP) * scale
        val minX = if (contentW <= width) tx else width - contentW - 24f * density
        val maxX = if (contentW <= width) tx else 24f * density
        val minY = min(topGapPx, height - contentH - 96f * density)
        scroller.fling(tx.toInt(), ty.toInt(), v.xVelocity.toInt(), v.yVelocity.toInt(), minX.toInt(), maxX.toInt(), minY.toInt(), topGapPx.toInt())
        postOnAnimation(glide)
    }

    private fun readerTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < ViewConfiguration.getDoubleTapTimeout() && hypot(x - lastTapX, y - lastTapY) < touchSlop * 4) {
            lastTapAt = 0L
            val target = if (scale > fitScale * 1.05f) fitScale else fitScale * 2.5f
            val f = target / scale
            tx = x - (x - tx) * f; ty = y - (y - ty) * f
            scale = target
            clampAndApply()
        } else {
            lastTapAt = now; lastTapX = x; lastTapY = y
        }
    }

    // ------------------------------------------------- hover and side button

    private var buttonDown = false
    private var buttonDownAt = 0L
    private var ringOpen = false
    private var hoverX = 0f; private var hoverY = 0f
    private val openRing = Runnable {
        if (buttonDown && !ringOpen) {
            ringOpen = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onRing(RingEvent.Open(hoverX, hoverY))
        }
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!readOnly && e.pointerCount > 0 && isPen(e, 0)) {
            if (handleHover(e)) return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    /**
     * Pen hover and button events. The view gets them through
     * [dispatchGenericMotionEvent]; a host that puts other views on top can
     * forward them here (view coordinates).
     */
    fun handleHover(e: MotionEvent): Boolean {
        if (readOnly) return false
        lastPenNear = SystemClock.uptimeMillis()
        hoverX = e.x; hoverY = e.y
        val pressed = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (pressed && !buttonDown) {
                    buttonDown = true; buttonDownAt = SystemClock.uptimeMillis()
                    postDelayed(openRing, RING_HOLD_MS)
                } else if (!pressed && buttonDown) {
                    buttonDown = false
                    removeCallbacks(openRing)
                    if (ringOpen) listener?.onRing(RingEvent.Release(e.x, e.y))
                    else if (SystemClock.uptimeMillis() - buttonDownAt < RING_HOLD_MS) listener?.onSideButtonClick()
                }
                if (ringOpen && e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) listener?.onRing(RingEvent.Move(e.x, e.y))
                if (!ringOpen) overlay.hoverAt(e.x, e.y) else overlay.hide()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                removeCallbacks(openRing)
                buttonDown = false
                if (ringOpen) { ringOpen = false; listener?.onRing(RingEvent.Cancel) }
                overlay.hide()
            }
        }
        return true
    }

    /** The host closed the ring (selection made or tapped away). */
    fun ringClosed() { ringOpen = false }

    // ------------------------------------------------------------- rendering

    private inner class PageLayer : View(context) {
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowRect = RectF()
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x1F000000 }
        private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9542F.toInt() }
        private val lock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND }
        private val nodes = HashMap<String, PageNode>()
        private val dirtyPages = HashSet<Int>()
        private val m = Matrix()
        private val rect = RectF()

        fun dirty(page: Int) { if (page >= 0) dirtyPages += page }
        fun dropCaches() {
            if (Build.VERSION.SDK_INT >= 29) nodes.values.forEach { it.discard() }
            nodes.clear(); dirtyPages.clear(); invalidate()
        }

        /** A picture finished decoding: pages recorded without it record again on the next draw. */
        fun imageReady() { imageEpoch++; invalidate() }

        /** This view's own list survives while the pages' lists may not, so nothing would redraw by itself. */
        private val memory = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { post { invalidate() } }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { post { invalidate() } }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            context.applicationContext.registerComponentCallbacks(memory)
        }

        override fun onDetachedFromWindow() {
            context.applicationContext.unregisterComponentCallbacks(memory)
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) invalidate()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) invalidate()
        }
        private var imageEpoch = 0

        /** Soft paper shadow: a few widening, fading rings below-right of the sheet. */
        private fun drawShadow(canvas: Canvas, r: RectF) {
            for (k in 1..6) {
                val grow = k * 1.6f * density
                shadowRect.set(r.left - grow * 0.5f, r.top - grow * 0.3f + 2f * density, r.right + grow * 0.5f, r.bottom + grow + 2f * density)
                shadow.color = Color.argb(14 - k * 2, 60, 45, 20)
                canvas.drawRoundRect(shadowRect, grow, grow, shadow)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (pages.isEmpty()) return
            val viewTop = floatArrayOf(0f, 0f).also { inverse.mapPoints(it) }[1]
            val viewBottom = floatArrayOf(0f, height.toFloat()).also { inverse.mapPoints(it) }[1]
            val first = max(0, (viewTop / (HEIGHT + GAP)).toInt())
            val last = min(pages.size - 1, (viewBottom / (HEIGHT + GAP)).toInt())
            for (i in first..last) {
                val top = pageTop(i)
                rect.set(0f, top, WIDTH, top + HEIGHT)
                view.mapRect(rect)
                drawShadow(canvas, rect)
                m.set(view); m.preTranslate(0f, top)
                canvas.save()
                canvas.concat(m)
                drawPage(canvas, i)
                canvas.restore()
                canvas.drawRect(rect, edge)
                val page = pages[i]
                if (page.bookmarked) drawRibbon(canvas, rect)
                if (page.locked) drawLock(canvas, rect)
            }
            prune(first, last)
        }

        /**
         * Display lists hold their pictures alive past the image cache, so only
         * the pages around the view keep one: a stack of 100 worksheets would
         * otherwise pile up in memory while scrolling through it.
         */
        private fun prune(first: Int, last: Int) {
            if (Build.VERSION.SDK_INT < 29 || nodes.size <= (last - first + 1) + 4) return
            val keep = HashSet<String>()
            for (i in max(0, first - 2)..min(pages.size - 1, last + 2)) keep += pages[i].id
            val it = nodes.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key !in keep) { e.value.discard(); it.remove() } }
        }

        /** Bookmark: a signal-orange ribbon hanging over the top edge. */
        private fun drawRibbon(canvas: Canvas, r: RectF) {
            val w = 18f * density; val h = 42f * density
            val x = r.right - 48f * density
            val path = Path().apply {
                moveTo(x, r.top - 6f * density); lineTo(x + w, r.top - 6f * density); lineTo(x + w, r.top + h)
                lineTo(x + w / 2, r.top + h - 8f * density); lineTo(x, r.top + h); close()
            }
            canvas.drawPath(path, ribbon)
        }

        /** Locked page: a small padlock in the top-left corner. */
        private fun drawLock(canvas: Canvas, r: RectF) {
            val x = r.left + 22f * density; val y = r.top + 26f * density
            val s = 7f * density
            canvas.drawRoundRect(x - s, y, x + s, y + s * 1.5f, 2f * density, 2f * density, lock)
            // Closed shackle: arc on top, both legs down into the body.
            canvas.drawArc(x - s * 0.65f, y - s * 1.3f, x + s * 0.65f, y, 180f, 180f, false, lock)
            canvas.drawLine(x - s * 0.65f, y - s * 0.65f, x - s * 0.65f, y, lock)
            canvas.drawLine(x + s * 0.65f, y - s * 0.65f, x + s * 0.65f, y, lock)
        }

        private fun drawPage(canvas: Canvas, i: Int) {
            val page = pages[i]
            val sel = selection?.takeIf { it.page == i }
            val hideS = sel?.strokes ?: emptySet()
            val hideI = sel?.items ?: emptySet()
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
                val ink = page.ink
                // Recorded for the zoom it is seen at, in steps so a pinch does not record every frame.
                val step = kotlin.math.round(kotlin.math.ln(scale) / ZOOM_STEP_LN)
                val sharp = kotlin.math.exp(step * ZOOM_STEP_LN)
                val key = ink.contentRevision * 31 + page.paper.ordinal * 7 + (sel?.hashCode()?.toLong() ?: 0L) + step.toLong() * 1_000_003L
                val n = nodes.getOrPut(page.id) { PageNode() }
                val count = ink.strokes.size
                // The system throws display lists away (app in the background, low memory, off screen)
                // without telling us; drawing such a node paints nothing and the sheet goes blank.
                val fresh = (n.recorded == -1) || n.key != key || i in dirtyPages || count < n.recorded ||
                    (n.incomplete && n.epoch != imageEpoch) ||
                    !n.base.hasDisplayList() || (n.recorded > n.baseCount && !n.tail.hasDisplayList())
                if (fresh || !n.canAppend(page, count)) {
                    val rc = n.base.beginRecording(WIDTH.toInt(), HEIGHT.toInt())
                    try { n.incomplete = !PagePainter.paint(rc, page, hideStrokes = hideS, hideItems = hideI, asyncImages = true, screenScale = sharp) } finally { n.base.endRecording() }
                    n.tail.discardDisplayList()
                    n.key = key; n.epoch = imageEpoch; n.baseCount = count; n.recorded = count
                    dirtyPages -= i
                } else if (count > n.recorded) {
                    // New pen strokes only: a small list on top instead of recording the whole page again.
                    val rc = n.tail.beginRecording(WIDTH.toInt(), HEIGHT.toInt())
                    try { PagePainter.paintStrokes(rc, page, n.baseCount, count, sharp) } finally { n.tail.endRecording() }
                    n.recorded = count
                }
                canvas.drawRenderNode(n.base)
                if (n.recorded > n.baseCount) canvas.drawRenderNode(n.tail)
            } else {
                PagePainter.paint(canvas, page, hideStrokes = hideS, hideItems = hideI, screenScale = scale)
            }
        }
    }

    /** One page's display lists: everything up to [baseCount] strokes, plus the strokes since. */
    @android.annotation.TargetApi(29)
    private class PageNode {
        val base = RenderNode("page").also { it.setPosition(0, 0, WIDTH.toInt(), HEIGHT.toInt()) }
        val tail = RenderNode("tail").also { it.setPosition(0, 0, WIDTH.toInt(), HEIGHT.toInt()) }
        var key = 0L
        var epoch = 0
        var baseCount = 0
        var recorded = -1
        var incomplete = false

        fun discard() { base.discardDisplayList(); tail.discardDisplayList() }

        /**
         * New strokes may lie on top only if the order stays right: markers
         * belong below the ink and text above it. A short list stays cheap;
         * then the page records once in full and the list starts empty.
         */
        fun canAppend(page: CanvasPage, count: Int): Boolean {
            if (count == recorded) return true
            if (count - baseCount > MAX_TAIL) return false
            if (page.ink.items.any { it is TextItem }) return false
            val strokes = page.ink.strokes
            for (i in recorded until count) if (strokes[i].tool == ToolKind.MARKER) return false
            return true
        }

        private companion object { const val MAX_TAIL = 32 }
    }

    /**
     * Everything that follows the pen above the ink: hover dot, eraser ring,
     * lasso loop, ruler line with its readout, and the selection lifted off
     * the page with its frame and scale handle.
     */
    private inner class Overlay : View(context) {
        private var x = 0f; private var y = 0f
        private var visible = false
        private var erasing = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * density; color = 0xFFD9542F.toInt()
            pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
        }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xFFD9542F.toInt()
            pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9542F.toInt() }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1B19.toInt() }
        private val m = Matrix()
        private val path = Path()

        init { isClickable = false; isFocusable = false }

        fun hoverAt(px: Float, py: Float) { x = px; y = py; visible = true; erasing = false; invalidate() }
        fun eraserAt(px: Float, py: Float) { x = px; y = py; visible = true; erasing = true; invalidate() }
        fun hide() { if (visible) { visible = false }; invalidate() }

        private val rulerFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rulerTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * density }
        private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG)

        private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55F2D04B }
        private val flashEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = 0xFFD9542F.toInt() }

        override fun onDraw(canvas: Canvas) {
            val fr = flashRect
            if (fr != null && SystemClock.uptimeMillis() < flashUntil && flashPage in pages.indices) {
                val r = toView(flashPage, RectF(fr).apply { inset(-8f, -8f) })
                canvas.drawRoundRect(r, 10f * density, 10f * density, flashPaint)
                canvas.drawRoundRect(r, 10f * density, 10f * density, flashEdge)
            }
            if (tool == ToolKind.RULER && !readOnly) drawPhysicalRuler(canvas)
            drawSelection(canvas)
            when (mode) {
                Mode.LASSO -> drawLasso(canvas)
                Mode.RULER -> drawRuler(canvas)
                else -> Unit
            }
            if (!visible) return
            if (erasing || tool.erases) {
                ring.color = 0x99000000.toInt()
                canvas.drawCircle(x, y, eraserRadiusDp * density, ring)
                ring.color = 0x99FFFFFF.toInt()
                canvas.drawCircle(x, y, eraserRadiusDp * density - ring.strokeWidth, ring)
                return
            }
            if (!tool.writes && tool != ToolKind.RULER) {
                ring.color = 0xCCD9542F.toInt()
                canvas.drawCircle(x, y, 4f * density, ring)
                return
            }
            val size = if (tool == ToolKind.MARKER) markerSize else penSize
            val color = if (tool == ToolKind.MARKER) Brushes.markerColor(markerColor) else penColor
            fill.color = color
            fill.alpha = if (tool == ToolKind.MARKER) 0x72 else 0xB0
            canvas.drawCircle(x, y, max(2f * density, size * scale / 2), fill)
        }

        private fun pageMatrix(page: Int): Matrix = m.apply { set(view); preTranslate(0f, pageTop(page)) }

        private fun drawLasso(canvas: Canvas) {
            if (lasso.size < 4) return
            val pm = pageMatrix(gesturePage)
            path.reset()
            val pts = lasso.toFloatArray().also { pm.mapPoints(it) }
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
            canvas.drawPath(path, dash)
        }

        private fun drawRuler(canvas: Canvas) {
            val pm = pageMatrix(gesturePage)
            val pts = floatArrayOf(rulerA[0], rulerA[1], rulerB[0], rulerB[1]).also { pm.mapPoints(it) }
            fill.color = penColor; fill.alpha = 0xFF
            fill.strokeWidth = max(1.5f * density, penSize * scale)
            fill.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], fill)
            // Readout like a drafting machine: length in mm and angle.
            val len = hypot(rulerB[0] - rulerA[0], rulerB[1] - rulerA[1]) / UNITS_PER_MM
            var deg = Math.toDegrees(atan2((rulerA[1] - rulerB[1]).toDouble(), (rulerB[0] - rulerA[0]).toDouble()))
            if (deg < 0) deg += 360.0
            val text = "%.0f mm  ·  %.0f°".format(len, deg)
            label.color = Color.WHITE
            val tw = label.measureText(text)
            val bx = (pts[2] + 16f * density).coerceAtMost(width - tw - 24f * density)
            val by = (pts[3] - 18f * density).coerceAtLeast(30f * density)
            canvas.drawRoundRect(bx - 10f * density, by - label.textSize - 6f * density, bx + tw + 10f * density, by + 8f * density, 8f * density, 8f * density, tag)
            canvas.drawText(text, bx, by, label)
        }

        /** Translucent ruler with mm/cm scale on the top edge (page millimetres at the current zoom). */
        private fun drawPhysicalRuler(canvas: Canvas) {
            ensureRuler()
            if (rulerCx < 0f) return
            val l = rulerL; val w = rulerW
            canvas.save()
            canvas.translate(rulerCx, rulerCy)
            canvas.rotate(Math.toDegrees(rulerAngle.toDouble()).toFloat())
            rulerFill.style = Paint.Style.FILL; rulerFill.color = 0xB8F4EFE4.toInt()
            canvas.drawRoundRect(-l / 2, 0f, l / 2, w, 10f * density, 10f * density, rulerFill)
            rulerFill.style = Paint.Style.STROKE; rulerFill.strokeWidth = 1.2f * density; rulerFill.color = 0x661C1B19
            canvas.drawRoundRect(-l / 2, 0f, l / 2, w, 10f * density, 10f * density, rulerFill)
            rulerTick.color = 0xCC1C1B19.toInt()
            rulerText.color = 0xCC1C1B19.toInt(); rulerText.textSize = 11f * density; rulerText.typeface = label.typeface
            val mm = UNITS_PER_MM * scale
            val every = if (mm < 3f * density) 5 else 1
            val start = -l / 2 + 12f * density
            var i = 0
            while (start + i * mm <= l / 2 - 12f * density) {
                val x = start + i * mm
                val len = when { i % 10 == 0 -> 20f; i % 5 == 0 -> 13f; else -> 8f } * density
                canvas.drawLine(x, 0f, x, len, rulerTick)
                canvas.drawLine(x, w, x, w - len * 0.6f, rulerTick)
                if (i % 10 == 0) canvas.drawText((i / 10).toString(), x + 3f * density, 32f * density, rulerText)
                i += every
            }
            // Angle in the middle, like a protractor readout.
            val deg = ((-Math.toDegrees(rulerAngle.toDouble())).roundToInt() % 360 + 360) % 360
            val text = "$deg°"
            rulerText.textSize = 13f * density
            canvas.drawText(text, -rulerText.measureText(text) / 2, w - 14f * density, rulerText)
            canvas.restore()
        }

        private fun drawSelection(canvas: Canvas) {
            val s = selection ?: return
            val page = pages.getOrNull(s.page) ?: return
            val pm = pageMatrix(s.page)
            canvas.save()
            canvas.concat(pm)
            canvas.concat(drag)
            PagePainter.paintSome(canvas, page, s.strokes, s.items, scale)
            canvas.restore()
            val r = RectF(s.bounds).also { drag.mapRect(it) }
            r.inset(-6f, -6f)
            pm.mapRect(r)
            canvas.drawRoundRect(r, 8f * density, 8f * density, frame)
            canvas.drawCircle(r.right, r.bottom, 9f * density, handle)
            canvas.drawCircle(r.right, r.bottom, 9f * density, handleRing)
        }
    }

    companion object {
        const val RING_HOLD_MS = 300L
    }
}

private val ZOOM_STEP_LN = kotlin.math.ln(1.2f)
