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
import de.millione.schulranzen.core.books.SearchHit
import de.millione.schulranzen.core.books.TextSearch
import de.millione.schulranzen.core.books.UndoStack
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.ink.Brushes
import de.millione.schulranzen.ink.PageEdit
import de.millione.schulranzen.ink.PageItem
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.TextItem
import de.millione.schulranzen.ink.ToolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** A note being written: where, and the note it replaces (null = new). */
internal data class NoteDraft(val page: Int, val x: Float, val y: Float, val old: BookNote?)

/** Text being typed on a page (unrotated units), and the text it replaces (null = new). */
internal data class TextDraft(val page: Int, val x: Float, val y: Float, val old: TextItem?)

/** What the book's text index can do right now, for the search and contents panels. */
internal data class IndexInfo(
    /** The PDF text pass went through all pages. */
    val textDone: Boolean,
    val pagesWithText: Int,
    /** Pages still without text (scanned pages not yet recognised). */
    val pagesWithoutText: Int,
    /** No page has a PDF text layer. */
    val scanned: Boolean,
)

/** One undoable action in a book. */
internal sealed class BookStep {
    /** Strokes and typed text, as the canvas reported them. */
    class Ink(val edits: List<PageEdit>) : BookStep()
    /** Notes, areas, bookmarks, page turns: the marks before and after. */
    class Marks(val before: BookMarks, val after: BookMarks) : BookStep()
}

