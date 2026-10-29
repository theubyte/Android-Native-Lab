package de.millione.schulranzen.study

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.Rating
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.softRaise
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.ui.Counter
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.SheetActions
import de.millione.schulranzen.study.ui.StudyIcons
import de.millione.schulranzen.study.ui.SubjectPicker
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * A classic index card: a coloured headline rule and faint ruled lines,
 * the way paper flash cards look in a card box.
 */
internal fun Modifier.indexCard(accent: Color, line: Color, headline: Float = 44f): Modifier = drawBehind {
    val top = headline * density
    drawLine(accent, Offset(0f, top), Offset(size.width, top), 1.6f * density)
    var y = top + 26f * density
    while (y < size.height - 8f * density) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), 0.7f * density)
        y += 26f * density
    }
}

@Composable
internal fun DeckRow(deck: DeckCounts, onOpen: () -> Unit, onStudy: () -> Unit) {
    val c = Ranzen.colors
    val accent = SubjectPalette.of(deck.colorIndex)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The deck seen from above: a small stack of cards with its subject tab.
        Box(Modifier.width(52.dp).height(40.dp)) {
            Box(Modifier.padding(start = 6.dp, top = 0.dp).width(42.dp).height(30.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
            Box(
                Modifier.padding(start = 0.dp, top = 8.dp).width(46.dp).height(30.dp).clip(RoundedCornerShape(4.dp))
                    .background(c.raised).border(1.dp, c.line, RoundedCornerShape(4.dp)),
            ) { Box(Modifier.fillMaxWidth().height(4.dp).background(accent)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(deck.name, style = Ranzen.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${deck.total} Karten · ${deck.fresh} neu", style = Ranzen.type.caption, color = c.inkMuted)
        }
        if (deck.due > 0) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onStudy).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lamp(c.signal, size = 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("%d fällig".format(deck.due), style = Ranzen.type.mono, color = c.ink)
                }
            }
        } else {
            Text("erledigt", style = Ranzen.type.mono, color = c.inkFaint, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

/** All cards of a deck as index cards; tap one to edit. */
@Composable
internal fun DeckCards(cards: List<Card>, accent: Color, today: LocalDate, onEdit: (Card) -> Unit, onNew: () -> Unit) {
    val c = Ranzen.colors
    PaperPanel(Modifier.fillMaxSize().padding(bottom = 24.dp), corner = 24.dp) {
        if (cards.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Noch keine Karten", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.height(8.dp))
                EmptyNote("Vorne die Frage, hinten die Antwort. Kurz und eindeutig lernt es sich am besten.")
                Spacer(Modifier.height(12.dp))
                SignalButton("Erste Karte", onNew, icon = de.millione.schulranzen.design.RanzenIcons.Plus)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.id }) { card -> CardTile(card, accent, today) { onEdit(card) } }
            }
        }
    }
}

@Composable
private fun CardTile(card: Card, accent: Color, today: LocalDate, onClick: () -> Unit) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(8.dp)
    val due = LocalDate.ofEpochDay(card.dueEpochDay)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.6f)
            .softRaise(8.dp, 0.6f, c.bevelLight, c.bevelShadow)
            .clip(shape).background(c.raised)
            .indexCard(accent, c.line.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(card.front, style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(26.dp).padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            Text(card.back, style = Ranzen.type.body, color = c.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when {
                    card.lastReviewEpochDay == null -> "NEU"
                    !due.isAfter(today) -> "FÄLLIG"
                    else -> "FÄLLIG " + due.format(Fmt.shortDate)
                },
                style = Ranzen.type.mono.copy(fontSize = Ranzen.type.caption.fontSize),
                color = if (!due.isAfter(today)) c.signal else c.inkFaint,
            )
        }
    }
}

