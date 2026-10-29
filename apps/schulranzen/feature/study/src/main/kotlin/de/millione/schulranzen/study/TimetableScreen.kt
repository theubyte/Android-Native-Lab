package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.DayStatus
import de.millione.schulranzen.core.study.PeriodTime
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.core.study.WeekCycle
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.Lesson
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.IconKey
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.RetroSwitch
import de.millione.schulranzen.study.ui.ScreenHeader
import de.millione.schulranzen.study.ui.SheetActions
import de.millione.schulranzen.study.ui.SheetHost
import de.millione.schulranzen.study.ui.StudyPage
import de.millione.schulranzen.study.ui.SubjectPicker
import de.millione.schulranzen.study.ui.latestNonNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private sealed interface TtSheet {
    data object None : TtSheet
    data class Edit(val day: Int, val period: PeriodTime, val lesson: Lesson?) : TtSheet
    data object Options : TtSheet
    data object Times : TtSheet
}

/** Current local time in minutes, refreshed every 15 s, so the highlighted lesson moves on its own. */
@Composable
internal fun rememberMinuteOfDay(): Int {
    var minute by remember { mutableStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); minute = LocalTime.now().let { it.hour * 60 + it.minute } }
    }
    return minute
}

/**
 * The weekly timetable as a printed grid: days across, periods down, breaks
 * as dashed rules. The current lesson is ringed in the signal colour.
 * Tapping a field edits it in place. [subjects] are the app's subjects,
 * offered as quick picks.
 */
@Composable
fun TimetableScreen(onBack: () -> Unit, subjects: List<StudySubject> = emptyList()) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val periods by repo.periods.collectAsState(initial = Timetable.DEFAULT_PERIODS)
    val lessons by repo.lessons.collectAsState(initial = emptyList())
    val settings by repo.settings.collectAsState(initial = TimetableSettings())
    val today by repo.today.collectAsState(initial = LocalDate.now())
    val knownStudy by repo.knownSubjects.collectAsState(initial = emptyList())
    val known = remember(subjects, knownStudy) { (subjects + knownStudy).distinctBy { it.name.lowercase() } }
    val minute = rememberMinuteOfDay()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<TtSheet>(TtSheet.None) }

    val currentIsA = settings.anchor?.let { Timetable.isWeekA(today, it) } ?: true
    var showA by remember(settings.abWeeks, currentIsA) { mutableStateOf(currentIsA) }
    val visible = lessons.filter { l ->
        !settings.abWeeks || when (StudyRepository.cycleOf(l)) {
            WeekCycle.EVERY -> true
            WeekCycle.A -> showA
            WeekCycle.B -> !showA
        }
    }
    val dow = today.dayOfWeek.value
    val showingThisWeek = !settings.abWeeks || showA == currentIsA
    val status = Timetable.status(periods, minute)
    val currentPeriod = (status as? DayStatus.InPeriod)?.index?.takeIf { showingThisWeek && dow <= settings.days }

    val subtitle = buildString {
        append(today.format(Fmt.dayDate))
        append(" · ")
        append(
            when {
                dow > settings.days -> "Wochenende"
                status is DayStatus.InPeriod -> "${status.index}. Stunde bis ${Timetable.formatTime(periods.first { it.index == status.index }.endMinute)}"
                status is DayStatus.Break -> "Pause, weiter um ${Timetable.formatTime(periods.first { it.index == status.nextIndex }.startMinute)}"
                status is DayStatus.BeforeSchool -> "Beginn ${Timetable.formatTime(periods.minOf { it.startMinute })}"
                else -> "Unterricht vorbei"
            },
        )
        if (settings.abWeeks) append(if (currentIsA) " · A-Woche" else " · B-Woche")
    }

    StudyPage(header = {
        ScreenHeader("Stundenplan", subtitle, onBack) {
            if (settings.abWeeks) {
                OptionPlate("A-Woche", showA, { showA = true })
                OptionPlate("B-Woche", !showA, { showA = false })
            }
            IconKey(RanzenIcons.Settings, "Einstellungen", { sheet = TtSheet.Options })
        }
    }) {
        PaperPanel(Modifier.fillMaxSize().padding(bottom = 24.dp), corner = 24.dp) {
            WeekGrid(
                periods = periods,
                lessons = visible,
                days = settings.days,
                today = if (showingThisWeek) dow else -1,
                currentPeriod = currentPeriod,
                empty = lessons.isEmpty(),
                onCell = { day, p, l -> sheet = TtSheet.Edit(day, p, l) },
            )
        }
    }

    val current = sheet
    val shownEdit = latestNonNull(current as? TtSheet.Edit)
    SheetHost(current is TtSheet.Edit, { sheet = TtSheet.None }) {
        if (shownEdit != null) key(shownEdit) {
            val current = shownEdit
            val nextPeriod = periods.sortedBy { it.startMinute }.let { ps -> ps.getOrNull(ps.indexOf(current.period) + 1) }
            val nextFree = nextPeriod != null && visible.none { it.day == current.day && it.period == nextPeriod.index }
            LessonSheet(
                day = current.day, period = current.period, lesson = current.lesson, known = known,
                abWeeks = settings.abWeeks, defaultCycle = if (settings.abWeeks) (if (showA) WeekCycle.A else WeekCycle.B) else WeekCycle.EVERY,
                doubleWith = nextPeriod.takeIf { current.lesson == null && nextFree },
                onCancel = { sheet = TtSheet.None },
                onDelete = current.lesson?.let { l -> { scope.launch { repo.deleteLesson(l.id) }; sheet = TtSheet.None } },
                onSave = { l, double ->
                    scope.launch {
                        repo.saveLesson(l)
                        if (double && nextPeriod != null) repo.saveLesson(l.copy(id = 0, period = nextPeriod.index))
                    }
                    sheet = TtSheet.None
                },
            )
        }
    }
    SheetHost(current is TtSheet.Options, { sheet = TtSheet.None }) {
        OptionsSheet(settings, today, onChange = { scope.launch { repo.saveSettings(it) } }, onTimes = { sheet = TtSheet.Times }, onClose = { sheet = TtSheet.None })
    }
    SheetHost(current is TtSheet.Times, { sheet = TtSheet.None }) {
        TimesSheet(periods, onCancel = { sheet = TtSheet.None }) {
            scope.launch { repo.savePeriods(it) }
            sheet = TtSheet.None
        }
    }
}

