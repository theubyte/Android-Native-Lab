package de.millione.schulranzen.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import de.millione.schulranzen.core.books.BookUnits
import de.millione.schulranzen.core.books.Outline
import de.millione.schulranzen.core.books.PageRotation
import de.millione.schulranzen.core.books.RenderMath
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.ink.Brushes
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.ToolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** A note being written: where, and the note it replaces (null = new). */
internal data class NoteDraft(val page: Int, val x: Float, val y: Float, val old: BookNote?)

/**
 * One open book: binds the reader view to storage and holds the toolbar
 * state. Ink, marks and the reading position are saved shortly after each
 * change on the shelf's own scope, so leaving the book never drops a write.
 */
internal class BookSession(private val context: Context, val bookId: String, val view: BookCanvasView) : BookCanvasView.Listener {
    private val store = ShelfStore.of(context)
    private val prefs = context.getSharedPreferences("books", Context.MODE_PRIVATE)
    private val ui = MainScope()

    var title by mutableStateOf("")
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var pageCount by mutableIntStateOf(0); private set
    var page by mutableIntStateOf(0); private set
    var tool by mutableStateOf(BookTool.READ); private set
    var penColor by mutableIntStateOf(prefs.getInt("penColor", InkPalette.pen[1])); private set
    var penSize by mutableFloatStateOf(prefs.getFloat("penSize", Brushes.penSizes[1])); private set
    var markerColor by mutableIntStateOf(prefs.getInt("markerColor", InkPalette.marker[0])); private set
    var markerSize by mutableFloatStateOf(prefs.getFloat("markerSize", Brushes.markerSizes[1])); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var marks by mutableStateOf(BookMarks()); private set
    var outline by mutableStateOf<Outline?>(null); private set
    /** (page, count) while the text analysis runs. */
    var analyzing by mutableStateOf<Pair<Int, Int>?>(null); private set
    /** Marked rectangle in view pixels, for the action bar. */
    var areaFrame by mutableStateOf<RectF?>(null); private set
    var noteDraft by mutableStateOf<NoteDraft?>(null); private set
    var toast by mutableStateOf<String?>(null); private set

    private var doc: BookDocument? = null
    private val inkSaves = HashMap<Int, Job>()
    private var pageSave: Job? = null
    private var toastJob: Job? = null
    private var analyzeJob: Job? = null
    private val marksLock = Mutex()
    private val thumbs = object : LruCache<Int, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun open() {
        view.listener = this
        view.buttonErases = prefs.getBoolean("buttonErases", true)
        applyTool()
        ui.launch {
            try {
                store.ensureLoaded()
                val record = store.records.value.firstOrNull { it.id == bookId }
                    ?: run { error = "Dieses Buch gibt es nicht mehr."; return@launch }
                title = record.title
                // Not cancellable: a document opened after the screen closed must still be closed.
                val opened = withContext(Dispatchers.IO + NonCancellable) { BookDocument.open(store, bookId) to store.loadMarks(bookId) }
                val d = opened.first
                if (!isActive) { d.close(); return@launch }
                doc = d
                marks = opened.second
                pageCount = d.pageCount
                page = record.lastPage.coerceIn(0, d.pageCount - 1)
                view.setDocument(d, BookInkStore(store.inkDir(bookId)), marks, page)
                loaded = true
                store.scope.launch { store.update(bookId) { it.copy(openedAt = System.currentTimeMillis()) } }
                outline = withContext(Dispatchers.IO) { BookOutlines.cached(store, bookId) }
                if (outline == null) BookOutlines.embedded(context, store, bookId)?.let { outline = it }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Das Buch lässt sich nicht öffnen."
            }
        }
    }

    fun close() {
        // Write what is still waiting, then let go of the PDF.
        for ((p, job) in inkSaves) { job.cancel(); saveInkNow(p) }
        inkSaves.clear()
        pageSave?.cancel()
        if (loaded) { val last = page; store.scope.launch { store.update(bookId) { it.copy(lastPage = last) } } }
        view.listener = null
        view.release()
        doc?.close()
        doc = null
        thumbs.evictAll()
        ui.cancel()
    }

