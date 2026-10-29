package de.millione.schulranzen.app.notebook

import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.ink.CanvasPage
import de.millione.schulranzen.ink.Clip
import de.millione.schulranzen.ink.ImageItem
import de.millione.schulranzen.ink.NotebookCanvasView
import de.millione.schulranzen.ink.PageGeometry
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.RingEvent
import de.millione.schulranzen.ink.TextItem
import de.millione.schulranzen.ink.ToolKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tool ring as the overlay draws it. */
data class RingState(val x: Float, val y: Float, val hoverX: Float, val hoverY: Float, val armed: Boolean)

/** Text being written: where, and the item it replaces (null = new). */
data class TextDraft(val page: Int, val x: Float, val y: Float, val old: TextItem?)

/**
 * One open notebook: binds the canvas to storage and the toolbar state.
 * Every page change is saved shortly after the pen stops (debounced), so
 * nothing is lost and writing never waits for the disk.
 */
class NotebookSession(
    val notebookId: String,
    val canvas: NotebookCanvasView,
    private val library: Library,
    private val settings: Settings,
    private val scope: CoroutineScope,
) : NotebookCanvasView.Listener {

    var title by mutableStateOf("")
    var loaded by mutableStateOf(false)
    var tool by mutableStateOf(ToolKind.PEN); private set
    var penColor by mutableIntStateOf(settings.penColor); private set
    var penSize by mutableFloatStateOf(settings.penSize); private set
    var markerColor by mutableIntStateOf(settings.markerColor); private set
    var markerSize by mutableFloatStateOf(settings.markerSize); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var page by mutableIntStateOf(0); private set
    var pageCount by mutableIntStateOf(1); private set
    var ring by mutableStateOf<RingState?>(null); private set
    /** Selection frame in view pixels (for the action bar), or null. */
    var selection by mutableStateOf<RectF?>(null); private set
    var clip by mutableStateOf<Clip?>(null); private set
    var textDraft by mutableStateOf<TextDraft?>(null); private set
    /** Short message at the bottom ("Seite ist gesperrt"). */
    var toast by mutableStateOf<String?>(null); private set
    /** Bumped when page flags or order change, so the overview redraws. */
    var pagesVersion by mutableIntStateOf(0); private set
    /** Tool before the side button toggled the eraser, to switch back. */
    private var beforeEraser = ToolKind.PEN

    private val saves = HashMap<String, Job>()

    fun open() {
        canvas.listener = this
        canvas.buttonErases = settings.buttonErases
        canvas.fingerDraws = settings.fingerDraws
        canvas.setPages(emptyList())
        applyTool()
        scope.launch {
            val nb = library.notebook(notebookId) ?: return@launch
            title = nb.title
            val pages = library.loadPages(notebookId)
            canvas.setPages(pages)
            pageCount = pages.size
            if (nb.lastPage > 0) canvas.scrollToPage(nb.lastPage)
            loaded = true
        }
    }

    /** Leaving the notebook: write every pending page now. */
    fun close() {
        for (id in saves.keys.toList()) { saves.remove(id)?.cancel(); saveNow(id) }
        scope.launch { library.setLastPage(notebookId, page) }
        canvas.listener = null
    }

    fun select(t: ToolKind) { tool = t; applyTool() }

    fun setPen(color: Int, size: Float = penSize) {
        penColor = color; penSize = size
        settings.penColor = color; settings.penSize = size
        if (!tool.writes && tool != ToolKind.RULER) tool = ToolKind.PEN
        applyTool()
    }

    fun setMarker(color: Int, size: Float = markerSize) {
        markerColor = color; markerSize = size
        settings.markerColor = color; settings.markerSize = size
        tool = ToolKind.MARKER
        applyTool()
    }

    private fun applyTool() {
        canvas.tool = tool
        canvas.penColor = penColor; canvas.penSize = penSize
        canvas.markerColor = markerColor; canvas.markerSize = markerSize
    }

    fun undo() = canvas.undo()
    fun redo() = canvas.redo()

    fun say(text: String) {
        toast = text
        scope.launch { delay(2200); if (toast == text) toast = null }
    }

    // ---- selection ----

    fun cut() { clip = canvas.cutSelection() }
    fun copy() { clip = canvas.copySelection(); say("Kopiert") }
    fun duplicate() = canvas.duplicateSelection()
    fun delete() = canvas.deleteSelection()
    fun recolor(color: Int) = canvas.recolorSelection(color)
    fun paste() {
        val c = clip ?: return
        if (tool != ToolKind.LASSO) { tool = ToolKind.LASSO; applyTool() }
        canvas.paste(c)
    }

    // ---- text ----

    fun commitText(text: String, size: Float, color: Int) {
        val d = textDraft ?: return
        textDraft = null
        val width = d.old?.width ?: (PageGeometry.WIDTH - d.x - 40f).coerceAtLeast(160f)
        val item = TextItem(canvas.newId(), d.old?.x ?: d.x, d.old?.y ?: d.y, width, text.trimEnd(), size, color)
        canvas.putText(d.page, item, d.old)
    }

    fun deleteText() {
        val d = textDraft ?: return
        textDraft = null
        canvas.putText(d.page, null, d.old)
    }

    fun cancelText() { textDraft = null }

    // ---- pictures and PDF ----

    fun insertImage(uri: Uri) {
        val target = canvas.currentPage
        if (canvas.page(target)?.locked == true) { say("Seite ist gesperrt"); return }
        scope.launch {
            val (file, aspect) = library.importImage(uri) ?: run { say("Bild konnte nicht geladen werden"); return@launch }
            val w = PageGeometry.WIDTH * 0.6f
            val h = w * aspect
            val c = canvas.visibleCentreOfCurrent()
            tool = ToolKind.LASSO; applyTool()
            canvas.addItem(target, ImageItem(canvas.newId(), c[0] - w / 2, c[1] - h / 2, w, h, file))
            say("Bild eingefügt – mit dem Rahmen verschieben und skalieren")
        }
    }

    fun exportPdf(uri: Uri, ruling: Boolean) {
        val pages = canvas.allPages.toList()
        scope.launch {
            runCatching { library.exportPdf(pages, uri, ruling) }
                .onSuccess { say("PDF gespeichert (${pages.size} Seiten)") }
                .onFailure { say("PDF-Export fehlgeschlagen: ${it.message}") }
        }
    }

    // ---- pages ----

    val pages: List<CanvasPage> get() = canvas.allPages

    fun addPage() {
        val at = page + 1
        val paper = canvas.page(page)?.paper ?: PaperStyle.LINED
        scope.launch {
            val p = library.addPage(notebookId, at, paper)
            canvas.addPage(p, at)
            pageCount = canvas.pageCount
            pagesVersion++
            canvas.scrollToPage(at)
        }
    }

    fun duplicatePage(index: Int) {
        val src = canvas.page(index) ?: return
        flush(src.id)
        scope.launch {
            val copy = library.duplicatePage(notebookId, index + 1, src, canvas::newId)
            canvas.addPage(copy, index + 1)
            pageCount = canvas.pageCount
            pagesVersion++
            canvas.scrollToPage(index + 1)
        }
    }

    fun deletePage(index: Int) {
        if (canvas.pageCount <= 1) { say("Ein Heft braucht mindestens eine Seite"); return }
        val removed = canvas.removePage(index) ?: return
        saves.remove(removed.id)?.cancel()
        pageCount = canvas.pageCount
        pagesVersion++
        scope.launch { library.removePage(notebookId, removed.id, canvas.allPages.map { it.id }) }
        say("Seite ${index + 1} gelöscht")
    }

    fun movePage(from: Int, to: Int) {
        if (to !in 0 until canvas.pageCount) return
        canvas.movePage(from, to)
        pagesVersion++
        scope.launch { library.reorder(notebookId, canvas.allPages.map { it.id }) }
    }

    fun goTo(index: Int) = canvas.scrollToPage(index)

    fun toggleBookmark(index: Int = page) {
        val p = canvas.page(index) ?: return
        p.bookmarked = !p.bookmarked
        canvas.refreshPage(index)
        pagesVersion++
        scope.launch { library.setBookmarked(p.id, p.bookmarked) }
        say(if (p.bookmarked) "Lesezeichen gesetzt" else "Lesezeichen entfernt")
    }

    fun toggleLock(index: Int = page) {
        val p = canvas.page(index) ?: return
        p.locked = !p.locked
        canvas.refreshPage(index)
        pagesVersion++
        scope.launch { library.setLocked(p.id, p.locked) }
        say(if (p.locked) "Seite gesperrt – Schreiben und Radieren aus" else "Seite entsperrt")
    }

    fun setPaper(style: PaperStyle) {
        val p = canvas.page(page) ?: return
        p.paper = style
        canvas.refreshPage(page)
        pagesVersion++
        scope.launch { library.setPaper(p.id, style) }
    }

    fun currentPaper(): PaperStyle = canvas.page(page)?.paper ?: PaperStyle.LINED
    fun currentPage(): CanvasPage? = canvas.page(page)

    // ---- canvas callbacks ----

    override fun onPageChanged(pageId: String) {
        saves.remove(pageId)?.cancel()
        saves[pageId] = scope.launch {
            delay(700)
            saves.remove(pageId)
            saveNow(pageId)
        }
        pagesVersion++
    }

    private fun flush(pageId: String) { if (saves.remove(pageId)?.also { it.cancel() } != null) saveNow(pageId) }

    private fun saveNow(pageId: String) {
        val p = canvas.pageById(pageId) ?: return
        val strokes = p.ink.strokes.toList()
        val items = p.ink.items.toList()
        scope.launch { library.savePage(notebookId, p.id, strokes, items) }
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo; this.canRedo = canRedo
    }

    override fun onVisiblePageChanged(page: Int, count: Int) {
        this.page = page; pageCount = count
    }

    override fun onSelectionChanged(frame: RectF?) { selection = frame }

    override fun onTextRequest(page: Int, x: Float, y: Float, existing: TextItem?) {
        textDraft = TextDraft(page, x, y, existing)
    }

    override fun onLockedPageTouched() = say("Seite ist gesperrt")

    override fun onSideButtonClick() {
        // Short click while hovering: toggle between eraser and the last writing tool.
        if (tool.erases) { tool = beforeEraser } else { beforeEraser = tool; tool = ToolKind.ERASER }
        applyTool()
    }

    override fun onRing(event: RingEvent) {
        when (event) {
            is RingEvent.Open -> {
                ToolRingGeometry.swatches = if (tool == ToolKind.MARKER) InkPalette.marker else InkPalette.pen
                ring = RingState(event.x, event.y, event.x, event.y, armed = true)
            }
            is RingEvent.Move -> ring = ring?.copy(hoverX = event.x, hoverY = event.y)
            is RingEvent.Release -> {
                val r = ring ?: return
                val hit = ToolRingGeometry.hit(r.x, r.y, event.x, event.y, density)
                if (hit != null) { pick(hit); closeRing() } else ring = r.copy(armed = false)
            }
            RingEvent.Cancel -> closeRing()
        }
    }

    var density = 1f

    fun pick(item: RingItem) {
        when (item) {
            is RingItem.Tool -> select(item.kind)
            is RingItem.Swatch -> if (tool == ToolKind.MARKER) setMarker(item.color) else setPen(item.color)
        }
    }

    fun closeRing() { ring = null; canvas.ringClosed() }
}
