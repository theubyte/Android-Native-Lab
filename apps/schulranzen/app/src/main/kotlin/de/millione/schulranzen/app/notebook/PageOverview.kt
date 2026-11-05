package de.millione.schulranzen.app.notebook

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.millione.schulranzen.app.data.Notebook
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.ink.CanvasPage
import de.millione.schulranzen.ink.PageGeometry
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All pages as small sheets. Tap jumps there; holding and dragging a sheet
 * moves it; the bar below acts on the chosen page: move, duplicate, send to
 * another notebook, bookmark, lock, delete. "Lesezeichen" shows only
 * bookmarked pages.
 */
@Composable
fun PageOverview(session: NotebookSession, modifier: Modifier, onClose: () -> Unit) {
    val c = Ranzen.colors
    val version = session.pagesVersion
    var chosen by remember { mutableStateOf(session.page) }
    var onlyBookmarks by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val all = remember(version, session.pageCount) { session.pages.toList() }
    val shown = all.withIndex().filter { !onlyBookmarks || it.value.bookmarked }
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) { runCatching { grid.scrollToItem((session.page - 2).coerceAtLeast(0)) } }
    // Dragging a sheet: which one, how far, and over which sheet it hangs now.
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragBy by remember { mutableStateOf(Offset.Zero) }
    var dropOn by remember { mutableStateOf<Int?>(null) }

    PaperPanel(modifier, corner = 24.dp) {
        if (moving) {
            MoveTarget(session, chosen, onDone = { moving = false; chosen = chosen.coerceAtMost(session.pageCount - 1) })
            return@PaperPanel
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 22.dp, end = 10.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Seiten", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.width(10.dp))
                Text("%02d".format(all.size), style = Ranzen.type.mono, color = c.inkMuted)
                Spacer(Modifier.weight(1f))
                QuietButton(if (onlyBookmarks) "Alle" else "Lesezeichen", { onlyBookmarks = !onlyBookmarks }, color = if (onlyBookmarks) c.signal else c.inkMuted)
                QuietButton("Schließen", onClose)
            }
            LazyVerticalGrid(
                GridCells.Fixed(2), state = grid,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(shown, key = { _, p -> p.value.id }) { gridIndex, (index, page) ->
                    val dragged = dragFrom == index
                    Box(
                        Modifier.animateItem()
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer {
                                if (dragged) { translationX = dragBy.x; translationY = dragBy.y; scaleX = 1.05f; scaleY = 1.05f; alpha = 0.9f }
                            }
                            // In the filtered view neighbours are not neighbours in the notebook.
                            .pointerInput(index, onlyBookmarks) {
                                if (onlyBookmarks) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragFrom = index; dragBy = Offset.Zero; dropOn = index; chosen = index; confirmDelete = false },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragBy += amount
                                        val items = grid.layoutInfo.visibleItemsInfo
                                        val me = items.firstOrNull { it.index == gridIndex }
                                        if (me != null) {
                                            val x = me.offset.x + me.size.width / 2 + dragBy.x
                                            val y = me.offset.y + me.size.height / 2 + dragBy.y
                                            items.firstOrNull { x >= it.offset.x && x < it.offset.x + it.size.width && y >= it.offset.y && y < it.offset.y + it.size.height }
                                                ?.let { dropOn = it.index }
                                        }
                                    },
                                    onDragEnd = {
                                        val to = dropOn
                                        if (to != null && to != index) { session.movePage(index, to); chosen = to }
                                        dragFrom = null; dropOn = null
                                    },
                                    onDragCancel = { dragFrom = null; dropOn = null },
                                )
                            },
                    ) {
                        Thumb(page, index, chosen == index || dropOn == index, version) {
                            chosen = index; confirmDelete = false
                            session.goTo(index)
                        }
                    }
                }
            }
            if (shown.isEmpty()) Text("Noch keine Lesezeichen. Setze eines im Menü „Mehr“.", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(22.dp))
            // Actions for the chosen page
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            val page = all.getOrNull(chosen)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action(RanzenIcons.ArrowUp, "Vor") { session.movePage(chosen, chosen - 1); chosen = (chosen - 1).coerceAtLeast(0) }
                Action(RanzenIcons.ArrowDown, "Zurück") { session.movePage(chosen, chosen + 1); chosen = (chosen + 1).coerceAtMost(all.size - 1) }
                Action(RanzenIcons.Pages, "Kopie") { session.duplicatePage(chosen) }
                Action(RanzenIcons.Notebook, "Heft …") { moving = true }
                Action(RanzenIcons.Bookmark, "Merken", active = page?.bookmarked == true) { session.toggleBookmark(chosen) }
                Action(RanzenIcons.Lock, "Sperren", active = page?.locked == true) { session.toggleLock(chosen) }
                Action(RanzenIcons.Trash, if (confirmDelete) "Sicher?" else "Löschen", danger = true) {
                    if (confirmDelete) { session.deletePage(chosen); chosen = chosen.coerceAtMost(all.size - 2).coerceAtLeast(0); confirmDelete = false }
                    else confirmDelete = true
                }
            }
        }
    }
}