    // ------------------------------------------------------------ tools

    fun select(t: BookTool) { tool = t; applyTool() }

    fun setPen(color: Int, size: Float = penSize) {
        penColor = color; penSize = size
        prefs.edit().putInt("penColor", color).putFloat("penSize", size).apply()
        if (tool != BookTool.PEN) tool = BookTool.PEN
        applyTool()
    }

    fun setMarker(color: Int, size: Float = markerSize) {
        markerColor = color; markerSize = size
        prefs.edit().putInt("markerColor", color).putFloat("markerSize", size).apply()
        if (tool != BookTool.MARKER) tool = BookTool.MARKER
        applyTool()
    }

    var buttonErases: Boolean
        get() = view.buttonErases
        set(value) { view.buttonErases = value; prefs.edit().putBoolean("buttonErases", value).apply() }

    private fun applyTool() {
        view.tool = tool
        view.penColor = penColor; view.penSize = penSize
        view.markerColor = markerColor; view.markerSize = markerSize
    }

    fun undo() = view.undo()
    fun redo() = view.redo()

    fun goTo(index: Int) {
        if (pageCount == 0) return
        view.scrollToPage(index.coerceIn(0, pageCount - 1))
    }

    // ---------------------------------------------------------- listener

    override fun onInkChanged(page: Int) {
        inkSaves.remove(page)?.cancel()
        inkSaves[page] = ui.launch {
            delay(700)
            inkSaves.remove(page)
            saveInkNow(page)
        }
    }

    /** Snapshot on the main thread, write on the shelf's scope. */
    private fun saveInkNow(page: Int) {
        val strokes: List<PageStroke> = ArrayList(view.inkOf(page).strokes)
        val ink = BookInkStore(store.inkDir(bookId))
        store.scope.launch { runCatching { ink.save(page, strokes) } }
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo; this.canRedo = canRedo
    }

    override fun onVisiblePageChanged(page: Int) {
        this.page = page
        pageSave?.cancel()
        pageSave = ui.launch {
            delay(1200)
            store.scope.launch { store.update(bookId) { it.copy(lastPage = page) } }
        }
    }

    override fun onAreaChanged(frame: RectF?) { areaFrame = frame }

    override fun onNoteRequest(page: Int, x: Float, y: Float) { noteDraft = NoteDraft(page, x, y, null) }

    override fun onNoteOpen(note: BookNote) { noteDraft = NoteDraft(note.page, note.x, note.y, note) }

    // ------------------------------------------------------------- marks

    private fun updateMarks(change: (BookMarks) -> BookMarks) {
        val next = change(marks)
        marks = next
        view.setMarks(next)
        store.scope.launch { marksLock.withLock { runCatching { store.saveMarks(bookId, marks) } } }
    }

    val currentBookmarked get() = page in marks.bookmarks

    fun toggleBookmark(p: Int = page) = updateMarks {
        it.copy(bookmarks = if (p in it.bookmarks) it.bookmarks - p else it.bookmarks + p)
    }

    fun rotateCurrent() {
        val p = page
        updateMarks {
            val next = PageRotation.next(it.rotation(p))
            it.copy(rotations = if (next == 0) it.rotations - p else it.rotations + (p to next))
        }
    }

    fun commitNote(text: String) {
        val d = noteDraft ?: return
        noteDraft = null
        val trimmed = text.trim()
        updateMarks { m ->
            val rest = m.notes.filterNot { it.id == d.old?.id }
            if (trimmed.isEmpty()) m.copy(notes = rest)
            else m.copy(notes = rest + BookNote(d.old?.id ?: System.currentTimeMillis(), d.page, d.x, d.y, trimmed, d.old?.created ?: System.currentTimeMillis()))
        }
    }

    fun deleteNote(id: Long) { if (noteDraft?.old?.id == id) noteDraft = null; updateMarks { m -> m.copy(notes = m.notes.filterNot { it.id == id }) } }
    fun cancelNote() { noteDraft = null }

    fun deleteArea(id: Long) = updateMarks { m -> m.copy(areas = m.areas.filterNot { it.id == id }) }

