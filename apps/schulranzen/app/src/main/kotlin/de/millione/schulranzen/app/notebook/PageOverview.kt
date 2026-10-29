package de.millione.schulranzen.app.notebook

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
 * All pages as small sheets. Tap jumps there; the bar below acts on the
 * chosen page: move, duplicate, bookmark, lock, delete. "Lesezeichen"
 * shows only bookmarked pages.
 */
@Composable
fun PageOverview(session: NotebookSession, modifier: Modifier, onClose: () -> Unit) {
    val c = Ranzen.colors
    val version = session.pagesVersion
    var chosen by remember { mutableStateOf(session.page) }
    var onlyBookmarks by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val all = remember(version, session.pageCount) { session.pages.toList() }
    val shown = all.withIndex().filter { !onlyBookmarks || it.value.bookmarked }
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) { runCatching { grid.scrollToItem((session.page - 2).coerceAtLeast(0)) } }

    PaperPanel(modifier, corner = 24.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 22.dp, end = 10.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Seiten", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.width(10.dp))
                Text("%02d".format(all.size), style = Ranzen.type.mono, color = c.inkMuted)
                Spacer(Modifier.weight(1f))
                QuietButton(if (onlyBookmarks) "Alle" else "Lesezeichen", { onlyBookmarks = !onlyBookmarks }, color = if (onlyBookmarks) c.signal else c.inkMuted)
            }
            LazyVerticalGrid(
                GridCells.Fixed(2), state = grid,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(shown, key = { _, p -> p.value.id }) { _, (index, page) ->
                    Thumb(page, index, chosen == index, version) {
                        chosen = index; confirmDelete = false
                        session.goTo(index)
                    }
                }
            }
            if (shown.isEmpty()) Text("Noch keine Lesezeichen. Setze eines im Menü „Mehr“.", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(22.dp))
            // Actions for the chosen page
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            val page = all.getOrNull(chosen)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action(RanzenIcons.ArrowUp, "Vor") { session.movePage(chosen, chosen - 1); chosen = (chosen - 1).coerceAtLeast(0) }
                Action(RanzenIcons.ArrowDown, "Zurück") { session.movePage(chosen, chosen + 1); chosen = (chosen + 1).coerceAtMost(all.size - 1) }
                Action(RanzenIcons.Pages, "Kopie") { session.duplicatePage(chosen) }
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

@Composable
private fun Thumb(page: CanvasPage, index: Int, chosen: Boolean, version: Int, onClick: () -> Unit) {
    val c = Ranzen.colors
    val widthPx = with(LocalDensity.current) { 150.dp.roundToPx() }
    // Snapshot on the main thread, draw in the background.
    val snapshot = remember(page, page.ink.revision, page.paper, version) {
        CanvasPage(page.id, page.paper, PageInk(page.ink.strokes.toList(), page.ink.items.toList()), background = page.background)
    }
    val bmp by produceState<Bitmap?>(null, snapshot) { value = withContext(Dispatchers.Default) { PagePainter.thumbnail(snapshot, widthPx) } }
    val shape = RoundedCornerShape(6.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
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
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RanzenIcon(icon, tint = tint, size = 20.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, style = Ranzen.type.caption, color = tint)
    }
}
