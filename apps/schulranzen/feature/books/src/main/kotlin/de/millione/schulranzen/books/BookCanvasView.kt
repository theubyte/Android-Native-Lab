package de.millione.schulranzen.books

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import de.millione.schulranzen.core.books.BookUnits
import de.millione.schulranzen.core.books.ColumnLayout
import de.millione.schulranzen.core.books.PageRotation
import de.millione.schulranzen.core.books.RenderMath
import de.millione.schulranzen.ink.Brushes
import de.millione.schulranzen.ink.ItemPainter
import de.millione.schulranzen.ink.PageEdit
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageItem
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.TextItem
import de.millione.schulranzen.ink.ToolKind
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the pen does on a book page. */
enum class BookTool {
    /** Pen scrolls like a finger; nothing is written. */
    READ,
    PEN, MARKER, ERASER, STROKE_ERASER,
    /** Loop around ink or text: move, scale, recolour, delete. */
    LASSO,
    /** Tap to type text; tap typed text to change it. */
    TEXT,
    /** Drag a rectangle: clip it into a notebook or keep it as a marked area. */
    AREA,
    /** Tap to stick a note on the page. */
    NOTE;

    val erases get() = this == ERASER || this == STROKE_ERASER
}

/**
 * The book reader: PDF pages in one vertical column with ink on top.
 *
 * - Pages render on the document's thread. Each page is cached as a bitmap
 *   at fit width (LRU, bounded); when zoomed in, the visible part of each
 *   page is rendered again at screen resolution once the gesture settles,
 *   so text stays sharp up to 10×.
 * - Two fingers zoom and pan at the same time; one finger scrolls (with
 *   fling). While the pen is near, other touches count as the resting hand.
 * - The stylus writes with Jetpack Ink's low-latency renderer; the side
 *   button or the eraser end erases. Ink, notes and marked areas are kept in
 *   unrotated page units, so turning a page turns them along. Typed text
 *   is anchored in those units too but always drawn upright.
 * - Undo lives in the session: every ink change is reported as one step
 *   ([Listener.onInkStep]) and replayed through [applyEdits].
 * - Touches only count when they went down inside this view, so a
 *   notebook next to the book keeps its own pen and hand.
 */
