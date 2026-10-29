package de.millione.schulranzen.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.books.SearchHit
import de.millione.schulranzen.core.books.TextSearch
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.TactileKey

/** Search in the book: field, status, hits with page and snippet; a tap jumps and marks the place. */
@Composable
internal fun SearchPanel(session: BookSession, modifier: Modifier, onClose: () -> Unit) {
    val c = Ranzen.colors
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        session.analyze()
        if (session.searchQuery.isEmpty()) runCatching { focus.requestFocus() }
    }
    PaperPanel(modifier, corner = 24.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Im Buch suchen", style = Ranzen.type.heading, color = c.ink, modifier = Modifier.weight(1f))
                TactileKey(onClose, contentDescription = "Schließen") { RanzenIcon(RanzenIcons.Close, size = 20.dp) }
            }
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.raised)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RanzenIcon(RanzenIcons.Search, tint = c.inkMuted, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    if (session.searchQuery.isEmpty()) Text("Wort oder Satzteil", style = Ranzen.type.body, color = c.inkFaint)
                    BasicTextField(
                        session.searchQuery, session::search,
                        textStyle = Ranzen.type.body.copy(color = c.ink),
                        singleLine = true,
                        cursorBrush = SolidColor(c.signal),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (session.searchQuery.isNotEmpty()) {
                    TactileKey({ session.search("") }, size = 40.dp, contentDescription = "Suche leeren") {
                        RanzenIcon(RanzenIcons.Close, tint = c.inkMuted, size = 16.dp)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                IndexStatus(session)
                val hits = session.searchHits
                val q = session.searchQuery.trim()
                when {
                    q.length < 2 -> Unit
                    session.searching && hits.isEmpty() -> Text("Suche …", style = Ranzen.type.caption, color = c.inkMuted)
                    hits.isEmpty() -> Text(
                        if (session.indexInfo?.pagesWithText == 0) "Noch kein lesbarer Text in diesem Buch." else "Nichts gefunden.",
                        style = Ranzen.type.caption, color = c.inkMuted,
                    )
                    else -> Text(
                        if (hits.size >= TextSearch.MAX_HITS) "Mehr als ${TextSearch.MAX_HITS} Treffer – genauer suchen"
                        else if (hits.size == 1) "1 Treffer" else "${hits.size} Treffer auf ${hits.distinctBy { it.page }.size} Seiten",
                        style = Ranzen.type.caption, color = c.inkMuted,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(session.searchHits, key = { i, h -> "${h.page}:${h.start}:$i" }) { _, h ->
                    HitRow(h, h == session.currentHit) {
                        focusManager.clearFocus()
                        session.openHit(h)
                        onClose()
                    }
                }
                item { RecognitionOffer(session, Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, current: Boolean, onClick: () -> Unit) {
    val c = Ranzen.colors
    val text = buildAnnotatedString {
        append(hit.snippet.substring(0, hit.matchStart))
        withStyle(SpanStyle(color = c.signal, fontWeight = FontWeight(650))) { append(hit.snippet.substring(hit.matchStart, hit.matchEnd)) }
        append(hit.snippet.substring(hit.matchEnd))
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
            .background(if (current) c.signal.copy(alpha = 0.08f) else Color.Transparent)
            .padding(start = 22.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("S. ${hit.page + 1}", style = Ranzen.type.mono, color = if (current) c.signal else c.inkMuted, modifier = Modifier.width(64.dp))
        Text(text, style = Ranzen.type.body, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** One line about background text work: reading the text layer or recognising pages. */
@Composable
internal fun IndexStatus(session: BookSession) {
    val c = Ranzen.colors
    val p = session.indexProgress ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Lamp(c.signal, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        val text = when (p.phase) {
            BookIndexer.Phase.TEXT -> if (p.done == 0) "Text wird gelesen …" else "Text wird gelesen: Seite ${p.done + 1} von ${p.total}"
            BookIndexer.Phase.OCR -> "Texterkennung: Seite ${p.done + 1} von ${p.total}"
        }
        Text(text, style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.weight(1f))
        if (p.phase == BookIndexer.Phase.OCR) QuietButton("Anhalten", session::stopRecognition)
    }
}

/**
 * For scanned books: offers text recognition on the device. It feeds the
 * search and the contents, runs in the background and resumes after a stop.
 */
@Composable
internal fun RecognitionOffer(session: BookSession, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val info = session.indexInfo ?: return
    if (session.indexProgress != null || !info.textDone || !info.scanned || info.pagesWithoutText == 0) return
    val started = info.pagesWithText > 0
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(if (started) "Texterkennung angehalten" else "Gescanntes Buch", style = Ranzen.type.label, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            if (started) "Noch ${info.pagesWithoutText} Seiten ohne Text. Die Erkennung macht dort weiter, wo sie aufgehört hat."
            else "Die Seiten sind Bilder ohne Text. Die Texterkennung liest sie auf dem Tablet, ohne Internet – danach gibt es Inhaltsverzeichnis und Suche. Das dauert etwa eine Sekunde pro Seite und läuft im Hintergrund weiter.",
            style = Ranzen.type.body, color = c.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        SignalButton(if (started) "Weiter erkennen" else "Text erkennen", session::startRecognition)
    }
}