@Composable
private fun WeekGrid(
    periods: List<PeriodTime>,
    lessons: List<Lesson>,
    days: Int,
    today: Int,
    currentPeriod: Int?,
    empty: Boolean,
    onCell: (day: Int, period: PeriodTime, lesson: Lesson?) -> Unit,
) {
    val c = Ranzen.colors
    val sorted = periods.sortedBy { it.startMinute }
    val slot = lessons.groupBy { it.day to it.period }.mapValues { it.value.first() }
    val labelWidth = 92.dp
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        // Day header with a double rule beneath, like a printed form.
        Row(
            Modifier.fillMaxWidth().height(44.dp).drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(c.ink.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1.2.dp.toPx())
                drawLine(c.ink.copy(alpha = 0.55f), Offset(0f, y - 3.dp.toPx()), Offset(size.width, y - 3.dp.toPx()), 0.6.dp.toPx())
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("STD.", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.width(labelWidth).padding(start = 4.dp))
            for (d in 1..days) {
                Row(Modifier.weight(1f).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (d == today) { Lamp(c.signal, size = 7.dp); Spacer(Modifier.width(8.dp)) }
                    Text(
                        Fmt.dayLong(d).uppercase(Locale.GERMANY), style = Ranzen.type.mono,
                        color = if (d == today) c.ink else c.inkMuted, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            sorted.forEachIndexed { i, p ->
                val prev = sorted.getOrNull(i - 1)
                if (prev != null && p.startMinute - prev.endMinute >= 10) BreakRule(p.startMinute - prev.endMinute, labelWidth)
                val isNow = p.index == currentPeriod
                Row(Modifier.fillMaxWidth().height(78.dp)) {
                    PeriodLabel(p, isNow, Modifier.width(labelWidth).fillMaxHeight())
                    for (d in 1..days) {
                        val l = slot[d to p.index]
                        val above = prev?.let { slot[d to it.index] }
                        val next = sorted.getOrNull(i + 1)
                        val below = next?.let { slot[d to it.index] }
                        val continues = l != null && prev != null && above != null && p.startMinute - prev.endMinute < 10 && sameBlock(l, above)
                        val continuesBelow = l != null && next != null && below != null && next.startMinute - p.endMinute < 10 && sameBlock(l, below)
                        LessonCell(
                            lesson = l, continues = continues, continuesBelow = continuesBelow, todayColumn = d == today, now = isNow && d == today,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { onCell(d, p, l) },
                        )
                    }
                }
            }
            if (empty) {
                Spacer(Modifier.height(18.dp))
                Text("Ein Feld antippen, um ein Fach einzutragen.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(start = labelWidth))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

internal fun sameBlock(a: Lesson, b: Lesson) =
    a.subject.equals(b.subject, ignoreCase = true) && a.room == b.room && a.teacher == b.teacher

@Composable
private fun PeriodLabel(p: PeriodTime, now: Boolean, modifier: Modifier) {
    val c = Ranzen.colors
    Row(modifier.drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${p.index}", style = Ranzen.type.monoLarge.copy(fontSize = Ranzen.type.heading.fontSize),
            color = if (now) c.signal else c.ink, modifier = Modifier.width(30.dp).padding(start = 4.dp),
        )
        Column {
            Text(Timetable.formatTime(p.startMinute), style = Ranzen.type.mono, color = if (now) c.ink else c.inkMuted)
            Text(Timetable.formatTime(p.endMinute), style = Ranzen.type.mono, color = c.inkFaint)
        }
    }
}

@Composable
private fun BreakRule(minutes: Int, labelWidth: androidx.compose.ui.unit.Dp) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().height(24.dp).drawBehind {
            val y = size.height / 2
            drawLine(
                c.inkFaint, Offset(labelWidth.toPx(), y), Offset(size.width, y), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PAUSE $minutes′", style = Ranzen.type.caption, color = c.inkFaint, modifier = Modifier.width(labelWidth).padding(start = 4.dp))
    }
}

@Composable
private fun LessonCell(lesson: Lesson?, continues: Boolean, continuesBelow: Boolean, todayColumn: Boolean, now: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Ranzen.colors
    val accent = lesson?.let { SubjectPalette.of(it.colorIndex) }
    Box(
        modifier
            .drawBehind {
                if (todayColumn) drawRect(c.ink.copy(alpha = if (c.isDark) 0.05f else 0.035f))
                // Column rule on the left, row rule at the bottom.
                drawLine(c.line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 6.dp, top = if (continues) 0.dp else 6.dp, bottom = if (continuesBelow) 0.dp else 6.dp),
    ) {
        if (lesson != null && accent != null) {
            // A double lesson reads as one block across both rows.
            val top = if (continues) 0.dp else 10.dp
            val bottom = if (continuesBelow) 0.dp else 10.dp
            val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            Row(
                Modifier.fillMaxSize().clip(shape)
                    .background(accent.copy(alpha = if (c.isDark) 0.16f else 0.10f))
                    .then(if (now) Modifier.border(1.5.dp, c.signal, shape) else Modifier),
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (!continues) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lesson.subject, style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (lesson.cycle != WeekCycle.EVERY.name) {
                                Spacer(Modifier.width(6.dp))
                                Text(lesson.cycle, style = Ranzen.type.caption, color = c.inkMuted)
                            }
                        }
                        val meta = listOf(lesson.room, lesson.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = Ranzen.type.mono.copy(fontSize = Ranzen.type.caption.fontSize), color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (lesson.note.isNotBlank()) Text(lesson.note, style = Ranzen.type.caption, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (now) {
                    Spacer(Modifier.weight(1f))
                    Lamp(c.signal, size = 8.dp, modifier = Modifier.padding(top = 10.dp, end = 10.dp))
                }
            }
        } else if (now) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).border(1.5.dp, c.signal.copy(alpha = 0.6f), RoundedCornerShape(10.dp)))
        }
    }
}

@Composable
private fun LessonSheet(
    day: Int,
    period: PeriodTime,
    lesson: Lesson?,
    known: List<StudySubject>,
    abWeeks: Boolean,
    defaultCycle: WeekCycle,
    doubleWith: PeriodTime?,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (Lesson, Boolean) -> Unit,
) {
    val c = Ranzen.colors
    var subject by remember { mutableStateOf(lesson?.subject ?: "") }
    var color by remember { mutableStateOf(lesson?.colorIndex ?: known.size) }
    var subjectId by remember { mutableStateOf(lesson?.subjectId) }
    var room by remember { mutableStateOf(lesson?.room ?: "") }
    var teacher by remember { mutableStateOf(lesson?.teacher ?: "") }
    var note by remember { mutableStateOf(lesson?.note ?: "") }
    var cycle by remember { mutableStateOf(lesson?.let { StudyRepository.cycleOf(it) } ?: defaultCycle) }
    var double by remember { mutableStateOf(false) }

    Text(if (lesson == null) "Stunde eintragen" else "Stunde bearbeiten", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(6.dp))
    Text(
        "${Fmt.dayLong(day)} · ${period.index}. Stunde · ${Timetable.formatTime(period.startMinute)}–${Timetable.formatTime(period.endMinute)}",
        style = Ranzen.type.mono, color = c.inkMuted,
    )
    Spacer(Modifier.height(22.dp))
    SubjectPicker(subject, color, known, onChange = { n, col, id -> subject = n; color = col; subjectId = id })
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            FieldLabel("Raum")
            LineField(room, { room = it }, "z. B. B 204", capitalization = KeyboardCapitalization.Characters)
        }
        Column(Modifier.weight(1f)) {
            FieldLabel("Lehrkraft")
            LineField(teacher, { teacher = it }, "z. B. Hr. Weber", capitalization = KeyboardCapitalization.Words)
        }
    }
    Spacer(Modifier.height(18.dp))
    FieldLabel("Notiz")
    LineField(note, { note = it }, "z. B. Werkstatt, Arbeitskleidung")
    if (abWeeks) {
        Spacer(Modifier.height(18.dp))
        FieldLabel("Wochen")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionPlate("Jede Woche", cycle == WeekCycle.EVERY, { cycle = WeekCycle.EVERY })
            OptionPlate("Nur A-Woche", cycle == WeekCycle.A, { cycle = WeekCycle.A })
            OptionPlate("Nur B-Woche", cycle == WeekCycle.B, { cycle = WeekCycle.B })
        }
    }
    if (doubleWith != null) {
        Spacer(Modifier.height(10.dp))
        RetroSwitch("Doppelstunde", double, { double = it }, hint = "Auch in die ${doubleWith.index}. Stunde eintragen")
    }
    SheetActions(
        primary = "Speichern",
        onPrimary = {
            if (subject.isBlank()) return@SheetActions
            onSave(
                Lesson(
                    id = lesson?.id ?: 0, day = day, period = period.index, cycle = cycle.name,
                    subject = subject.trim(), colorIndex = color, subjectId = subjectId,
                    room = room.trim(), teacher = teacher.trim(), note = note.trim(),
                ),
                double,
            )
        },
        onCancel = onCancel,
        onDelete = onDelete,
        deleteLabel = "Austragen",
    )
}

@Composable
private fun OptionsSheet(settings: TimetableSettings, today: LocalDate, onChange: (TimetableSettings) -> Unit, onTimes: () -> Unit, onClose: () -> Unit) {
    val c = Ranzen.colors
    Text("Stundenplan einrichten", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(18.dp))
    RetroSwitch("Samstag", settings.saturday, { onChange(settings.copy(saturday = it)) }, hint = "Samstag als sechsten Tag zeigen")
    RetroSwitch("A- und B-Wochen", settings.abWeeks, {
        onChange(settings.copy(abWeeks = it, anchorEpochDay = settings.anchorEpochDay ?: today.toEpochDay()))
    }, hint = "Für Unterricht im Wechsel")
    if (settings.abWeeks) {
        val isA = settings.anchor?.let { Timetable.isWeekA(today, it) } ?: true
        Spacer(Modifier.height(8.dp))
        FieldLabel("Diese Woche ist")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionPlate("A-Woche", isA, { onChange(settings.copy(anchorEpochDay = today.toEpochDay())) })
            OptionPlate("B-Woche", !isA, { onChange(settings.copy(anchorEpochDay = today.minusWeeks(1).toEpochDay())) })
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onTimes),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Stundenzeiten", style = Ranzen.type.body, color = c.ink)
            Text("Beginn und Ende jeder Stunde", style = Ranzen.type.caption, color = c.inkMuted)
        }
        RanzenIcon(de.millione.schulranzen.study.ui.StudyIcons.Chevron, tint = c.inkMuted, size = 20.dp)
    }
    Spacer(Modifier.height(20.dp))
    Row {
        Spacer(Modifier.weight(1f))
        QuietButton("Fertig", onClose, color = c.ink)
    }
}

