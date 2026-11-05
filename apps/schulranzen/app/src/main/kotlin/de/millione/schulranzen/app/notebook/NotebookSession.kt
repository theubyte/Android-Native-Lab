package de.millione.schulranzen.app.notebook

import de.millione.schulranzen.backup.EditGate
import kotlinx.coroutines.joinAll
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Notebook
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.core.notebook.PagePlacement
import de.millione.schulranzen.core.notebook.PenPreset
import de.millione.schulranzen.core.notebook.PenPresets
import de.millione.schulranzen.core.notebook.UserMessages
import de.millione.schulranzen.ink.PageInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    var clip by mutableStateOf(sharedClip); private set
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

    /** The notebook does not exist (any more), e.g. a link to a deleted one: the screen leaves. */
    var missing by mutableStateOf(false); private set
    /** Colours last used, newest first; favourites. */
    var recentPen by mutableStateOf(PenPresets.decodeColors(settings.recentPenColors)); private set
    var recentMarker by mutableStateOf(PenPresets.decodeColors(settings.recentMarkerColors)); private set
    var presets by mutableStateOf(PenPresets.decode(settings.penPresets)); private set
    /** Other notebooks a page can move to. */
    val otherNotebooks: Flow<List<Notebook>> get() = library.notebooks.map { all -> all.filter { it.id != notebookId } }

    /** A page waiting for its save; holds the page itself, so the save survives a change of notebook. */
    private class PendingSave(val page: CanvasPage, val job: Job)
    private val saves = HashMap<String, PendingSave>()
    private var failedSaves = 0
    private var loadJob: Job? = null
    private var gate: AutoCloseable? = null
    /** Ruling of new pages after a worksheet (a worksheet's own paper is plain). */
    private var defaultPaper = PaperStyle.LINED

    fun open() {
        canvas.listener = this
        canvas.buttonErases = settings.buttonErases
        canvas.fingerDraws = settings.fingerDraws
        canvas.setPages(emptyList())
        applyTool()
        loadJob = scope.launch {
            try {
                // A merge must not change pages held here; a reader never saves them, so it only waits.
                val entered = EditGate.enterWhenFree()
                if (canvas.readOnly) entered.close() else gate = entered
                val nb = library.notebook(notebookId) ?: run { missing = true; return@launch }
                title = nb.title
                defaultPaper = PaperStyle.of(nb.paper)
                // Only a big notebook takes long enough to need a word.
                val hint = launch { delay(300); busy = "Heft wird geöffnet …" }
                val result = try { library.loadPagesChecked(notebookId) } finally { hint.cancel(); busy = null }
                // A notebook needs a page to write on; a reader (phone) changes nothing.
                val pages = if (result.pages.isEmpty() && !canvas.readOnly) listOf(library.addPage(notebookId, 0, defaultPaper)) else result.pages
                canvas.setPages(pages)
                pageCount = pages.size
                val target = (startPage ?: nb.lastPage).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                if (target > 0) canvas.scrollToPage(target)
                if (startFlash != null) canvas.flash(target, startFlash)
                loaded = true
                reportLoad(result)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                busy = null
                say(UserMessages.of(t, "Heft öffnen"))
                missing = true
            }
        }
    }

    private fun reportLoad(result: Library.LoadedPages) {
        fun list(p: List<Int>) = p.joinToString(", ") { "${it + 1}" }
        when {
            result.unreadable.isNotEmpty() ->
                say("Seite ${list(result.unreadable)} konnte nicht gelesen werden und bleibt gesperrt. Die Datei ist aufbewahrt.")
            result.damaged.isNotEmpty() ->
                say("Seite ${list(result.damaged)} war beschädigt: geladen, was lesbar war. Eine Kopie ist aufbewahrt.")
        }
    }

    /** Leaving the notebook: write every pending page now. */
    fun close() {
        loadJob?.cancel()
        canvas.settleStrokes()
        flushAll()
        val last = page
        val writing = saves.values.map { it.job }
        val leaving = gate; gate = null
        scope.launch {
            try {
                writing.joinAll()
                if (loaded) library.setLastPage(notebookId, last)
            } finally {
                leaving?.close()
            }
        }
        mathInk?.close(); mathInk = null
        // The next notebook may already have opened on the canvas (screen fading out).
        if (canvas.listener === this) canvas.listener = null
    }

    /** The app goes to the background and may be killed there: nothing waits for the timer. */
    fun onPause() {
        if (canvas.listener !== this) return
        canvas.settleStrokes()
        flushAll()
        if (loaded) { val last = page; launchSafe("Speichern") { library.setLastPage(notebookId, last) } }
    }

    /** Runs [block] and turns any failure into a message, instead of taking the app down. */
    private fun launchSafe(what: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            busy = null
            say(UserMessages.of(t, what))
        }
    }

    fun select(t: ToolKind) { tool = t; applyTool() }

    fun setPen(color: Int, size: Float = penSize) {
        if (color != penColor) rememberColor(color, marker = false)
        penColor = color; penSize = size
        settings.penColor = color; settings.penSize = size
        if (!tool.writes && tool != ToolKind.RULER) tool = ToolKind.PEN
        applyTool()
    }

    fun setMarker(color: Int, size: Float = markerSize) {
        if (color != markerColor) rememberColor(color, marker = true)
        markerColor = color; markerSize = size
        settings.markerColor = color; settings.markerSize = size
        tool = ToolKind.MARKER
        applyTool()
    }

    private fun rememberColor(color: Int, marker: Boolean) {
        if (marker) {
            recentMarker = PenPresets.pushColor(recentMarker, color)
            settings.recentMarkerColors = PenPresets.encodeColors(recentMarker)
        } else {
            recentPen = PenPresets.pushColor(recentPen, color)
            settings.recentPenColors = PenPresets.encodeColors(recentPen)
        }
    }

    // ---- favourite pens ----

    /** The current pen or marker becomes a favourite. */
    fun addPreset() {
        val marker = tool == ToolKind.MARKER
        val p = PenPreset((if (marker) ToolKind.MARKER else ToolKind.PEN).code, if (marker) markerColor else penColor, if (marker) markerSize else penSize)
        savePresets(PenPresets.add(presets, p))
    }

    fun removePreset(p: PenPreset) = savePresets(presets - p)

    private fun savePresets(list: List<PenPreset>) {
        presets = list
        settings.penPresets = PenPresets.encode(list)
    }

    fun usePreset(p: PenPreset) {
        if (ToolKind.of(p.tool) == ToolKind.MARKER) setMarker(p.color, p.size)
        else { tool = ToolKind.PEN; setPen(p.color, p.size) }
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
        scope.launch { delay((text.length * 60L).coerceIn(2200L, 7000L)); if (toast == text) toast = null }
    }

    // ---- selection ----

    private fun keep(c: Clip?) { sharedClip = c; clip = c }

    fun cut() {
        // A locked page keeps its content, so nothing was cut.
        val locked = canvas.selectionInfo()?.let { canvas.page(it.first)?.locked } == true
        if (locked) { say("Seite ist gesperrt"); return }
        keep(canvas.cutSelection())
    }
    fun copy() { keep(canvas.copySelection()); say("Kopiert") }
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
        return StudyLinkDraft(StudyLinkKind.NOTEBOOK_PAGE, notebookId, p, rect, "$title, S. ${p + 1}" + if (rect != null) " (Auswahl)" else "", pageId = canvas.allPages.getOrNull(p)?.id)
    }

    fun markForExam() { markDraft = linkDraft() }

    /** The selection as a picture for a flash card. */
    fun cardFromSelection() {
        val bmp = canvas.renderSelection() ?: run { say("Erst mit dem Lasso etwas auswählen"); return }
        val link = linkDraft()
        launchSafe("Bildkarte") {
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
        val (_, bounds) = canvas.selectionInfo() ?: run { say("Die Auswahl ist nicht mehr da"); return }
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
        // Runs after every stroke: ordinary handwriting is too small to be a loop, so it costs nothing.
        if (maxOf(stroke.maxX - stroke.minX, stroke.maxY - stroke.minY) < 30f) return
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
        val old = d.old
        val item = if (old != null) TextItem(canvas.newId(), old.x, old.y, old.width, text.trimEnd(), size, color) else {
            // Near the edge it moves inwards: the sheet clips what sticks out.
            val box = PagePlacement.textBox(d.x, d.y, size, PageGeometry.WIDTH, PageGeometry.HEIGHT)
            TextItem(canvas.newId(), box[0], box[1], box[2], text.trimEnd(), size, color)
        }
        canvas.putText(d.page, item, old)
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
        launchSafe("Bild einfügen") {
            val (file, aspect) = library.importImage(uri) ?: run { say("Das Bild konnte nicht gelesen werden"); return@launchSafe }
            placeImage(target, file, aspect)
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
        placeImage(target, file.name, aspect)
        say("Ausschnitt eingefügt – mit dem Rahmen verschieben und skalieren")
    }

    /** On the visible part of page [target], whole and within the sheet, even for a tall photo. */
    private fun placeImage(target: Int, file: String, aspect: Float) {
        val c = canvas.visibleCentreOf(target)
        val b = PagePlacement.imageBox(aspect, c[0], c[1], PageGeometry.WIDTH, PageGeometry.HEIGHT)
        tool = ToolKind.LASSO; applyTool()
        canvas.addItem(target, ImageItem(canvas.newId(), b[0], b[1], b[2], b[3], file))
    }

    // ---- worksheets as pages ----

    /** Photos (gallery or camera) become new pages after the current one. */
    fun addWorksheetImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchSafe("Arbeitsblatt einfügen") {
            busy = "Arbeitsblatt wird eingefügt …"
            val files = uris.mapIndexedNotNull { i, u ->
                busy = "Bild ${i + 1} von ${uris.size} …"
                library.importImage(u)?.first
            }
            insertWorksheets(files)
            val failed = uris.size - files.size
            if (failed > 0 && files.isNotEmpty()) say("$failed von ${uris.size} Bildern konnten nicht gelesen werden")
            else if (files.isEmpty()) say("Die Bilder konnten nicht gelesen werden")
        }
    }

    /** Every page of a PDF becomes a page of the notebook to write on. */
    fun addWorksheetPdf(uri: Uri) {
        scope.launch {
            busy = "PDF wird gelesen …"
            val files = try {
                library.importPdfPages(uri) { done, total -> busy = "PDF-Seite $done von $total …" }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                busy = null; say(UserMessages.ofPdf(t)); return@launch
            }
            try {
                insertWorksheets(files)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                busy = null; say(UserMessages.of(t, "PDF einfügen"))
            }
        }
    }

    private suspend fun insertWorksheets(files: List<String>) {
        if (files.isEmpty()) { busy = null; say("Nichts eingefügt"); return }
        // A photo can arrive right after the process came back, before the pages are there.
        loadJob?.join()
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

    /** The pages as they are now: the export reads them off the main thread while writing may go on. */
    private fun snapshot(): List<CanvasPage> = canvas.allPages.map {
        CanvasPage(it.id, it.paper, PageInk(it.ink.strokes.toList(), it.ink.items.toList()), it.bookmarked, it.locked, it.background)
    }

    fun exportPdf(uri: Uri, ruling: Boolean) {
        val pages = snapshot()
        launchSafe("PDF-Export") {
            busy = "PDF wird erstellt …"
            library.exportPdf(pages, uri, ruling)
            busy = null
            say("PDF gespeichert (${pages.size} Seiten)")
        }
    }

    /** The notebook as PDF, handed to another app (mail, messenger, drive). */
    fun sharePdf() {
        val pages = snapshot()
        launchSafe("PDF teilen") {
            busy = "PDF wird erstellt …"
            val file = library.pdfForSharing(pages, title)
            busy = null
            if (!PdfShare.open(canvas.context, file)) say("Keine App zum Teilen gefunden")
        }
    }

    // ---- pages ----

    val pages: List<CanvasPage> get() = canvas.allPages

    fun addPage() {
        val at = page + 1
        val current = canvas.page(page)
        val paper = if (current == null || current.background != null) defaultPaper else current.paper
        launchSafe("Neue Seite") {
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
        launchSafe("Seite kopieren") {
            val copy = library.duplicatePage(notebookId, index + 1, src, canvas::newId)
            canvas.addPage(copy, index + 1)
            pageCount = canvas.pageCount
            pagesVersion++
            canvas.scrollToPage(index + 1)
        }
    }

    fun deletePage(index: Int) {
        if (canvas.pageCount <= 1) { say("Ein Heft braucht mindestens eine Seite"); return }
        if (canvas.page(index)?.locked == true) { say("Seite ist gesperrt – erst entsperren"); return }
        // Saved first: the picture clean-up looks at what the other pages have on disk.
        flushAll()
        val removed = canvas.removePage(index) ?: return
        saves.remove(removed.id)?.job?.cancel()
        handwriting?.let { h -> scope.launch { runCatching { h.remove(removed.id) } } }
        pageCount = canvas.pageCount
        pagesVersion++
        val order = canvas.allPages.map { it.id }
        launchSafe("Seite löschen") { library.removePage(notebookId, removed.id, order) }
        say("Seite ${index + 1} gelöscht")
    }

    /** Page [index] goes to the end of another notebook, with everything on it. */
    fun movePageTo(index: Int, target: Notebook) {
        if (canvas.pageCount <= 1) { say("Ein Heft braucht mindestens eine Seite"); return }
        val p = canvas.page(index) ?: return
        if (p.locked) { say("Seite ist gesperrt – erst entsperren"); return }
        flush(p.id)
        val removed = canvas.removePage(index) ?: return
        pageCount = canvas.pageCount
        pagesVersion++
        val order = canvas.allPages.map { it.id }
        launchSafe("Seite verschieben") {
            library.movePage(removed.id, notebookId, target.id, order)
            // The search must lead to the page's new home.
            handwriting?.schedule(target.id, removed.id, removed.ink.strokes.toList())
            say("Seite ${index + 1} ist jetzt in „${target.title}“")
        }
    }

    fun movePage(from: Int, to: Int) {
        if (to !in 0 until canvas.pageCount) return
        canvas.movePage(from, to)
        pagesVersion++
        val order = canvas.allPages.map { it.id }
        launchSafe("Seite verschieben") { library.reorder(notebookId, order) }
    }

    fun goTo(index: Int) = canvas.scrollToPage(index)

    fun toggleBookmark(index: Int = page) {
        val p = canvas.page(index) ?: return
        p.bookmarked = !p.bookmarked
        canvas.refreshPage(index)
        pagesVersion++
        launchSafe("Lesezeichen") { library.setBookmarked(p.id, p.bookmarked) }
        say(if (p.bookmarked) "Lesezeichen gesetzt" else "Lesezeichen entfernt")
    }

    fun toggleLock(index: Int = page) {
        val p = canvas.page(index) ?: return
        p.locked = !p.locked
        canvas.refreshPage(index)
        pagesVersion++
        launchSafe("Seite sperren") { library.setLocked(p.id, p.locked) }
        say(if (p.locked) "Seite gesperrt – Schreiben und Radieren aus" else "Seite entsperrt")
    }

    fun setPaper(style: PaperStyle) {
        val p = canvas.page(page) ?: return
        if (p.background != null) { say("Ein Arbeitsblatt hat kein Papiermuster"); return }
        if (p.locked) { say("Seite ist gesperrt"); return }
        p.paper = style
        canvas.refreshPage(page)
        pagesVersion++
        launchSafe("Papier ändern") { library.setPaper(p.id, style) }
    }

    /** The current page's paper for all pages (not worksheets, not locked ones) and for new ones. */
    fun setPaperForAll() {
        val style = currentPaper()
        canvas.allPages.forEachIndexed { i, p ->
            if (p.background == null && !p.locked && p.paper != style) { p.paper = style; canvas.refreshPage(i) }
        }
        defaultPaper = style
        pagesVersion++
        launchSafe("Papier ändern") { library.setPaperForAll(notebookId, style) }
        say("Alle Seiten: ${style.label}")
    }

    fun currentPaper(): PaperStyle = canvas.page(page)?.paper ?: PaperStyle.LINED
    fun currentPage(): CanvasPage? = canvas.page(page)

    // ---- canvas callbacks ----

    override fun onPageChanged(pageId: String) {
        val p = canvas.pageById(pageId) ?: return
        scheduleSave(p, 700)
        pagesVersion++
    }

    private fun scheduleSave(p: CanvasPage, delayMs: Long) {
        saves.remove(p.id)?.job?.cancel()
        saves[p.id] = PendingSave(p, scope.launch {
            delay(delayMs)
            saves.remove(p.id)
            saveNow(p)
        })
    }

    private fun flush(pageId: String) { saves.remove(pageId)?.let { it.job.cancel(); saveNow(it.page) } }

    private fun saveNow(p: CanvasPage) {
        val strokes = p.ink.strokes.toList()
        val items = p.ink.items.toList()
        scope.launch {
            try {
                library.savePage(notebookId, p.id, strokes, items)
                if (failedSaves > 0) { failedSaves = 0; say("Wieder gespeichert") }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The page stays in memory: tell the user and keep trying, never lose it quietly.
                failedSaves++
                say(UserMessages.of(t, "Speichern"))
                if (saves[p.id] == null) scheduleSave(p, (5_000L * failedSaves).coerceAtMost(60_000L))
            }
        }
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
                ToolRingGeometry.swatches = if (tool == ToolKind.MARKER) PenPresets.fill(recentMarker, InkPalette.marker, 5)
                    else PenPresets.fill(recentPen, InkPalette.pen, 6)
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

    private companion object {
        /** What was copied stays available in the next notebook. */
        var sharedClip: Clip? = null
    }
}
