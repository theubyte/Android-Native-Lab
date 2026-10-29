package de.millione.schulranzen.app.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.ink.NotebookCanvasView
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.RingEvent
import de.millione.schulranzen.ink.ToolKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tool ring as the overlay draws it. */
data class RingState(val x: Float, val y: Float, val hoverX: Float, val hoverY: Float, val armed: Boolean)

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
    /** Tool before the side button toggled the eraser, to switch back. */
    private var beforeEraser = ToolKind.PEN

    private val saves = HashMap<Int, Job>()

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
        for (i in saves.keys.toList()) { saves.remove(i)?.cancel(); saveNow(i) }
        scope.launch { library.setLastPage(notebookId, page) }
        canvas.listener = null
    }

    fun select(t: ToolKind) { tool = t; applyTool() }

    fun setPen(color: Int, size: Float = penSize) {
        penColor = color; penSize = size
        settings.penColor = color; settings.penSize = size
        if (!tool.writes) tool = ToolKind.PEN
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

    fun addPage() {
        val at = page + 1
        val paper = canvas.page(page)?.paper ?: PaperStyle.LINED
        scope.launch {
            val p = library.addPage(notebookId, at, paper)
            canvas.addPage(p, at)
            pageCount = canvas.pageCount
            canvas.scrollToPage(at)
        }
    }

    fun setPaper(style: PaperStyle) {
        val p = canvas.page(page) ?: return
        p.paper = style
        canvas.refreshPage(page)
        scope.launch { library.setPaper(p.id, style) }
    }

    fun currentPaper(): PaperStyle = canvas.page(page)?.paper ?: PaperStyle.LINED

    // ---- canvas callbacks ----

    override fun onInkChanged(page: Int) {
        saves.remove(page)?.cancel()
        saves[page] = scope.launch {
            delay(700)
            saves.remove(page)
            saveNow(page)
        }
    }

    private fun saveNow(index: Int) {
        val p = canvas.page(index) ?: return
        val snapshot = p.ink.strokes.toList()
        scope.launch { library.savePage(notebookId, p.id, snapshot) }
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        this.canUndo = canUndo; this.canRedo = canRedo
    }

    override fun onVisiblePageChanged(page: Int, count: Int) {
        this.page = page; pageCount = count
    }

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
