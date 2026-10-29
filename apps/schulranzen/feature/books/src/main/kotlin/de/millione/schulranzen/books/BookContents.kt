package de.millione.schulranzen.books

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.books.OutlineEntry
import de.millione.schulranzen.core.books.OutlineSource
import de.millione.schulranzen.core.books.PageBlocks
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.RegisterTabs
import de.millione.schulranzen.design.TactileKey

/** The side sheet: contents, page overview and the reader's own marks, as register tabs. */
@Composable
internal fun ContentsPanel(session: BookSession, modifier: Modifier, onClose: () -> Unit) {
    val c = Ranzen.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    PaperPanel(modifier, corner = 24.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(session.title, style = Ranzen.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TactileKey(onClose, contentDescription = "Schließen") { RanzenIcon(RanzenIcons.Close, size = 20.dp) }
            }
            RegisterTabs(
                tabs = listOf("Inhalt" to null, "Seiten" to null, "Merkliste" to null),
                selected = tab, onSelect = { tab = it },
                modifier = Modifier.padding(start = 16.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            when (tab) {
                0 -> OutlineTab(session, onClose)
                1 -> PagesTab(session, onClose)
                else -> MarksTab(session, onClose)
            }
        }
    }
}

@Composable
private fun OutlineTab(session: BookSession, onClose: () -> Unit) {
    val c = Ranzen.colors
    LaunchedEffect(Unit) { if (session.outline == null) session.analyze() }
    val outline = session.outline
    val running = session.indexProgress
    val entries = outline?.entries.orEmpty()
    val current = entries.indexOfLast { it.page <= session.page }
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) { if (current > 2) list.scrollToItem(current - 2) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            if (running != null) {
                IndexStatus(session)
            } else if (outline != null) {
                Text(outline.summary(), style = Ranzen.type.caption, color = c.inkMuted)
                if (outline.source == OutlineSource.PRINTED_TOC || outline.source == OutlineSource.HEADINGS) {
                    Text("Automatisch erkannt – Seiten können leicht abweichen.", style = Ranzen.type.caption, color = c.inkFaint)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list) {
            itemsIndexed(entries, key = { i, _ -> i }) { i, e -> OutlineRow(e, i == current) { session.goTo(e.page); onClose() } }
            if (running == null && outline != null && entries.isEmpty() && outline.hasText) item {
                Text(
                    "Keine Kapitel gefunden. Die Seitenübersicht hilft weiter.",
                    style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(22.dp),
                )
            }
            item { RecognitionOffer(session, Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) }
        }
        if (running == null && outline != null && outline.source != OutlineSource.EMBEDDED) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                QuietButton("Neu analysieren", { session.analyze(force = true) })
            }
        }
    }
}

@Composable
private fun OutlineRow(e: OutlineEntry, current: Boolean, onClick: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
            .background(if (current) c.signal.copy(alpha = 0.08f) else Color.Transparent)
            .padding(start = (22 + e.level.coerceAtMost(4) * 18).dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current) { Lamp(c.signal, size = 7.dp); Spacer(Modifier.width(10.dp)) }
        Text(
            e.title,
            style = if (e.level == 0) Ranzen.type.body.copy(fontWeight = FontWeight(600)) else Ranzen.type.body,
            color = if (e.level == 0 || current) c.ink else c.inkMuted,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text("${e.page + 1}", style = Ranzen.type.mono, color = c.inkMuted)
    }
}