@Composable
private fun TimesSheet(periods: List<PeriodTime>, onCancel: () -> Unit, onSave: (List<PeriodTime>) -> Unit) {
    val c = Ranzen.colors
    val rows = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(periods.sortedBy { it.startMinute }.map { Timetable.formatTime(it.startMinute) to Timetable.formatTime(it.endMinute) })
        }
    }
    val parsed = rows.map { (a, b) -> Timetable.parseTime(a) to Timetable.parseTime(b) }
    val valid = parsed.isNotEmpty() && parsed.all { (a, b) -> a != null && b != null && b > a } &&
        parsed.zipWithNext().all { (x, y) -> x.second!! <= y.first!! }

    Text("Stundenzeiten", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(6.dp))
    Text("Uhrzeiten als 07:45 eingeben.", style = Ranzen.type.caption, color = c.inkMuted)
    Spacer(Modifier.height(16.dp))
    rows.forEachIndexed { i, (a, b) ->
        val bad = parsed[i].let { (x, y) -> x == null || y == null || y <= x } ||
            (i > 0 && parsed[i - 1].second != null && parsed[i].first != null && parsed[i - 1].second!! > parsed[i].first!!)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}.", style = Ranzen.type.mono, color = if (bad) c.signal else c.inkMuted, modifier = Modifier.width(36.dp))
            Box(Modifier.width(110.dp)) { LineField(a, { rows[i] = it to rows[i].second }, "07:45", style = Ranzen.type.mono) }
            Text("–", style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.padding(horizontal = 14.dp))
            Box(Modifier.width(110.dp)) { LineField(b, { rows[i] = rows[i].first to it }, "08:30", style = Ranzen.type.mono) }
            Spacer(Modifier.weight(1f))
            if (i == rows.lastIndex && rows.size > 1) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable { rows.removeAt(i) }, contentAlignment = Alignment.Center) {
                    RanzenIcon(RanzenIcons.Close, tint = c.inkMuted, size = 18.dp)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton("+ Stunde", {
            val end = parsed.lastOrNull()?.second ?: (7 * 60 + 45)
            val e = (end + 45).coerceAtMost(23 * 60 + 59)
            if (e > end) rows.add(Timetable.formatTime(end) to Timetable.formatTime(e))
        }, color = c.ink)
        QuietButton("Übliche Zeiten", {
            rows.clear()
            rows.addAll(Timetable.DEFAULT_PERIODS.map { Timetable.formatTime(it.startMinute) to Timetable.formatTime(it.endMinute) })
        })
    }
    if (!valid) {
        Spacer(Modifier.height(8.dp))
        Text("Bitte Zeiten prüfen: jede Stunde muss nach der vorigen liegen.", style = Ranzen.type.caption, color = c.signal)
    }
    SheetActions(
        primary = "Speichern",
        onPrimary = {
            if (valid) onSave(parsed.mapIndexed { i, (a, b) -> PeriodTime(i + 1, a!!, b!!) })
        },
        onCancel = onCancel,
    )
    if (periods.size > rows.size) {
        Spacer(Modifier.height(8.dp))
        Text("Einträge in entfernten Stunden werden gelöscht.", style = Ranzen.type.caption, color = c.inkMuted)
    }
}