@Composable
internal fun CardSheet(card: Card?, onCancel: () -> Unit, onDelete: (() -> Unit)?, onSave: (front: String, back: String, keepOpen: Boolean) -> Unit) {
    val c = Ranzen.colors
    var front by remember { mutableStateOf(card?.front ?: "") }
    var back by remember { mutableStateOf(card?.back ?: "") }
    var made by remember { mutableIntStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (card == null) "Neue Karte" else "Karte bearbeiten", style = Ranzen.type.title, color = c.ink, modifier = Modifier.weight(1f))
        if (made > 0) Text("$made angelegt", style = Ranzen.type.mono, color = c.inkMuted)
    }
    Spacer(Modifier.height(22.dp))
    FieldLabel("Vorderseite · Frage")
    LineField(front, { front = it }, "z. B. Mindesttemperatur Warmwasser am Speicheraustritt?", singleLine = false, minLines = 2, style = Ranzen.type.heading)
    Spacer(Modifier.height(20.dp))
    FieldLabel("Rückseite · Antwort")
    LineField(back, { back = it }, "z. B. 60 °C (Legionellenschutz)", singleLine = false, minLines = 3)
    SheetActions(
        primary = if (card == null) "Speichern, nächste" else "Speichern",
        onPrimary = {
            if (front.isBlank()) return@SheetActions
            onSave(front, back, card == null)
            if (card == null) { front = ""; back = ""; made++ }
        },
        onCancel = onCancel,
        onDelete = onDelete,
        cancelLabel = if (card == null && made > 0) "Fertig" else "Abbrechen",
    )
}

@Composable
internal fun DeckSheet(deck: DeckCounts?, known: List<StudySubject>, onCancel: () -> Unit, onDelete: (() -> Unit)?, onSave: (name: String, color: Int, subjectId: Long?) -> Unit) {
    val c = Ranzen.colors
    var name by remember { mutableStateOf(deck?.name ?: "") }
    var color by remember { mutableStateOf(deck?.colorIndex ?: known.size) }
    var subjectId by remember { mutableStateOf(deck?.subjectId) }
    Text(if (deck == null) "Neuer Stapel" else "Stapel bearbeiten", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(6.dp))
    Text("Ein Stapel je Fach. Prüfungen im selben Fach steuern die Wiederholung.", style = Ranzen.type.caption, color = c.inkMuted)
    Spacer(Modifier.height(20.dp))
    SubjectPicker(name, color, known, onChange = { n, col, id -> name = n; color = col; subjectId = id })
    if (deck != null && deck.total > 0) {
        Spacer(Modifier.height(10.dp))
        Text("Beim Löschen gehen auch die ${deck.total} Karten verloren.", style = Ranzen.type.caption, color = c.inkMuted)
    }
    SheetActions(
        primary = "Speichern",
        onPrimary = { if (name.isNotBlank()) onSave(name.trim(), color, subjectId) },
        onCancel = onCancel,
        onDelete = onDelete,
    )
}

/**
 * Learning mode: one card at a time, tap to turn it over, then rate.
 * "Nochmal" puts the card at the end of today's round. The queue is read
 * once at the start so the order stays calm while answers are saved.
 */
