package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.data.StudyLink
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.SheetActions
import de.millione.schulranzen.study.ui.SheetHost
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * A sheet over whatever the host shows, for the public entry points
 * (mark for exam, card from cut-out, card suggestions). Shown while it is
 * in the composition; tapping beside it or back cancels.
 */
@Composable
internal fun StudyOverlay(onDismiss: () -> Unit, width: Dp = 600.dp, content: @Composable () -> Unit) {
    SheetHost(visible = true, onDismiss = onDismiss, width = width, content = content)
}

/**
 * "Für Prüfung markieren": the host passes the place (notebook or book
 * page, optional area); the learner picks one of the coming exams (nearest
 * first) or the bookmark list, adds a note if wanted, and saves.
 * [onDone] is called after the link is stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkForExamSheet(link: StudyLinkDraft, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val exams by repo.nextExams.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<Long?>(null) }
    var picked by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var label by remember { mutableStateOf(link.label) }
    var saving by remember { mutableStateOf(false) }
    val c = Ranzen.colors
    val today = LocalDate.now()

    // Preselect the nearest exam once the list is there.
    LaunchedEffect(exams) { if (!picked && exams != null) { choice = exams!!.firstOrNull()?.id; picked = true } }

    StudyOverlay(onCancel, width = 620.dp) {
        Text("Als Prüfungsstoff merken", style = Ranzen.type.title, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RanzenIcon(if (link.kind == StudyLinkKind.BOOK_PAGE) RanzenIcons.Book else RanzenIcons.Notebook, tint = c.inkMuted, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${link.kind.label} · Seite ${link.page + 1}" + if (link.rect != null) " · Ausschnitt" else "",
                style = Ranzen.type.mono, color = c.inkMuted,
            )
        }
        Spacer(Modifier.height(20.dp))
        FieldLabel("Bezeichnung")
        LineField(label, { label = it }, "z. B. Fachkunde SHK S. 112", style = Ranzen.type.heading)
        Spacer(Modifier.height(20.dp))
        FieldLabel("Für welche Prüfung?")
        val list = exams.orEmpty()
        if (exams != null && list.isEmpty()) EmptyNote("Keine Prüfung geplant. Die Stelle kommt auf die Merkliste.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { e ->
                val date = LocalDate.ofEpochDay(e.dateEpochDay)
                OptionPlate(
                    "${e.subject} · ${date.format(Fmt.shortDate)} (${Fmt.countdown(today, date)})",
                    choice == e.id, { choice = e.id }, lamp = SubjectPalette.of(e.colorIndex),
                )
            }
            OptionPlate("Merkliste (ohne Prüfung)", choice == null, { choice = null }, lamp = c.inkFaint)
        }
        Spacer(Modifier.height(20.dp))
        FieldLabel("Notiz (optional)")
        LineField(note, { note = it }, "z. B. Tabelle unten auswendig lernen", singleLine = false, minLines = 2)
        SheetActions(
            primary = if (saving) "Speichert …" else "Speichern",
            onPrimary = {
                if (saving) return@SheetActions
                saving = true
                scope.launch {
                    repo.addLink(choice, link.copy(label = label), note)
                    onDone()
                }
            },
            onCancel = onCancel,
        )
    }
}

/** One marked place: done lamp, label and note; tap opens it in the notebook or book. */
@Composable
internal fun LinkRow(link: StudyLink, onOpen: () -> Unit, onToggleDone: (() -> Unit)?, onDelete: () -> Unit, examLabel: String? = null) {
    val c = Ranzen.colors
    var confirm by remember { mutableStateOf(false) }
    val done = link.doneAt != null
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onToggleDone != null) {
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (done) "Als offen markieren" else "Als erledigt markieren", onClick = onToggleDone),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(24.dp).clip(shape).background(if (done) c.signal else c.raised).border(1.dp, if (done) c.signal else c.line, shape),
                    contentAlignment = Alignment.Center,
                ) { if (done) RanzenIcon(RanzenIcons.Check, tint = c.onSignal, size = 18.dp) }
            }
            Spacer(Modifier.width(6.dp))
        }
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Öffnen", onClick = onOpen).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RanzenIcon(if (link.linkKind == StudyLinkKind.BOOK_PAGE) RanzenIcons.Book else RanzenIcons.Notebook, tint = c.inkMuted, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).alpha(if (done) 0.6f else 1f)) {
                Text(
                    link.label, style = Ranzen.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                val meta = listOfNotNull(
                    "S. ${link.page + 1}" + if (link.rect != null) " · Ausschnitt" else "",
                    examLabel,
                    link.note.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                Text(meta, style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Entfernen") { if (confirm) onDelete() else confirm = true }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (confirm) Text("Entfernen?", style = Ranzen.type.label, color = c.signal)
            else RanzenIcon(RanzenIcons.Close, tint = c.inkFaint, size = 18.dp)
        }
    }
}

