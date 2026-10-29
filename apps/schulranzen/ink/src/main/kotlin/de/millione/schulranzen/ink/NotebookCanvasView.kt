package de.millione.schulranzen.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import de.millione.schulranzen.ink.PageGeometry.GAP
import de.millione.schulranzen.ink.PageGeometry.HEIGHT
import de.millione.schulranzen.ink.PageGeometry.WIDTH
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One sheet as the canvas sees it. */
class CanvasPage(val id: String, var paper: PaperStyle, val ink: PageInk)

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
 * - Side button held while touching = eraser (if enabled); held while
 *   hovering = tool ring ([RingEvent]).
 */
@SuppressLint("ViewConstructor")
class NotebookCanvasView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onInkChanged(page: Int) {}
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {}
        fun onVisiblePageChanged(page: Int, count: Int) {}
        fun onRing(event: RingEvent) {}
        /** Short press of the side button while hovering. */
        fun onSideButtonClick() {}
    }

    var listener: Listener? = null

    // ---- tool state (set by the toolbar / ring) ----
    var tool: ToolKind = ToolKind.PEN
        set(value) { field = value; cursor.invalidate() }
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

    private val pages = ArrayList<CanvasPage>()
    val pageCount get() = pages.size
    val history = InkHistory()
    private var nextStrokeId = SystemClock.elapsedRealtimeNanos()
    private fun newId() = nextStrokeId++

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

    // ---- children ----
    private val layer = PageLayer()
    private val inProgress = InProgressStrokesView(context)
    private val cursor = CursorLayer()

    private val predictor = MotionEventPredictor.newInstance(this)
    private val renderer = CanvasStrokeRenderer.create()

    private class Pending(val page: Int, val tool: ToolKind, val color: Int, val size: Float)
    private val pending = HashMap<InProgressStrokeId, Pending>()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cursor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
        })
        // The resting hand must never scroll the page while the pen writes.
        isMotionEventSplittingEnabled = false
    }

    // ------------------------------------------------------------------ pages

    fun setPages(list: List<CanvasPage>) {
        pages.clear(); pages += list
        history.clear()
        layer.dropCaches()
        clampAndApply()
        listener?.onHistoryChanged(false, false)
        reportVisiblePage()
    }

    fun page(index: Int): CanvasPage? = pages.getOrNull(index)

    fun addPage(page: CanvasPage, at: Int = pages.size) {
        pages.add(at.coerceIn(0, pages.size), page)
        layer.dropCaches()
        clampAndApply()
        reportVisiblePage()
    }

    fun refreshPage(index: Int) { layer.dirty(index); layer.invalidate() }

    fun scrollToPage(index: Int) {
        ty = -(pageTop(index.coerceIn(0, max(0, pages.size - 1))) * scale) + 24f * density
        clampAndApply()
    }

    fun undo() { touched(history.undo { pages.getOrNull(it)?.ink }) }
    fun redo() { touched(history.redo { pages.getOrNull(it)?.ink }) }

    private fun touched(set: Set<Int>) {
        for (p in set) { layer.dirty(p); listener?.onInkChanged(p) }
        layer.invalidate()
        listener?.onHistoryChanged(history.canUndo, history.canRedo)
    }

    private fun pageTop(i: Int) = i * (HEIGHT + GAP)
    private fun pageAt(worldY: Float): Int {
        if (pages.isEmpty()) return -1
        val i = (worldY / (HEIGHT + GAP)).toInt()
        if (i < 0 || i >= pages.size) return -1
        val local = worldY - pageTop(i)
        return if (local in 0f..HEIGHT) i else -1
    }

    // -------------------------------------------------------------- transform

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val margin = 28f * density
        // Landscape: the sheet as tall as fits nicely; portrait: full width.
        fitScale = min((w - 2 * margin) / WIDTH, (h * 1.18f) / HEIGHT)
        if (!laidOut) {
            scale = fitScale
            ty = 24f * density
            laidOut = true
        } else {
            scale = scale.coerceIn(fitScale * 0.5f, fitScale * 6f)
        }
        clampAndApply()
    }

    private fun clampAndApply() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val contentW = WIDTH * scale
        tx = if (contentW <= w) (w - contentW) / 2 else tx.coerceIn(w - contentW - 24f * density, 24f * density)
        val contentH = (pages.size * (HEIGHT + GAP) - GAP) * scale
        val top = 24f * density
        val minTy = min(top, h - contentH - 96f * density)
        ty = ty.coerceIn(minTy, top)
        view.setScale(scale, scale)
        view.postTranslate(tx, ty)
        view.invert(inverse)
        layer.invalidate()
        cursor.invalidate()
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

    // ------------------------------------------------------------------ input

    private enum class Mode { NONE, DRAW, ERASE, PAN }
    private var mode = Mode.NONE
    private var strokeId: InProgressStrokeId? = null
    private var stylusPointer = -1
    private var eraseWhole = false
    private var eraseLast: FloatArray? = null
    private val eraseStep = ArrayList<PageEdit>()
    private var lastPenNear = 0L

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
        val action = e.actionMasked
        val index = e.actionIndex
        // A new pen contact takes over from any finger gesture.
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && isPen(e, index) && mode != Mode.DRAW && mode != Mode.ERASE) {
            if (mode == Mode.PAN) endPan()
            beginPen(e, index)
            return true
        }
        when (mode) {
            Mode.DRAW, Mode.ERASE -> { penEvent(e); return true }
            else -> Unit
        }
        if (fingerDraws && (action == MotionEvent.ACTION_DOWN) && !isPen(e, index)) {
            beginPen(e, index)
            return true
        }
        // Fingers: ignored while the pen is near (resting hand).
        if (SystemClock.uptimeMillis() - lastPenNear < 400) {
            if (mode == Mode.PAN) endPan()
            return true
        }
        fingerEvent(e)
        return true
    }

    private fun beginPen(e: MotionEvent, index: Int) {
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
        stylusPointer = e.getPointerId(index)
        lastPenNear = SystemClock.uptimeMillis()
        val buttonHeld = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val eraserEnd = e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val erase = tool.erases || eraserEnd || (buttonHeld && buttonErases)
        if (erase) {
            mode = Mode.ERASE
            eraseWhole = if (tool.erases) tool == ToolKind.STROKE_ERASER else buttonEraserWhole
            eraseLast = toWorld(e.getX(index), e.getY(index))
            eraseStep.clear()
            eraseTo(e.getX(index), e.getY(index))
            cursor.eraserAt(e.getX(index), e.getY(index))
            return
        }
        val w = toWorld(e.getX(index), e.getY(index))
        val page = pageAt(w[1])
        if (page < 0) { mode = Mode.DRAW; strokeId = null; return }
        val (color, size) = if (tool == ToolKind.MARKER) markerColor to markerSize else penColor to penSize
        val brush = Brushes.brush(tool, color, size)
        val strokeToWorld = Matrix().apply { setTranslate(0f, pageTop(page)) }
        predictor.record(e)
        val id = inProgress.startStroke(e, stylusPointer, brush, Matrix(inverse), strokeToWorld)
        pending[id] = Pending(page, tool, color, size)
        strokeId = id
        mode = Mode.DRAW
        cursor.hide()
    }

    private fun penEvent(e: MotionEvent) {
        val i = e.findPointerIndex(stylusPointer)
        lastPenNear = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (i < 0) return
                if (mode == Mode.DRAW) {
                    val id = strokeId ?: return
                    predictor.record(e)
                    val predicted = predictor.predict()
                    try { inProgress.addToStroke(e, stylusPointer, id, predicted) } finally { predicted?.recycle() }
                } else {
                    for (h in 0 until e.historySize) eraseTo(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    eraseTo(e.getX(i), e.getY(i))
                    cursor.eraserAt(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) != stylusPointer) return
                if (mode == Mode.DRAW) strokeId?.let { inProgress.finishStroke(e, stylusPointer, it) } else finishErase()
                endPen()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW) strokeId?.let { inProgress.cancelStroke(it, e); pending.remove(it) } else finishErase()
                endPen()
            }
        }
    }

    private fun endPen() {
        mode = Mode.NONE; strokeId = null; stylusPointer = -1; eraseLast = null
        cursor.hide()
    }

    private fun eraseTo(x: Float, y: Float) {
        val w = toWorld(x, y)
        val last = eraseLast ?: w
        eraseLast = w
        val radius = eraserRadiusDp * density / scale
        val first = pageAt(min(last[1], w[1]) - radius).let { if (it < 0) pageAt(min(last[1], w[1])) else it }
        val candidates = listOf(pageAt(last[1]), pageAt(w[1]), first).filter { it >= 0 }.toSet()
        for (p in candidates) {
            val top = pageTop(p)
            val edit = pages[p].ink.erase(p, last[0], last[1] - top, w[0], w[1] - top, radius, eraseWhole, ::newId) ?: continue
            eraseStep += edit
            layer.dirty(p)
            layer.invalidate()
        }
    }

    private fun finishErase() {
        if (eraseStep.isEmpty()) return
        history.push(eraseStep.toList())
        eraseStep.mapTo(HashSet()) { it.page }.forEach { listener?.onInkChanged(it) }
        eraseStep.clear()
        listener?.onHistoryChanged(history.canUndo, history.canRedo)
    }

    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        val step = ArrayList<PageEdit>()
        for ((id, stroke) in strokes) {
            val p = pending.remove(id) ?: continue
            val page = pages.getOrNull(p.page) ?: continue
            val s = PageStroke(newId(), p.tool, p.color, p.size, stroke)
            page.ink.add(s)
            step += PageEdit(p.page, emptyList(), listOf(s))
            layer.dirty(p.page)
        }
        layer.invalidate()
        // Same frame as the page layer draws them: no flicker at the handoff.
        inProgress.removeFinishedStrokes(strokes.keys)
        if (step.isNotEmpty()) {
            history.push(step)
            step.mapTo(HashSet()) { it.page }.forEach { listener?.onInkChanged(it) }
            listener?.onHistoryChanged(history.canUndo, history.canRedo)
        }
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPan()
        }
    }

    private fun startPinch(e: MotionEvent) {
        pinchDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
        pinchMidX = (e.getX(0) + e.getX(1)) / 2; pinchMidY = (e.getY(0) + e.getY(1)) / 2
        panning = true
    }

    private fun endPan() { mode = Mode.NONE; panId = -1; panning = false; pinchDist = 0f }

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
        if (e.pointerCount > 0 && isPen(e, 0)) {
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
                if (!ringOpen) cursor.hoverAt(e.x, e.y) else cursor.hide()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                removeCallbacks(openRing)
                buttonDown = false
                if (ringOpen) { ringOpen = false; listener?.onRing(RingEvent.Cancel) }
                cursor.hide()
            }
        }
        return true
    }

    /** The host closed the ring (selection made or tapped away). */
    fun ringClosed() { ringOpen = false }

    // ------------------------------------------------------------- rendering

    private inner class PageLayer : View(context) {
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            setShadowLayer(18f, 0f, 6f, 0x33000000)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x1F000000 }
        private val nodes = HashMap<String, Pair<Long, Any>>()   // page id -> (revision+paper key, RenderNode)
        private val dirtyPages = HashSet<Int>()
        private val m = Matrix()
        private val rect = RectF()

        fun dirty(page: Int) { dirtyPages += page }
        fun dropCaches() { nodes.clear(); dirtyPages.clear(); invalidate() }

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
                canvas.drawRect(rect, shadow)
                m.set(view); m.preTranslate(0f, top)
                canvas.save()
                canvas.concat(m)
                drawPage(canvas, i)
                canvas.restore()
                canvas.drawRect(rect, edge)
            }
        }

        private fun drawPage(canvas: Canvas, i: Int) {
            val page = pages[i]
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
                val key = page.ink.revision * 8 + page.paper.ordinal
                val cached = nodes[page.id]
                val node = (cached?.second as? RenderNode) ?: RenderNode("page").also { it.setPosition(0, 0, WIDTH.toInt(), HEIGHT.toInt()) }
                if (cached == null || cached.first != key || i in dirtyPages) {
                    val rc = node.beginRecording(WIDTH.toInt(), HEIGHT.toInt())
                    try { paint(rc, page) } finally { node.endRecording() }
                    nodes[page.id] = key to node
                    dirtyPages -= i
                }
                canvas.drawRenderNode(node)
            } else {
                paint(canvas, page)
            }
        }

        private val identity = Matrix()

        /** Sheet, ruling, then markers below pens, like a real highlighter under ink. */
        private fun paint(canvas: Canvas, page: CanvasPage) {
            PaperPainter.draw(canvas, page.paper)
            val strokes = page.ink.strokes
            for (s in strokes) if (s.tool == ToolKind.MARKER) renderer.draw(canvas, s.stroke, identity)
            for (s in strokes) if (s.tool != ToolKind.MARKER) renderer.draw(canvas, s.stroke, identity)
        }
    }

    /** Hover preview: a dot in the pen colour, or the eraser ring. */
    private inner class CursorLayer : View(context) {
        private var x = 0f; private var y = 0f
        private var visible = false
        private var erasing = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density }

        init { isClickable = false; isFocusable = false }

        fun hoverAt(px: Float, py: Float) { x = px; y = py; visible = true; erasing = false; invalidate() }
        fun eraserAt(px: Float, py: Float) { x = px; y = py; visible = true; erasing = true; invalidate() }
        fun hide() { if (visible) { visible = false; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            if (erasing || tool.erases) {
                ring.color = 0x99000000.toInt()
                canvas.drawCircle(x, y, eraserRadiusDp * density, ring)
                ring.color = 0x99FFFFFF.toInt()
                canvas.drawCircle(x, y, eraserRadiusDp * density - ring.strokeWidth, ring)
                return
            }
            val size = if (tool == ToolKind.MARKER) markerSize else penSize
            val color = if (tool == ToolKind.MARKER) Brushes.markerColor(markerColor) else penColor
            fill.color = color
            fill.alpha = if (tool == ToolKind.MARKER) 0x72 else 0xB0
            canvas.drawCircle(x, y, max(2f * density, size * scale / 2), fill)
        }
    }

    companion object {
        const val RING_HOLD_MS = 300L
    }
}