@Composable
internal fun ReviewSession(repo: StudyRepository, deckId: Long?, onDone: () -> Unit, onProgress: (done: Int, left: Int, canUndo: Boolean) -> Unit, undoSignal: Int) {
    val c = Ranzen.colors
    val scope = rememberCoroutineScope()
    val queue = remember { mutableStateListOf<Card>() }
    var loaded by remember { mutableStateOf(false) }
    var flipped by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var again by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<Pair<StudyRepository.Undo, Boolean>>() }
    var preview by remember { mutableStateOf<Map<Rating, Int>>(emptyMap()) }

    LaunchedEffect(deckId) {
        queue.clear(); queue.addAll(repo.studyQueue(deckId)); loaded = true
    }
    val card = queue.firstOrNull()
    LaunchedEffect(card?.id, card?.reps, card?.dueEpochDay) { preview = card?.let { repo.preview(it) } ?: emptyMap() }
    LaunchedEffect(done, queue.size, history.size) { onProgress(done, queue.size, history.isNotEmpty()) }
    // Undo is triggered from the header key.
    LaunchedEffect(undoSignal) {
        if (undoSignal == 0) return@LaunchedEffect
        val (token, wasAgain) = history.removeLastOrNull() ?: return@LaunchedEffect
        repo.undo(token)
        if (wasAgain) { queue.removeAll { it.id == token.before.id }; again-- }
        queue.add(0, token.before)
        done--
        flipped = false
    }

    var busy by remember { mutableStateOf(false) }
    fun rate(r: Rating) {
        val current = card ?: return
        // One answer per card, even if a key is tapped twice quickly.
        if (busy) return
        busy = true
        scope.launch {
            try {
                val token = repo.rate(current, r)
                queue.removeAt(0)
                if (r == Rating.AGAIN) { queue.add(token.after); again++ }
                history.add(token to (r == Rating.AGAIN))
                done++
                flipped = false
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!loaded) return@Column
        if (card == null) {
            Finished(done, again, onDone)
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            key(card.id, done) { FlipCard(card, flipped, onFlip = { flipped = !flipped }) }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.widthIn(max = 820.dp).fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
            if (!flipped) {
                SignalButton("Antwort zeigen", { flipped = true }, icon = StudyIcons.Flip)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RatingKey("Nochmal", preview[Rating.AGAIN], SubjectPalette.of(1), Modifier.weight(1f)) { rate(Rating.AGAIN) }
                    RatingKey("Schwer", preview[Rating.HARD], SubjectPalette.of(2), Modifier.weight(1f)) { rate(Rating.HARD) }
                    RatingKey("Gut", preview[Rating.GOOD], SubjectPalette.of(3), Modifier.weight(1f)) { rate(Rating.GOOD) }
                    RatingKey("Leicht", preview[Rating.EASY], SubjectPalette.of(0), Modifier.weight(1f)) { rate(Rating.EASY) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FlipCard(card: Card, flipped: Boolean, onFlip: () -> Unit) {
    val c = Ranzen.colors
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "flip")
    val showBack = rotation > 90f
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().aspectRatio(1.65f)
            .graphicsLayer { rotationY = rotation; cameraDistance = 14f * density }
            .softRaise(14.dp, 1f, c.bevelLight, c.bevelShadow)
            .clip(shape).background(c.raised)
            .clickable(role = Role.Button, onClickLabel = "Karte umdrehen", onClick = onFlip),
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { if (showBack) rotationY = 180f }
                .indexCard(if (showBack) SubjectPalette.of(3) else c.signal, c.line.copy(alpha = 0.55f), headline = 64f)
                .padding(horizontal = 36.dp, vertical = 20.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (showBack) "ANTWORT" else "FRAGE", style = Ranzen.type.mono,
                    color = c.inkMuted, modifier = Modifier.height(32.dp).padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    if (showBack) {
                        Text(card.front, style = Ranzen.type.body, color = c.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Text(card.back.ifBlank { "–" }, style = Ranzen.type.title, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(card.front, style = Ranzen.type.title, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!showBack) Text("Antippen zum Umdrehen", style = Ranzen.type.caption, color = c.inkFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RatingKey(label: String, days: Int?, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Ranzen.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth by animateFloatAsState(if (pressed) 0.15f else 1f, spring(dampingRatio = 0.55f), label = "d")
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f), label = "s")
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxHeight().scale(s)
            .softRaise(16.dp, depth, c.bevelLight, c.bevelShadow)
            .clip(shape).background(c.raised).border(1.dp, c.line, shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(accent, size = 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = Ranzen.type.label, color = c.ink)
        }
        Spacer(Modifier.height(4.dp))
        Text(days?.let(Fmt::interval)?.uppercase(Locale.GERMANY) ?: " ", style = Ranzen.type.mono, color = c.inkMuted)
    }
}

@Composable
private fun Finished(done: Int, again: Int, onDone: () -> Unit) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (done == 0) "Nichts fällig" else "Geschafft", style = Ranzen.type.hero, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Text(
            if (done == 0) "Für heute ist alles wiederholt." else "Für heute ist dieser Stapel durch. Morgen geht es weiter.",
            style = Ranzen.type.body, color = c.inkMuted,
        )
        if (done > 0) {
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Counter("%02d".format(done), "Antworten")
                Counter("%02d".format(again), "Nochmal")
            }
        }
        Spacer(Modifier.height(32.dp))
        SignalButton("Zur Übersicht", onDone)
    }
}