/** "Prüfungsstoff" inside the exam view. */
@Composable
internal fun MaterialSection(links: List<StudyLink>, repo: StudyRepository, onOpenLink: (StudyLink) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Prüfungsstoff", style = Ranzen.type.heading, color = c.ink)
        Spacer(Modifier.width(10.dp))
        Text("%02d/%02d".format(links.count { it.doneAt != null }, links.size), style = Ranzen.type.mono, color = c.inkMuted)
    }
    if (links.isEmpty()) EmptyNote("Im Heft oder Buch eine Stelle „Für Prüfung markieren“. Sie erscheint dann hier.")
    links.forEach { l ->
        LinkRow(
            l, onOpen = { onOpenLink(l) },
            onToggleDone = { scope.launch { repo.setLinkDone(l, l.doneAt == null) } },
            onDelete = { scope.launch { repo.deleteLink(l.id) } },
        )
    }
}

/** The bookmark list on the study home: places without an exam, assignable later. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookmarkSection(links: List<StudyLink>, upcoming: List<Exam>, repo: StudyRepository, onOpenLink: (StudyLink) -> Unit) {
    val scope = rememberCoroutineScope()
    var assigning by remember { mutableStateOf<Long?>(null) }
    links.forEach { l ->
        LinkRow(l, onOpen = { onOpenLink(l) }, onToggleDone = null, onDelete = { scope.launch { repo.deleteLink(l.id) } })
        if (upcoming.isNotEmpty()) {
            if (assigning == l.id) {
                FlowRow(Modifier.padding(start = 34.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    upcoming.forEach { e ->
                        OptionPlate(e.subject + " · " + LocalDate.ofEpochDay(e.dateEpochDay).format(Fmt.shortDate), false, {
                            scope.launch { repo.moveLink(l, e.id) }; assigning = null
                        }, lamp = SubjectPalette.of(e.colorIndex))
                    }
                }
            } else {
                Text(
                    "Einer Prüfung zuordnen", style = Ranzen.type.label, color = Ranzen.colors.inkMuted,
                    modifier = Modifier.padding(start = 34.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable { assigning = l.id }.padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Where new cards go: a deck, or the deck of an exam (created when needed). */
internal sealed interface CardTarget {
    data class ToDeck(val deckId: Long) : CardTarget
    data class ToExam(val examId: Long) : CardTarget
}

internal suspend fun StudyRepository.deckOf(target: CardTarget): Long = when (target) {
    is CardTarget.ToDeck -> target.deckId
    is CardTarget.ToExam -> deckForExam(target.examId)
}

/**
 * Pick a deck or a coming exam. Exams come first (that is what one learns
 * for); choosing an exam files the cards in its subject's deck.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardTargetPicker(exams: List<Exam>, decks: List<DeckCounts>, selected: CardTarget?, onSelect: (CardTarget) -> Unit) {
    val today = LocalDate.now()
    FieldLabel("Wohin?")
    if (exams.isEmpty() && decks.isEmpty()) EmptyNote("Noch kein Stapel und keine Prüfung. Die Karte kommt in „Allgemein“.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        exams.forEach { e ->
            val date = LocalDate.ofEpochDay(e.dateEpochDay)
            OptionPlate("Prüfung ${e.subject} · ${Fmt.countdown(today, date)}", selected == CardTarget.ToExam(e.id), { onSelect(CardTarget.ToExam(e.id)) }, lamp = SubjectPalette.of(e.colorIndex))
        }
        decks.forEach { d ->
            OptionPlate("Stapel ${d.name}", selected == CardTarget.ToDeck(d.id), { onSelect(CardTarget.ToDeck(d.id)) }, lamp = SubjectPalette.of(d.colorIndex))
        }
    }
}

/** Default target: the given deck, else the nearest exam, else the first deck. */
internal fun defaultTarget(exams: List<Exam>, decks: List<DeckCounts>, preferDeck: Long? = null, preferExam: Long? = null): CardTarget? = when {
    preferDeck != null -> CardTarget.ToDeck(preferDeck)
    preferExam != null -> CardTarget.ToExam(preferExam)
    exams.isNotEmpty() -> CardTarget.ToExam(exams.first().id)
    decks.isNotEmpty() -> CardTarget.ToDeck(decks.first().id)
    else -> null
}

/** Resolves a target, falling back to the deck "Allgemein". */
internal suspend fun StudyRepository.resolveDeck(target: CardTarget?): Long =
    target?.let { deckOf(it) } ?: run {
        decks.now().firstOrNull { it.name.equals("Allgemein", true) }?.id
            ?: saveDeck(de.millione.schulranzen.study.data.Deck(name = "Allgemein", colorIndex = 0))
    }
