package de.millione.schulranzen.app.phone

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.AppIcons
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.notebook.NotebookSession
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.ink.CanvasPage
import de.millione.schulranzen.ink.PageGeometry
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A notebook for reading. Like on the tablet the sheets are the native canvas
 * below this transparent layer, but switched to read-only: a finger or pen
 * only scrolls and zooms. Bookmarks and exam marks are notes about a page,
 * not writing, so they stay.
 */
@Composable
fun PhoneNotebookScreen(
    session: NotebookSession,
    library: Library,
    onBack: () -> Unit,
    /** Scan new sheets into this notebook, behind the page given. */
    onScanHere: (after: Int) -> Unit,
) {
    val c = Ranzen.colors
    var overview by rememberSaveable { mutableStateOf(false) }
    // Null until the database answered; false = the link leads nowhere (deleted, or not synced here yet).
    val exists by produceState<Boolean?>(null, session.notebookId) { value = library.notebook(session.notebookId) != null }
    BackHandler {
        when {
            session.markDraft != null -> session.markDraft = null
            overview -> overview = false
            else -> onBack()
        }
    }
    // If the process dies while reading, the place is not lost.
    LaunchedEffect(session.page, session.loaded) {
        if (session.loaded) { delay(1200); library.setLastPage(session.notebookId, session.page) }
    }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        // Top: back, title, page number, bookmark.
        Box(safe.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
            PaperPanel(
                Modifier.fillMaxWidth(),
                corner = 18.dp, depth = 0.7f,
            ) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TactileKey(onBack, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.title, style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (session.loaded) "Seite ${session.page + 1} von ${session.pageCount}" else "", style = Ranzen.type.caption, color = c.inkMuted)
                    }
                    @Suppress("UNUSED_VARIABLE") val v = session.pagesVersion
                    val marked = session.currentPage()?.bookmarked == true
                    TactileKey({ session.toggleBookmark() }, selected = marked, contentDescription = if (marked) "Lesezeichen entfernen" else "Lesezeichen setzen") {
                        RanzenIcon(RanzenIcons.Bookmark, tint = if (marked) c.signal else c.ink)
                    }
                }
            }
        }

        // Bottom, under the thumb: leaf, overview, exam mark, add sheets.
        if (session.loaded) Box(safe.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
            PaperPanel(corner = 20.dp, depth = 0.8f) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TactileKey({ session.goTo(session.page - 1) }, contentDescription = "Vorige Seite") {
                        RanzenIcon(RanzenIcons.ArrowUp, tint = if (session.page > 0) c.ink else c.inkFaint)
                    }
                    TactileKey({ session.goTo(session.page + 1) }, contentDescription = "Nächste Seite") {
                        RanzenIcon(RanzenIcons.ArrowDown, tint = if (session.page < session.pageCount - 1) c.ink else c.inkFaint)
                    }
                    TactileKey({ overview = true }, contentDescription = "Alle Seiten") { RanzenIcon(RanzenIcons.Pages) }
                    TactileKey(session::markForExam, contentDescription = "Für eine Prüfung merken") { RanzenIcon(AppIcons.Study) }
                    TactileKey({ onScanHere(session.page + 1) }, contentDescription = "Blatt hier einscannen") { RanzenIcon(AppIcons.Scan) }
                }
            }
        }

        when {
            exists == false || session.missing -> Box(Modifier.fillMaxSize().background(c.desk).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                PaperPanel(Modifier.padding(24.dp), corner = 20.dp) {
                    Column(Modifier.padding(20.dp)) {
                        // Exists but failed to load: that is no reason to talk of deleting or syncing.
                        Text(if (exists == false) "Heft nicht gefunden" else "Heft konnte nicht geöffnet werden", style = Ranzen.type.heading, color = c.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (exists == false) "Es wurde gelöscht oder ist auf diesem Gerät noch nicht angekommen. Abgleichen holt es vom Tablet."
                            else "Geh zurück und versuch es noch einmal.",
                            style = Ranzen.type.body, color = c.inkMuted,
                        )
                        Spacer(Modifier.height(12.dp))
                        QuietButton("Zurück", onBack, color = c.ink)
                    }
                }
            }
            !session.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.signal, strokeWidth = 2.5.dp)
            }
        }

        if (overview) PhonePageOverview(session, onClose = { overview = false })

        session.toast?.let { msg ->
            Box(safe.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 88.dp), contentAlignment = Alignment.BottomCenter) {
                Text(msg, style = Ranzen.type.label, color = c.paper, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
    // The first page starts below the bar that lies over the canvas.
    val insets = WindowInsets.safeDrawing.getTop(density)
    LaunchedEffect(insets) { session.canvas.topGapPx = insets + with(density) { 76.dp.toPx() } }
}

/** All pages to look at and jump to; nothing here changes them. */
@Composable
private fun PhonePageOverview(session: NotebookSession, onClose: () -> Unit) {
    val c = Ranzen.colors
    val version = session.pagesVersion
    var onlyMarked by rememberSaveable { mutableStateOf(false) }
    val all = remember(version, session.pageCount) { session.pages.toList() }
    val shown = all.withIndex().filter { !onlyMarked || it.value.bookmarked }
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) { runCatching { grid.scrollToItem((session.page - 3).coerceAtLeast(0)) } }
    Column(Modifier.fillMaxSize().background(c.desk).pointerInput(Unit) { detectTapGestures { } }.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TactileKey(onClose, contentDescription = "Schließen") { RanzenIcon(RanzenIcons.Close) }
            Spacer(Modifier.width(12.dp))
            Text("Seiten", style = Ranzen.type.title, color = c.ink)
            Spacer(Modifier.width(8.dp))
            Text("%02d".format(all.size), style = Ranzen.type.mono, color = c.inkMuted)
            Spacer(Modifier.weight(1f))
            QuietButton(if (onlyMarked) "Alle" else "Lesezeichen", { onlyMarked = !onlyMarked }, color = if (onlyMarked) c.signal else c.inkMuted)
        }
        if (shown.isEmpty()) Text("Noch keine Lesezeichen in diesem Heft.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(20.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(100.dp), state = grid,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(shown, key = { _, p -> p.value.id }) { _, (index, page) ->
                PageThumb(page, index, index == session.page, version) { session.goTo(index); onClose() }
            }
        }
    }
}

@Composable
private fun PageThumb(page: CanvasPage, index: Int, current: Boolean, version: Int, onClick: () -> Unit) {
    val c = Ranzen.colors
    val widthPx = with(LocalDensity.current) { 110.dp.roundToPx() }
    val snapshot = remember(page, page.ink.revision, page.paper, version) {
        CanvasPage(page.id, page.paper, PageInk(page.ink.strokes.toList(), page.ink.items.toList()), background = page.background)
    }
    val bmp by produceState<Bitmap?>(null, snapshot) { value = withContext(Dispatchers.Default) { runCatching { PagePainter.thumbnail(snapshot, widthPx) }.getOrNull() } }
    val shape = RoundedCornerShape(6.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(PageGeometry.WIDTH / PageGeometry.HEIGHT).clip(shape).background(Color(0xFFFFFEFA))
                .border(if (current) 2.dp else 1.dp, if (current) c.signal else c.line, shape),
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            if (page.bookmarked) RanzenIcon(RanzenIcons.Bookmark, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = c.signal, size = 16.dp)
            if (page.locked) RanzenIcon(RanzenIcons.Lock, Modifier.align(Alignment.TopStart).padding(4.dp), tint = Color(0xFF55524C), size = 14.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text("%02d".format(index + 1), style = Ranzen.type.mono, color = if (current) c.signal else c.inkMuted)
    }
}
