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
import de.millione.schulranzen.core.math.MathResult
import de.millione.schulranzen.rechnen.MathInk
import de.millione.schulranzen.rechnen.InkSolve
import de.millione.schulranzen.rechnen.InkModelStatus
import de.millione.schulranzen.handwriting.HandwritingIndex
import de.millione.schulranzen.handwriting.HandwritingRecognizer
import de.millione.schulranzen.handwriting.InkInput
import de.millione.schulranzen.core.shapes.ShapeRecognizer
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.study.LinkRect
import de.millione.schulranzen.study.StudyLinkDraft
import de.millione.schulranzen.study.StudyLinkKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Search index over the handwriting (null in tests or when not wanted). */
    private val handwriting: HandwritingIndex? = null,
    private val recognizer: HandwritingRecognizer? = null,
    /** Open at this page instead of the last one (jump from a search hit or exam link). */
    private val startPage: Int? = null,
    /** Area to mark briefly after opening, page units. */
    private val startFlash: RectF? = null,
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
    var showCalculator by mutableStateOf(false)
    /** Result of "Ausrechnen" with the selection frame it belongs to. */
    var mathResult by mutableStateOf<Pair<MathResult, RectF>?>(null); private set
    private var mathInk: MathInk? = null

    /** Sheets for the learning features, shown by the screen while non-null. */
    var markDraft by mutableStateOf<StudyLinkDraft?>(null)
    var cardClip by mutableStateOf<Pair<java.io.File, StudyLinkDraft?>?>(null)
    /** Handwriting → text: Some(null) while reading, Some(text) when done. */
    var convert by mutableStateOf<ConvertState?>(null)
    var explainText by mutableStateOf<String?>(null)
    var suggestText by mutableStateOf<String?>(null)
    class ConvertState(val text: String?)

    /** Longer work in progress ("Seite 3 von 8 …"), or null. */
    var busy by mutableStateOf<String?>(null); private set
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
            val target = startPage ?: nb.lastPage
            if (target > 0) canvas.scrollToPage(target.coerceAtMost(pages.size - 1))
            if (startFlash != null) canvas.flash(target.coerceIn(0, pages.size - 1), startFlash)
            loaded = true
        }
    }

    /** Leaving the notebook: write every pending page now. */
    fun close() {
        for (id in saves.keys.toList()) { saves.remove(id)?.cancel(); saveNow(id) }
        scope.launch { library.setLastPage(notebookId, page) }
        mathInk?.close(); mathInk = null
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

    /** Reads the selected handwriting as a calculation and solves it. */
    fun solveSelection() {
        val frame = selection ?: return
        val strokes = canvas.selectedStrokePoints()
        if (strokes.isEmpty()) { say("Keine Handschrift ausgewählt"); return }
        val ink = mathInk ?: MathInk(canvas.context).also { mathInk = it }
        scope.launch {
            busy = "Rechnung wird erkannt …"
            val result = runCatching { ink.solveOrExplain(strokes) }.getOrNull()
            busy = null
            when (result) {
                is InkSolve.Solved -> mathResult = result.result to RectF(frame)
                is InkSolve.Unavailable -> say(result.message)
                else -> say("Keine Rechnung erkannt – deutlicher schreiben oder Rechner nutzen")
            }
        }
    }

    fun dismissMath() { mathResult = null }

    // ---- learning: exam marks, cards, text ----

    /** Link to the selection (or the visible page) for the study features. */
    private fun linkDraft(): StudyLinkDraft {
        val info = canvas.selectionInfo()
        val p = info?.first ?: page
        val rect = info?.second?.let { r ->
            LinkRect(r.left / PageGeometry.WIDTH, r.top / PageGeometry.HEIGHT, r.width() / PageGeometry.WIDTH, r.height() / PageGeometry.HEIGHT)
        }
        return StudyLinkDraft(StudyLinkKind.NOTEBOOK_PAGE, notebookId, p, rect, "$title, S. ${p + 1}" + if (rect != null) " (Auswahl)" else "")
    }

    fun markForExam() { markDraft = linkDraft() }

    /** The selection as a picture for a flash card. */
    fun cardFromSelection() {
        val bmp = canvas.renderSelection() ?: run { say("Erst mit dem Lasso etwas auswählen"); return }
        val link = linkDraft()
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = java.io.File(canvas.context.cacheDir, "karte-${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle(); f
            }
            cardClip = file to link
        }
    }

    private suspend fun selectionText(): String? {
        val r = recognizer ?: return null
        val strokes = canvas.selectedStrokePoints()
        if (strokes.isEmpty()) { say("Keine Handschrift ausgewählt"); return null }
        return runCatching { r.recognize(strokes) }.getOrNull()
    }

    fun convertSelection() {
        convert = ConvertState(null)
        scope.launch {
            val text = selectionText()
            if (text == null) { convert = null; say("Keine Schrift erkannt – Handschrift-Modell geladen?") } else convert = ConvertState(text)
        }
    }

    /** Replaces the selected handwriting by typed [text] (one undo step). */
    fun insertConverted(text: String) {
        convert = null
        val (_, bounds) = canvas.selectionInfo() ?: return
        val item = InkInput.textItem(canvas.newId(), text.trim(), bounds, text.trim().lines().size, penColor)
        canvas.replaceSelection(listOf(item))
    }

    fun explainSelection() { scope.launch { selectionText()?.let { explainText = it } ?: say("Keine Schrift erkannt") } }
    fun suggestFromSelection() { scope.launch { selectionText()?.let { suggestText = it } ?: say("Keine Schrift erkannt") } }

    // ---- shapes and circled calculations ----

    override fun transformStroke(page: Int, stroke: PageStroke): List<PageStroke>? {
        if (!settings.shapes) return null
        val pts = InkInput.points(stroke)
        if (!ShapeRecognizer.heldAtEnd(pts)) return null
        val shape = ShapeRecognizer.recognize(pts) ?: return null
        return InkInput.shapeStrokes(shape, stroke, canvas::newId).ifEmpty { null }
    }

    override fun onStrokeAdded(page: Int, stroke: PageStroke) {
        if (!settings.circleSolve || MathInk.modelStatus.value != InkModelStatus.Ready) return
        val p = canvas.page(page) ?: return
        val others = p.ink.strokes.filter { it.id != stroke.id && it.tool == ToolKind.PEN }
        if (others.isEmpty()) return
        val inside = ShapeRecognizer.enclosedBy(InkInput.points(stroke), others.map { InkInput.bounds(it) })
        if (inside.isEmpty()) return
        val ids = inside.mapTo(HashSet()) { others[it].id }
        val points = canvas.strokePoints(page, ids)
        val loop = RectF(stroke.minX, stroke.minY, stroke.maxX, stroke.maxY)
        val ink = mathInk ?: MathInk(canvas.context).also { mathInk = it }
        scope.launch {
            // Only a real calculation answers; a circle around words stays quiet.
            val r = runCatching { ink.solveOrExplain(points) }.getOrNull() as? InkSolve.Solved ?: return@launch
            mathResult = r.result to canvas.viewRectOf(page, loop)
        }
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

    /**
     * A picture that is already in the image folder (e.g. a clipping from
     * the book beside the notebook) onto the visible page.
     */
    fun insertImageFile(file: java.io.File) {
        val target = canvas.currentPage
        if (canvas.page(target)?.locked == true) { say("Seite ist gesperrt"); return }
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, opts)
        val aspect = if (opts.outWidth > 0) opts.outHeight.toFloat() / opts.outWidth else 0.75f
        val w = minOf(PageGeometry.WIDTH * 0.6f, PageGeometry.HEIGHT * 0.8f / aspect)
        val h = w * aspect
        val c = canvas.visibleCentreOfCurrent()
        tool = ToolKind.LASSO; applyTool()
        canvas.addItem(target, ImageItem(canvas.newId(), c[0] - w / 2, c[1] - h / 2, w, h, file.name))
        say("Ausschnitt eingefügt – mit dem Rahmen verschieben und skalieren")
    }

    // ---- worksheets as pages ----

    /** Photos (gallery or camera) become new pages after the current one. */
    fun addWorksheetImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = "Arbeitsblatt wird eingefügt …"
            val files = uris.mapIndexedNotNull { i, u ->
                busy = "Bild ${i + 1} von ${uris.size} …"
                library.importImage(u)?.first
            }
            insertWorksheets(files)
        }
    }

    /** Every page of a PDF becomes a page of the notebook to write on. */
    fun addWorksheetPdf(uri: Uri) {
        scope.launch {
            busy = "PDF wird gelesen …"
            val files = runCatching { library.importPdfPages(uri) { done, total -> busy = "PDF-Seite $done von $total …" } }
                .getOrElse { busy = null; say("PDF konnte nicht gelesen werden: ${it.message}"); return@launch }
            insertWorksheets(files)
        }
    }

    private suspend fun insertWorksheets(files: List<String>) {
        if (files.isEmpty()) { busy = null; say("Nichts eingefügt"); return }
        flushAll()
        val at = page + 1
        val pages = library.addWorksheetPages(notebookId, at, files)
        pages.forEachIndexed { i, p -> canvas.addPage(p, at + i) }
        pageCount = canvas.pageCount
        pagesVersion++
        busy = null
        canvas.scrollToPage(at)
        say(if (files.size == 1) "Arbeitsblatt als Seite ${at + 1} eingefügt" else "${files.size} Arbeitsblatt-Seiten ab Seite ${at + 1} eingefügt")
    }

    private fun flushAll() { for (id in saves.keys.toList()) flush(id) }

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
        handwriting?.let { h -> scope.launch { h.remove(removed.id) } }
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
        handwriting?.schedule(notebookId, p.id, strokes)
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