    /** Keeps the marked rectangle as a favourite area. */
    fun keepArea() {
        val (p, rect) = view.areaSelection() ?: return
        val u = view.boxToUnits(p, rect)
        val now = System.currentTimeMillis()
        updateMarks { it.copy(areas = it.areas + BookArea(now, p, u.left, u.top, u.right, u.bottom, "Markierung", now)) }
        view.clearArea()
        say("Bereich gemerkt")
    }

    fun cancelArea() = view.clearArea()

    /**
     * Renders the marked rectangle (PDF and ink) at print resolution into
     * `filesDir/images/<uuid>.png` and hands the file to [onClip].
     */
    fun clipArea(onClip: (File) -> Unit) {
        val d = doc ?: return
        val (p, rect) = view.areaSelection() ?: return
        val strokes = ArrayList(view.inkOf(p).strokes)
        val toBox = view.unitToBoxMatrix(p)
        val pts = d.pointsToUnits(p)
        ui.launch {
            val file = runCatching {
                val bmp = d.onRenderThread { renderClip(d, p, rect, toBox, pts, strokes) }
                withContext(Dispatchers.IO) {
                    val out = File(store.imageDir, "${UUID.randomUUID()}.png")
                    val tmp = File(store.imageDir, out.name + ".tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    if (!tmp.renameTo(out)) error("rename")
                    out
                }
            }.getOrNull()
            if (file != null) {
                view.clearArea()
                onClip(file)
                say("Ausschnitt ins Heft übernommen")
            } else say("Ausschnitt konnte nicht gespeichert werden")
        }
    }

    private fun renderClip(d: BookDocument, page: Int, rect: RectF, toBox: Matrix, pts: Matrix, strokes: List<PageStroke>): Bitmap {
        val (w, h) = RenderMath.clipSize(rect.width(), rect.height())
        val f = w / rect.width()
        val boxToOut = Matrix().apply { setTranslate(-rect.left, -rect.top); postScale(f, f) }
        val unitToOut = Matrix(toBox).apply { postConcat(boxToOut) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.renderBlocking(page, bmp, Matrix(pts).apply { postConcat(unitToOut) })
        val canvas = Canvas(bmp)
        canvas.concat(unitToOut)
        val renderer = CanvasStrokeRenderer.create()
        for (s in strokes) if (s.tool == ToolKind.MARKER) renderer.draw(canvas, s.stroke, unitToOut)
        for (s in strokes) if (s.tool != ToolKind.MARKER) renderer.draw(canvas, s.stroke, unitToOut)
        return bmp
    }

    // ----------------------------------------------------------- outline

    /** Starts the text analysis unless it runs or a result is there ([force] redoes it). */
    fun analyze(force: Boolean = false) {
        if (analyzeJob?.isActive == true) return
        if (!force && outline != null) return
        analyzeJob = ui.launch {
            analyzing = 0 to pageCount
            try {
                outline = BookOutlines.analyze(context, store, bookId) { p, n -> analyzing = p to n }
            } finally {
                analyzing = null
            }
        }
    }

    // -------------------------------------------------------- thumbnails

    /** Small picture of [page] as shown (turned), for the page overview. */
    suspend fun thumbnail(page: Int, widthPx: Int = 220): Bitmap? {
        val d = doc ?: return null
        val r = view.rotation(page)
        val key = page * 4 + r / 90
        thumbs.get(key)?.let { return it }
        val toBox = view.unitToBoxMatrix(page)
        val h = (widthPx * view.boxHeight(page) / BookUnits.WIDTH).roundToInt().coerceAtLeast(1)
        val m = d.pointsToUnits(page).apply { postConcat(toBox); postScale(widthPx / BookUnits.WIDTH, widthPx / BookUnits.WIDTH) }
        val bmp = runCatching {
            d.onRenderThread {
                val full = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                d.renderBlocking(page, full, m)
                full
            }
        }.getOrNull() ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    fun boxAspect(page: Int): Float = if (page in 0 until view.pageCount) view.boxHeight(page) / BookUnits.WIDTH else 1.414f

    // ------------------------------------------------------------- toast

    fun say(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = ui.launch { delay(2200); toast = null }
    }
}
