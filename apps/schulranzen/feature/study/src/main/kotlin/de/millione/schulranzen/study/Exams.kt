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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.ExamPlanner
import de.millione.schulranzen.core.study.PlanPhase
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.ui.Counter
import de.millione.schulranzen.study.ui.DatePad
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.SectionHead
import de.millione.schulranzen.study.ui.SheetActions
import de.millione.schulranzen.study.ui.SubjectPicker
import java.time.LocalDate
import java.util.Locale

internal fun planPhaseLabel(p: PlanPhase): String = when (p) {
    PlanPhase.START -> "Stoff sichten, Karten anlegen"
    PlanPhase.REVIEW -> "Wiederholen"
    PlanPhase.FINAL -> "Letzter Durchgang: nur Unsicheres"
}

/** Deck that belongs to an exam: same subject id, or the same name. */
internal fun deckFor(exam: Exam, decks: List<DeckCounts>): DeckCounts? =
    decks.firstOrNull { exam.subjectId != null && it.subjectId == exam.subjectId }
        ?: decks.firstOrNull { it.name.equals(exam.subject, ignoreCase = true) }

/**
 * A torn-off calendar leaf: month above, day number below. Used to mark
 * exam dates in lists, so the date is the first thing the eye finds.
 */
@Composable
internal fun CalendarLeaf(date: LocalDate, accent: androidx.compose.ui.graphics.Color) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(56.dp).clip(shape).background(c.raised).border(1.dp, c.line, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(18.dp).background(accent), contentAlignment = Alignment.Center) {
            Text(
                date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.GERMANY).uppercase(Locale.GERMANY).take(3),
                style = Ranzen.type.caption, color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Text("%02d".format(date.dayOfMonth), style = Ranzen.type.monoLarge.copy(fontSize = Ranzen.type.title.fontSize), color = c.ink, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
internal fun ExamRow(exam: Exam, today: LocalDate, onClick: () -> Unit) {
    val c = Ranzen.colors
    val date = LocalDate.ofEpochDay(exam.dateEpochDay)
    val days = Fmt.daysUntil(today, date)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .alpha(if (days < 0) 0.5f else 1f).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarLeaf(date, SubjectPalette.of(exam.colorIndex))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(exam.subject, style = Ranzen.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${ExamKind.of(exam.kind).label} · ${date.format(Fmt.dayDate)}", style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            Fmt.countdown(today, date).uppercase(Locale.GERMANY), style = Ranzen.type.mono,
            color = if (days in 0..3) c.signal else c.inkMuted, textAlign = TextAlign.End,
        )
    }
}

/** Detail of one exam: countdown, topics and sources, the study plan and its deck. */
@Composable
internal fun ExamDetail(
    exam: Exam,
    today: LocalDate,
    deck: DeckCounts?,
    wide: Boolean,
    onStudyDeck: (Long) -> Unit,
    onCreateDeck: () -> Unit,
) {
    val c = Ranzen.colors
    val date = LocalDate.ofEpochDay(exam.dateEpochDay)
    val days = Fmt.daysUntil(today, date)
    val plan = remember(exam.dateEpochDay, today) { ExamPlanner.plan(today, date) }

    val left: @Composable () -> Unit = {
        PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
            Column(Modifier.padding(26.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Counter(if (days >= 0) "%02d".format(days) else "--", if (days == 1L) "Tag bis zur Prüfung" else "Tage bis zur Prüfung", accent = if (days in 0..3) c.signal else null)
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text(date.format(Fmt.longDate), style = Ranzen.type.body, color = c.ink)
                        Text(Fmt.countdown(today, date), style = Ranzen.type.mono, color = c.inkMuted)
                    }
                }
                Spacer(Modifier.height(24.dp))
                SectionHead("Themen", null)
                val topics = exam.topics.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (topics.isEmpty()) EmptyNote("Noch keine Themen notiert.")
                topics.forEach { Bullet(it) }
                Spacer(Modifier.height(16.dp))
                SectionHead("Quellen", null)
                val sources = exam.sources.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (sources.isEmpty()) EmptyNote("Heft, Buch und Seite hier eintragen.")
                sources.forEach { Bullet(it, mono = true) }
            }
        }
    }
    val right: @Composable () -> Unit = {
        PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
            Column(Modifier.padding(26.dp)) {
                SectionHead("Lernplan", plan.size)
                if (plan.isEmpty()) EmptyNote(if (days <= 0) "Die Prüfung ist heute oder vorbei." else "Kein Plan nötig.")
                plan.forEach { d ->
                    val isToday = d.date == today
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(22.dp)) { if (isToday) Lamp(c.signal, size = 8.dp) }
                        Text(d.date.format(Fmt.dayDate), style = Ranzen.type.mono, color = if (isToday) c.ink else c.inkMuted, modifier = Modifier.width(110.dp))
                        Text(planPhaseLabel(d.phase), style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                        Text("−${ExamPlanner.daysUntil(d.date, date)}", style = Ranzen.type.mono, color = c.inkFaint)
                    }
                }
                Spacer(Modifier.height(24.dp))
                SectionHead("Karteikarten", deck?.total)
                if (deck == null) {
                    EmptyNote("Noch kein Stapel für ${exam.subject}.")
                    QuietButton("Stapel „${exam.subject}“ anlegen", onCreateDeck, color = c.ink)
                } else {
                    val quota = ExamPlanner.cardsToday(deck.due - deck.fresh, deck.fresh, today, date)
                    Text(
                        "${deck.due} fällig · ${deck.fresh} neu · Vorschlag heute: $quota",
                        style = Ranzen.type.mono, color = c.inkMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    SignalButton("Karten lernen", { onStudyDeck(deck.id) }, icon = de.millione.schulranzen.study.ui.StudyIcons.Play)
                }
            }
        }
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) { left() }
            Box(Modifier.weight(1f)) { right() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { left(); right() }
    }
}