/**
 * One open book: binds the reader view to storage and holds the toolbar
 * state. Ink, marks and the reading position are saved shortly after each
 * change on the shelf's own scope, so leaving the book never drops a write.
 * Undo covers everything done in the book: ink, text, lasso changes,
 * notes, areas, bookmarks and page turns.
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
    /** Background text work of this book (text layer or recognition), or null. */
    var indexProgress by mutableStateOf<BookIndexer.Progress?>(null); private set
    var indexInfo by mutableStateOf<IndexInfo?>(null); private set
    /** Marked rectangle in view pixels, for the action bar. */
    var areaFrame by mutableStateOf<RectF?>(null); private set
    /** Lasso selection in view pixels, for its action bar. */
    var selectionFrame by mutableStateOf<RectF?>(null); private set
    var noteDraft by mutableStateOf<NoteDraft?>(null); private set
    var textDraft by mutableStateOf<TextDraft?>(null); private set
    var toast by mutableStateOf<String?>(null); private set

    var searchQuery by mutableStateOf(""); private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    /** The hit last jumped to, marked in the list. */
    var currentHit by mutableStateOf<SearchHit?>(null); private set

    private var doc: BookDocument? = null
    private val history = UndoStack<BookStep>()
    private val inkSaves = HashMap<Int, Job>()
    private var pageSave: Job? = null
    private var toastJob: Job? = null
    private var searchJob: Job? = null
    private val marksLock = Mutex()
    private var pendingJump: Pair<Int, RectF?>? = null
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
                val jump = pendingJump
                pendingJump = null
                page = (jump?.first ?: record.lastPage).coerceIn(0, d.pageCount - 1)
                view.setDocument(d, BookInkStore(store.inkDir(bookId)), marks, page)
                jump?.second?.let { view.highlight(page, listOf(it)) }
                loaded = true
                store.scope.launch { store.update(bookId) { it.copy(openedAt = System.currentTimeMillis()) } }
                outline = withContext(Dispatchers.IO) { BookOutlines.cached(store, bookId) }
                if (outline == null) BookOutlines.embedded(context, store, bookId)?.let { outline = it }
                watchIndex()
                BookIndexer.ensureText(store, bookId)
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

    /** Jumps to [page] and marks [rect] (unrotated units) there; before loading, where reading starts. */
    fun openAt(page: Int, rect: RectF?) {
        if (!loaded) { pendingJump = page to rect; return }
        val p = page.coerceIn(0, pageCount - 1)
        if (rect != null) view.highlight(p, listOf(rect)) else { view.clearHighlight(); view.scrollToPage(p) }
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

    fun goTo(index: Int) {
        if (pageCount == 0) return
        view.scrollToPage(index.coerceIn(0, pageCount - 1))
    }

    // ----------------------------------------------------------- history

    private fun pushStep(step: BookStep) {
        history.push(step)
        syncHistory()
    }

    private fun syncHistory() { canUndo = history.canUndo; canRedo = history.canRedo }

    fun undo() {
        val step = history.undo() ?: return
        when (step) {
            is BookStep.Ink -> view.applyEdits(step.edits, forward = false)
            is BookStep.Marks -> applyMarks(step.before)
        }
        syncHistory()
    }

    fun redo() {
        val step = history.redo() ?: return
        when (step) {
            is BookStep.Ink -> view.applyEdits(step.edits, forward = true)
            is BookStep.Marks -> applyMarks(step.after)
        }
        syncHistory()
    }

    // ---------------------------------------------------------- listener

    override fun onInkStep(edits: List<PageEdit>) = pushStep(BookStep.Ink(edits))

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
        val ink = view.inkOf(page)
        val strokes: List<PageStroke> = ArrayList(ink.strokes)
        val items: List<PageItem> = ArrayList(ink.items)
        val files = BookInkStore(store.inkDir(bookId))
        store.scope.launch { runCatching { files.save(page, strokes, items) } }
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

    override fun onSelectionChanged(frame: RectF?) { selectionFrame = frame }

    override fun onNoteRequest(page: Int, x: Float, y: Float) { noteDraft = NoteDraft(page, x, y, null) }

    override fun onNoteOpen(note: BookNote) { noteDraft = NoteDraft(note.page, note.x, note.y, note) }

    override fun onTextRequest(page: Int, x: Float, y: Float, existing: TextItem?) { textDraft = TextDraft(page, x, y, existing) }

    // ------------------------------------------------------------- marks

    /** Changes the marks as one undoable step. */
    private fun updateMarks(change: (BookMarks) -> BookMarks) {
        val before = marks
        val next = change(before)
        if (next == before) return
        applyMarks(next)
        pushStep(BookStep.Marks(before, next))
    }

    private fun applyMarks(next: BookMarks) {
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

    // -------------------------------------------------------------- links

    /** The page being read, as a link. */
    fun linkForPage(): BookLink = BookLink(bookId, page, null, BookLink.label(title, page, area = false))

    /** The marked rectangle as a link (unrotated units), or null when none is drawn. */
    fun linkForArea(): BookLink? {
        val (p, rect) = view.areaSelection() ?: return null
        return BookLink(bookId, p, view.boxToUnits(p, rect), BookLink.label(title, p, area = true))
    }

    /** Hands the area (or else the page) to [onMark] and says so. */
    fun markForExam(onMark: (BookLink) -> Unit, area: Boolean) {
        val link = (if (area) linkForArea() else null) ?: linkForPage()
        onMark(link)
        if (area) view.clearArea()
        say("Für die Prüfung gemerkt")
    }

    // -------------------------------------------------------------- text

    /** Size of text in unrotated units of [page] for one of [TextItem.sizes]. */
    fun textUnits(page: Int, size: Float): Float = size * view.boxToUnitScale(page)

    fun commitText(text: String, size: Float, color: Int) {
        val d = textDraft ?: return
        textDraft = null
        val old = d.old
        val width = old?.width ?: view.defaultTextWidth(d.page, d.x, d.y)
        val item = TextItem(view.newId(), old?.x ?: d.x, old?.y ?: d.y, width, text.trimEnd(), textUnits(d.page, size), color)
        view.putText(d.page, item, old)
    }

    fun deleteText() {
        val d = textDraft ?: return
        textDraft = null
        view.putText(d.page, null, d.old)
    }

    fun cancelText() { textDraft = null }

    // ------------------------------------------------------------- lasso

    fun recolorSelection(color: Int) = view.recolorSelection(color)
    fun deleteSelection() = view.deleteSelection()
    fun clearSelection() = view.clearSelection()

    // ----------------------------------------------------- clip to notebook

    /**
     * Renders the marked rectangle (PDF, ink and text) at print resolution
     * into `filesDir/images/<uuid>.png` and hands the file to [onClip].
     */
    fun clipArea(onClip: (File) -> Unit) {
        val d = doc ?: return
        val (p, rect) = view.areaSelection() ?: return
        val strokes = ArrayList(view.inkOf(p).strokes)
        val toBox = view.unitToBoxMatrix(p)
        val pts = d.pointsToUnits(p)
        ui.launch {
            val file = runCatching {
                val (bmp, unitToOut) = d.onRenderThread { renderClip(d, p, rect, toBox, pts, strokes) }
                // Typed text on the main thread: the text painter is shared with the screen.
                Canvas(bmp).apply { concat(unitToOut); view.paintItems(this, p) }
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

    private fun renderClip(d: BookDocument, page: Int, rect: RectF, toBox: Matrix, pts: Matrix, strokes: List<PageStroke>): Pair<Bitmap, Matrix> {
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
        return bmp to unitToOut
    }

    // ------------------------------------------------- text index, outline

    private fun watchIndex() {
        ui.launch { BookIndexer.running.map { it[bookId] }.distinctUntilChanged().collect { indexProgress = it } }
        ui.launch {
            BookIndexer.revisions.map { it[bookId] ?: 0 }.distinctUntilChanged().collect {
                val (info, cached) = withContext(Dispatchers.IO) {
                    val index = BookIndexer.index(store, bookId)
                    index?.let { IndexInfo(it.textDone, it.pagesWithText, it.pagesWithoutText().size, it.looksScanned) } to BookOutlines.cached(store, bookId)
                }
                indexInfo = info
                if (cached != null) outline = cached
                if (searchQuery.isNotBlank()) runSearch(searchQuery, debounce = false)
            }
        }
    }

    /** Makes sure the text pass runs; [force] rebuilds the contents from the text already read. */
    fun analyze(force: Boolean = false) {
        if (!force) { BookIndexer.ensureText(store, bookId); return }
        ui.launch {
            outline = withContext(Dispatchers.IO) {
                BookIndexer.index(store, bookId)?.let { BookOutlines.fromIndex(store, bookId, it) }
            } ?: outline
            BookIndexer.ensureText(store, bookId)
        }
    }

    /** Text recognition for pages without text (scanned books). Goes on after the book is closed. */
    fun startRecognition() {
        BookIndexer.startOcr(store, bookId)
        say("Texterkennung läuft im Hintergrund")
    }

    fun stopRecognition() = BookIndexer.stop(bookId)

    // ------------------------------------------------------------ search

    fun search(query: String) {
        searchQuery = query
        runSearch(query, debounce = true)
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        if (query.trim().length < 2) { searchHits = emptyList(); searching = false; return }
        searchJob = ui.launch {
            if (debounce) delay(250)
            searching = true
            val hits = withContext(Dispatchers.Default) {
                val index = withContext(Dispatchers.IO) { BookIndexer.index(store, bookId) }
                if (index == null) emptyList() else TextSearch.find(index.pages.values, query)
            }
            searchHits = hits
            searching = false
        }
    }

    /** Jumps to a hit and marks the found words. */
    fun openHit(hit: SearchHit) {
        currentHit = hit
        view.highlight(hit.page, hit.rects.map { RectF(it.left, it.top, it.right, it.bottom) })
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