/** Into which notebook the chosen page moves. */
@Composable
private fun MoveTarget(session: NotebookSession, index: Int, onDone: () -> Unit) {
    val c = Ranzen.colors
    val notebooks by session.otherNotebooks.collectAsState(initial = emptyList<Notebook>())
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Seite ${index + 1} verschieben", style = Ranzen.type.title, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text("Sie kommt ans Ende des gewählten Hefts.", style = Ranzen.type.caption, color = c.inkMuted)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            notebooks.forEach { nb ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { session.movePageTo(index, nb); onDone() }.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RanzenIcon(RanzenIcons.Notebook, size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(nb.title, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text("${nb.pageCount} S.", style = Ranzen.type.mono, color = c.inkMuted)
                }
            }
            if (notebooks.isEmpty()) Text("Es gibt noch kein anderes Heft.", style = Ranzen.type.body, color = c.inkMuted)
        }
        Spacer(Modifier.height(8.dp))
        Row { Spacer(Modifier.weight(1f)); QuietButton("Abbrechen", onDone) }
    }
}

@Composable
private fun Thumb(page: CanvasPage, index: Int, chosen: Boolean, version: Int, onClick: () -> Unit) {
    val c = Ranzen.colors
    val widthPx = with(LocalDensity.current) { 150.dp.roundToPx() }
    // Snapshot on the main thread, draw in the background.
    val snapshot = remember(page, page.ink.revision, page.paper, version) {
        CanvasPage(page.id, page.paper, PageInk(page.ink.strokes.toList(), page.ink.items.toList()), background = page.background)
    }
    // Out of memory costs a small picture, not the app.
    val bmp by produceState<Bitmap?>(null, snapshot) {
        value = withContext(Dispatchers.Default) { runCatching { PagePainter.thumbnail(snapshot, widthPx) }.getOrNull() }
    }
    val shape = RoundedCornerShape(6.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClickLabel = "Zu dieser Seite", onClick = onClick).semantics {
            contentDescription = "Seite ${index + 1}" + (if (page.bookmarked) ", Lesezeichen" else "") + (if (page.locked) ", gesperrt" else "")
            selected = chosen
        },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(PageGeometry.WIDTH / PageGeometry.HEIGHT)
                .clip(shape).background(androidx.compose.ui.graphics.Color(0xFFFFFEFA))
                .border(if (chosen) 2.dp else 1.dp, if (chosen) c.signal else c.line, shape),
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            if (page.bookmarked) RanzenIcon(RanzenIcons.Bookmark, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = c.signal, size = 18.dp)
            if (page.locked) RanzenIcon(RanzenIcons.Lock, Modifier.align(Alignment.TopStart).padding(6.dp), tint = c.inkMuted, size = 16.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text("%02d".format(index + 1), style = Ranzen.type.mono, color = if (chosen) c.signal else c.inkMuted)
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, active: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = Ranzen.colors
    val tint = when { danger -> c.signal; active -> c.signal; else -> c.ink }
    Column(
        Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RanzenIcon(icon, tint = tint, size = 20.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, style = Ranzen.type.caption, color = tint)
    }
}