/** Thumbnails in blocks of [PageBlocks.SIZE], so a big book never renders all pages at once. */
@Composable
private fun PagesTab(session: BookSession, onClose: () -> Unit) {
    val c = Ranzen.colors
    val blocks = PageBlocks.count(session.pageCount)
    var block by rememberSaveable { mutableIntStateOf(PageBlocks.blockOf(session.page)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TactileKey({ if (block > 0) block-- }, contentDescription = "Vorige Seiten") {
                RanzenIcon(BookIcons.Prev, tint = if (block > 0) c.ink else c.inkFaint)
            }
            Text(
                "Seiten ${PageBlocks.label(block, session.pageCount)}", style = Ranzen.type.mono, color = c.ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            TactileKey({ if (block < blocks - 1) block++ }, contentDescription = "Nächste Seiten") {
                RanzenIcon(BookIcons.Next, tint = if (block < blocks - 1) c.ink else c.inkFaint)
            }
        }
        val pages = remember(block, session.pageCount) { PageBlocks.block(block, session.pageCount).toList() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(pages, key = { it }) { p ->
                Thumb(session, p, current = p == session.page, bookmarked = p in session.marks.bookmarks) { session.goTo(p); onClose() }
            }
        }
    }
}

@Composable
private fun Thumb(session: BookSession, page: Int, current: Boolean, bookmarked: Boolean, onClick: () -> Unit) {
    val c = Ranzen.colors
    val rotation = session.marks.rotation(page)
    val image by produceState<ImageBitmap?>(null, page, rotation) { value = session.thumbnail(page)?.asImageBitmap() }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f / session.boxAspect(page)).clip(shape).background(Color.White)
                .border(if (current) 2.dp else 1.dp, if (current) c.signal else c.line, shape),
        ) {
            image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (bookmarked) RanzenIcon(RanzenIcons.Bookmark, tint = c.signal, size = 18.dp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("${page + 1}", style = Ranzen.type.mono, color = if (current) c.signal else c.inkMuted)
    }
}

/** Bookmarks, kept areas and notes of this book, each a jump target. */
@Composable
private fun MarksTab(session: BookSession, onClose: () -> Unit) {
    val c = Ranzen.colors
    val m = session.marks
    val bookmarks = m.bookmarks.sorted()
    val areas = m.areas.sortedBy { it.page }
    val notes = m.notes.sortedBy { it.page }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Section("Lesezeichen") }
        if (bookmarks.isEmpty()) item { Hint("Noch keine. Das Lesezeichen oben merkt die aktuelle Seite.") }
        items(bookmarks, key = { "b$it" }) { p ->
            MarkRow(RanzenIcons.Bookmark, c.signal, "Seite ${p + 1}", null, onOpen = { session.goTo(p); onClose() }, onDelete = { session.toggleBookmark(p) })
        }
        item { Section("Markierte Bereiche") }
        if (areas.isEmpty()) item { Hint("Mit dem Bereich-Werkzeug ein Rechteck ziehen und „Merken“ wählen.") }
        items(areas, key = { "a${it.id}" }) { a ->
            MarkRow(BookIcons.Star, c.signal, a.label, "S. ${a.page + 1}", onOpen = { session.goTo(a.page); onClose() }, onDelete = { session.deleteArea(a.id) })
        }
        item { Section("Notizen") }
        if (notes.isEmpty()) item { Hint("Mit dem Notiz-Werkzeug auf eine Stelle tippen.") }
        items(notes, key = { "n${it.id}" }) { n ->
            MarkRow(BookIcons.Note, Color(0xFFC99A2E), n.text, "S. ${n.page + 1}", onOpen = { session.goTo(n.page); onClose() }, onDelete = { session.deleteNote(n.id) })
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), style = Ranzen.type.caption, color = Ranzen.colors.inkMuted,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = Ranzen.type.body, color = Ranzen.colors.inkFaint, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

@Composable
private fun MarkRow(icon: ImageVector, tint: Color, title: String, page: String?, onOpen: () -> Unit, onDelete: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen).padding(start = 22.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RanzenIcon(icon, tint = tint, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(title, style = Ranzen.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (page != null) { Spacer(Modifier.width(10.dp)); Text(page, style = Ranzen.type.mono, color = c.inkMuted) }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            RanzenIcon(RanzenIcons.Close, tint = c.inkFaint, size = 18.dp)
        }
    }
}