@Composable
private fun Bullet(text: String, mono: Boolean = false) {
    val c = Ranzen.colors
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 9.dp).size(5.dp).clip(RoundedCornerShape(1.dp)).background(c.inkMuted))
        Spacer(Modifier.width(12.dp))
        Text(text, style = if (mono) Ranzen.type.mono else Ranzen.type.body, color = c.ink)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExamSheet(
    exam: Exam?,
    today: LocalDate,
    known: List<StudySubject>,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (Exam) -> Unit,
) {
    val c = Ranzen.colors
    var subject by remember { mutableStateOf(exam?.subject ?: "") }
    var color by remember { mutableStateOf(exam?.colorIndex ?: known.size) }
    var subjectId by remember { mutableStateOf(exam?.subjectId) }
    var kind by remember { mutableStateOf(ExamKind.of(exam?.kind ?: ExamKind.KLASSENARBEIT.name)) }
    var date by remember { mutableStateOf(exam?.let { LocalDate.ofEpochDay(it.dateEpochDay) } ?: today.plusWeeks(2)) }
    var topics by remember { mutableStateOf(exam?.topics ?: "") }
    var sources by remember { mutableStateOf(exam?.sources ?: "") }

    Text(if (exam == null) "Neue Prüfung" else "Prüfung bearbeiten", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(22.dp))
    SubjectPicker(subject, color, known, onChange = { n, col, id -> subject = n; color = col; subjectId = id })
    Spacer(Modifier.height(20.dp))
    FieldLabel("Art")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExamKind.entries.forEach { k -> OptionPlate(k.label, k == kind, { kind = k }) }
    }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("Datum", Modifier.weight(1f))
        Text("${date.format(Fmt.dayDate)} · ${Fmt.countdown(today, date)}", style = Ranzen.type.mono, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
    }
    DatePad(date, today) { date = it }
    Spacer(Modifier.height(20.dp))
    FieldLabel("Themen und Notizen")
    LineField(topics, { topics = it }, "Ein Thema pro Zeile, z. B. Trinkwasserinstallation", singleLine = false, minLines = 3)
    Spacer(Modifier.height(20.dp))
    FieldLabel("Quellen")
    LineField(sources, { sources = it }, "z. B. Fachkunde SHK S. 112–118", singleLine = false, minLines = 2)
    SheetActions(
        primary = "Speichern",
        onPrimary = {
            if (subject.isBlank()) return@SheetActions
            onSave(
                (exam ?: Exam(subject = "", colorIndex = 0, dateEpochDay = 0, kind = "")).copy(
                    subject = subject.trim(), colorIndex = color, subjectId = subjectId,
                    dateEpochDay = date.toEpochDay(), kind = kind.name, topics = topics.trim(), sources = sources.trim(),
                ),
            )
        },
        onCancel = onCancel,
        onDelete = onDelete,
    )
}

/** Small header action: edit pencil. */
@Composable
internal fun EditKey(onClick: () -> Unit) {
    de.millione.schulranzen.study.ui.IconKey(de.millione.schulranzen.study.ui.StudyIcons.Edit, "Bearbeiten", onClick)
}

@Composable
internal fun PlusKey(description: String, onClick: () -> Unit) {
    de.millione.schulranzen.study.ui.IconKey(RanzenIcons.Plus, description, onClick)
}

@Composable
internal fun ChevronIcon() {
    RanzenIcon(de.millione.schulranzen.study.ui.StudyIcons.Chevron, tint = Ranzen.colors.inkFaint, size = 18.dp)
}