@SuppressLint("ViewConstructor")
internal class BookCanvasView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onInkChanged(page: Int) {}
        /** One user action on ink or text (already applied), for undo. */
        fun onInkStep(edits: List<PageEdit>) {}
        fun onVisiblePageChanged(page: Int) {}
        /** The marked rectangle in view pixels, or null when it is gone. */
        fun onAreaChanged(frame: RectF?) {}
        /** Note tool tapped a page at (x, y) in unrotated page units. */
        fun onNoteRequest(page: Int, x: Float, y: Float) {}
        fun onNoteOpen(note: BookNote) {}
        /** Lasso selection frame in view pixels, or null when nothing is selected. */
        fun onSelectionChanged(frame: RectF?) {}
        /** Text tool tapped (x, y) in unrotated units of [page]; [existing] is the text there. */
        fun onTextRequest(page: Int, x: Float, y: Float, existing: TextItem?) {}
    }

    var listener: Listener? = null

    // ---- tool state ----
    var tool: BookTool = BookTool.READ
        set(value) {
            field = value
            if (value != BookTool.AREA) clearArea()
            if (value != BookTool.LASSO) clearSelection()
            overlay.invalidate()
        }
    var penColor: Int = 0xFF1C1B19.toInt()
    var penSize: Float = 3f
    var markerColor: Int = 0xFFF2D04B.toInt()
    var markerSize: Float = 18f
    /** Eraser radius in screen dp, independent of zoom. */
    var eraserRadiusDp: Float = 14f
    /** Side button held while writing erases. */
    var buttonErases: Boolean = true
    var deskColor: Int = 0xFFEDE8DE.toInt()
        set(value) { field = value; setBackgroundColor(value); layer.invalidate() }
    var accentColor: Int = 0xFFD9542F.toInt()
        set(value) { field = value; layer.invalidate(); overlay.invalidate() }

    // ---- document ----
    private var doc: BookDocument? = null
    private var inkStore: BookInkStore? = null
    private var marks = BookMarks()
    private var layout = ColumnLayout(FloatArray(0))
    private var unitH = FloatArray(0)
    private var rot = IntArray(0)
    private var unitToBox = emptyArray<Matrix>()
    private var boxScale = FloatArray(0)
    val pageCount get() = unitH.size

    private val inks = HashMap<Int, PageInk>()
    private val inkFiles = HashSet<Int>()
    private val inkLoading = HashSet<Int>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "book-ink") }
    private var nextStrokeId = SystemClock.elapsedRealtimeNanos()
    fun newId() = nextStrokeId++

    // ---- view transform: column units -> view pixels ----
    private val view = Matrix()
    private val inverse = Matrix()
    private var scale = 1f
    private var fitScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var laidOut = false
    private var pendingPage = -1
    private var pendingFocus: RectF? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    // ---- render caches ----
    private class Base(val bmp: Bitmap, val rotation: Int)
    private class Detail(val world: RectF, val bmp: Bitmap, val rotation: Int)
    private val bases = object : LruCache<Int, Base>(cacheBudget()) {
        override fun sizeOf(key: Int, value: Base) = value.bmp.byteCount
    }
    private val baseRequested = HashSet<Int>()
    private val details = HashMap<Int, Detail>()
    private var basePx = 1000
    @Volatile private var docGen = 0
    @Volatile private var detailGen = 0
    private val detailRunnable = Runnable { requestDetails() }

    // ---- children ----
    private val layer = PageLayer()
    private val inProgress = InProgressStrokesView(context)
    private val overlay = Overlay()
    private val predictor = MotionEventPredictor.newInstance(this)

    private class Pending(val page: Int, val tool: ToolKind, val color: Int, val size: Float)
    private val pending = HashMap<InProgressStrokeId, Pending>()

    init {
        setBackgroundColor(deskColor)
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
        })
        // The resting hand must never scroll the page while the pen writes.
        isMotionEventSplittingEnabled = false
    }

    private fun cacheBudget(): Int = min(Runtime.getRuntime().maxMemory() / 4, 96L * 1024 * 1024).toInt()

    // ------------------------------------------------------------- document

    /** Shows [document] with its [marks]; [page] is where reading starts. */
    fun setDocument(document: BookDocument, store: BookInkStore, marks: BookMarks, page: Int) {
        doc = document
        inkStore = store
        inks.clear(); inkLoading.clear()
        selection = null; drag.reset(); highlightPage = -1; highlightRects = emptyList()
        io.execute {
            val files = store.pagesWithInk()
            post { inkFiles.clear(); inkFiles += files; layer.invalidate() }
        }
        unitH = FloatArray(document.pageCount) { document.unitHeight(it) }
        this.marks = marks
        relayout()
        if (pendingPage < 0) pendingPage = page
        if (laidOut) { flushPending() }
    }

    private fun flushPending() {
        if (pendingPage < 0) return
        val focus = pendingFocus
        if (focus != null) focusOn(pendingPage, focus) else scrollToPage(pendingPage)
        pendingPage = -1; pendingFocus = null
    }

    /** New marks (notes, areas, bookmarks, rotations) from the session. */
    fun setMarks(next: BookMarks) {
        val turned = next.rotations != marks.rotations
        marks = next
        if (turned) {
            clearSelection()
            val keep = currentPage
            val within = pageOffsetFraction(keep)
            relayout()
            ty = -((layout.top(keep) + within * layout.height(keep)) * scale) + height / 3f
            clampAndApply()
            scheduleDetail()
        }
        layer.invalidate(); overlay.invalidate()
    }

    /** Where the reading line (a third down the screen) is within [page], 0..1. */
    private fun pageOffsetFraction(page: Int): Float {
        if (page !in 0 until pageCount) return 0f
        val y = toWorld(width / 2f, height / 3f)[1]
        return ((y - layout.top(page)) / layout.height(page)).coerceIn(0f, 1f)
    }

    private fun relayout() {
        val n = unitH.size
        rot = IntArray(n) { PageRotation.normalize(marks.rotation(it)) }
        boxScale = FloatArray(n)
        val aspects = FloatArray(n)
        unitToBox = Array(n) { i ->
            val w = BookUnits.WIDTH; val h = unitH[i]
            val (bw, bh) = PageRotation.boxSize(w, h, rot[i])
            boxScale[i] = BookUnits.WIDTH / bw
            aspects[i] = bh / bw
            Matrix().apply {
                when (rot[i]) {
                    90 -> { setRotate(90f); postTranslate(h, 0f) }
                    180 -> { setRotate(180f); postTranslate(w, h) }
                    270 -> { setRotate(270f); postTranslate(0f, w) }
                }
                postScale(boxScale[i], boxScale[i])
            }
        }
        layout = ColumnLayout(aspects)
        docGen++
        details.clear()
        baseRequested.clear()
        clampAndApply()
    }

    /** Stops background work; the session closes the document. */
    fun release() {
        removeCallbacks(detailRunnable)
        flingAnim?.cancel(); zoomAnim?.cancel()
        io.shutdown()
        docGen++; detailGen++
        bases.evictAll(); details.clear()
        doc = null
    }

    fun rotation(page: Int) = rot.getOrElse(page) { 0 }

    /** Unrotated page units -> page box units (the page as shown, 1000 wide). */
    fun unitToBoxMatrix(page: Int): Matrix = Matrix(unitToBox[page])

    /** Page box height in units (width is 1000). */
    fun boxHeight(page: Int): Float = layout.height(page)

    fun unitHeight(page: Int): Float = unitH[page]

    /** Ink of [page] as it is now (loaded on demand). */
    fun inkOf(page: Int): PageInk = inks[page] ?: loadInkNow(page)

    private fun loadInkNow(page: Int): PageInk {
        val ink = if (page in inkFiles) inkStore?.load(page) ?: PageInk() else PageInk()
        inks[page] = ink
        return ink
    }

    private fun ensureInk(page: Int) {
        if (page in inks || page in inkLoading) return
        if (page !in inkFiles) { inks[page] = PageInk(); return }
        val store = inkStore ?: return
        inkLoading += page
        runCatching {
            io.execute {
                val ink = store.load(page)
                post {
                    inkLoading -= page
                    if (page !in inks) { inks[page] = ink; layer.invalidate() }
                }
            }
        }
    }

    // ------------------------------------------------------------ transform

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val margin = 28f * density
        // Landscape: the page about as tall as the screen; portrait: full width.
        val oldFit = fitScale
        fitScale = min((w - 2 * margin) / BookUnits.WIDTH, (h * 1.12f) / (BookUnits.WIDTH * 1.414f))
        val px = RenderMath.baseWidth(fitScale * BookUnits.WIDTH)
        if (px != basePx) { basePx = px; bases.evictAll(); baseRequested.clear(); docGen++ }
        if (!laidOut) {
            scale = fitScale
            tx = (w - BookUnits.WIDTH * scale) / 2
            ty = 24f * density
            laidOut = true
            if (doc != null) flushPending()
        } else {
            scale = (scale / oldFit * fitScale).coerceIn(minScale(), maxScale())
        }
        clampAndApply()
        scheduleDetail()
    }

    private fun minScale() = fitScale * 0.4f
    private fun maxScale() = fitScale * 10f

    /** Allowed translation: x range, y range. */
    private fun bounds(): FloatArray {
        val w = width.toFloat(); val h = height.toFloat()
        val m = 24f * density
        val contentW = BookUnits.WIDTH * scale
        val contentH = layout.totalHeight * scale
        val x0: Float; val x1: Float
        if (contentW <= w) { x0 = 0f; x1 = w - contentW } else { x0 = w - contentW - m; x1 = m }
        val y1 = m
        val y0 = min(y1, h - contentH - 96f * density)
        return floatArrayOf(x0, x1, y0, y1)
    }

    private fun clampAndApply() {
        if (width == 0) return
        val b = bounds()
        tx = tx.coerceIn(b[0], b[1])
        ty = ty.coerceIn(b[2], b[3])
        view.setScale(scale, scale)
        view.postTranslate(tx, ty)
        view.invert(inverse)
        layer.invalidate()
        overlay.invalidate()
        reportArea()
        reportSelection()
        reportVisiblePage()
    }

    private var lastReported = -1
    val currentPage get() = lastReported.coerceAtLeast(0)

    private fun reportVisiblePage() {
        if (pageCount == 0 || height == 0) return
        val y = toWorld(width / 2f, height / 3f)[1]
        val i = layout.nearest(y).coerceIn(0, pageCount - 1)
        if (i != lastReported) { lastReported = i; listener?.onVisiblePageChanged(i) }
    }

    fun scrollToPage(index: Int) {
        if (pageCount == 0) return
        val i = index.coerceIn(0, pageCount - 1)
        flingAnim?.cancel()
        ty = -(layout.top(i) * scale) + 24f * density
        clampAndApply()
        scheduleDetail()
    }

    private fun toWorld(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { inverse.mapPoints(it) }

    /** Unrotated units of [page] -> column units. */
    private fun unitToWorld(page: Int): Matrix = Matrix(unitToBox[page]).apply { postTranslate(0f, layout.top(page)) }

    /** Unrotated units of [page] -> view pixels. */
    private fun unitToView(page: Int): Matrix = unitToWorld(page).apply { postConcat(view) }

    /** View point -> unrotated units on [page]. */
    private fun viewToUnit(page: Int, x: Float, y: Float): FloatArray {
        val inv = Matrix().also { unitToView(page).invert(it) }
        return floatArrayOf(x, y).also { inv.mapPoints(it) }
    }

    /** View point -> page box units on [page] (as shown, 1000 wide). */
    private fun viewToBox(page: Int, x: Float, y: Float): FloatArray {
        val w = toWorld(x, y)
        return floatArrayOf(w[0], w[1] - layout.top(page))
    }

    private fun pageViewRect(page: Int): RectF =
        RectF(0f, layout.top(page), BookUnits.WIDTH, layout.bottom(page)).also { view.mapRect(it) }

    private fun visiblePages(): IntRange {
        if (pageCount == 0 || height == 0) return IntRange.EMPTY
        val top = toWorld(0f, 0f)[1]
        val bottom = toWorld(0f, height.toFloat())[1]
        return layout.visible(top, bottom)
    }

    // -------------------------------------------------------------- render

    private fun requestBase(page: Int) {
        val d = doc ?: return
        if (page !in 0 until pageCount) return
        val r = rot[page]
        if (bases.get(page)?.rotation == r || page in baseRequested) return
        baseRequested += page
        val w = basePx
        val h = (w * layout.height(page) / BookUnits.WIDTH).roundToInt().coerceAtLeast(1)
        val m = d.pointsToUnits(page).apply { postConcat(unitToBox[page]); postScale(w / BookUnits.WIDTH, w / BookUnits.WIDTH) }
        val gen = docGen
        d.post {
            if (gen != docGen) return@post
            val bmp = runCatching {
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.renderBlocking(page, full, m)
                // Half the memory: pages are opaque, 565 is plenty for the cached overview.
                full.copy(Bitmap.Config.RGB_565, false).also { full.recycle() }
            }.getOrNull()
            post {
                baseRequested -= page
                if (bmp != null && gen == docGen && page < rot.size && rot[page] == r) {
                    bases.put(page, Base(bmp, r))
                    layer.invalidate()
                }
            }
        }
    }

    private fun scheduleDetail() {
        removeCallbacks(detailRunnable)
        postDelayed(detailRunnable, 140)
    }

    /** Renders the visible part of each visible page at screen resolution. */
    private fun requestDetails() {
        val d = doc ?: return
        val gen = ++detailGen
        val visible = visiblePages()
        details.keys.retainAll(visible.toSet())
        if (!RenderMath.needsDetail(BookUnits.WIDTH * scale, basePx)) {
            if (details.isNotEmpty()) { details.clear(); layer.invalidate() }
            return
        }
        for (page in visible) {
            val v = pageViewRect(page)
            if (!v.intersect(0f, 0f, width.toFloat(), height.toFloat())) continue
            val left = v.left.toInt(); val top = v.top.toInt()
            val w = (v.right.roundToInt() - left); val h = (v.bottom.roundToInt() - top)
            if (w <= 0 || h <= 0) continue
            val world = RectF(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat()).also { inverse.mapRect(it) }
            val m = d.pointsToUnits(page).apply {
                postConcat(unitToWorld(page)); postConcat(view); postTranslate(-left.toFloat(), -top.toFloat())
            }
            val r = rot[page]
            d.post {
                if (gen != detailGen) return@post
                val bmp = runCatching {
                    val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    d.renderBlocking(page, full, m)
                    full.copy(Bitmap.Config.RGB_565, false).also { full.recycle() }
                }.getOrNull() ?: return@post
                post {
                    if (gen == detailGen && page < rot.size && rot[page] == r) {
                        details[page] = Detail(world, bmp, r)
                        layer.invalidate()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ history

    /** Replays a step from the session's history: forward (redo) or backward (undo). */
    fun applyEdits(edits: List<PageEdit>, forward: Boolean) {
        clearSelection()
        val order = if (forward) edits else edits.asReversed()
        for (edit in order) {
            val page = edit.page.toIntOrNull() ?: continue
            if (page !in 0 until pageCount) continue
            val ink = inkOf(page)
            if (forward) ink.apply(edit) else ink.revert(edit)
        }
        edits.mapNotNullTo(HashSet()) { it.page.toIntOrNull() }.forEach { listener?.onInkChanged(it) }
        layer.invalidate(); overlay.invalidate()
    }

    /** Applies a new user action and reports it as one undo step. */
    private fun commit(edits: List<PageEdit>) {
        val real = edits.filterNot { it.isEmpty }
        if (real.isEmpty()) return
        for (edit in real) edit.page.toIntOrNull()?.let { inkOf(it).apply(edit) }
        listener?.onInkStep(real)
        real.mapNotNullTo(HashSet()) { it.page.toIntOrNull() }.forEach { listener?.onInkChanged(it) }
        layer.invalidate(); overlay.invalidate()
    }

    // ----------------------------------------------------------- selection

    private class Selection(val page: Int, val strokes: Set<Long>, val items: Set<Long>, val bounds: RectF)
    private var selection: Selection? = null
    /** Live move/scale of the selection, unrotated page units. */
    private val drag = Matrix()

    val hasSelection get() = selection != null

    fun clearSelection() {
        if (selection == null) return
        selection = null
        drag.reset()
        layer.invalidate(); overlay.invalidate()
        listener?.onSelectionChanged(null)
    }

    private fun select(page: Int, strokes: Set<Long>, items: Set<Long>) {
        val b = boundsOf(page, strokes, items)
        if (b == null) { clearSelection(); return }
        selection = Selection(page, strokes, items, b)
        drag.reset()
        layer.invalidate(); overlay.invalidate()
        reportSelection()
    }

    private fun boundsOf(page: Int, strokes: Set<Long>, items: Set<Long>): RectF? {
        val ink = inkOf(page)
        var r: RectF? = null
        for (s in ink.strokes) if (s.id in strokes) r = (r ?: RectF(s.bounds)).also { it.union(s.bounds) }
        for (o in ink.items) if (o.id in items) { val b = itemBounds(page, o); r = (r ?: RectF(b)).also { it.union(b) } }
        return r
    }

    /** Selection frame in view pixels (with the live drag). */
    private fun selectionViewRect(s: Selection): RectF =
        RectF(s.bounds).also { drag.mapRect(it); unitToView(s.page).mapRect(it) }

    private fun reportSelection() {
        val s = selection ?: return
        listener?.onSelectionChanged(selectionViewRect(s))
    }

    fun deleteSelection() {
        val s = selection ?: return
        clearSelection()
        commit(listOf(inkOf(s.page).removeEdit(s.page.toString(), s.strokes, s.items)))
    }

    fun recolorSelection(color: Int) {
        val s = selection ?: return
        val ink = inkOf(s.page)
        val strokes = ink.strokes.filter { it.id in s.strokes }.associate { it.id to it.recolored(color, newId()) }
        val items = ink.items.filter { it.id in s.items && it is TextItem }.associate { it.id to (it as TextItem).copy(id = newId(), color = color) }
        commit(listOf(ink.replaceEdit(s.page.toString(), strokes, items)))
        select(s.page, strokes.values.mapTo(HashSet()) { it.id } + (s.strokes - strokes.keys), items.values.mapTo(HashSet()) { it.id } + (s.items - items.keys))
    }

    private fun commitDrag() {
        val s = selection ?: return
        if (drag.isIdentity) return
        val ink = inkOf(s.page)
        val m = Matrix(drag)
        val strokes = ink.strokes.filter { it.id in s.strokes }.associate { it.id to it.transformed(m, newId()) }
        val items = ink.items.filter { it.id in s.items }.associate { it.id to it.transformed(m, newId()) }
        drag.reset()
        commit(listOf(ink.replaceEdit(s.page.toString(), strokes, items)))
        select(s.page, strokes.values.mapTo(HashSet()) { it.id }, items.values.mapTo(HashSet()) { it.id })
    }

    // ---------------------------------------------------------------- text

    /** Adds [item] on [page], or replaces [old] by it; null or blank text deletes [old]. */
    fun putText(page: Int, item: TextItem?, old: TextItem?) {
        if (page !in 0 until pageCount) return
        val ink = inkOf(page)
        val removed = old?.let { o -> ink.items.indexOfFirst { it.id == o.id }.takeIf { it >= 0 }?.let { listOf(it to ink.items[it]) } } ?: emptyList()
        val added = if (item == null || item.text.isBlank()) emptyList() else listOf(item)
        commit(listOf(PageEdit(page.toString(), emptyList(), emptyList(), removed, added)))
    }

    /** Width for new text at (x, y): to the right page edge as shown, in unrotated units. */
    fun defaultTextWidth(page: Int, x: Float, y: Float): Float {
        val p = floatArrayOf(x, y).also { unitToBox[page].mapPoints(it) }
        return ((BookUnits.WIDTH - p[0] - 40f).coerceAtLeast(180f)) / boxScale[page]
    }

    /** Scale from page box units to unrotated units of [page] (text looks the same size on any page). */
    fun boxToUnitScale(page: Int): Float = 1f / boxScale.getOrElse(page) { 1f }

    /** Text is drawn upright on a turned page: turned back about its anchor. */
    private fun textMatrix(page: Int, t: TextItem): Matrix? =
        rot.getOrElse(page) { 0 }.takeIf { it != 0 }?.let { r -> Matrix().apply { setRotate(-r.toFloat(), t.x, t.y) } }

    private fun itemBounds(page: Int, item: PageItem): RectF {
        val b = item.bounds()
        if (item is TextItem) textMatrix(page, item)?.mapRect(b)
        return b
    }

    private fun textAt(page: Int, x: Float, y: Float): TextItem? =
        inkOf(page).items.asReversed().firstOrNull { it is TextItem && itemBounds(page, it).contains(x, y) } as? TextItem

    private fun drawItem(canvas: Canvas, page: Int, item: PageItem) {
        val m = (item as? TextItem)?.let { textMatrix(page, it) }
        if (m == null) { ItemPainter.draw(canvas, item); return }
        canvas.save(); canvas.concat(m); ItemPainter.draw(canvas, item); canvas.restore()
    }

    /** Typed text of [page] painted on [canvas] already set to unrotated units (clippings). */
    fun paintItems(canvas: Canvas, page: Int) {
        for (it in inkOf(page).items) drawItem(canvas, page, it)
    }

    // ----------------------------------------------------------- highlight

    private var highlightPage = -1
    private var highlightRects: List<RectF> = emptyList()

    /**
     * Marks [rects] (unrotated units) on [page], e.g. a search hit, and
     * scrolls so they are in view. Stays until [clearHighlight] or a tap.
     */
    fun highlight(page: Int, rects: List<RectF>) {
        val union = rects.fold(null as RectF?) { acc, r -> (acc ?: RectF(r)).also { it.union(r) } }
        highlightRects = rects.map { RectF(it) }
        if (pageCount == 0 || !laidOut) {
            highlightPage = page
            pendingPage = page; pendingFocus = union
            return
        }
        val p = page.coerceIn(0, pageCount - 1)
        highlightPage = p
        if (union != null) focusOn(p, union) else scrollToPage(p)
        layer.invalidate()
    }

    fun clearHighlight() {
        if (highlightPage < 0) return
        highlightPage = -1; highlightRects = emptyList()
        layer.invalidate()
    }

    /** Scrolls so [rect] (unrotated units on [page]) sits in the upper third, centred when zoomed in. */
    private fun focusOn(page: Int, rect: RectF) {
        if (page !in 0 until pageCount) return
        flingAnim?.cancel()
        val w = RectF(rect).also { unitToWorld(page).mapRect(it) }
        ty = -(w.top * scale) + height * 0.3f
        if (BookUnits.WIDTH * scale > width) tx = width / 2f - w.centerX() * scale
        clampAndApply()
        scheduleDetail()
    }

    // ---------------------------------------------------------------- area

    private class Area(val page: Int, var rect: RectF)
    private var area: Area? = null

    /** The marked rectangle: page and rectangle in page box units (as shown). */
    fun areaSelection(): Pair<Int, RectF>? = area?.let { it.page to RectF(it.rect) }

    fun clearArea() {
        if (area == null) return
        area = null
        overlay.invalidate()
        listener?.onAreaChanged(null)
    }

    private fun areaViewRect(a: Area): RectF =
        RectF(a.rect).also { it.offset(0f, layout.top(a.page)); view.mapRect(it) }

    private fun reportArea() {
        val a = area ?: return
        if (mode == Mode.AREA) return
        listener?.onAreaChanged(areaViewRect(a))
    }

    /** Box rectangle -> unrotated units, for storing a marked area. */
    fun boxToUnits(page: Int, r: RectF): RectF {
        val inv = Matrix().also { unitToBox[page].invert(it) }
        return RectF(r).also { inv.mapRect(it) }
    }

    // --------------------------------------------------------------- input

    private enum class Mode { NONE, DRAW, ERASE, PAN, PEN_PAN, AREA, NOTE_TAP, LASSO, TEXT_TAP, MOVE_SEL, SCALE_SEL, IGNORE }
    private var mode = Mode.NONE
    private var strokeId: InProgressStrokeId? = null
    private var stylusPointer = -1
    private var eraseWhole = false
    private var eraseLast: FloatArray? = null
    private val eraseStep = ArrayList<PageEdit>()
    private var lastPenNear = 0L
    private var areaByFinger = false
    private var areaStart = FloatArray(2)
    private var downX = 0f; private var downY = 0f
    private var gesturePage = -1
    /** Lasso loop in unrotated units of [gesturePage]. */
    private val lasso = ArrayList<Float>()
    private var selStart = FloatArray(2)
    private var selAnchor = FloatArray(2)

    // finger pan / pinch
    private var panId = -1
    private var lastX = 0f; private var lastY = 0f
    private var startX = 0f; private var startY = 0f
    private var panning = false
    private var pinchDist = 0f
    private var pinchMidX = 0f; private var pinchMidY = 0f
    private var velocity: VelocityTracker? = null
    private var lastTapAt = 0L
    private var lastTapX = 0f; private var lastTapY = 0f

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private val penModes = setOf(Mode.DRAW, Mode.ERASE, Mode.PEN_PAN, Mode.AREA, Mode.NOTE_TAP, Mode.LASSO, Mode.TEXT_TAP, Mode.MOVE_SEL, Mode.SCALE_SEL, Mode.IGNORE)

    // ---- touch fence: only pointers that went down inside this view ----
    private val owned = HashSet<Int>()

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) owned.clear()
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i); val y = e.getY(i)
            if (x >= 0f && y >= 0f && x < width && y < height) owned += e.getPointerId(i)
        }
        val all = (0 until e.pointerCount).all { e.getPointerId(it) in owned }
        val event = if (all) e else TouchFence.split(e, owned)
        when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> owned.clear()
            MotionEvent.ACTION_POINTER_UP -> owned -= e.getPointerId(e.actionIndex)
        }
        // Only foreign pointers (a hand on the notebook next door): not ours.
        if (event == null) return false
        return try { super.dispatchTouchEvent(event) } finally { if (event !== e) event.recycle() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doc == null || pageCount == 0) return true
        val action = e.actionMasked
        val index = e.actionIndex
        val down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        if (action == MotionEvent.ACTION_DOWN) { flingAnim?.cancel(); zoomAnim?.cancel() }
        // Pen or finger on the lasso selection: move or scale it.
        val restingHand = !isPen(e, index) && SystemClock.uptimeMillis() - lastPenNear < 400
        if (down && mode == Mode.NONE && selection != null && !restingHand && beginSelectionDrag(e, index)) return true
        // A new pen contact takes over from any finger gesture.
        if (down && isPen(e, index) && (mode == Mode.NONE || mode == Mode.PAN)) {
            if (mode == Mode.PAN) endPan()
            beginPen(e, index)
            return true
        }
        // A second finger while a finger draws a rectangle: it was a pinch after all.
        if (mode == Mode.AREA && areaByFinger && action == MotionEvent.ACTION_POINTER_DOWN) {
            area = null; overlay.invalidate()
            endPen()
            mode = Mode.PAN
            panId = e.getPointerId(0)
            startPinch(e)
            return true
        }
        if (mode in penModes) { penEvent(e); return true }
        // Fingers: ignored while the pen is near (resting hand).
        if (SystemClock.uptimeMillis() - lastPenNear < 400) {
            if (mode == Mode.PAN) endPan()
            return true
        }
        // One finger draws the rectangle in the area tool; two fingers still zoom.
        if (action == MotionEvent.ACTION_DOWN && tool == BookTool.AREA && noteAt(e.x, e.y) == null) {
            beginTool(e, 0, byFinger = true)
            return true
        }
        fingerEvent(e)
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
            mode = Mode.ERASE
            eraseWhole = tool == BookTool.STROKE_ERASER
            eraseLast = null
            eraseStep.clear()
            eraseTo(x, y)
            overlay.eraserAt(x, y)
            return
        }
        when (tool) {
            BookTool.READ -> {
                mode = Mode.PEN_PAN
                lastX = x; lastY = y; startX = x; startY = y; panning = false
            }
            BookTool.AREA, BookTool.NOTE -> beginTool(e, index, byFinger = false)
            BookTool.LASSO -> {
                val page = layout.pageAt(toWorld(x, y)[1])
                if (page < 0) { mode = Mode.IGNORE; clearSelection(); return }
                inkOf(page)
                gesturePage = page
                val u = viewToUnit(page, x, y)
                lasso.clear(); lasso += u[0]; lasso += u[1]
                mode = Mode.LASSO
            }
            BookTool.TEXT -> mode = Mode.TEXT_TAP
            BookTool.PEN, BookTool.MARKER -> {
                val page = layout.pageAt(toWorld(x, y)[1])
                if (page < 0) { mode = Mode.IGNORE; return }
                inkOf(page)
                val marker = tool == BookTool.MARKER
                // Brush size in unrotated units, so it looks the same on a turned page.
                val size = (if (marker) markerSize else penSize) / boxScale[page]
                val color = if (marker) markerColor else penColor
                val kind = if (marker) ToolKind.MARKER else ToolKind.PEN
                predictor.record(e)
                val id = inProgress.startStroke(e, stylusPointer, Brushes.brush(kind, color, size), Matrix(inverse), unitToWorld(page))
                pending[id] = Pending(page, kind, color, size)
                strokeId = id
                mode = Mode.DRAW
                overlay.hide()
            }
            else -> mode = Mode.IGNORE
        }
    }

    private fun beginSelectionDrag(e: MotionEvent, index: Int): Boolean {
        val s = selection ?: return false
        val frame = selectionViewRect(s)
        val x = e.getX(index); val y = e.getY(index)
        val onHandle = hypot(x - frame.right, y - frame.bottom) < 30f * density
        val pad = 12f * density
        val inside = x in frame.left - pad..frame.right + pad && y in frame.top - pad..frame.bottom + pad
        if (!onHandle && !inside) {
            clearSelection()
            return false
        }
        stylusPointer = e.getPointerId(index)
        gesturePage = s.page
        downX = x; downY = y
        selStart = viewToUnit(s.page, x, y)
        selAnchor = viewToUnit(s.page, frame.left, frame.top)
        mode = if (onHandle) Mode.SCALE_SEL else Mode.MOVE_SEL
        if (isPen(e, index)) lastPenNear = SystemClock.uptimeMillis()
        return true
    }

    private fun addLasso(x: Float, y: Float) {
        val p = viewToUnit(gesturePage, x, y)
        val n = lasso.size
        if (n >= 2 && hypot(p[0] - lasso[n - 2], p[1] - lasso[n - 1]) < 2f) return
        lasso += p[0]; lasso += p[1]
    }

    private fun finishLasso() {
        if (lasso.size < 6 || gesturePage < 0) { clearSelection(); return }
        val poly = lasso.toFloatArray()
        val ink = inkOf(gesturePage)
        val strokes = ink.select(poly).first
        val items = ink.items.filter { val b = itemBounds(gesturePage, it); PageInk.contains(poly, b.centerX(), b.centerY()) }.mapTo(HashSet()) { it.id }
        if (strokes.isEmpty() && items.isEmpty()) clearSelection() else {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            select(gesturePage, strokes, items)
        }
    }

    /** Area or note tool, by pen or (one) finger. */
    private fun beginTool(e: MotionEvent, index: Int, byFinger: Boolean) {
        stylusPointer = e.getPointerId(index)
        val x = e.getX(index); val y = e.getY(index)
        downX = x; downY = y
        val page = layout.pageAt(toWorld(x, y)[1])
        if (tool == BookTool.NOTE) {
            mode = Mode.NOTE_TAP
            return
        }
        if (page < 0) { mode = Mode.IGNORE; return }
        areaByFinger = byFinger
        mode = Mode.AREA
        areaStart = viewToBox(page, x, y)
        area = Area(page, RectF(areaStart[0], areaStart[1], areaStart[0], areaStart[1]))
        listener?.onAreaChanged(null)
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
                    Mode.PEN_PAN -> {
                        if (!panning && hypot(x - startX, y - startY) > touchSlop) panning = true
                        if (panning) { panBy(x - lastX, y - lastY); }
                        lastX = x; lastY = y
                    }
                    Mode.LASSO -> {
                        for (h in 0 until e.historySize) addLasso(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        addLasso(x, y)
                        overlay.invalidate()
                    }
                    Mode.MOVE_SEL -> {
                        val p = viewToUnit(gesturePage, x, y)
                        drag.setTranslate(p[0] - selStart[0], p[1] - selStart[1])
                        layer.invalidate(); overlay.invalidate(); reportSelection()
                    }
                    Mode.SCALE_SEL -> {
                        val p = viewToUnit(gesturePage, x, y)
                        val ax = selAnchor[0]; val ay = selAnchor[1]
                        val d0 = hypot(selStart[0] - ax, selStart[1] - ay).coerceAtLeast(1f)
                        val f = (hypot(p[0] - ax, p[1] - ay) / d0).coerceIn(0.15f, 8f)
                        drag.setScale(f, f, ax, ay)
                        layer.invalidate(); overlay.invalidate(); reportSelection()
                    }
                    Mode.AREA -> {
                        val a = area ?: return
                        val p = viewToBox(a.page, x, y)
                        val h = layout.height(a.page)
                        a.rect = RectF(
                            min(areaStart[0], p[0]).coerceIn(0f, BookUnits.WIDTH), min(areaStart[1], p[1]).coerceIn(0f, h),
                            max(areaStart[0], p[0]).coerceIn(0f, BookUnits.WIDTH), max(areaStart[1], p[1]).coerceIn(0f, h),
                        )
                        overlay.invalidate()
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) != stylusPointer) return
                val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
                val tap = hypot(x - downX, y - downY) < touchSlop * 2
                when (mode) {
                    Mode.DRAW -> strokeId?.let { inProgress.finishStroke(e, stylusPointer, it) }
                    Mode.ERASE -> finishErase()
                    Mode.PEN_PAN -> if (!panning) {
                        val note = noteAt(x, y)
                        if (note != null) listener?.onNoteOpen(note) else clearHighlight()
                    }
                    Mode.AREA -> finishArea()
                    Mode.LASSO -> finishLasso()
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> commitDrag()
                    Mode.TEXT_TAP -> if (tap) textTap(x, y)
                    Mode.NOTE_TAP -> if (tap) {
                        val existing = noteAt(x, y)
                        if (existing != null) listener?.onNoteOpen(existing)
                        else {
                            val page = layout.pageAt(toWorld(x, y)[1])
                            if (page >= 0) {
                                val u = viewToUnit(page, x, y)
                                listener?.onNoteRequest(page, u[0], u[1])
                            }
                        }
                    }
                    else -> Unit
                }
                endPen()
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.DRAW -> strokeId?.let { inProgress.cancelStroke(it, e); pending.remove(it) }
                    Mode.ERASE -> finishErase()
                    Mode.AREA -> { area = null; listener?.onAreaChanged(null) }
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> { drag.reset(); layer.invalidate(); overlay.invalidate(); reportSelection() }
                    else -> Unit
                }
                endPen()
            }
        }
    }

    private fun endPen() {
        if (mode == Mode.PEN_PAN && panning) scheduleDetail()
        mode = Mode.NONE; strokeId = null; stylusPointer = -1; eraseLast = null
        panning = false; areaByFinger = false
        lasso.clear()
        overlay.hide()
    }

    /** Text tool: edit the text under (x, y) or start new text there. */
    private fun textTap(x: Float, y: Float) {
        val page = layout.pageAt(toWorld(x, y)[1])
        if (page < 0) return
        val u = viewToUnit(page, x, y)
        listener?.onTextRequest(page, u[0], u[1], textAt(page, u[0], u[1]))
    }

    private fun finishArea() {
        val a = area ?: return
        // Too small to mean anything: treat as a tap that clears.
        if (a.rect.width() < 16f || a.rect.height() < 16f) { clearArea(); return }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        mode = Mode.NONE
        listener?.onAreaChanged(areaViewRect(a))
        overlay.invalidate()
    }

    private fun eraseTo(x: Float, y: Float) {
        val w = toWorld(x, y)
        val last = eraseLast ?: w
        eraseLast = w
        val candidates = listOf(layout.pageAt(last[1]), layout.pageAt(w[1])).filter { it >= 0 }.toSet()
        for (p in candidates) {
            val ink = inkOf(p)
            if (ink.strokes.isEmpty()) continue
            val inv = Matrix().also { unitToWorld(p).invert(it) }
            val pts = floatArrayOf(last[0], last[1], w[0], w[1]).also { inv.mapPoints(it) }
            val radius = eraserRadiusDp * density / (scale * boxScale[p])
            val edit = ink.erase(p.toString(), pts[0], pts[1], pts[2], pts[3], radius, eraseWhole, ::newId) ?: continue
            eraseStep += edit
            layer.invalidate()
        }
    }

    private fun finishErase() {
        if (eraseStep.isEmpty()) return
        listener?.onInkStep(eraseStep.toList())
        eraseStep.mapTo(HashSet()) { it.page.toInt() }.forEach { listener?.onInkChanged(it) }
        eraseStep.clear()
    }

    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        val step = ArrayList<PageEdit>()
        for ((id, stroke) in strokes) {
            val p = pending.remove(id) ?: continue
            val ink = inkOf(p.page)
            val s = PageStroke(newId(), p.tool, p.color, p.size, stroke)
            ink.add(s)
            step += PageEdit(p.page.toString(), emptyList(), listOf(s))
        }
        layer.invalidate()
        // Same frame as the page layer draws them: no flicker at the handoff.
        inProgress.removeFinishedStrokes(strokes.keys)
        if (step.isNotEmpty()) {
            listener?.onInkStep(step)
            step.mapTo(HashSet()) { it.page.toInt() }.forEach { listener?.onInkChanged(it) }
        }
    }

    private fun panBy(dx: Float, dy: Float) {
        // At fit width one finger only scrolls; two fingers move freely.
        val lockX = BookUnits.WIDTH * scale <= width && pinchDist == 0f
        if (!lockX) tx += dx
        ty += dy
        clampAndApply()
    }

    private fun fingerEvent(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panId = e.getPointerId(0)
                lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                panning = false
                mode = Mode.PAN
                pinchDist = 0f
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) startPinch(e)
            MotionEvent.ACTION_MOVE -> {
                if (mode != Mode.PAN) return
                velocity?.addMovement(e)
                if (e.pointerCount >= 2 && pinchDist > 0f) {
                    val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val newScale = (scale * d / pinchDist).coerceIn(minScale(), maxScale())
                    val f = newScale / scale
                    // Zoom about the fingers' midpoint and follow its movement: pinch and pan at once.
                    tx = mx - (pinchMidX - tx) * f
                    ty = my - (pinchMidY - ty) * f
                    scale = newScale
                    pinchDist = d; pinchMidX = mx; pinchMidY = my
                    clampAndApply()
                } else {
                    val i = e.findPointerIndex(panId).takeIf { it >= 0 } ?: return
                    if (!panning && hypot(e.getX(i) - startX, e.getY(i) - startY) > touchSlop) panning = true
                    if (panning) panBy(e.getX(i) - lastX, e.getY(i) - lastY)
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
                scheduleDetail()
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                if (!panning && mode == Mode.PAN) tap(e.x, e.y)
                else if (panning) fling()
                endPan()
            }
            MotionEvent.ACTION_CANCEL -> endPan()
        }
    }

    private fun tap(x: Float, y: Float) {
        noteAt(x, y)?.let { listener?.onNoteOpen(it); return }
        if (area != null) { clearArea(); return }
        if (selection != null) { clearSelection(); return }
        if (tool == BookTool.TEXT) { textTap(x, y); return }
        clearHighlight()
        if (tool == BookTool.NOTE) {
            val page = layout.pageAt(toWorld(x, y)[1])
            if (page >= 0) { val u = viewToUnit(page, x, y); listener?.onNoteRequest(page, u[0], u[1]) }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 300 && hypot(x - lastTapX, y - lastTapY) < 48f * density) {
            lastTapAt = 0L
            val target = if (scale > fitScale * 1.3f) fitScale else fitScale * 2.5f
            animateZoom(target, x, y)
        } else {
            lastTapAt = now; lastTapX = x; lastTapY = y
        }
    }

    private fun startPinch(e: MotionEvent) {
        pinchDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
        pinchMidX = (e.getX(0) + e.getX(1)) / 2; pinchMidY = (e.getY(0) + e.getY(1)) / 2
        panning = true
    }

    private fun endPan() {
        if (panning) scheduleDetail()
        mode = Mode.NONE; panId = -1; panning = false; pinchDist = 0f
        velocity?.recycle(); velocity = null
    }

    // ------------------------------------------------------ fling and zoom

    private val scroller = OverScroller(context)
    private var flingAnim: ValueAnimator? = null
    private var zoomAnim: ValueAnimator? = null

    private fun fling() {
        val v = velocity ?: return
        v.computeCurrentVelocity(1000)
        val vy = v.getYVelocity(panId.coerceAtLeast(0)).takeIf { !it.isNaN() } ?: 0f
        val vxRaw = v.getXVelocity(panId.coerceAtLeast(0)).takeIf { !it.isNaN() } ?: 0f
        val vx = if (BookUnits.WIDTH * scale <= width) 0f else vxRaw
        if (abs(vy) < minFling * 2 && abs(vx) < minFling * 2) return
        val b = bounds()
        scroller.fling(tx.roundToInt(), ty.roundToInt(), vx.roundToInt(), vy.roundToInt(),
            b[0].roundToInt(), b[1].roundToInt(), b[2].roundToInt(), b[3].roundToInt())
        flingAnim?.cancel()
        flingAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            addUpdateListener {
                if (scroller.computeScrollOffset()) {
                    tx = scroller.currX.toFloat(); ty = scroller.currY.toFloat()
                    clampAndApply()
                } else { cancel() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { scroller.forceFinished(true); scheduleDetail() }
            })
            start()
        }
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        val from = scale
        val anchor = toWorld(fx, fy)
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                scale = from + (target - from) * t
                tx = fx - anchor[0] * scale
                ty = fy - anchor[1] * scale
                clampAndApply()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = scheduleDetail()
            })
            start()
        }
    }

    // ------------------------------------------------------------- hover

    /** Pen hovering at a view point (from Compose, which receives hover first). */
    fun penHover(x: Float, y: Float) {
        lastPenNear = SystemClock.uptimeMillis()
        overlay.hoverAt(x, y)
    }

    fun penHoverExit() {
        overlay.hide()
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 0 && isPen(e, 0)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> penHover(e.x, e.y)
                MotionEvent.ACTION_HOVER_EXIT -> penHoverExit()
            }
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ------------------------------------------------------------- notes

    private fun noteRect(n: BookNote): RectF? {
        if (n.page !in 0 until pageCount) return null
        val p = floatArrayOf(n.x, n.y).also { unitToView(n.page).mapPoints(it) }
        val s = NOTE_DP * density
        return RectF(p[0], p[1] - s, p[0] + s, p[1])
    }

    private fun noteAt(x: Float, y: Float): BookNote? {
        val visible = visiblePages()
        val slack = 6f * density
        return marks.notes.lastOrNull { n ->
            n.page in visible && noteRect(n)?.let { r -> x in r.left - slack..r.right + slack && y in r.top - slack..r.bottom + slack } == true
        }
    }

    // --------------------------------------------------------- rendering

    private inner class PageLayer : View(context) {
        private val renderer by lazy { CanvasStrokeRenderer.create() }
        private val identity = Matrix()
        private val paper = Paint().apply { color = Color.WHITE }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowRect = RectF()
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x1F000000 }
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val areaLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG)
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; textAlign = Paint.Align.CENTER; textSize = 22f * density }
        private val nodes = HashMap<Int, Pair<Long, Any>>()

        override fun onDraw(canvas: Canvas) {
            val visible = visiblePages()
            if (visible.isEmpty()) return
            nodes.keys.retainAll(visible.toSet())
            for (i in visible) {
                val r = pageViewRect(i)
                drawShadow(canvas, r)
                canvas.drawRect(r, paper)
                val base = bases.get(i)?.takeIf { it.rotation == rot[i] }
                if (base != null) canvas.drawBitmap(base.bmp, null, r, bitmapPaint)
                else {
                    requestBase(i)
                    canvas.drawText("${i + 1}", r.centerX(), r.centerY(), number)
                }
                details[i]?.takeIf { it.rotation == rot[i] }?.let { d ->
                    val dst = RectF(d.world).also { view.mapRect(it) }
                    canvas.save(); canvas.clipRect(r)
                    canvas.drawBitmap(d.bmp, null, dst, bitmapPaint)
                    canvas.restore()
                }
                canvas.save()
                canvas.concat(unitToView(i))
                drawAreas(canvas, i)
                ensureInk(i)
                inks[i]?.let { drawInk(canvas, i, it) }
                if (i == highlightPage) drawHighlight(canvas, i)
                canvas.restore()
                canvas.drawRect(r, edge)
                if (i in marks.bookmarks) drawRibbon(canvas, r)
            }
            // Next pages ready before they scroll in.
            for (k in 1..2) { requestBase(visible.last + k); requestBase(visible.first - k) }
        }

        private fun drawAreas(canvas: Canvas, page: Int) {
            val list = marks.areas
            if (list.isEmpty()) return
            val px = 1f / (scale * boxScale[page])
            areaFill.color = (accentColor and 0x00FFFFFF) or (0x1C shl 24)
            areaLine.color = (accentColor and 0x00FFFFFF) or (0xB0 shl 24)
            areaLine.strokeWidth = 2f * density * px
            for (a in list) if (a.page == page) {
                val rr = 6f * density * px
                canvas.drawRoundRect(a.left, a.top, a.right, a.bottom, rr, rr, areaFill)
                canvas.drawRoundRect(a.left, a.top, a.right, a.bottom, rr, rr, areaLine)
            }
        }

        private fun drawInk(canvas: Canvas, page: Int, ink: PageInk) {
            if (ink.strokes.isEmpty() && ink.items.isEmpty()) return
            // The lifted selection is drawn by the overlay, not here.
            val sel = selection?.takeIf { it.page == page }
            val hideS = sel?.strokes ?: emptySet()
            val hideI = sel?.items ?: emptySet()
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
                val key = ink.revision * 31 + rot[page] * 7 + (sel?.hashCode()?.toLong() ?: 0L)
                val cached = nodes[page]
                val node = (cached?.second as? RenderNode) ?: RenderNode("book-ink").also {
                    it.setPosition(0, 0, BookUnits.WIDTH.toInt(), unitH[page].roundToInt().coerceAtLeast(1))
                    it.clipToBounds = false
                }
                if (cached == null || cached.first != key) {
                    val rc = node.beginRecording()
                    try { paintInk(rc, page, ink, hideS, hideI) } finally { node.endRecording() }
                    nodes[page] = key to node
                }
                canvas.drawRenderNode(node)
            } else paintInk(canvas, page, ink, hideS, hideI)
        }

        /** Markers below ink, typed text on top, like in the notebook. [only] paints just the given ids. */
        fun paintInk(canvas: Canvas, page: Int, ink: PageInk, ids: Set<Long>, itemIds: Set<Long>, only: Boolean = false) {
            fun shown(id: Long, set: Set<Long>) = if (only) id in set else id !in set
            for (s in ink.strokes) if (s.tool == ToolKind.MARKER && shown(s.id, ids)) renderer.draw(canvas, s.stroke, identity)
            for (s in ink.strokes) if (s.tool != ToolKind.MARKER && shown(s.id, ids)) renderer.draw(canvas, s.stroke, identity)
            for (it in ink.items) if (shown(it.id, itemIds)) drawItem(canvas, page, it)
        }

        private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hlLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        /** Search hit or jump target: a soft marker-like box around the place. */
        private fun drawHighlight(canvas: Canvas, page: Int) {
            if (highlightRects.isEmpty()) return
            canvas.save()
            canvas.concat(unitToView(page))
            val px = 1f / (scale * boxScale[page])
            hlFill.color = (accentColor and 0x00FFFFFF) or (0x38 shl 24)
            hlLine.color = (accentColor and 0x00FFFFFF) or (0xC8 shl 24)
            hlLine.strokeWidth = 2f * density * px
            val pad = 3f * density * px
            val rr = 4f * density * px
            for (r in highlightRects) {
                canvas.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, rr, rr, hlFill)
                canvas.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, rr, rr, hlLine)
            }
            canvas.restore()
        }

        private fun drawShadow(canvas: Canvas, r: RectF) {
            for (k in 1..5) {
                val grow = k * 1.6f * density
                shadowRect.set(r.left - grow * 0.5f, r.top - grow * 0.3f + 2f * density, r.right + grow * 0.5f, r.bottom + grow + 2f * density)
                shadow.color = Color.argb(14 - k * 2, 40, 30, 15)
                canvas.drawRoundRect(shadowRect, grow, grow, shadow)
            }
        }

        /** Bookmark: a ribbon hanging over the top edge, like in the notebook. */
        private fun drawRibbon(canvas: Canvas, r: RectF) {
            ribbon.color = accentColor
            val w = 18f * density; val h = 42f * density
            val x = r.right - 48f * density
            val path = Path().apply {
                moveTo(x, r.top - 6f * density); lineTo(x + w, r.top - 6f * density); lineTo(x + w, r.top + h)
                lineTo(x + w / 2, r.top + h - 8f * density); lineTo(x, r.top + h); close()
            }
            canvas.drawPath(path, ribbon)
        }
    }

    /** Hover dot, eraser ring, the rectangle being drawn and the note pins. */
    private inner class Overlay : View(context) {
        private var x = 0f; private var y = 0f
        private var visible = false
        private var erasing = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f * density
            pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
        }
        private val veil = Paint()
        private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2D04B.toInt() }
        private val noteFold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9B23A.toInt() }
        private val noteLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x996B5A2A.toInt(); strokeWidth = 1.4f * density; strokeCap = Paint.Cap.ROUND }
        private val noteShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val path = Path()

        init { isClickable = false; isFocusable = false }

        fun hoverAt(px: Float, py: Float) { x = px; y = py; visible = true; erasing = false; invalidate() }
        fun eraserAt(px: Float, py: Float) { x = px; y = py; visible = true; erasing = true; invalidate() }
        fun hide() { visible = false; invalidate() }

        override fun onDraw(canvas: Canvas) {
            drawNotes(canvas)
            area?.let { drawArea(canvas, it) }
            drawSelection(canvas)
            if (mode == Mode.LASSO) drawLasso(canvas)
            if (!visible) return
            if (erasing || tool.erases) {
                ring.color = 0x99000000.toInt()
                canvas.drawCircle(x, y, eraserRadiusDp * density, ring)
                ring.color = 0x99FFFFFF.toInt()
                canvas.drawCircle(x, y, eraserRadiusDp * density - ring.strokeWidth, ring)
                return
            }
            when (tool) {
                BookTool.PEN, BookTool.MARKER -> {
                    val marker = tool == BookTool.MARKER
                    val size = if (marker) markerSize else penSize
                    fill.color = if (marker) Brushes.markerColor(markerColor) else penColor
                    fill.alpha = if (marker) 0x72 else 0xB0
                    canvas.drawCircle(x, y, max(2f * density, size * scale / 2), fill)
                }
                else -> {
                    ring.color = (accentColor and 0x00FFFFFF) or (0xCC shl 24)
                    canvas.drawCircle(x, y, 4f * density, ring)
                }
            }
        }

        private fun drawArea(canvas: Canvas, a: Area) {
            val r = areaViewRect(a)
            // Dim the rest of the page so the chosen part stands out.
            val page = pageViewRect(a.page)
            veil.color = 0x22000000
            canvas.save()
            canvas.clipRect(page)
            canvas.clipOutRectCompat(r)
            canvas.drawRect(page, veil)
            canvas.restore()
            dash.color = accentColor
            canvas.drawRoundRect(r, 6f * density, 6f * density, dash)
        }

        private val selFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * density
            pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density }

        private fun drawLasso(canvas: Canvas) {
            if (lasso.size < 4 || gesturePage !in 0 until pageCount) return
            val pts = lasso.toFloatArray().also { unitToView(gesturePage).mapPoints(it) }
            path.reset()
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
            dash.color = accentColor
            canvas.drawPath(path, dash)
        }

        /** The selection lifted off the page, with its frame and the scale handle. */
        private fun drawSelection(canvas: Canvas) {
            val s = selection ?: return
            if (s.page !in 0 until pageCount) return
            val ink = inks[s.page] ?: return
            canvas.save()
            canvas.concat(unitToView(s.page))
            canvas.concat(drag)
            layer.paintInk(canvas, s.page, ink, s.strokes, s.items, only = true)
            canvas.restore()
            val r = selectionViewRect(s)
            r.inset(-6f * density, -6f * density)
            selFrame.color = accentColor
            handle.color = accentColor
            canvas.drawRoundRect(r, 8f * density, 8f * density, selFrame)
            canvas.drawCircle(r.right, r.bottom, 9f * density, handle)
            canvas.drawCircle(r.right, r.bottom, 9f * density, handleRing)
        }

        private fun drawNotes(canvas: Canvas) {
            if (marks.notes.isEmpty()) return
            val visiblePages = visiblePages()
            val s = NOTE_DP * density
            for (n in marks.notes) {
                if (n.page !in visiblePages) continue
                val r = noteRect(n) ?: continue
                canvas.drawRoundRect(r.left + 2 * density, r.top + 3 * density, r.right + 2 * density, r.bottom + 3 * density, 4 * density, 4 * density, noteShadow)
                val fold = s * 0.3f
                path.reset()
                path.moveTo(r.left, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.bottom - fold)
                path.lineTo(r.right - fold, r.bottom); path.lineTo(r.left, r.bottom); path.close()
                canvas.drawPath(path, note)
                path.reset()
                path.moveTo(r.right, r.bottom - fold); path.lineTo(r.right - fold, r.bottom - fold); path.lineTo(r.right - fold, r.bottom); path.close()
                canvas.drawPath(path, noteFold)
                val inset = s * 0.22f
                canvas.drawLine(r.left + inset, r.top + s * 0.35f, r.right - inset, r.top + s * 0.35f, noteLine)
                canvas.drawLine(r.left + inset, r.top + s * 0.55f, r.right - inset * 2, r.top + s * 0.55f, noteLine)
            }
        }
    }

    companion object {
        /** Note pin size on screen. */
        const val NOTE_DP = 26f
    }
}

/** clipOutRect exists from API 26 (our minimum); kept in one place. */
private fun Canvas.clipOutRectCompat(r: RectF) { clipOutRect(r) }
